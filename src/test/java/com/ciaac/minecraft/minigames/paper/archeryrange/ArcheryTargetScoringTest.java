package com.ciaac.minecraft.minigames.paper.archeryrange;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ciaac.minecraft.minigames.archeryrange.ArcheryConfig;
import com.ciaac.minecraft.minigames.archeryrange.ArcheryPhase;
import com.ciaac.minecraft.minigames.archeryrange.ArcherySession;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.InMemorySnapshotRepository;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class ArcheryTargetScoringTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final Map<String, Integer> SCORES = Map.of(
            "bullseye", 10, "inner", 7, "middle", 7, "outer", 2);

    @Test void acceptsConfiguredBullseyeAndNonBullseyeTargetHits() {
        assertDoesNotThrow(() -> hit("bullseye", 10, true));
        assertDoesNotThrow(() -> hit("inner", 7, false));
        // Equal point values remain distinguishable through the registered target identity.
        assertDoesNotThrow(() -> hit("middle", 7, false));
    }

    @Test void rejectsBullseyeMismatchAndWrongConfiguredPoints() {
        assertThrows(IllegalArgumentException.class, () -> hit("bullseye", 10, false));
        assertThrows(IllegalArgumentException.class, () -> hit("inner", 7, true));
        assertThrows(IllegalArgumentException.class, () -> hit("inner", 6, false));
    }

    private static void hit(String band, int points, boolean bullseye) throws Exception {
        Fixture fixture = new Fixture();
        UUID targetId = UUID.randomUUID();
        UUID projectileId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        fixture.controller.registerTarget(targetId, 0, "score-target", band);
        fixture.controller.launch(fixture.playerId, projectileId);

        fixture.controller.onProjectileHit(fixture.playerId, projectileId, targetId,
                points, bullseye, eventId);

        // A repeated domain event proves the first shot was recorded without completing the run.
        UUID secondProjectileId = UUID.randomUUID();
        fixture.controller.launch(fixture.playerId, secondProjectileId);
        assertThrows(IllegalStateException.class, () -> fixture.controller.onProjectileHit(
                fixture.playerId, secondProjectileId, targetId, points, bullseye, eventId));

        if (fixture.game.phase() != ArcheryPhase.RUNNING) {
            throw new AssertionError("first valid shot should leave the two-shot session running");
        }
    }

    private static final class Fixture {
        private final UUID playerId = UUID.randomUUID();
        private final UUID connectionId = UUID.randomUUID();
        private final UUID matchId = UUID.randomUUID();
        private final UUID sessionId = UUID.randomUUID();
        private final UUID requestId = UUID.randomUUID();
        private final UUID snapshotId = UUID.randomUUID();
        // Location retains its World weakly; keep it strongly reachable for the fixture.
        private final World world = proxy(World.class, method -> method.getName().equals("getUID")
                ? UUID.randomUUID() : defaultValue(method.getReturnType()));
        private final Player player = proxy(Player.class, method -> method.getName().equals("getUniqueId")
                ? playerId : defaultValue(method.getReturnType()));
        private final ArcheryPaperSettings.LaneSettings lane = new ArcheryPaperSettings.LaneSettings(
                0, "archery-range.lane-0", new Location(world, 2, 65, 2), "score-target");
        private final ArcheryPaperSettings settings = ArcheryPaperSettings.multi(true, List.of(lane), 2,
                Duration.ofMinutes(2), Duration.ofSeconds(30), Material.BOW, SCORES);
        private final ArcheryPaperController controller = new ArcheryPaperController(settings,
                Map.of(0, new ArcheryConfig("scoring-test-v1", 0, 2, 10)), coordinator(),
                new ProtectedRegionRegistry(), new RegionAdmissionRegistry(),
                new TemporaryItemTagger(proxy(Plugin.class, method -> switch (method.getName()) {
            case "getName", "namespace" -> "ciaacplatform";
                    default -> defaultValue(method.getReturnType());
                })),
                Clock.fixed(NOW, ZoneOffset.UTC), StatisticsResultSink.unavailable());
        private final AdmissionRequest request = new AdmissionRequest(requestId, sessionId, snapshotId,
                matchId, playerId, connectionId, GameKey.ARCHERY_RANGE, NOW);
        private final ArcherySession game = seedActiveRun();

        private SessionCoordinator coordinator() {
            SessionRepository repository = proxy(SessionRepository.class,
                    method -> defaultValue(method.getReturnType()));
            PlayerStateGateway gateway = proxy(PlayerStateGateway.class,
                    method -> defaultValue(method.getReturnType()));
            return new SessionCoordinator(new AuthenticationRegistry(), new SessionRegistry(), repository,
                    new InMemorySnapshotRepository(), gateway, IsolationPolicy.strictNoProgress(),
                    new SnapshotEnvelopeCodec(), Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private ArcherySession seedActiveRun() {
            ArcherySession activeGame = new ArcherySession(matchId, playerId,
                    new ArcheryConfig("scoring-test-v1", 0, 2, 10));
            activeGame.start(UUID.randomUUID());
            try {
                Class<?> activeType = java.util.Arrays.stream(ArcheryPaperController.class.getDeclaredClasses())
                        .filter(type -> type.getSimpleName().equals("Active"))
                        .findFirst().orElseThrow();
                var constructor = activeType.getDeclaredConstructor(AdmissionRequest.class, Player.class,
                        ArcherySession.class, ArcheryPaperSettings.LaneSettings.class, Instant.class, Instant.class);
                constructor.setAccessible(true);
                Object active = constructor.newInstance(request, player, activeGame, lane,
                        NOW, NOW.plus(Duration.ofSeconds(30)));
                putActive("activeByPlayer", playerId, active);
                putActive("activeByLane", 0, playerId);
                return activeGame;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not seed real Archery active run", e);
            }
        }

        @SuppressWarnings("unchecked")
        private void putActive(String fieldName, Object key, Object value) throws ReflectiveOperationException {
            Field field = ArcheryPaperController.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            ((Map<Object, Object>) field.get(controller)).put(key, value);
        }
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
}
