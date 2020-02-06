package scaveleous.mcregion;

/*
** Author: Scaveleous (Minecraft Forum)
** (Public domain)
**/
// Interfaces with region files on the disk
/*

Region File Format

Each region file represents a 32x32 group of chunks. The conversion from
chunk number to region number is floor(coord / 32): a chunk at (30, -3)
would be in region (0, -1), and one at (70, -30) would be at (3, -1).
Region files are named "r.x.z.data", where x and z are the region coordinates.

A region file begins with a 4KB header that describes where chunks are stored
in the file. A 4-byte big-endian integer represents sector offsets and sector
counts. The chunk offset for a chunk (x, z) begins at byte 4*(x+z*32) in the
file. The bottom byte of the chunk offset indicates the number of sectors the
chunk takes up, and the top 3 bytes represent the sector number of the chunk.
Given a chunk offset o, the chunk data begins at byte 4096*(o/256) and takes up
at most 4096*(o%256) bytes. A chunk cannot exceed 1MB in size. If a chunk
offset is 0, the corresponding chunk is not stored in the region file.

Chunk data begins with a 4-byte big-endian integer representing the chunk data
length in bytes, not counting the length field. The length must be smaller than
4096 times the number of sectors. The next byte is a version field, to allow
backwards-compatible updates to how chunks are encoded.

A version of 1 represents a gzipped NBT file. The gzipped data is the chunk
length - 1.

A version of 2 represents a deflated (zlib compressed) NBT file. The deflated
data is the chunk length - 1.
 */
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class RegionFile
{
	/* lets chunk writing be multithreaded by not locking the whole file as a
	   chunk is serializing -- only writes when serialization is over */
	class ChunkBuffer extends ByteArrayOutputStream
	{
		private int x, z;

		public ChunkBuffer(int x, int z)
		{
			super(8192); // initialize to 8KB
			this.x = x;
			this.z = z;
		}

		@Override
		public void close()
		{ RegionFile.this.write(x, z, buf, count); }
	}

	static final int CHUNK_HEADER_SIZE = 5;
	private static final byte emptySector[] = new byte[4096];
	private final File fileName;
	private RandomAccessFile file;
	private final int offsets[];
	private ArrayList<Boolean> sectorFree;
	private int sizeDelta;
	private long lastModified = 0;

	public RegionFile(File path)
	{
		offsets = new int[1024];
		fileName = path;
		debugln("REGION LOAD " + fileName);
		sizeDelta = 0;
		try
		{
			if (path.exists())
				lastModified = path.lastModified();
			file = new RandomAccessFile(path, "rw");
			if (file.length() < 4096)
			{
				/* we need to write the chunk offset table */
				for (int i = 0; i < 1024; ++i)
					file.writeInt(0);
				sizeDelta += 4096;
			}
			if ((file.length() & 0xfff) != 0)
			{
				/* the file size is not a multiple of 4KB, grow it */
				for (int i = 0; i < (file.length() & 0xfff); ++i)
					file.write((byte) 0);
			}
			/* set up the available sector map */
			int nSectors = (int) file.length() / 4096;
			sectorFree = new ArrayList<Boolean>(nSectors);
			for (int i = 0; i < nSectors; ++i)
			{ sectorFree.add(true); }
			sectorFree.set(0, false); // chunk offset table
			file.seek(0);
			for (int i = 0; i < 1024; ++i)
			{
				int offset = file.readInt();
				offsets[i] = offset;
				if (offset != 0 && (offset >> 8) + (offset & 0xFF) <= sectorFree.size())
				{
					for (int sectorNum = 0; sectorNum < (offset & 0xFF); ++sectorNum)
					{ sectorFree.set((offset >> 8) + sectorNum, false); }
				}
			}
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}

	public void close() throws IOException
	{ file.close(); }

	// various small debug printing helpers
	private void debug(String in)
	{
		//System.out.print(in);
	}

	private void debug(String mode, int x, int z, int count, String in)
	{ debug("REGION " + mode + " " + fileName.getName() + "[" + x + "," + z + "] " + count + "B = " + in); }

	private void debug(String mode, int x, int z, String in)
	{ debug("REGION " + mode + " " + fileName.getName() + "[" + x + "," + z + "] = " + in); }

	private void debugln(String in)
	{ debug(in + "\n"); }

	private void debugln(String mode, int x, int z, String in)
	{ debug(mode, x, z, in + "\n"); }

	/* gets an (uncompressed) stream representing the chunk data
	   returns null if the chunk is not found or an error occurs  */
	public synchronized DataInputStream getChunkDataInputStream(int x, int z)
	{
		if (outOfBounds(x, z))
		{
			debugln("READ", x, z, "out of bounds");
			return null;
		}
		try
		{
			int offset = getOffset(x, z);
			if (offset == 0)
			{
				// debugln("READ", x, z, "miss");
				return null;
			}
			int sectorNumber = offset >> 8;
			int numSectors = offset & 0xFF;
			if (sectorNumber + numSectors > sectorFree.size())
			{
				debugln("READ", x, z, "invalid sector");
				return null;
			}
			file.seek(sectorNumber * 4096);
			int length = file.readInt();
			if (length > 4096 * numSectors)
			{
				debugln("READ", x, z, "invalid length: " + length + " > 4096 * " + numSectors);
				return null;
			}
			byte version = file.readByte();
			if (version == 1)
			{
				byte[] data = new byte[length - 1];
				file.read(data);
				DataInputStream ret = new DataInputStream(new GZIPInputStream(
					new ByteArrayInputStream(data)));
				// debug("READ", x, z, " = found");
				return ret;
			}
			else if (version == 2)
			{
				byte[] data = new byte[length - 1];
				file.read(data);
				DataInputStream ret = new DataInputStream(new InflaterInputStream(
					new ByteArrayInputStream(data)));
				// debug("READ", x, z, " = found");
				return ret;
			}
			debugln("READ", x, z, "unknown version " + version);
			return null;
		}
		catch (IOException e)
		{
			debugln("READ", x, z, "exception");
			return null;
		}
	}

	public DataOutputStream getChunkDataOutputStream(int x, int z)
	{
		if (outOfBounds(x, z))
			return null;
		return new DataOutputStream(new DeflaterOutputStream(
			new ChunkBuffer(x, z)));
	}

	private int getOffset(int x, int z) throws IOException
	{ return offsets[x + z * 32]; }

	/* gets how much the region file has grown since it was last checked */
	public synchronized int getSizeDelta()
	{
		int ret = sizeDelta;
		sizeDelta = 0;
		return ret;
	}

	/* the modification date of the region file when it was first opened */
	public long lastModified()
	{ return lastModified; }

	/* is this an invalid chunk coordinate? */
	private boolean outOfBounds(int x, int z)
	{ return x < 0 || x >= 32 || z < 0 || z >= 32; }

	private void setOffset(int x, int z, int offset) throws IOException
	{
		offsets[x + z * 32] = offset;
		file.seek((x + z * 32) * 4);
		file.writeInt(offset);
	}

	/* write a chunk data to the region file at specified sector number */
	private void write(int sectorNumber, byte[] data, int length) throws IOException
	{
		debugln(" " + sectorNumber);
		file.seek(sectorNumber * 4096);
		file.writeInt(length + 1); // chunk length
		file.writeByte(2); // chunk version number
		file.write(data, 0, length); // chunk data
	}

	/* write a chunk at (x,z) with length bytes of data to disk */
	protected synchronized void write(int x, int z, byte[] data, int length)
	{
		try
		{
			int offset = getOffset(x, z);
			int sectorNumber = offset >> 8;
			int sectorsAllocated = offset & 0xFF;
			int sectorsNeeded = (length + CHUNK_HEADER_SIZE) / 4096 + 1;
			if (sectorsNeeded >= 256) // maximum chunk size is 1MB
				return;
			if (sectorNumber != 0 && sectorsAllocated == sectorsNeeded)
			{
				/* we can simply overwrite the old sectors */
				debug("SAVE", x, z, length, "rewrite");
				write(sectorNumber, data, length);
			}
			else
			{
				/* we need to allocate new sectors */
				/* mark the sectors previously used for this chunk as free */
				for (int i = 0; i < sectorsAllocated; ++i)
					sectorFree.set(sectorNumber + i, true);
				/* scan for a free space large enough to store this chunk */
				int runStart = sectorFree.indexOf(true);
				int runLength = 0;
				if (runStart != -1)
				{
					for (int i = runStart; i < sectorFree.size(); ++i)
					{
						if (runLength != 0)
						{
							if (sectorFree.get(i))
								runLength++;
							else
								runLength = 0;
						}
						else if (sectorFree.get(i))
						{
							runStart = i;
							runLength = 1;
						}
						if (runLength >= sectorsNeeded)
							break;
					}
				}
				if (runLength >= sectorsNeeded)
				{
					/* we found a free space large enough */
					debug("SAVE", x, z, length, "reuse");
					sectorNumber = runStart;
					setOffset(x, z, (sectorNumber << 8) | sectorsNeeded);
					for (int i = 0; i < sectorsNeeded; ++i)
						sectorFree.set(sectorNumber + i, false);
					write(sectorNumber, data, length);
				}
				else
				{
					/* no free space large enough found -- we need to grow the file */
					debug("SAVE", x, z, length, "grow");
					file.seek(file.length());
					sectorNumber = sectorFree.size();
					for (int i = 0; i < sectorsNeeded; ++i)
					{
						file.write(emptySector);
						sectorFree.add(false);
					}
					sizeDelta += 4096 * sectorsNeeded;
					write(sectorNumber, data, length);
					setOffset(x, z, (sectorNumber << 8) | sectorsNeeded);
				}
			}
		}
		catch (IOException e)
		{e.printStackTrace();}
	}
}