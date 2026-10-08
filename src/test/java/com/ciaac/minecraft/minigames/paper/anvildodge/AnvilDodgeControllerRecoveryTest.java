package com.ciaac.minecraft.minigames.paper.anvildodge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgeConfig;
import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgeGame;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.InMemorySnapshotRepository;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.WorldRecoveryPendingException;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.lang.reflect.Field;
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
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.ArmorStand;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnvilDodgeControllerRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private Field bukkitServerField;
    private Object previousBukkitServer;

    @BeforeEach
    void captureBukkitServer() throws ReflectiveOperationException {
        bukkitServerField = Bukkit.class.getDeclaredField("server");
        bukkitServerField.setAccessible(true);
        previousBukkitServer = bukkitServerField.get(null);
    }

    @AfterEach
    void restoreBukkitServer() throws IllegalAccessException {
        bukkitServerField.set(null, previousBukkitServer);
    }

    @Test
    void leaveReportsPendingAndRetryUsesTheSameRecoveryOperation() {
        Harness harness = new Harness(1, session -> true);
        PlayerSession session = harness.join(10);
        UUID root = UUID.randomUUID();

        AdmissionResult result = harness.controller.leave(session.playerId(), root);

        assertEquals(AdmissionStatus.REJECTED, result.status());
        assertEquals("RECOVERY_PENDING", result.code());
        assertEquals(SessionPhase.RECOVERING, session.phase());
        assertEquals(1, harness.gateway.restoreCalls);
        UUID firstOperation = harness.gateway.restoreOperations.getFirst();
        UUID sessionRoot = OperationIds.derive(root, "RESTORE_" + session.playerId());
        UUID scopedRoot = OperationIds.derive(sessionRoot, "SESSION_" + session.sessionId());
        assertEquals(OperationIds.derive(scopedRoot, "SNAPSHOT_RESTORE_APPLY"), firstOperation);

        harness.controller.tick(NOW.plusSeconds(1));

        assertEquals(2, harness.gateway.restoreCalls);
        assertEquals(List.of(firstOperation, firstOperation), harness.gateway.restoreOperations);
        assertEquals(List.of(session.playerId()), harness.gateway.restoredPlayers);
        assertEquals(SessionPhase.CLOSED, session.phase());
        assertEquals(com.ciaac.minecraft.minigames.anvildodge.AnvilDodgePhase.CLOSED,
                harness.controller.status().phase());
    }

    @Test
    void disconnectDefersDepartingPlayerButRestoresOpponentAndFinishesAfterGenericRecovery() {
        Harness harness = new Harness(0, session -> true);
        PlayerSession departing = harness.join(20);
        PlayerSession opponent = harness.join(30);

        harness.controller.onDisconnect(departing.playerId(), UUID.randomUUID());

        assertEquals(List.of(opponent.playerId()), harness.gateway.restoredPlayers);
        assertEquals(SessionPhase.ACTIVE, departing.phase());
        assertEquals(SessionPhase.CLOSED, opponent.phase());
        assertEquals(com.ciaac.minecraft.minigames.anvildodge.AnvilDodgePhase.RECOVERING,
                harness.controller.status().phase());

        AdmissionResult externalRecovery = harness.coordinator.recover(
                departing.sessionId(), UUID.randomUUID(), "AUTHENTICATED_RECONNECT");
        assertEquals(AdmissionStatus.RECOVERED, externalRecovery.status());
        harness.controller.tick(NOW.plusSeconds(1));

        assertEquals(SessionPhase.CLOSED, departing.phase());
        assertEquals(com.ciaac.minecraft.minigames.anvildodge.AnvilDodgePhase.CLOSED,
                harness.controller.status().phase());
    }

    @Test
    void restorationReadinessIsRecheckedOnEveryRetry() {
        boolean[] ready = {false};
        Harness harness = new Harness(0, session -> ready[0]);
        PlayerSession session = harness.join(40);

        AdmissionResult result = harness.controller.leave(session.playerId(), UUID.randomUUID());

        assertEquals(AdmissionStatus.REJECTED, result.status());
        assertEquals("RECOVERY_PENDING", result.code());
        assertEquals(0, harness.gateway.restoreCalls);
        assertEquals(SessionPhase.ACTIVE, session.phase());
        assertFalse(harness.controller.status().admissionReady());

        ready[0] = true;
        harness.controller.tick(NOW.plusSeconds(1));

        assertEquals(1, harness.gateway.restoreCalls);
        assertEquals(SessionPhase.CLOSED, session.phase());
    }

    @Test
    void missingNativeOwnershipRejectsAdmissionBeforeSessionOrGatewayMutation() {
        Harness harness = new Harness(0, session -> true, true);
        AdmissionRequest request = harness.request(45);
        RegionAdmissionToken token = harness.token(request);
        AdmissionResult result = harness.controller.join(player(request.playerId()), request, token);

        assertEquals(AdmissionStatus.REJECTED, result.status());
        assertEquals("CONFIGURATION_UNAVAILABLE", result.code());
        assertEquals(0, harness.gateway.restoreCalls);
        assertEquals(0, harness.gateway.captureCalls);
        assertTrue(harness.sessions.findByPlayer(request.playerId()).isEmpty());
        assertTrue(harness.repository.sessions.isEmpty());
    }

    @Test
    void lateJoinIsRejectedBeforeSessionPreparationOnceMatchIsRunning() {
        Harness harness = new Harness(0, session -> true);
        harness.join(50);
        harness.controller.tick(NOW);
        UUID requestId = UUID.randomUUID();
        UUID playerId = uuid(60);
        UUID connectionId = uuid(61);
        UUID sessionId = uuid(62);
        UUID snapshotId = uuid(63);
        harness.authentication.authenticated(new AuthenticatedSession(uuid(64), playerId,
                connectionId, NOW.minusSeconds(1), NOW.plusSeconds(600)));
        AdmissionRequest request = new AdmissionRequest(requestId, sessionId, snapshotId,
                harness.matchId, playerId, connectionId, GameKey.ANVIL_DODGE, NOW);
        RegionAdmissionToken token = new RegionAdmissionToken(uuid(65), sessionId, playerId,
                "anvil-boundary", NOW, NOW.plusSeconds(60));

        AdmissionResult rejected = harness.controller.join(player(playerId), request, token);

        assertEquals(AdmissionStatus.REJECTED, rejected.status());
        assertEquals("MATCH_UNAVAILABLE", rejected.code());
        assertTrue(harness.sessions.findByPlayer(playerId).isEmpty());
    }

    private static UUID uuid(long value) { return new UUID(0L, value); }

    private static Player player(UUID id) {
        return proxy(Player.class, method -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "isOnline", "isValid", "teleport" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static World world(UUID id) {
        World[] holder = new World[1];
        holder[0] = proxy(World.class, method -> switch (method.getName()) {
            case "getUID" -> id;
            case "getName" -> "synthetic-world";
            case "isChunkLoaded" -> true;
            case "getPlayers" -> List.of();
            case "spawn" -> method.getReturnType().isAssignableFrom(ArmorStand.class)
                    ? armorStand(holder[0]) : null;
            default -> defaultValue(method.getReturnType());
        });
        return holder[0];
    }

    private static ArmorStand armorStand(World world) {
        boolean[] dead = {false};
        PersistentDataContainer data = proxy(PersistentDataContainer.class, method -> defaultValue(method.getReturnType()));
        EntityEquipment equipment = proxy(EntityEquipment.class, method -> defaultValue(method.getReturnType()));
        return proxy(ArmorStand.class, method -> switch (method.getName()) {
            case "getWorld" -> world;
            case "getPersistentDataContainer" -> data;
            case "getEquipment" -> equipment;
            case "isDead" -> dead[0];
            case "remove" -> { dead[0] = true; yield null; }
            case "isValid" -> !dead[0];
            case "teleport" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static void installBukkitServer(Server server) {
        try {
            Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not install scoped Bukkit server proxy", failure);
        }
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        @SuppressWarnings("unchecked")
        T proxy = (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (target, method, args) -> invocation.invoke(method));
        return proxy;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    private static final class MemorySessionRepository implements SessionRepository {
        private final Map<UUID, PlayerSession> sessions = new HashMap<>();
        @Override public boolean save(PlayerSession session) { sessions.put(session.sessionId(), session); return true; }
        @Override public Optional<PlayerSession> find(UUID sessionId) { return Optional.ofNullable(sessions.get(sessionId)); }
        @Override public List<PlayerSession> nonTerminal() {
            return sessions.values().stream().filter(session -> !session.phase().terminal()).toList();
        }
    }

    private static final class RetryingGateway implements PlayerStateGateway {
        private int pendingRestores;
        private int captureCalls;
        private int restoreCalls;
        private final List<UUID> restoreOperations = new ArrayList<>();
        private final List<UUID> restoredPlayers = new ArrayList<>();
        private RetryingGateway(int pendingRestores) { this.pendingRestores = pendingRestores; }
        @Override public Set<PlayerStateFacet> supportedFacets() { return EnumSet.allOf(PlayerStateFacet.class); }
        @Override public PlayerStateSnapshot capture(UUID snapshotId, UUID operationId, UUID sessionId,
                UUID matchId, UUID playerId, UUID connectionId, GameKey game, Instant capturedAt) {
            captureCalls++;
            return new PlayerStateSnapshot(2, snapshotId, operationId, sessionId, matchId,
                    playerId, connectionId, game, capturedAt, Map.of());
        }
        @Override public void enterTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {}
        @Override public void purgeTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {}
        @Override public void restore(UUID operationId, PlayerStateSnapshot snapshot) {
            restoreCalls++;
            restoreOperations.add(operationId);
            if (pendingRestores > 0) { pendingRestores--; throw new WorldRecoveryPendingException(); }
            restoredPlayers.add(snapshot.playerId());
        }
    }

    private final class Harness {
        private final UUID matchId = UUID.randomUUID();
        private final UUID worldId = UUID.randomUUID();
        private final World world = world(worldId);
        private final SessionRegistry sessions = new SessionRegistry();
        private final MemorySessionRepository repository = new MemorySessionRepository();
        private final AuthenticationRegistry authentication = new AuthenticationRegistry();
        private final RetryingGateway gateway;
        private final SessionCoordinator coordinator;
        private final AnvilDodgeGame game;
        private final AnvilDodgeController controller;
        private long domainSequence;

        private Harness(int pendingRestores, Predicate<PlayerSession> restorationReady) {
            this(pendingRestores, restorationReady, false);
        }

        private Harness(int pendingRestores, Predicate<PlayerSession> restorationReady, boolean legacyConstructor) {
            installBukkitServer(proxy(Server.class, method -> switch (method.getName()) {
                case "isPrimaryThread" -> true;
                default -> defaultValue(method.getReturnType());
            }));
            gateway = new RetryingGateway(pendingRestores);
            coordinator = new SessionCoordinator(authentication, sessions, repository,
                    new InMemorySnapshotRepository(), gateway, IsolationPolicy.strictNoProgress(),
                    new SnapshotEnvelopeCodec(), Clock.fixed(NOW, ZoneOffset.UTC));
            AnvilDodgeConfig config = new AnvilDodgeConfig(1, 4, 2, Duration.ofSeconds(1),
                    Duration.ofSeconds(5), 17, "test-v1", IsolationPolicy.strictNoProgress(), 1, 1, 0);
            AnvilDodgePaperSettings settings = new AnvilDodgePaperSettings(true, config, world,
                    "anvil-boundary", new Location(world, 0.5, 65, 0.5),
                    new Location(world, 0, 64, 0), 1, 1);
            ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
            regions.register(new ProtectedRegion("anvil-boundary", GameKey.ANVIL_DODGE,
                    new CuboidRegion(worldId, -2, 60, -2, 2, 80, 2),
                    ProtectedRegionRole.PARTICIPANT_ONLY, true));
            Plugin plugin = proxy(Plugin.class, method -> switch (method.getName()) {
                case "getName" -> "test-plugin";
                case "namespace" -> "ciaacplatform";
                default -> defaultValue(method.getReturnType());
            });
            game = new AnvilDodgeGame(matchId, config);
            controller = legacyConstructor
                    ? new AnvilDodgeController(matchId, game, settings,
                            coordinator, sessions, new RegionAdmissionRegistry(), regions,
                            Clock.fixed(NOW, ZoneOffset.UTC), plugin, StatisticsResultSink.unavailable())
                    : new AnvilDodgeController(matchId, game, settings,
                            coordinator, sessions, new RegionAdmissionRegistry(), regions,
                            Clock.fixed(NOW, ZoneOffset.UTC), plugin, StatisticsResultSink.unavailable(), restorationReady);
        }

        private PlayerSession join(long seed) {
            AdmissionRequest request = request(seed);
            AdmissionResult prepared = coordinator.prepare(request);
            assertEquals(AdmissionStatus.PREPARED, prepared.status());
            PlayerSession session = prepared.session().orElseThrow();
            assertTrue(coordinator.activate(session.sessionId(), uuid(seed + 7_000), NOW));
            if (domainSequence == 0) {
                game.open(new com.ciaac.minecraft.minigames.hotpotato.OperationId(matchId, ++domainSequence));
            }
            game.join(session.playerId(), new com.ciaac.minecraft.minigames.hotpotato.OperationId(matchId, ++domainSequence));
            setControllerSequence(domainSequence);
            return session;
        }

        private AdmissionRequest request(long seed) {
            UUID playerId = uuid(seed);
            UUID connectionId = uuid(seed + 1_000);
            UUID requestId = uuid(seed + 2_000);
            UUID sessionId = uuid(seed + 3_000);
            UUID snapshotId = uuid(seed + 4_000);
            authentication.authenticated(new AuthenticatedSession(uuid(seed + 5_000), playerId,
                    connectionId, NOW.minusSeconds(1), NOW.plusSeconds(600)));
            AdmissionRequest request = new AdmissionRequest(requestId, sessionId, snapshotId,
                    matchId, playerId, connectionId, GameKey.ANVIL_DODGE, NOW);
            return request;
        }

        private RegionAdmissionToken token(AdmissionRequest request) {
            return new RegionAdmissionToken(uuid(request.requestId().getLeastSignificantBits() + 6_000),
                    request.sessionId(), request.playerId(), "anvil-boundary", NOW, NOW.plusSeconds(60));
        }

        private void setControllerSequence(long sequence) {
            try {
                Field field = AnvilDodgeController.class.getDeclaredField("operationSequence");
                field.setAccessible(true);
                field.setLong(controller, sequence);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not align controller operation sequence", failure);
            }
        }
    }

    @FunctionalInterface private interface Invocation {
        Object invoke(java.lang.reflect.Method method) throws Throwable;
    }
}
