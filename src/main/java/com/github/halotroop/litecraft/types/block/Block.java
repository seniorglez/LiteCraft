package com.github.halotroop.litecraft.types.block;

import com.github.halotroop.litecraft.types.item.Item;

public class Block
{
	public Item blockItem;
	
	public Block()
	{
		
	}
	
	public void createItem()
	{
		this.blockItem = new Item(this);
	}
}
