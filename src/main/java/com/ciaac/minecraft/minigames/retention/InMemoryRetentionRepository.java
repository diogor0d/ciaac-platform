package com.ciaac.minecraft.minigames.retention;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Test/reference implementation; production must use the migration exposed by {@link RetentionSchemaMigration}. */
public final class InMemoryRetentionRepository implements RetentionRepository {
    private final Map<UUID, PlayerRetentionLedger> ledgers = new LinkedHashMap<>();
    @Override public synchronized <T> T transaction(UUID playerId, Function<PlayerRetentionLedger, T> work) {
        return Objects.requireNonNull(work, "work").apply(ledgers.computeIfAbsent(Objects.requireNonNull(playerId, "playerId"), PlayerRetentionLedger::new));
    }
    @Override public synchronized List<PlayerRetentionLedger> allLedgers() { return List.copyOf(new ArrayList<>(ledgers.values())); }
}
