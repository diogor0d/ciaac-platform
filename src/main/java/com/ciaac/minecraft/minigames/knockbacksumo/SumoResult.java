package com.ciaac.minecraft.minigames.knockbacksumo;
import java.util.UUID;
public record SumoResult(UUID resultId, UUID sessionId, String rulesetRevision, UUID winner, UUID loser, int rounds, String reasonCode) {
    public SumoResult { if (resultId == null || sessionId == null || rulesetRevision == null || winner == null || loser == null || winner.equals(loser) || rounds < 1 || reasonCode == null || reasonCode.isBlank()) throw new IllegalArgumentException("invalid result"); }

    /** A timeout is a deterministic draw; winner/loser retain roster order for compatibility only. */
    public boolean draw() { return "ROUND_TIMEOUT".equals(reasonCode); }
}
