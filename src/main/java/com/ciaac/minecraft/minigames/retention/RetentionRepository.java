package com.ciaac.minecraft.minigames.retention;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.time.Instant;

/** Persistence boundary. Production implementations must make a player transaction durable before returning. */
public interface RetentionRepository {
    <T> T transaction(UUID playerId, Function<PlayerRetentionLedger, T> work);
    List<PlayerRetentionLedger> allLedgers();
    default void purgeDetailedBefore(Instant cutoff) {}
}
