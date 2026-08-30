package com.ciaac.minecraft.minigames.display;

public record DisplayLocation(double x, double y, double z, float yaw, float pitch) {
    public DisplayLocation {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("display location is not finite");
        if (y < -64 || y > 320 || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) throw new IllegalArgumentException("display location is out of bounds");
    }
}
