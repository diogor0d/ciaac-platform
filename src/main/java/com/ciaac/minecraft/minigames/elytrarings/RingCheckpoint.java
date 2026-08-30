package com.ciaac.minecraft.minigames.elytrarings;
public record RingCheckpoint(int order,double x,double y,double z){public RingCheckpoint{if(order<1||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("invalid checkpoint");}}
