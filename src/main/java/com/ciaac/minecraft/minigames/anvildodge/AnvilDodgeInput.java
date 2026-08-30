package com.ciaac.minecraft.minigames.anvildodge;
import java.util.UUID;
/** Bounded adapter event; coordinates are floor-cell indexes, never world input. */
public record AnvilDodgeInput(UUID player,int floorCell){public AnvilDodgeInput{if(player==null||floorCell<0||floorCell>4095)throw new IllegalArgumentException("invalid bounded event");}}
