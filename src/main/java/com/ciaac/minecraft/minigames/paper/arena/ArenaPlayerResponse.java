package com.ciaac.minecraft.minigames.paper.arena;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded Portuguese-Portugal result suitable for command or chat adapters. */
public record ArenaPlayerResponse(String code, String messagePtPt, Optional<UUID> matchId) {
    public ArenaPlayerResponse {
        code = Objects.requireNonNull(code, "code").trim();
        messagePtPt = Objects.requireNonNull(messagePtPt, "messagePtPt").trim();
        matchId = Objects.requireNonNull(matchId, "matchId");
        if (code.isBlank() || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid response code");
        }
        if (messagePtPt.isBlank() || messagePtPt.length() > 300) {
            throw new IllegalArgumentException("Response message must contain 1-300 characters");
        }
    }

    public void sendTo(org.bukkit.entity.Player player) {
        Objects.requireNonNull(player, "player").sendRichMessage(messagePtPt);
    }
}
