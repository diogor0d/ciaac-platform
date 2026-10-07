package com.ciaac.minecraft.minigames.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import me.clip.placeholderapi.replacer.CharsReplacer;
import me.clip.placeholderapi.replacer.Replacer;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

class PassportPlaceholderExpansionTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    @Test void placeholderApiParserRoutesPublicPassportTokensToCiaacExpansion() {
        PassportPlaceholderExpansion expansion = new PassportPlaceholderExpansion(
                null, service(new InMemoryRetentionRepository()), java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        OfflinePlayer player = (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(), new Class<?>[] {OfflinePlayer.class},
                (proxy, method, args) -> method.getName().equals("getUniqueId") ? PLAYER : null);
        AtomicInteger lookups = new AtomicInteger();

        String result = new CharsReplacer(Replacer.Closure.PERCENT).apply(
                "points=%ciaac_passport_points%", player, identifier -> {
                    lookups.incrementAndGet();
                    return identifier.equals(expansion.getIdentifier()) ? expansion : null;
                });

        assertEquals("ciaac", expansion.getIdentifier());
        assertEquals("points=0", result);
        assertEquals(1, lookups.get());
        assertEquals("0", expansion.onRequest(player, "passport_points"));
        assertEquals("0", expansion.onRequest(player, "PASSPORT_points"));
        assertEquals(null, expansion.onRequest(player, "points"));
    }

    @Test void unknownParametersReturnBeforeRepositoryReads() {
        CountingRepository repository = new CountingRepository();
        PassportPlaceholderValues values = new PassportPlaceholderValues(service(repository));

        assertTrue(values.value(PLAYER, "not_a_placeholder", NOW).isEmpty());
        assertEquals(0, repository.findCalls.get());
        assertEquals(0, repository.transactionCalls.get());
        assertFalse(values.value(PLAYER, "points", NOW).isEmpty());
        assertEquals(1, repository.findCalls.get());
        assertEquals(0, repository.transactionCalls.get());
    }

    private static PassportService service(RetentionRepository repository) {
        RetentionConfiguration configuration = new RetentionConfiguration(true, LisbonSeasonCalendar.LISBON,
                LisbonSeasonCalendar.ANCHOR, 3, 14, Duration.ofMinutes(10), 15, 3, 12,
                false, false, false, List.of());
        return new PassportService(configuration, repository, List.of(), List.of());
    }

    private static final class CountingRepository implements RetentionRepository {
        private final InMemoryRetentionRepository delegate = new InMemoryRetentionRepository();
        private final AtomicInteger transactionCalls = new AtomicInteger();
        private final AtomicInteger findCalls = new AtomicInteger();

        @Override public <T> T transaction(UUID playerId, java.util.function.Function<PlayerRetentionLedger, T> work) {
            transactionCalls.incrementAndGet();
            return delegate.transaction(playerId, work);
        }
        @Override public Optional<PlayerRetentionLedger> findLedger(UUID playerId) {
            findCalls.incrementAndGet();
            return delegate.findLedger(playerId);
        }
        @Override public List<PlayerRetentionLedger> allLedgers() { return delegate.allLedgers(); }
    }
}
