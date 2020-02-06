package com.github.halotroop.litecraft.render;

import org.lwjgl.opengl.*;

import com.github.halotroop.litecraft.render.model.Model;
import com.github.halotroop.litecraft.render.model.Vertex;

public class Renderer
{
	private Model model;

	public Renderer()
	{
		init();
		model = new Model();
		Vertex[] verts =
		{
			new Vertex(-1, -1, 0),
			new Vertex(1, -1, 0),
			new Vertex(0, 1, 0)
		};
		model.bufferVertices(verts);
	}

	public void render()
	{
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, model.getVBO());
		GL20.glEnableVertexAttribArray(0);
		GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, Vertex.SIZE * 4, 0);
		GL20.glDrawArrays(GL20.GL_TRIANGLES, 0, model.getSize());
		GL20.glDisableVertexAttribArray(0);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
	}

	private void init()
	{ prepare(); }

	private void prepare()
	{
		GL11.glClearColor(96/255F, 26/255F, 108/255F, 0.0F); // Set the background color
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT); // clear the framebuffer
	}

	public void cleanUp()
	{
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
		GL20.glDisableVertexAttribArray(0);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
	}
}
