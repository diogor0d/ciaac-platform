package com.ciaac.minecraft.minigames.colorfloor;
import java.util.UUID;
/** Adapter contract carries only an admitted UUID and bounded floor cell. */
public record ColorFloorInput(UUID player,int floorCell){public ColorFloorInput{if(player==null||floorCell<0||floorCell>4095)throw new IllegalArgumentException("invalid bounded event");}}
