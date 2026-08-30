package com.ciaac.minecraft.minigames.display;

import java.util.Objects;
import java.util.UUID;

/** Exact world identity: a matching name alone is not sufficient. */
public record DisplayWorldIdentity(UUID uuid, String name) {
    public DisplayWorldIdentity {
        Objects.requireNonNull(uuid, "uuid");
        name = Objects.requireNonNull(name, "name").trim();
        if (!name.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("world name is invalid");
    }
}
