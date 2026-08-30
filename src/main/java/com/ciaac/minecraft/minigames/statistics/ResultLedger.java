package com.ciaac.minecraft.minigames.statistics;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ResultLedger {
    private final Map<UUID, MatchResult> resultsById = new HashMap<>();
    private final Map<UUID, UUID> resultIdByMatchId = new HashMap<>();

    public synchronized boolean record(MatchResult result) {
        Objects.requireNonNull(result, "result");
        MatchResult previous = resultsById.get(result.resultId());
        if (previous != null) {
            if (!previous.equals(result)) {
                throw new IllegalStateException("Conflicting replay for result " + result.resultId());
            }
            return false;
        }
        UUID previousResultId = resultIdByMatchId.get(result.matchId());
        if (previousResultId != null) {
            throw new IllegalStateException(
                    "Match " + result.matchId() + " already has result " + previousResultId);
        }
        resultsById.put(result.resultId(), result);
        resultIdByMatchId.put(result.matchId(), result.resultId());
        return true;
    }

    public synchronized Optional<MatchResult> findByResultId(UUID resultId) {
        return Optional.ofNullable(resultsById.get(Objects.requireNonNull(resultId, "resultId")));
    }

    public synchronized Optional<MatchResult> findByMatchId(UUID matchId) {
        UUID resultId = resultIdByMatchId.get(Objects.requireNonNull(matchId, "matchId"));
        return resultId == null ? Optional.empty() : Optional.of(resultsById.get(resultId));
    }
}
