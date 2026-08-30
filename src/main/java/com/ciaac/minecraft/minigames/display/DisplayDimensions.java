package com.ciaac.minecraft.minigames.display;

public record DisplayDimensions(float width, float height) {
    public DisplayDimensions {
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0 || width > 16 || height > 16) throw new IllegalArgumentException("display dimensions are invalid");
    }
}
