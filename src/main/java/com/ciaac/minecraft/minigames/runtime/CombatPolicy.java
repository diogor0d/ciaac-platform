package com.ciaac.minecraft.minigames.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CombatPolicy(
        UUID matchId,
        boolean playerDamage,
        boolean environmentalDamage,
        boolean friendlyFire,
        Map<UUID, String> teamByPlayer) {

    public CombatPolicy {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(teamByPlayer, "teamByPlayer");
        teamByPlayer = Map.copyOf(teamByPlayer);
        teamByPlayer.forEach((player, team) -> {
            Objects.requireNonNull(player, "player");
            if (team == null || !team.matches("[A-Za-z0-9_.-]{1,64}")) {
                throw new IllegalArgumentException("Team identifiers must be bounded");
            }
        });
    }

    public boolean permits(UUID attacker, UUID victim) {
        if (!playerDamage || attacker.equals(victim)) {
            return false;
        }
        Optional<String> attackerTeam = Optional.ofNullable(teamByPlayer.get(attacker));
        Optional<String> victimTeam = Optional.ofNullable(teamByPlayer.get(victim));
        return friendlyFire || attackerTeam.isEmpty() || victimTeam.isEmpty()
                || !attackerTeam.equals(victimTeam);
    }
}
