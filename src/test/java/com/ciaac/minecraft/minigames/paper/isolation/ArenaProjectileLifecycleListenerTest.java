package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionViolation;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArenaProjectileLifecycleListenerTest {
    private static final UUID PLAYER_ID = uuid(1);
    private static final UUID SESSION_ID = uuid(10);
    private static final UUID MATCH_ID = uuid(11);
    private static final UUID CONNECTION_ID = uuid(12);
    private static final UUID WORLD_ID = uuid(20);
    private static final Instant NOW = Instant.parse("2026-10-04T15:00:00Z");

    @TempDir Path temporary;

    @Test
    void launchWithoutArenaIsolationSessionOrAlreadyCancelledLaunchDoesNotWriteOwnership() {
        try (Environment environment = environment("unmatched-launch")) {
            PlayerSession session = activeSession();
            Player player = player(PLAYER_ID);
            environment.serverState.player = player;
            PlayerStateOperation capture = capture(session);
            environment.ledger.capture(capture, manifest());
            environment.connections.begin(player, NOW.minusSeconds(1));

            ArrowState withoutSessionArrow = new ArrowState(environment.serverState, uuid(30), player);
            ProjectileLaunchEvent withoutSession = new ProjectileLaunchEvent(withoutSessionArrow.arrow);
            environment.listener.launch(withoutSession);

            assertFalse(withoutSession.isCancelled());
            assertEquals(0, withoutSessionArrow.setterCalls);
            assertTrue(withoutSessionArrow.dataState.values.isEmpty());
            assertTrue(environment.scheduler.tasks.isEmpty());
            assertTrue(environment.ledger.entities(capture).isEmpty());

            environment.sessions.register(session);
            ArrowState cancelledArrow = new ArrowState(environment.serverState, uuid(31), player);
            ProjectileLaunchEvent alreadyCancelled = new ProjectileLaunchEvent(cancelledArrow.arrow);
            alreadyCancelled.setCancelled(true);
            environment.listener.launch(alreadyCancelled);

            assertTrue(alreadyCancelled.isCancelled());
            assertEquals(0, cancelledArrow.setterCalls);
            assertTrue(cancelledArrow.dataState.values.isEmpty());
            assertTrue(environment.scheduler.tasks.isEmpty());
            assertTrue(environment.ledger.entities(capture).isEmpty());
            assertEquals(ArenaWorldLedger.Status.CAPTURED, environment.ledger.requireLease(capture).status());
            assertTrue(environment.loggerState.messages.isEmpty());
        }
    }

    @Test
    void unauthenticatedActiveArenaLaunchCancelsAndDefersViolationUntilAfterNativeCallback() {
        try (Environment environment = environment("unauthenticated-launch")) {
            PlayerSession session = activeSession();
            environment.sessions.register(session);
            Player player = player(PLAYER_ID);
            environment.serverState.player = player;
            PlayerStateOperation capture = capture(session);
            environment.ledger.capture(capture, manifest());
            environment.connections.begin(player, NOW.minusSeconds(1));
            ArrowState arrow = new ArrowState(environment.serverState, uuid(32), player);
            ProjectileLaunchEvent event = new ProjectileLaunchEvent(arrow.arrow);

            environment.listener.launch(event);

            assertTrue(event.isCancelled());
            assertEquals(0, environment.violations.size());
            assertEquals(1, environment.scheduler.tasks.size());
            assertEquals(0, arrow.setterCalls);
            assertTrue(arrow.dataState.values.isEmpty());
            assertTrue(environment.ledger.entities(capture).isEmpty());
            assertTrue(environment.loggerState.messages.isEmpty());

            environment.scheduler.runNext();

            assertEquals(List.of(new Violation(player, session, SessionViolation.WORLD_ISOLATION_FAILURE)),
                    environment.violations);
        }
    }

    @Test
    void deferredLaunchViolationIgnoresSessionThatIsNoLongerRegistered() {
        try (Environment environment = environment("stale-session")) {
            PlayerSession session = activeSession();
            environment.sessions.register(session);
            Player player = player(PLAYER_ID);
            environment.serverState.player = player;
            PlayerStateOperation capture = capture(session);
            environment.ledger.capture(capture, manifest());
            environment.connections.begin(player, NOW.minusSeconds(1));
            ArrowState arrow = new ArrowState(environment.serverState, uuid(33), player);
            ProjectileLaunchEvent event = new ProjectileLaunchEvent(arrow.arrow);

            environment.listener.launch(event);
            assertTrue(event.isCancelled());
            assertEquals(1, environment.scheduler.tasks.size());
            assertTrue(environment.violations.isEmpty());

            closeSession(session);
            environment.sessions.releaseClosed(session.sessionId());
            environment.scheduler.runNext();

            assertTrue(environment.violations.isEmpty());
            assertTrue(environment.ledger.entities(capture).isEmpty());
            assertTrue(environment.loggerState.messages.isEmpty());
        }
    }

    @Test
    void unrelatedArrowWorldAndHitEventsDoNotChangeLedgerOrProvider() {
        try (Environment environment = environment("unrelated-events")) {
            PlayerSession session = activeSession();
            PlayerStateOperation capture = capture(session);
            environment.ledger.capture(capture, manifest());
            ArrowState unrelated = new ArrowState(environment.serverState, uuid(34), player(PLAYER_ID));

            environment.listener.added(new EntityAddToWorldEvent(unrelated.entity(), unrelated.world));
            environment.listener.removed(new EntityRemoveFromWorldEvent(unrelated.entity(), unrelated.world));
            ProjectileHitEvent hit = new ProjectileHitEvent(unrelated.arrow);
            environment.listener.hit(hit);

            assertFalse(hit.isCancelled());
            assertEquals(0, unrelated.setterCalls);
            assertTrue(unrelated.dataState.values.isEmpty());
            assertTrue(environment.scheduler.tasks.isEmpty());
            assertTrue(environment.ledger.entities(capture).isEmpty());
            assertEquals(ArenaWorldLedger.Status.CAPTURED, environment.ledger.requireLease(capture).status());
            assertTrue(environment.violations.isEmpty());
            assertTrue(environment.loggerState.messages.isEmpty());
        }
    }

    @Test
    void closeSuppressesQueuedViolationAndEntityReconciliationCallbacks() {
        try (Environment environment = environment("closed-callbacks")) {
            PlayerSession session = activeSession();
            environment.sessions.register(session);
            Player player = player(PLAYER_ID);
            environment.serverState.player = player;
            PlayerStateOperation capture = capture(session);
            environment.ledger.capture(capture, manifest());
            environment.connections.begin(player, NOW.minusSeconds(1));

            ProjectileLaunchEvent launch = new ProjectileLaunchEvent(
                    new ArrowState(environment.serverState, uuid(35), player).arrow);
            environment.listener.launch(launch);
            assertTrue(launch.isCancelled());
            assertEquals(1, environment.scheduler.tasks.size());
            assertTrue(environment.violations.isEmpty());

            environment.ledger.arm(capture);
            ArrowState removedArrow = new ArrowState(environment.serverState, uuid(36), player);
            environment.ownership.beforeAdd(capture, removedArrow.arrow);
            removedArrow.simulateRemoved();
            environment.listener.removed(new EntityRemoveFromWorldEvent(removedArrow.entity(), removedArrow.world));
            assertEquals(2, environment.scheduler.tasks.size());
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    environment.ledger.findEntity(removedArrow.id).orElseThrow().status());

            environment.listener.close();
            environment.scheduler.runNext();
            environment.scheduler.runNext();

            assertTrue(environment.violations.isEmpty());
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    environment.ledger.findEntity(removedArrow.id).orElseThrow().status());
            assertTrue(environment.loggerState.messages.isEmpty());
        }
    }

    @Test
    void disabledPluginDoesNotScheduleDeferredNativeRemovalWork() {
        try (Environment environment = environment("disabled-removal")) {
            PlayerSession session = activeSession();
            PlayerStateOperation capture = capture(session);
            environment.ledger.capture(capture, manifest());
            environment.ledger.arm(capture);
            ArrowState arrow = new ArrowState(environment.serverState, uuid(37), player(PLAYER_ID));
            environment.ownership.beforeAdd(capture, arrow.arrow);
            arrow.simulateRemoved();
            environment.pluginState.enabled = false;

            environment.listener.removed(new EntityRemoveFromWorldEvent(arrow.entity(), arrow.world));

            assertTrue(environment.scheduler.tasks.isEmpty());
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    environment.ledger.findEntity(arrow.id).orElseThrow().status());
            assertTrue(environment.loggerState.messages.isEmpty());
        }
    }

    @Test
    void eventsAfterCloseCannotConfirmOrMutateOwnedEntity() {
        try (Environment environment = environment("events-after-close")) {
            PlayerSession session = activeSession();
            environment.sessions.register(session);
            Player player = player(PLAYER_ID);
            environment.serverState.player = player;
            PlayerStateOperation capture = capture(session);
            environment.ledger.capture(capture, manifest());
            environment.ledger.arm(capture);
            environment.connections.begin(player, NOW.minusSeconds(1));
            ArrowState arrow = new ArrowState(environment.serverState, uuid(38), player);
            environment.ownership.beforeAdd(capture, arrow.arrow);
            arrow.simulateAdded();
            int setterCalls = arrow.setterCalls;

            environment.listener.close();
            environment.listener.added(new EntityAddToWorldEvent(arrow.entity(), arrow.world));
            environment.listener.removed(new EntityRemoveFromWorldEvent(arrow.entity(), arrow.world));
            ProjectileHitEvent hit = new ProjectileHitEvent(arrow.arrow);
            environment.listener.hit(hit);
            ProjectileLaunchEvent launch = new ProjectileLaunchEvent(arrow.arrow);
            environment.listener.launch(launch);

            assertFalse(hit.isCancelled());
            assertFalse(launch.isCancelled());
            assertEquals(setterCalls, arrow.setterCalls);
            assertTrue(environment.scheduler.tasks.isEmpty());
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    environment.ledger.findEntity(arrow.id).orElseThrow().status());
            assertTrue(environment.serverState.entities.get(arrow.id) == arrow.entity());
            assertTrue(environment.violations.isEmpty());
            assertTrue(environment.loggerState.messages.isEmpty());
        }
    }

    @Test
    void closeRejectsWrongThreadWithoutDisablingListener() {
        try (Environment environment = environment("wrong-thread-close")) {
            PlayerSession session = activeSession();
            environment.sessions.register(session);
            Player player = player(PLAYER_ID);
            environment.serverState.player = player;
            PlayerStateOperation capture = capture(session);
            environment.ledger.capture(capture, manifest());
            environment.connections.begin(player, NOW.minusSeconds(1));

            environment.serverState.primaryThread = false;
            assertThrows(IllegalStateException.class, environment.listener::close);
            environment.serverState.primaryThread = true;
            ProjectileLaunchEvent event = new ProjectileLaunchEvent(
                    new ArrowState(environment.serverState, uuid(39), player).arrow);
            environment.listener.launch(event);

            assertTrue(event.isCancelled());
            assertEquals(1, environment.scheduler.tasks.size());
            assertTrue(environment.violations.isEmpty());
            environment.listener.close();
        }
    }

    private Environment environment(String name) {
        SchedulerState schedulerState = new SchedulerState();
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, schedulerState::invoke);
        ServerState serverState = new ServerState(scheduler);
        Server server = proxy(Server.class, serverState::invoke);
        LoggerState loggerState = new LoggerState();
        PluginState pluginState = new PluginState();
        Plugin plugin = proxy(Plugin.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getServer" -> server;
            case "getName" -> "CIAACPlatform";
            case "namespace" -> "ciaacplatform";
            case "getLogger" -> loggerState.logger;
            case "isEnabled" -> pluginState.enabled;
            default -> defaultValue(method.getReturnType());
        });
        ArenaWorldLedger ledger = new ArenaWorldLedger(temporary.resolve(name + "-ledger"));
        ExternalOperationJournal journal = new ExternalOperationJournal(temporary.resolve(name + "-journal"));
        var ownership = new ArenaProjectileOwnership(plugin, ledger);
        ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
        ArenaWorldStatePort worldState = new ArenaWorldStatePort(server, regions, ledger, ownership, journal,
                new RecordingAudit());
        SessionRegistry sessions = new SessionRegistry();
        AuthenticationRegistry authentication = new AuthenticationRegistry();
        ConnectionRegistry connections = new ConnectionRegistry();
        List<Violation> violations = new ArrayList<>();
        var listener = new ArenaProjectileLifecycleListener(plugin, ledger, ownership, worldState, sessions,
                authentication, connections, regions, new RegionAdmissionRegistry(),
                (player, session, violation) -> violations.add(new Violation(player, session, violation)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Environment(serverState, schedulerState, loggerState, pluginState, listener, sessions,
                connections, violations, ownership, ledger, journal);
    }

    private static PlayerSession activeSession() {
        PlayerSession session = new PlayerSession(SESSION_ID, MATCH_ID, PLAYER_ID, GameKey.ARENA,
                NOW.minusSeconds(10));
        session.transition(uuid(100), SessionPhase.REQUESTED, SessionPhase.SNAPSHOTTING,
                NOW.minusSeconds(9), "test");
        session.bindSnapshot(uuid(13));
        session.transition(uuid(101), SessionPhase.SNAPSHOTTING, SessionPhase.SNAPSHOT_COMMITTED,
                NOW.minusSeconds(8), "test");
        session.transition(uuid(102), SessionPhase.SNAPSHOT_COMMITTED, SessionPhase.PREPARING,
                NOW.minusSeconds(7), "test");
        session.transition(uuid(103), SessionPhase.PREPARING, SessionPhase.ACTIVE,
                NOW.minusSeconds(6), "test");
        return session;
    }

    private static void closeSession(PlayerSession session) {
        session.transition(uuid(104), SessionPhase.ACTIVE, SessionPhase.FINISHING,
                NOW.minusSeconds(5), "test");
        session.transition(uuid(105), SessionPhase.FINISHING, SessionPhase.RESTORING,
                NOW.minusSeconds(4), "test");
        session.transition(uuid(106), SessionPhase.RESTORING, SessionPhase.CLOSED,
                NOW.minusSeconds(3), "test");
    }

    private static PlayerStateOperation capture(PlayerSession session) {
        UUID operationId = uuid(40);
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, operationId, operationId, uuid(13),
                session.sessionId(), session.matchId(), session.playerId(), CONNECTION_ID, CONNECTION_ID,
                GameKey.ARENA, NOW.minusSeconds(10));
    }

    private static byte[] manifest() {
        return new ArenaWorldManifest("Paper test-version", List.of(
                new ProtectedRegion("arena", GameKey.ARENA,
                        new CuboidRegion(WORLD_ID, 0, 0, 0, 2, 2, 2),
                        ProtectedRegionRole.PARTICIPANT_ONLY, true),
                new ProtectedRegion("spectators", GameKey.ARENA,
                        new CuboidRegion(WORLD_ID, 5, 0, 0, 7, 2, 2),
                        ProtectedRegionRole.SPECTATOR_PUBLIC, true))).encode();
    }

    private static Player player(UUID id) {
        return proxy(Player.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "isOnline", "isValid" -> true;
            case "toString" -> "test-player-" + id;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static UUID uuid(long value) { return new UUID(0, value); }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    private record Violation(Player player, PlayerSession session, SessionViolation violation) {}

    private record Environment(ServerState serverState, SchedulerState scheduler, LoggerState loggerState,
            PluginState pluginState, ArenaProjectileLifecycleListener listener, SessionRegistry sessions,
            ConnectionRegistry connections, List<Violation> violations, ArenaProjectileOwnership ownership,
            ArenaWorldLedger ledger, ExternalOperationJournal journal)
            implements AutoCloseable {
        @Override public void close() {
            journal.close();
            ledger.close();
        }
    }

    private static final class ServerState {
        private final BukkitScheduler scheduler;
        private final Map<UUID, Entity> entities = new HashMap<>();
        private boolean primaryThread = true;
        private Player player;

        private ServerState(BukkitScheduler scheduler) { this.scheduler = scheduler; }

        private Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "isPrimaryThread" -> primaryThread;
                case "getScheduler" -> scheduler;
                case "getPlayer" -> player;
                case "getEntity" -> entities.get(arguments[0]);
                case "toString" -> "test-server";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static final class SchedulerState {
        private final List<Runnable> tasks = new ArrayList<>();

        private Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getName().equals("runTask")) {
                tasks.add((Runnable) arguments[1]);
                return null;
            }
            return defaultValue(method.getReturnType());
        }

        private void runNext() { tasks.removeFirst().run(); }
    }

    private static final class LoggerState {
        private final List<String> messages = new ArrayList<>();
        private final Logger logger = Logger.getAnonymousLogger();

        private LoggerState() {
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
                @Override public void flush() {}
                @Override public void close() {}
            });
        }
    }

    private static final class ArrowState {
        private final ServerState server;
        private final UUID id;
        private final World world;
        private final PersistentDataContainer data;
        private final DataState dataState = new DataState();
        private final Arrow arrow;
        private boolean persistent = true;
        private boolean inWorld;
        private boolean valid = true;
        private boolean dead;
        private int fireTicks = 100;
        private AbstractArrow.PickupStatus pickupStatus = AbstractArrow.PickupStatus.ALLOWED;
        private int setterCalls;

        private ArrowState(ServerState server, UUID id, Player shooter) {
            this.server = server;
            this.id = id;
            this.world = proxy(World.class, (instance, method, arguments) -> switch (method.getName()) {
                case "getUID" -> WORLD_ID;
                case "getName" -> "test-arena";
                default -> defaultValue(method.getReturnType());
            });
            this.data = proxy(PersistentDataContainer.class, dataState::invoke);
            this.arrow = proxy(Arrow.class, (instance, method, arguments) -> switch (method.getName()) {
                case "getShooter" -> shooter;
                case "getUniqueId" -> id;
                case "getWorld" -> world;
                case "getPersistentDataContainer" -> data;
                case "isPersistent" -> persistent;
                case "setPersistent" -> { persistent = (boolean) arguments[0]; setterCalls++; yield null; }
                case "setFireTicks" -> { fireTicks = (int) arguments[0]; setterCalls++; yield null; }
                case "setPickupStatus" -> {
                    pickupStatus = (AbstractArrow.PickupStatus) arguments[0]; setterCalls++; yield null;
                }
                case "getFireTicks" -> fireTicks;
                case "getPickupStatus" -> pickupStatus;
                case "isInWorld" -> inWorld;
                case "isValid" -> valid;
                case "isDead" -> dead;
                case "getVehicle" -> null;
                case "getPassengers" -> List.of();
                case "toString" -> "test-arrow-" + id;
                default -> defaultValue(method.getReturnType());
            });
        }

        private Entity entity() { return arrow; }

        private void simulateRemoved() {
            inWorld = false;
            valid = false;
            dead = true;
            server.entities.remove(id);
        }

        private void simulateAdded() {
            inWorld = true;
            valid = true;
            dead = false;
            server.entities.put(id, entity());
        }
    }

    private static final class PluginState { private boolean enabled = true; }

    private static final class DataState {
        private final Map<NamespacedKey, Object> values = new HashMap<>();

        private Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "set" -> { values.put((NamespacedKey) arguments[0], arguments[2]); yield null; }
                case "get" -> values.get(arguments[0]);
                case "has" -> values.containsKey(arguments[0]);
                case "remove" -> { values.remove(arguments[0]); yield null; }
                case "getKeys" -> Set.copyOf(values.keySet());
                case "isEmpty" -> values.isEmpty();
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static final class RecordingAudit implements AuditRepository {
        private final List<AuditEvent> events = new ArrayList<>();
        @Override public boolean append(AuditEvent event) { events.add(event); return true; }
    }
}
