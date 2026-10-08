package com.ciaac.minecraft.minigames.paper.archeryrange;

import static org.junit.jupiter.api.Assertions.*;

import com.ciaac.minecraft.minigames.archeryrange.ArcheryConfig;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.InMemorySnapshotRepository;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class ArcheryReadinessTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final String TARGET_ID = "lane-target";
    private static final Map<String, Integer> SCORES = Map.of(
            "bullseye", 10, "inner", 7, "middle", 5, "outer", 2);

    @Test void missingIncompleteAndAmbiguousTargetsCloseStatusAndRejectBeforeCapture() {
        assertUnavailable(new Fixture(List.of()));

        Fixture missingBand = new Fixture(List.of("bullseye", "inner", "middle"));
        assertUnavailable(missingBand);

        Fixture ambiguous = new Fixture(List.of("bullseye", "middle", "outer"));
        ambiguous.addStand(Set.of(tag("inner"), tag("outer")));
        assertUnavailable(ambiguous);
    }

    @Test void allFourConfiguredTargetBandsAllowReadyStatus() {
        Fixture fixture = new Fixture(List.copyOf(SCORES.keySet()));

        assertTrue(fixture.controller.status().enabled());
        assertEquals(1, fixture.controller.status().configuredLanes());
        assertEquals(0, fixture.gateway.captureCalls);
    }

    private static void assertUnavailable(Fixture fixture) {
        assertFalse(fixture.controller.status().enabled());

        var result = fixture.controller.join(fixture.request, fixture.player);

        assertEquals(AdmissionStatus.REJECTED, result.status());
        assertEquals("CONFIGURATION_UNAVAILABLE", result.code());
        assertEquals(0, fixture.gateway.captureCalls, "unready targets must be rejected before snapshot capture");
        assertTrue(fixture.sessionRepository.sessions.isEmpty(), "unready targets must not register a session");
        assertTrue(fixture.sessions.findByPlayer(fixture.playerId).isEmpty());
    }

    private static String tag(String band) {
        return "ciaac-archery-target:" + TARGET_ID + ":" + band;
    }

    private static World world(UUID id, List<Entity> entities) {
        return proxy(World.class, method -> switch (method.getName()) {
            case "getUID" -> id;
            case "getEntities" -> entities;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ArmorStand stand(World world, Set<String> tags) {
        return proxy(ArmorStand.class, method -> switch (method.getName()) {
            case "getWorld" -> world;
            case "getLocation" -> new Location(world, 4, 66, 4);
            case "getScoreboardTags" -> tags;
            case "isValid" -> true;
            case "isDead", "isMarker" -> false;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Player player(UUID id) {
        return proxy(Player.class, method -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "isOnline", "isValid" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Plugin plugin() {
        return proxy(Plugin.class, method -> switch (method.getName()) {
            case "getName", "namespace" -> "ciaacplatform";
            default -> defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        Object value = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + " test proxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> null;
                        };
                    }
                    return invocation.invoke(method);
                });
        return type.cast(value);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0;
        if (type == char.class) return '\0';
        throw new AssertionError("unknown primitive: " + type);
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method) throws Throwable;
    }

    private static final class Fixture {
        private final UUID playerId = UUID.randomUUID();
        private final UUID connectionId = UUID.randomUUID();
        private final UUID matchId = UUID.randomUUID();
        private final UUID sessionId = UUID.randomUUID();
        private final UUID requestId = UUID.randomUUID();
        private final UUID snapshotId = UUID.randomUUID();
        // Bukkit Location stores its World weakly, so retain this strong reference for the fixture.
        private final List<Entity> entities = new ArrayList<>();
        private final World world = world(UUID.randomUUID(), entities);
        private final Player player = player(playerId);
        private final ArcheryPaperSettings.LaneSettings lane = new ArcheryPaperSettings.LaneSettings(
                0, "archery-range.lane-0", new Location(world, 2, 65, 2), TARGET_ID);
        private final ProtectedRegion region = new ProtectedRegion(lane.regionId(), GameKey.ARCHERY_RANGE,
                new CuboidRegion(worldId(), 0, 60, 0, 16, 80, 16),
                ProtectedRegionRole.PARTICIPANT_ONLY, true);
        private final ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
        private final SessionRegistry sessions = new SessionRegistry();
        private final SessionRepositoryMemory sessionRepository = new SessionRepositoryMemory();
        private final RecordingGateway gateway = new RecordingGateway();
        private final ArcheryPaperController controller;
        private final AdmissionRequest request = new AdmissionRequest(requestId, sessionId, snapshotId,
                matchId, playerId, connectionId, GameKey.ARCHERY_RANGE, NOW);

        private Fixture(List<String> bands) {
            bands.forEach(band -> addStand(Set.of(tag(band))));
            regions.register(region);
            AuthenticationRegistry authentication = new AuthenticationRegistry();
            authentication.authenticated(new AuthenticatedSession(UUID.randomUUID(), playerId, connectionId,
                    NOW.minusSeconds(1), NOW.plusSeconds(600)));
            SessionCoordinator coordinator = new SessionCoordinator(authentication, sessions, sessionRepository,
                    new InMemorySnapshotRepository(), gateway, IsolationPolicy.strictNoProgress(),
                    new SnapshotEnvelopeCodec(), Clock.fixed(NOW, ZoneOffset.UTC));
            ArcheryPaperSettings settings = ArcheryPaperSettings.multi(true, List.of(lane), 4,
                    Duration.ofMinutes(2), Duration.ofSeconds(30), Material.BOW, SCORES);
            controller = new ArcheryPaperController(settings, Map.of(0,
                    new ArcheryConfig("archery-readiness-v1", 0, 4, 10)), coordinator,
                    regions, new RegionAdmissionRegistry(), new TemporaryItemTagger(plugin()),
                    Clock.fixed(NOW, ZoneOffset.UTC), StatisticsResultSink.unavailable());
        }

        private UUID worldId() {
            return world.getUID();
        }

        private void addStand(Set<String> tags) {
            entities.add(stand(world, tags));
        }
    }

    private static final class RecordingGateway implements PlayerStateGateway {
        private int captureCalls;

        @Override public Set<PlayerStateFacet> supportedFacets() {
            return EnumSet.allOf(PlayerStateFacet.class);
        }

        @Override public PlayerStateSnapshot capture(UUID snapshotId, UUID operationId, UUID sessionId,
                UUID matchId, UUID playerId, UUID connectionId, GameKey game, Instant capturedAt) {
            captureCalls++;
            throw new IllegalStateException("snapshot capture must not run while targets are unready");
        }

        @Override public void enterTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {
            throw new AssertionError("temporary state must not be entered");
        }

        @Override public void purgeTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {
            throw new AssertionError("temporary state must not be purged");
        }

        @Override public void restore(UUID operationId, PlayerStateSnapshot snapshot) {
            throw new AssertionError("player state must not be restored");
        }
    }

    private static final class SessionRepositoryMemory implements SessionRepository {
        private final Map<UUID, PlayerSession> sessions = new HashMap<>();

        @Override public boolean save(PlayerSession session) {
            sessions.put(session.sessionId(), session);
            return true;
        }

        @Override public Optional<PlayerSession> find(UUID sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override public List<PlayerSession> nonTerminal() {
            return sessions.values().stream().filter(session -> !session.phase().terminal()).toList();
        }
    }
}
