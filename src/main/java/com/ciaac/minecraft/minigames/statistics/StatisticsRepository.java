package com.ciaac.minecraft.minigames.statistics;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Durable-boundary contract for immutable match evidence and derived
 * statistics. Implementations must make record application idempotent by
 * result ID and reject conflicting match or result identities.
 */
public interface StatisticsRepository {
    /**
     * Records a new result, returning false for an identical replay.
     *
     * @throws IllegalStateException if either identity is replayed with a
     *         different immutable payload
     */
    boolean record(MatchResult result);

    Optional<MatchResult> findByResultId(UUID resultId);

    Optional<MatchResult> findByMatchId(UUID matchId);

    /**
     * Returns ranked scopes containing the requested metric, in deterministic
     * ruleset-then-mode order.
     */
    List<LeaderboardScope> discoverScopes(GameKey game, String metric);

    List<LeaderboardEntry> leaderboard(LeaderboardQuery query);

    Optional<LeaderboardEntry> position(LeaderboardQuery query, UUID playerId);

    default OptionalInt rank(LeaderboardQuery query, UUID playerId) {
        return position(query, playerId).map(entry -> OptionalInt.of(entry.rank()))
                .orElseGet(OptionalInt::empty);
    }
}
