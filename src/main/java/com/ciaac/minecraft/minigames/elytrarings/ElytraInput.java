package com.ciaac.minecraft.minigames.elytrarings;
import java.util.UUID;
/** Adapter input is a course ring identity, not arbitrary player coordinates. */
public record ElytraInput(UUID player,int ringOrder){public ElytraInput{if(player==null||ringOrder<1||ringOrder>4096)throw new IllegalArgumentException("invalid bounded event");}}
