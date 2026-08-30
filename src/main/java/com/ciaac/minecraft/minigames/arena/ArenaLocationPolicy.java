package com.ciaac.minecraft.minigames.arena;

import java.util.Objects;

/** Named geometry contract; Paper/WorldGuard adapters resolve these identifiers. */
public record ArenaLocationPolicy(String world, String combatFloorRegion, String spectatorRegion,
                                  String teamASpawn, String teamBSpawn, String spectatorFallback) {
    public ArenaLocationPolicy {
        world = required(world, "world");
        combatFloorRegion = required(combatFloorRegion, "combatFloorRegion");
        spectatorRegion = required(spectatorRegion, "spectatorRegion");
        teamASpawn = required(teamASpawn, "teamASpawn");
        teamBSpawn = required(teamBSpawn, "teamBSpawn");
        spectatorFallback = required(spectatorFallback, "spectatorFallback");
        if (combatFloorRegion.equals(spectatorRegion)) {
            throw new IllegalArgumentException("Combat and spectator regions must be distinct");
        }
    }

    private static String required(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank() || value.equals("__SET_ME__")) {
            throw new IllegalArgumentException(name + " must be configured");
        }
        return value;
    }
}
