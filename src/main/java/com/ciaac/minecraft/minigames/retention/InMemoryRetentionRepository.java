package com.ciaac.minecraft.minigames.retention;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Test/reference implementation; production must use the migration exposed by {@link RetentionSchemaMigration}. */
public final class InMemoryRetentionRepository implements RetentionRepository {
    private final Map<UUID, PlayerRetentionLedger> ledgers = new LinkedHashMap<>();
    @Override public synchronized <T> T transaction(UUID playerId, Function<PlayerRetentionLedger, T> work) {
        UUID id = Objects.requireNonNull(playerId, "playerId");
        PlayerRetentionLedger candidate = ledgers.getOrDefault(id, new PlayerRetentionLedger(id)).copy();
        T result = Objects.requireNonNull(work, "work").apply(candidate);
        ledgers.put(id, candidate.copy());
        return result;
    }
    @Override public synchronized Optional<PlayerRetentionLedger> findLedger(UUID playerId) {
        return Optional.ofNullable(ledgers.get(Objects.requireNonNull(playerId, "playerId"))).map(PlayerRetentionLedger::copy);
    }
    @Override public synchronized List<PlayerRetentionLedger> allLedgers() {
        return ledgers.values().stream().map(PlayerRetentionLedger::copy).toList();
    }
}
