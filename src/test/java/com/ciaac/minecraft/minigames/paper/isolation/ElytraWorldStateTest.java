package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.*;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.elytrarings.ElytraCourseRevision;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsConfig;
import com.ciaac.minecraft.minigames.elytrarings.RingCheckpoint;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedElytraRingsConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.TemplateResolution;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ElytraWorldStateTest {
    @TempDir Path temporary;
    private final UUID worldId = UUID.randomUUID();
    private final UUID matchId = UUID.randomUUID();
    private boolean loaded = true;

    private static Object zero(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private PlayerStateOperation capture(int id) {
        UUID operation = new UUID(0, id);
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, operation, operation,
                new UUID(1, id), new UUID(2, id), matchId, new UUID(3, id), new UUID(4, id),
                new UUID(4, id), GameKey.ELYTRA_RINGS, Instant.parse("2026-10-07T12:00:00Z"));
    }

    private PlayerStateOperation phase(PlayerStateOperation captured, PlayerStateOperation.Kind kind) {
        UUID operationId = new UUID(kind.ordinal() + 10L, captured.sessionId().getLeastSignificantBits());
        return new PlayerStateOperation(kind, operationId, captured.captureOperationId(), captured.snapshotId(),
                captured.sessionId(), captured.matchId(), captured.playerId(), captured.capturedConnectionId(),
                captured.capturedConnectionId(), captured.game(), captured.capturedAt());
    }

    private World world() {
        return proxy(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUID" -> worldId;
            case "getName" -> "synthetic-elytra";
            case "getMinHeight" -> 0;
            case "getMaxHeight" -> 256;
            case "isChunkLoaded" -> loaded;
            default -> zero(method.getReturnType());
        });
    }

    private Server server(World world) {
        return proxy(Server.class, (proxy, method, args) -> switch (method.getName()) {
            case "isPrimaryThread" -> true;
            case "getVersion" -> "Paper fixture";
            case "getWorld" -> args[0] instanceof UUID id ? id.equals(worldId) ? world : null
                    : "synthetic-elytra".equals(args[0]) ? world : null;
            default -> zero(method.getReturnType());
        });
    }

    private ResolvedElytraRingsConfiguration configuration(World world, int rockets) {
        var boundary = new CuboidRegion(worldId, 0, 0, 0, 64, 255, 64);
        var first = new CuboidRegion(worldId, 15, 20, 15, 17, 22, 17);
        var second = new CuboidRegion(worldId, 31, 30, 31, 33, 32, 33);
        var rings = List.of(new RingCheckpoint(1, 16, 21, 16), new RingCheckpoint(2, 32, 31, 32));
        var course = new ElytraCourseRevision("fixture-v1", worldId.toString(), rings);
        return new ResolvedElytraRingsConfiguration(world,
                Map.of("start", new Location(world, 8.5, 70, 8.5), "exit", new Location(world, 4.5, 70, 4.5)),
                Map.of("course-boundary", boundary),
                ElytraRingsConfig.dedicatedWorld(Duration.ofMinutes(10), course), course,
                "immutable-course-fixture-v1", List.of("ring-a", "ring-b"),
                Map.of("ring-a", first, "ring-b", second), 1, rockets, true, 1, 3.0,
                TemplateResolution.readyForAdapter("ordered-ring-targets-v1"));
    }

    @Test void capturedManifestIsStableAndRestoreWaitsForExactFireworkPurge() {
        World world = world();
        var server = server(world);
        var config = new AtomicReference<>(configuration(world, 8));
        var regions = new ProtectedRegionRegistry();
        regions.register(new ProtectedRegion("elytra-rings.course-boundary", GameKey.ELYTRA_RINGS,
                config.get().regions().get("course-boundary"), ProtectedRegionRole.GAME_WORLD_BOUNDARY, true));
        AtomicInteger cleanupValidations = new AtomicInteger(), cleanups = new AtomicInteger();
        try (var ledger = new ArenaWorldLedger(temporary.resolve("ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            ElytraWorldState.EntityCleanup ownership = new ElytraWorldState.EntityCleanup() {
                @Override public void validate(PlayerStateOperation capture) { cleanupValidations.incrementAndGet(); }
                @Override public void purge(PlayerStateOperation capture) {
                    cleanups.incrementAndGet();
                    ledger.beginPurge(capture);
                    ledger.completePurge(capture);
                }
            };
            var state = new ElytraWorldState("elytra-world", server, regions, ledger, journal,
                    event -> true, config::get, ownership);
            var captured = capture(1);
            byte[] manifest = state.capture(captured);
            assertArrayEquals(manifest, state.capture(captured));
            assertEquals(worldId, ElytraWorldState.capturedWorld(manifest));
            state.enter(phase(captured, PlayerStateOperation.Kind.ENTER));
            state.enter(phase(captured, PlayerStateOperation.Kind.ENTER));
            assertThrows(IllegalStateException.class, () -> state.restore(
                    phase(captured, PlayerStateOperation.Kind.RESTORE), 1, manifest));
            assertEquals(0, cleanups.get());
            state.purge(phase(captured, PlayerStateOperation.Kind.PURGE));
            state.purge(phase(captured, PlayerStateOperation.Kind.PURGE));
            state.restore(phase(captured, PlayerStateOperation.Kind.RESTORE), 1, manifest);
            assertEquals(ArenaWorldLedger.Status.RESTORED, ledger.requireLease(captured).status());
            assertEquals(1, cleanups.get());
            assertTrue(cleanupValidations.get() >= 4);
        }
    }

    @Test void configurationOrChunkDriftFailsBeforeLeaseOrPurge() {
        World world = world();
        var config = new AtomicReference<>(configuration(world, 8));
        var regions = new ProtectedRegionRegistry();
        regions.register(new ProtectedRegion("elytra-rings.course-boundary", GameKey.ELYTRA_RINGS,
                config.get().regions().get("course-boundary"), ProtectedRegionRole.GAME_WORLD_BOUNDARY, true));
        AtomicInteger purges = new AtomicInteger();
        try (var ledger = new ArenaWorldLedger(temporary.resolve("ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var state = new ElytraWorldState("elytra-world", server(world), regions, ledger, journal,
                    event -> true, config::get, new ElytraWorldState.EntityCleanup() {
                        @Override public void validate(PlayerStateOperation capture) { }
                        @Override public void purge(PlayerStateOperation capture) { purges.incrementAndGet(); }
                    });
            loaded = false;
            assertThrows(IllegalStateException.class, () -> state.capture(capture(2)));
            assertThrows(IllegalStateException.class, () -> ledger.requireLease(capture(2)));
            loaded = true;
            var captured = capture(3);
            byte[] manifest = state.capture(captured);
            config.set(configuration(world, 9));
            assertThrows(IllegalStateException.class, () -> state.validate(
                    phase(captured, PlayerStateOperation.Kind.PURGE), 1, manifest));
            assertThrows(IllegalStateException.class, () -> state.purge(
                    phase(captured, PlayerStateOperation.Kind.PURGE)));
            assertEquals(0, purges.get());
            assertEquals(ArenaWorldLedger.Status.CAPTURED, ledger.requireLease(captured).status());
        }
    }

    @Test void capturedWorldPrefixAndMalformedManifestAreBounded() {
        byte[] invalid = new byte[128 * 1024 + 1];
        assertThrows(IllegalArgumentException.class, () -> ElytraWorldState.capturedWorld(invalid));
        assertThrows(IllegalArgumentException.class, () -> ElytraWorldState.capturedWorld(new byte[] {1, 2, 3}));
    }

    @Test void pendingSqliteStagesResumeAfterReadBackAndAuditReplaysIdempotently() {
        World world = world();
        var config = new AtomicReference<>(configuration(world, 8));
        var regions = new ProtectedRegionRegistry();
        regions.register(new ProtectedRegion("elytra-rings.course-boundary", GameKey.ELYTRA_RINGS,
                config.get().regions().get("course-boundary"), ProtectedRegionRole.GAME_WORLD_BOUNDARY, true));
        Set<UUID> auditIds = new HashSet<>();
        AtomicInteger purgeCalls = new AtomicInteger();
        AtomicInteger cleanupValidation = new AtomicInteger();
        AtomicInteger cleanupPurge = new AtomicInteger();
        try (var ledger = new ArenaWorldLedger(temporary.resolve("ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var state = new ElytraWorldState("elytra-world", server(world), regions, ledger, journal,
                    event -> auditIds.add(event.eventId()), config::get, new ElytraWorldState.EntityCleanup() {
                        @Override public void validate(PlayerStateOperation capture) { cleanupValidation.incrementAndGet(); }
                        @Override public void purge(PlayerStateOperation capture) {
                            assertTrue(cleanupValidation.get() > 0, "native purge must follow prevalidation");
                            purgeCalls.incrementAndGet();
                            cleanupPurge.incrementAndGet();
                            ledger.beginPurge(capture);
                            ledger.completePurge(capture);
                        }
                    });
            var captured = capture(4);
            assertEquals(ExternalOperationJournal.State.NEW,
                    journal.begin("elytra-world", 1, captured, new byte[0]));
            byte[] manifest = state.capture(captured);
            assertArrayEquals(manifest, state.capture(captured));
            assertTrue(journal.committedResult("elytra-world", 1, captured, new byte[0]).length > 0);

            var enter = phase(captured, PlayerStateOperation.Kind.ENTER);
            assertEquals(ExternalOperationJournal.State.NEW, journal.begin("elytra-world", 1, enter, manifest));
            state.enter(enter);
            state.enter(enter);
            assertEquals(ArenaWorldLedger.Status.ARMED, ledger.requireLease(captured).status());

            var purge = phase(captured, PlayerStateOperation.Kind.PURGE);
            assertEquals(ExternalOperationJournal.State.NEW, journal.begin("elytra-world", 1, purge, manifest));
            state.purge(purge);
            state.purge(purge);
            assertEquals(ArenaWorldLedger.Status.PURGED, ledger.requireLease(captured).status());

            var restore = phase(captured, PlayerStateOperation.Kind.RESTORE);
            assertEquals(ExternalOperationJournal.State.NEW, journal.begin("elytra-world", 1, restore, manifest));
            state.restore(restore, 1, manifest);
            state.restore(restore, 1, manifest);
            assertEquals(ArenaWorldLedger.Status.RESTORED, ledger.requireLease(captured).status());
            state.purge(purge); // committed purge replay remains safe after the lease reaches RESTORED
            assertEquals(1, purgeCalls.get());
            assertEquals(4, auditIds.size());
            assertEquals(1, cleanupPurge.get());
        }
    }

    @Test void pendingJournalRejectsDifferentCaptureIdentity() {
        World world = world();
        var config = configuration(world, 8);
        var regions = new ProtectedRegionRegistry();
        regions.register(new ProtectedRegion("elytra-rings.course-boundary", GameKey.ELYTRA_RINGS,
                config.regions().get("course-boundary"), ProtectedRegionRole.GAME_WORLD_BOUNDARY, true));
        try (var ledger = new ArenaWorldLedger(temporary.resolve("ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var state = new ElytraWorldState("elytra-world", server(world), regions, ledger, journal,
                    event -> true, () -> config, new ElytraWorldState.EntityCleanup() {
                        @Override public void validate(PlayerStateOperation capture) { }
                        @Override public void purge(PlayerStateOperation capture) { }
                    });
            var captured = capture(5);
            journal.begin("elytra-world", 1, captured, new byte[0]);
            var conflicting = new PlayerStateOperation(captured.kind(), captured.operationId(),
                    captured.captureOperationId(), UUID.randomUUID(), captured.sessionId(), captured.matchId(),
                    captured.playerId(), captured.capturedConnectionId(), captured.connectionId(), captured.game(),
                    captured.capturedAt());
            assertThrows(IllegalStateException.class, () -> state.capture(conflicting));
            assertThrows(IllegalStateException.class, () -> ledger.requireLease(captured));
        }
    }
}
