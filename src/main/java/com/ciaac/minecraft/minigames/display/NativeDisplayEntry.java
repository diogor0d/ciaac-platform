package com.ciaac.minecraft.minigames.display;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Duration;
import java.util.Objects;

/** Validated, stable configuration for one native display pair. */
public record NativeDisplayEntry(String id, GameKey game, DisplayWorldIdentity world,
                                 DisplayLocation location, DisplayDimensions dimensions,
                                 Duration refresh, String joinCommand) {
    public NativeDisplayEntry {
        id = Objects.requireNonNull(id, "id").trim().toLowerCase(java.util.Locale.ROOT);
        if (!id.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("display id is invalid");
        game = Objects.requireNonNull(game, "game"); world = Objects.requireNonNull(world, "world"); location = Objects.requireNonNull(location, "location"); dimensions = Objects.requireNonNull(dimensions, "dimensions");
        if (refresh == null || refresh.isNegative() || refresh.isZero() || refresh.compareTo(Duration.ofMinutes(10)) > 0) throw new IllegalArgumentException("refresh is invalid");
        joinCommand = Objects.requireNonNull(joinCommand, "joinCommand").trim();
        if (!joinCommand.matches("/[a-z0-9][a-z0-9_-]{0,31} entrar")) throw new IllegalArgumentException("joinCommand is invalid");
    }
}
