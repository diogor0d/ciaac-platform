package com.ciaac.minecraft.minigames.statistics.recording;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Deterministic identities used by terminal game-result recording. */
public final class ResultIds {
    private static final String RESULT_NAMESPACE = "ciaac-minigames:result:v1:";

    private ResultIds() {
    }

    /**
     * Returns the only result identity allowed for a match.
     *
     * <p>The match identifier is the durable idempotency boundary. Retries,
     * reconnects, and repeated ticks therefore cannot create a second result
     * for the same game instance.</p>
     */
    public static UUID forMatch(UUID matchId) {
        Objects.requireNonNull(matchId, "matchId");
        return UUID.nameUUIDFromBytes(
                (RESULT_NAMESPACE + matchId).getBytes(StandardCharsets.UTF_8));
    }
}
