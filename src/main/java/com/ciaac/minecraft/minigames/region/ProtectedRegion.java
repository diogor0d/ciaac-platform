package com.ciaac.minecraft.minigames.region;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.Objects;

public record ProtectedRegion(
        String id,
        GameKey game,
        CuboidRegion bounds,
        ProtectedRegionRole role,
        boolean immutable) {

    public ProtectedRegion {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Region id must be a bounded lowercase identifier");
        }
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(role, "role");
    }

    public boolean requiresAdmission() {
        return role == ProtectedRegionRole.PARTICIPANT_ONLY
                || role == ProtectedRegionRole.GAME_WORLD_BOUNDARY;
    }
}
