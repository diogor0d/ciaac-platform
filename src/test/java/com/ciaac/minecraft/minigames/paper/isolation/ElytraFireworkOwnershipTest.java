package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.NativeProcessIdentity;
import io.papermc.paper.datacomponent.item.Fireworks;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ElytraFireworkOwnershipTest {
    private static final UUID WORLD_ID = uuid(700);
    private static final Instant CAPTURED_AT = Instant.parse("2026-10-07T11:12:13.123456789Z");

    @TempDir Path temporary;

    @Test
    void recordsIntentBeforeInsertionAndConfirmsOnlyExactNativeFirework() {
        try (Environment env = environment("native-add")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(env, capture);
            FireworkState rocket = new FireworkState(env.serverState, uuid(40), WORLD_ID, capture.playerId());
            rocket.meta.power = 0; // Native item has its default effective flight duration of one.

            env.ownership.beforeAdd(capture, rocket.firework);

            assertFalse(rocket.persistent);
            assertFalse(rocket.inWorld);
            assertTrue(env.ownership.hasLabel(rocket.firework));
            assertEquals(Set.of(expectedLabel(capture)), Set.copyOf(rocket.data.values.values()));
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    env.ledger.findEntity(rocket.id).orElseThrow().status());
            assertNull(env.serverState.entities.get(rocket.id));

            FireworkState impostor = new FireworkState(env.serverState, rocket.id, WORLD_ID, capture.playerId());
            impostor.data.values.putAll(rocket.data.values);
            impostor.persistent = false;
            assertThrows(IllegalStateException.class, () -> env.ownership.afterAdd(impostor.firework));
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING, env.ledger.findEntity(rocket.id).orElseThrow().status());

            env.serverState.add(rocket);
            env.ownership.afterAdd(rocket.firework);
            env.ownership.afterAdd(rocket.firework);
            assertEquals(ArenaWorldLedger.EntityStatus.CONFIRMED,
                    env.ledger.findEntity(rocket.id).orElseThrow().status());
        }
    }

    @Test
    void cancelledSpawnSettlesOnlyBeforeInsertionAndNativeRemovalIsProven() {
        try (Environment env = environment("cancelled")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(env, capture);
            FireworkState rocket = new FireworkState(env.serverState, uuid(41), WORLD_ID, capture.playerId());
            env.ownership.beforeAdd(capture, rocket.firework);
            env.ownership.cancelledBeforeAdd(rocket.firework);
            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED,
                    env.ledger.findEntity(rocket.id).orElseThrow().status());

            FireworkState tracked = new FireworkState(env.serverState, uuid(42), WORLD_ID, capture.playerId());
            env.ownership.beforeAdd(capture, tracked.firework);
            env.serverState.add(tracked);
            env.ownership.afterAdd(tracked.firework);
            assertThrows(IllegalStateException.class, () -> env.ownership.afterRemove(tracked.firework));
            env.serverState.entities.remove(tracked.id);
            tracked.valid = false;
            tracked.dead = false;
            tracked.inWorld = true;
            env.ownership.afterUntracking(tracked.firework);
            assertTrue(tracked.dead);
            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED,
                    env.ledger.findEntity(tracked.id).orElseThrow().status());
        }
    }

    @Test
    void rejectsWrongPlayerAttachmentWorldAndInvalidEffectiveFireworksBeforeLedgerWrite() {
        try (Environment env = environment("invalid-before-add")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(env, capture);
            for (String drift : List.of("spawner", "attachment", "effective-zero", "effective-two",
                    "effective-effect", "component-removed", "vehicle", "passenger", "fire")) {
                FireworkState rocket = new FireworkState(env.serverState, uuid(50 + drift.hashCode()), WORLD_ID,
                        capture.playerId());
                rocket.drift(drift);
                assertThrows(IllegalStateException.class, () -> env.ownership.beforeAdd(capture, rocket.firework), drift);
                assertTrue(rocket.data.values.isEmpty(), drift);
                assertEquals(0, rocket.setterCalls, drift);
            }
            FireworkState wrongWorld = new FireworkState(env.serverState, uuid(60), uuid(701), capture.playerId());
            env.serverState.worlds.remove(uuid(701));
            assertThrows(IllegalStateException.class, () -> env.ownership.beforeAdd(capture, wrongWorld.firework));
            assertTrue(env.ledger.entities(capture).isEmpty());
        }
    }

    @Test
    void purgePrevalidatesAllRowsAndColdAbsenceRequiresAnotherProcessAndLoadedWorld() {
        try (Environment env = environment("prevalidate")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(env, capture);
            FireworkState first = confirmed(env, capture, uuid(40));
            FireworkState second = confirmed(env, capture, uuid(41));
            second.effectiveFlightDuration = 2;
            assertThrows(IllegalStateException.class, () -> env.ownership.purge(purge(capture)));
            assertEquals(0, first.removalCalls);
            assertEquals(0, second.removalCalls);
            assertEquals(ArenaWorldLedger.Status.ARMED, env.ledger.requireLease(capture).status());
        }
        try (Environment env = environment("cold-proof")) {
            PlayerStateOperation capture = capture(1, 22, 3);
            arm(env, capture);
            UUID id = uuid(442);
            env.ledger.beginEntity(capture, id, WORLD_ID, ArenaWorldLedger.EntityType.ELYTRA_FIREWORK, priorProcess());
            env.ownership.purge(purge(capture));
            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED, env.ledger.findEntity(id).orElseThrow().status());
            assertEquals(ArenaWorldLedger.Status.PURGED, env.ledger.requireLease(capture).status());
        }
        try (Environment env = environment("same-process")) {
            PlayerStateOperation capture = capture(1, 23, 3);
            arm(env, capture);
            UUID id = uuid(443);
            env.ledger.beginEntity(capture, id, WORLD_ID, ArenaWorldLedger.EntityType.ELYTRA_FIREWORK);
            assertThrows(IllegalStateException.class, () -> env.ownership.purge(purge(capture)));
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING, env.ledger.findEntity(id).orElseThrow().status());
        }
    }

    @Test
    void validationAllowsAttachmentToEndButRejectsChangedOwnerMetadataOrLabel() {
        for (String drift : List.of("label", "spawner", "effect")) {
            try (Environment env = environment("drift-" + drift)) {
                PlayerStateOperation capture = capture(1, 30 + drift.hashCode(), 3);
                arm(env, capture);
                FireworkState rocket = confirmed(env, capture, uuid(80));
                rocket.attached = null; // Elytra gliding may end while the boost firework remains alive.
                if (drift.equals("label")) rocket.data.values.put(
                        new NamespacedKey(env.plugin, "elytra-firework-owner-v1"), "foreign-owner");
                if (drift.equals("spawner")) rocket.spawningId = uuid(999);
                if (drift.equals("effect")) rocket.effectiveEffects = List.of(FireworkEffect.builder()
                        .withColor(Color.RED).build());
                assertThrows(IllegalStateException.class, () -> env.ownership.validatePurge(purge(capture)), drift);
                assertEquals(0, rocket.removalCalls, drift);
            }
        }
    }

    private Environment environment(String name) {
        ServerState state = new ServerState();
        state.worlds.put(WORLD_ID, world(WORLD_ID));
        Server server = proxy(Server.class, state::invoke);
        Plugin plugin = proxy(Plugin.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getServer" -> server;
            case "getName" -> "CIAACPlatform";
            case "namespace" -> "ciaacplatform";
            default -> defaultValue(method.getReturnType());
        });
        ArenaWorldLedger ledger = new ArenaWorldLedger(temporary.resolve(name));
        return new Environment(state, plugin, new ElytraFireworkOwnership(plugin, ledger,
                firework -> state.fireworkStates.get(firework).component()), ledger);
    }

    private static FireworkState confirmed(Environment env, PlayerStateOperation capture, UUID id) {
        FireworkState rocket = new FireworkState(env.serverState, id, WORLD_ID, capture.playerId());
        env.ownership.beforeAdd(capture, rocket.firework);
        env.serverState.add(rocket);
        env.ownership.afterAdd(rocket.firework);
        return rocket;
    }

    private static void arm(Environment env, PlayerStateOperation capture) {
        env.ledger.capture(capture, manifestPrefix());
        env.ledger.arm(capture);
    }

    private static byte[] manifestPrefix() {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var out = new java.io.DataOutputStream(bytes)) {
                out.writeInt(0x43414557); out.writeInt(1);
                out.writeUTF("test-provider"); out.writeUTF("test-course");
                out.writeLong(WORLD_ID.getMostSignificantBits()); out.writeLong(WORLD_ID.getLeastSignificantBits());
            }
            return bytes.toByteArray();
        } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static PlayerStateOperation capture(long operation, long session, long player) {
        UUID connection = uuid(100 + player);
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, uuid(operation), uuid(operation),
                uuid(200 + operation), uuid(session), uuid(300 + session), uuid(player), connection, connection,
                GameKey.ELYTRA_RINGS, CAPTURED_AT);
    }

    private static PlayerStateOperation purge(PlayerStateOperation capture) {
        return new PlayerStateOperation(PlayerStateOperation.Kind.PURGE, uuid(900), capture.captureOperationId(),
                capture.snapshotId(), capture.sessionId(), capture.matchId(), capture.playerId(),
                capture.capturedConnectionId(), uuid(901), capture.game(), capture.capturedAt());
    }

    private static NativeProcessIdentity priorProcess() {
        NativeProcessIdentity current = NativeProcessIdentity.current();
        return new NativeProcessIdentity(current.pid() == Long.MAX_VALUE ? current.pid() - 1 : current.pid() + 1,
                current.startedAtEpochMillis() == Long.MAX_VALUE ? current.startedAtEpochMillis() - 1
                        : current.startedAtEpochMillis() + 1);
    }

    private static String expectedLabel(PlayerStateOperation c) {
        return "v1/" + c.sessionId() + "/" + c.matchId() + "/" + c.playerId() + "/"
                + c.captureOperationId() + "/" + c.capturedConnectionId();
    }
    private static UUID uuid(long value) { return new UUID(0, value); }
    private static World world(UUID id) {
        return proxy(World.class, (instance, method, arguments) -> method.getName().equals("getUID") ? id
                : defaultValue(method.getReturnType()));
    }
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
        throw new IllegalArgumentException(type.toString());
    }

    private static final class Environment implements AutoCloseable {
        private final ServerState serverState;
        private final Plugin plugin;
        private final ElytraFireworkOwnership ownership;
        private final ArenaWorldLedger ledger;
        private Environment(ServerState s, Plugin p, ElytraFireworkOwnership o, ArenaWorldLedger l) {
            serverState = s; plugin = p; ownership = o; ledger = l;
        }
        @Override public void close() { ledger.close(); }
    }

    private static final class ServerState {
        private final Map<UUID, Entity> entities = new HashMap<>();
        private final Map<UUID, World> worlds = new HashMap<>();
        private final Map<UUID, Player> players = new HashMap<>();
        private final Map<Firework, FireworkState> fireworkStates = new IdentityHashMap<>();
        private Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "isPrimaryThread" -> true;
                case "getLogger" -> java.util.logging.Logger.getLogger("ElytraFireworkOwnershipTest");
                case "getEntity" -> entities.get(args[0]);
                case "getWorld" -> worlds.get(args[0]);
                case "getPlayer" -> players.get(args[0]);
                default -> defaultValue(method.getReturnType());
            };
        }
        private void add(FireworkState firework) {
            firework.inWorld = true; firework.valid = true; entities.put(firework.id, firework.firework);
        }
    }

    private static final class DataState {
        private final Map<NamespacedKey, Object> values = new HashMap<>();
        private PersistentDataContainer container = proxy(PersistentDataContainer.class, (instance, method, args) -> switch (method.getName()) {
            case "set" -> { values.put((NamespacedKey) args[0], args[2]); yield null; }
            case "get" -> values.get(args[0]);
            case "has" -> values.containsKey(args[0]);
            case "remove" -> { values.remove(args[0]); yield null; }
            case "getKeys" -> Set.copyOf(values.keySet());
            case "isEmpty" -> values.isEmpty();
            default -> defaultValue(method.getReturnType());
        });
    }

    private static final class MetaState {
        private int power = 1;
        private List<FireworkEffect> effects = List.of();
        private FireworkMeta proxy = (FireworkMeta) ElytraFireworkOwnershipTest.proxy(FireworkMeta.class,
                (instance, method, args) -> switch (method.getName()) {
                    case "getPower" -> power;
                    case "getEffects" -> effects;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static final class FireworkState {
        private final ServerState server;
        private final UUID id;
        private final World world;
        private final DataState data = new DataState();
        private final MetaState meta = new MetaState();
        private final Player spawning;
        private Player attached;
        private UUID spawningId;
        private int effectiveFlightDuration = 1;
        private List<FireworkEffect> effectiveEffects = List.of();
        private boolean componentPresent = true;
        private final Fireworks nativeComponent;
        private boolean persistent = true, inWorld, valid = true, dead, vehicle, passenger;
        private int fireTicks, setterCalls, removalCalls;
        private final Firework firework;
        private FireworkState(ServerState server, UUID id, UUID worldId, UUID playerId) {
            this.server = server; this.id = id; this.world = server.worlds.computeIfAbsent(worldId, ElytraFireworkOwnershipTest::world);
            this.spawningId = playerId;
            this.spawning = proxy(Player.class, (instance, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> this.spawningId;
                case "isOnline" -> true;
                default -> defaultValue(method.getReturnType());
            });
            this.attached = spawning;
            server.players.put(playerId, spawning);
            this.nativeComponent = proxy(Fireworks.class, (instance, method, args) -> switch (method.getName()) {
                case "flightDuration" -> effectiveFlightDuration;
                case "effects" -> effectiveEffects;
                default -> defaultValue(method.getReturnType());
            });
            firework = proxy(Firework.class, this::invoke);
            server.fireworkStates.put(firework, this);
        }
        private Fireworks component() { return componentPresent ? nativeComponent : null; }
        private void drift(String kind) {
            switch (kind) {
                case "spawner" -> spawningId = uuid(999);
                case "attachment" -> attached = proxy(Player.class, (instance, method, args) ->
                        method.getName().equals("getUniqueId") ? uuid(998) : defaultValue(method.getReturnType()));
                case "effective-zero" -> effectiveFlightDuration = 0;
                case "effective-two" -> effectiveFlightDuration = 2;
                case "effective-effect" -> effectiveEffects = List.of(FireworkEffect.builder()
                        .withColor(Color.RED).build());
                case "component-removed" -> componentPresent = false;
                case "effect" -> effectiveEffects = List.of(FireworkEffect.builder()
                        .withColor(Color.RED).build());
                case "vehicle" -> vehicle = true;
                case "passenger" -> passenger = true;
                case "fire" -> fireTicks = 1;
                default -> throw new AssertionError(kind);
            }
        }
        private Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getWorld" -> world;
                case "isPersistent" -> persistent;
                case "setPersistent" -> { persistent = (boolean) args[0]; setterCalls++; yield null; }
                case "isInWorld" -> inWorld;
                case "isValid" -> valid;
                case "isDead" -> dead;
                case "getVehicle" -> vehicle ? firework : null;
                case "getPassengers" -> passenger ? List.of(firework) : List.of();
                case "getFireTicks" -> fireTicks;
                case "getPersistentDataContainer" -> data.container;
                case "getSpawningEntity" -> spawningId;
                case "getAttachedTo" -> attached;
                case "getFireworkMeta" -> meta.proxy;
                case "remove" -> { removalCalls++; valid = false; dead = true; server.entities.remove(id); yield null; }
                default -> defaultValue(method.getReturnType());
            };
        }
    }
}
