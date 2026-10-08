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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnvilHazardOwnershipTest {
    private static final UUID WORLD_ID = uuid(700);
    private static final Instant CAPTURED_AT = Instant.parse("2026-10-07T11:12:13.123456789Z");

    @TempDir Path temporary;

    @Test
    void recordsIntentBeforeInsertionAndConfirmsOnlyTheExactNativeMarker() {
        try (Environment environment = environment("native-add")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            StandState stand = new StandState(environment.serverState, uuid(40), WORLD_ID);

            environment.ownership.beforeAdd(capture.sessionId(), stand.stand);

            assertFalse(stand.persistent);
            assertFalse(stand.inWorld);
            assertTrue(environment.ownership.hasLabel(stand.entity()));
            assertEquals(Set.of(expectedLabel(capture)), Set.copyOf(stand.data.values.values()));
            assertEquals(List.of(ArenaWorldLedger.EntityStatus.PENDING),
                    environment.ledger.entities(capture).stream().map(ArenaWorldLedger.Entity::status).toList());
            assertNull(environment.serverState.entities.get(stand.id));

            StandState sameUuidDifferentInstance = new StandState(environment.serverState, stand.id, WORLD_ID);
            sameUuidDifferentInstance.data.values.putAll(stand.data.values);
            sameUuidDifferentInstance.persistent = false;
            environment.serverState.add(sameUuidDifferentInstance);
            assertThrows(IllegalStateException.class, () -> environment.ownership.afterAdd(stand.entity()));
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    environment.ledger.findEntity(stand.id).orElseThrow().status());

            environment.serverState.add(stand);
            environment.ownership.afterAdd(stand.entity());
            environment.ownership.afterAdd(stand.entity());
            assertEquals(ArenaWorldLedger.EntityStatus.CONFIRMED,
                    environment.ledger.findEntity(stand.id).orElseThrow().status());
        }
    }

    @Test
    void cancelledSpawnIsRemovedOnlyWhenNoNativeInsertionOccurred() {
        try (Environment environment = environment("cancelled")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            StandState stand = new StandState(environment.serverState, uuid(41), WORLD_ID);
            environment.ownership.beforeAdd(capture, stand.stand);

            environment.ownership.cancelledBeforeAdd(stand.entity());

            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED,
                    environment.ledger.findEntity(stand.id).orElseThrow().status());
            assertFalse(stand.inWorld);
            assertNull(environment.serverState.entities.get(stand.id));
        }
    }

    @Test
    void rejectsUnconfiguredOrWrongWorldMarkersBeforeWritingIntent() {
        try (Environment environment = environment("invalid-before-add")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);

            for (String drift : List.of("visible", "not-marker", "gravity", "vulnerable", "helmet",
                    "extra-equipment", "vehicle", "passenger", "fire")) {
                StandState stand = new StandState(environment.serverState, uuid(50 + drift.hashCode()), WORLD_ID);
                stand.drift(drift);
                assertThrows(IllegalStateException.class,
                        () -> environment.ownership.beforeAdd(capture, stand.stand), drift);
                assertTrue(stand.data.values.isEmpty(), drift);
                assertEquals(0, stand.setterCalls, drift);
            }

            UUID unloadedWorld = uuid(701);
            StandState notLoaded = new StandState(environment.serverState, uuid(60), unloadedWorld);
            environment.serverState.worlds.remove(unloadedWorld);
            assertThrows(IllegalStateException.class,
                    () -> environment.ownership.beforeAdd(capture, notLoaded.stand));
            assertEquals(0, notLoaded.setterCalls);
            assertTrue(environment.ledger.entities(capture).isEmpty());
        }
    }

    @Test
    void purgePrevalidatesEveryOwnedMarkerAndLeavesUnrelatedEntitiesUntouched() {
        try (Environment environment = environment("prevalidate")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            StandState first = confirmedStand(environment, capture, uuid(40));
            StandState second = confirmedStand(environment, capture, uuid(41));
            StandState unrelated = new StandState(environment.serverState, uuid(42), WORLD_ID);
            environment.serverState.add(unrelated);
            second.visible = true; // Drift in a later row must be found before deleting the first.

            assertThrows(IllegalStateException.class, () -> environment.ownership.purge(purge(capture)));

            assertEquals(0, first.removalCalls);
            assertEquals(0, second.removalCalls);
            assertEquals(0, unrelated.removalCalls);
            assertTrue(environment.serverState.entities.get(unrelated.id) == unrelated.entity());
            assertEquals(ArenaWorldLedger.Status.ARMED, environment.ledger.requireLease(capture).status());
            assertTrue(environment.ledger.entities(capture).stream()
                    .allMatch(row -> row.status() == ArenaWorldLedger.EntityStatus.CONFIRMED));
        }
    }

    @Test
    void changedLabelWorldOrMarkerFlagsBlockPurgeWithoutRemovingAnything() {
        for (String drift : List.of("label", "world", "marker", "helmet")) {
            try (Environment environment = environment("drift-" + drift)) {
                PlayerStateOperation capture = capture(1, 70 + drift.hashCode(), 3);
                arm(environment, capture);
                StandState stand = confirmedStand(environment, capture, uuid(70));
                switch (drift) {
                    case "label" -> stand.data.values.put(
                            new NamespacedKey(environment.plugin, "anvil-hazard-owner-v1"), "foreign-owner");
                    case "world" -> stand.worldId[0] = uuid(702);
                    case "marker" -> stand.marker = false;
                    case "helmet" -> stand.equipment.helmet = new TestItemStack(Material.DIAMOND_HELMET);
                    default -> throw new AssertionError(drift);
                }

                assertThrows(IllegalStateException.class, () -> environment.ownership.purge(purge(capture)), drift);
                assertEquals(0, stand.removalCalls, drift);
                assertEquals(ArenaWorldLedger.EntityStatus.CONFIRMED,
                        environment.ledger.findEntity(stand.id).orElseThrow().status());
                assertEquals(ArenaWorldLedger.Status.ARMED, environment.ledger.requireLease(capture).status());
            }
        }
    }

    @Test
    void coldProcessProofAllowsAbsentRowsOnlyForLoadedWorldAndDifferentNativeProcess() {
        for (boolean confirmed : List.of(false, true)) {
            try (Environment environment = environment("cold-" + confirmed)) {
                PlayerStateOperation capture = capture(1, confirmed ? 12 : 11, 3);
                arm(environment, capture);
                UUID id = uuid(440 + (confirmed ? 1 : 0));
                environment.ledger.beginEntity(capture, id, WORLD_ID,
                        ArenaWorldLedger.EntityType.ANVIL_MARKER, priorProcess());
                if (confirmed) environment.ledger.confirmEntity(capture, id);

                environment.ownership.purge(purge(capture));

                assertEquals(ArenaWorldLedger.EntityStatus.REMOVED,
                        environment.ledger.findEntity(id).orElseThrow().status());
                assertEquals(ArenaWorldLedger.Status.PURGED, environment.ledger.requireLease(capture).status());
            }
        }
    }

    @Test
    void sameProcessLegacyAndUnloadedWorldAbsenceAreNotRemovalProof() {
        try (Environment environment = environment("same-process")) {
            PlayerStateOperation capture = capture(1, 13, 3);
            arm(environment, capture);
            StandState stand = new StandState(environment.serverState, uuid(442), WORLD_ID);
            environment.ownership.beforeAdd(capture, stand.stand);

            AnvilHazardOwnership reloadedOwner = new AnvilHazardOwnership(environment.plugin, environment.ledger);
            assertThrows(IllegalStateException.class, () -> reloadedOwner.purge(purge(capture)));
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    environment.ledger.findEntity(stand.id).orElseThrow().status());
            assertEquals(ArenaWorldLedger.Status.ARMED, environment.ledger.requireLease(capture).status());
        }
        try (Environment environment = environment("legacy-unloaded")) {
            PlayerStateOperation capture = capture(1, 14, 3);
            arm(environment, capture);
            UUID id = uuid(443);
            environment.ledger.beginEntity(capture, id, WORLD_ID, ArenaWorldLedger.EntityType.ANVIL_MARKER);
            environment.serverState.worlds.remove(WORLD_ID);

            assertThrows(IllegalStateException.class, () -> environment.ownership.purge(purge(capture)));
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    environment.ledger.findEntity(id).orElseThrow().status());
            assertEquals(ArenaWorldLedger.Status.ARMED, environment.ledger.requireLease(capture).status());
        }
    }

    @Test
    void observedRemovalAndTrackingEndRequireNativeRemovalEvidenceAndReplaySafely() {
        try (Environment environment = environment("post-events")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            StandState stand = confirmedStand(environment, capture, uuid(40));

            assertThrows(IllegalStateException.class, () -> environment.ownership.afterRemove(stand.entity()));
            environment.serverState.entities.remove(stand.id);
            stand.valid = false;
            stand.dead = true;
            stand.inWorld = true; // Paper can retain the historical flag after discard.
            environment.ownership.afterRemove(stand.entity());
            environment.ownership.afterRemove(stand.entity());
            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED,
                    environment.ledger.findEntity(stand.id).orElseThrow().status());

            StandState tracked = confirmedStand(environment, capture, uuid(41));
            environment.serverState.entities.remove(tracked.id);
            tracked.valid = false;
            tracked.dead = false;
            tracked.inWorld = true;
            environment.ownership.afterUntracking(tracked.entity());
            environment.ownership.afterUntracking(tracked.entity());
            assertEquals(1, tracked.removalCalls);
            assertTrue(tracked.dead);
            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED,
                    environment.ledger.findEntity(tracked.id).orElseThrow().status());
        }
    }

    @Test
    void wrongThreadCannotMutateMarkerOrWriteOwnershipIntent() {
        try (Environment environment = environment("wrong-thread")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            StandState stand = new StandState(environment.serverState, uuid(40), WORLD_ID);
            environment.serverState.primaryThread = false;

            assertThrows(IllegalStateException.class,
                    () -> environment.ownership.beforeAdd(capture, stand.stand));

            assertEquals(0, stand.setterCalls);
            assertTrue(stand.data.values.isEmpty());
            assertTrue(environment.ledger.entities(capture).isEmpty());
        }
    }

    @Test
    void sessionOverloadRequiresAnExistingDurableCapture() {
        try (Environment environment = environment("missing-capture")) {
            StandState stand = new StandState(environment.serverState, uuid(40), WORLD_ID);

            assertThrows(IllegalStateException.class,
                    () -> environment.ownership.beforeAdd(uuid(999), stand.stand));

            assertEquals(0, stand.setterCalls);
            assertTrue(stand.data.values.isEmpty());
        }
    }

    private Environment environment(String directoryName) {
        ServerState serverState = new ServerState();
        serverState.worlds.put(WORLD_ID, proxy(World.class, (instance, method, arguments) -> method.getName().equals("getUID") ? WORLD_ID : defaultValue(method.getReturnType())));
        Server server = proxy(Server.class, serverState::invoke);
        Plugin plugin = proxy(Plugin.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getServer" -> server;
            case "getName" -> "CIAACPlatform";
            case "namespace" -> "ciaacplatform";
            default -> defaultValue(method.getReturnType());
        });
        ArenaWorldLedger ledger = new ArenaWorldLedger(temporary.resolve(directoryName));
        AnvilHazardOwnership ownership = new AnvilHazardOwnership(plugin, ledger);
        return new Environment(serverState, plugin, ownership, ledger);
    }

    private static StandState confirmedStand(Environment environment, PlayerStateOperation capture, UUID id) {
        StandState stand = new StandState(environment.serverState, id, WORLD_ID);
        environment.ownership.beforeAdd(capture, stand.stand);
        environment.serverState.add(stand);
        environment.ownership.afterAdd(stand.entity());
        return stand;
    }

    private static NativeProcessIdentity priorProcess() {
        NativeProcessIdentity current = NativeProcessIdentity.current();
        long priorPid = current.pid() == Long.MAX_VALUE ? current.pid() - 1 : current.pid() + 1;
        long priorStart = current.startedAtEpochMillis() == Long.MAX_VALUE
                ? current.startedAtEpochMillis() - 1 : current.startedAtEpochMillis() + 1;
        return new NativeProcessIdentity(priorPid, priorStart);
    }

    private static void arm(Environment environment, PlayerStateOperation capture) {
        environment.ledger.capture(capture, manifestPrefix());
        environment.ledger.arm(capture);
    }

    private static PlayerStateOperation capture(long operation, long session, long player) {
        UUID operationId = uuid(operation);
        UUID connectionId = uuid(100 + player);
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, operationId, operationId,
                uuid(200 + operation), uuid(session), uuid(300 + session), uuid(player), connectionId,
                connectionId, GameKey.ANVIL_DODGE, CAPTURED_AT);
    }

    private static byte[] manifestPrefix() {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var out = new java.io.DataOutputStream(bytes)) {
                out.writeInt(0x43414148); out.writeInt(1);
                out.writeUTF("Paper reviewed fixture"); out.writeUTF("test-anvil-world");
                out.writeLong(WORLD_ID.getMostSignificantBits()); out.writeLong(WORLD_ID.getLeastSignificantBits());
            }
            return bytes.toByteArray();
        } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static PlayerStateOperation purge(PlayerStateOperation capture) {
        return new PlayerStateOperation(PlayerStateOperation.Kind.PURGE, uuid(900), capture.captureOperationId(),
                capture.snapshotId(), capture.sessionId(), capture.matchId(), capture.playerId(),
                capture.capturedConnectionId(), uuid(901), capture.game(), capture.capturedAt());
    }

    private static String expectedLabel(PlayerStateOperation capture) {
        return "v1/" + capture.sessionId() + "/" + capture.matchId() + "/" + capture.playerId()
                + "/" + capture.captureOperationId() + "/" + capture.capturedConnectionId();
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

    private static final class Environment implements AutoCloseable {
        private final ServerState serverState;
        private final Plugin plugin;
        private final AnvilHazardOwnership ownership;
        private final ArenaWorldLedger ledger;
        private Environment(ServerState serverState, Plugin plugin, AnvilHazardOwnership ownership,
                ArenaWorldLedger ledger) {
            this.serverState = serverState;
            this.plugin = plugin;
            this.ownership = ownership;
            this.ledger = ledger;
        }
        @Override public void close() { ledger.close(); }
    }

    private static final class ServerState {
        private final Map<UUID, Entity> entities = new HashMap<>();
        private final Map<UUID, World> worlds = new HashMap<>();
        private boolean primaryThread = true;
        private Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "isPrimaryThread" -> primaryThread;
                case "getEntity" -> entities.get(arguments[0]);
                case "getWorld" -> worlds.get(arguments[0]);
                case "toString" -> "test-server";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> defaultValue(method.getReturnType());
            };
        }
        private void add(StandState stand) {
            stand.inWorld = true;
            stand.valid = true;
            entities.put(stand.id, stand.entity());
        }
    }

    private static final class TestItemStack extends ItemStack {
        private final Material type;
        TestItemStack(Material type) { super(); this.type = type; }
        @Override public Material getType() { return type; }
    }

    private static final class StandState {
        private final ServerState serverState;
        private final UUID id;
        private final UUID[] worldId;
        private final World world;
        private final DataState data = new DataState();
        private final EquipmentState equipment = new EquipmentState();
        private final ArmorStand stand;
        private boolean persistent = true;
        private boolean inWorld;
        private boolean valid = true;
        private boolean dead;
        private boolean visible;
        private boolean marker = true;
        private boolean gravity;
        private boolean invulnerable = true;
        private boolean vehicle;
        private boolean passenger;
        private int fireTicks;
        private int setterCalls;
        private int removalCalls;

        private StandState(ServerState server, UUID id, UUID worldId) {
            this.serverState = server;
            this.id = id;
            this.worldId = new UUID[] {worldId};
            this.world = proxy(World.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "getUID" -> this.worldId[0];
                case "getName" -> "test-anvil-world";
                case "toString" -> "world-" + this.worldId[0];
                default -> defaultValue(method.getReturnType());
            });
            server.worlds.put(worldId, world);
            equipment.helmet = new TestItemStack(Material.ANVIL);
            this.data.container = proxy(PersistentDataContainer.class, data::invoke);
            this.stand = proxy(ArmorStand.class, this::invoke);
        }

        private Entity entity() { return stand; }

        private void drift(String kind) {
            switch (kind) {
                case "visible" -> visible = true;
                case "not-marker" -> marker = false;
                case "gravity" -> gravity = true;
                case "vulnerable" -> invulnerable = false;
                case "helmet" -> equipment.helmet = new TestItemStack(Material.STONE);
                case "extra-equipment" -> equipment.boots = new TestItemStack(Material.STONE);
                case "vehicle" -> vehicle = true;
                case "passenger" -> passenger = true;
                case "fire" -> fireTicks = 1;
                default -> throw new AssertionError(kind);
            }
        }

        private Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getWorld" -> world;
                case "isPersistent" -> persistent;
                case "setPersistent" -> { persistent = (boolean) arguments[0]; setterCalls++; yield null; }
                case "isInWorld" -> inWorld;
                case "isValid" -> valid;
                case "isDead" -> dead;
                case "isVisible" -> visible;
                case "setVisible" -> { visible = (boolean) arguments[0]; setterCalls++; yield null; }
                case "isMarker" -> marker;
                case "setMarker" -> { marker = (boolean) arguments[0]; setterCalls++; yield null; }
                case "hasGravity" -> gravity;
                case "setGravity" -> { gravity = (boolean) arguments[0]; setterCalls++; yield null; }
                case "isInvulnerable" -> invulnerable;
                case "setInvulnerable" -> { invulnerable = (boolean) arguments[0]; setterCalls++; yield null; }
                case "getVehicle" -> vehicle ? entity() : null;
                case "getPassengers" -> passenger ? List.of(entity()) : List.of();
                case "getPersistentDataContainer" -> data.container;
                case "getEquipment" -> equipment.proxy;
                case "getFireTicks" -> fireTicks;
                case "setFireTicks" -> { fireTicks = (int) arguments[0]; setterCalls++; yield null; }
                case "remove" -> {
                    removalCalls++;
                    valid = false;
                    dead = true;
                    serverState.entities.remove(id);
                    yield null;
                }
                case "toString" -> "test-stand-" + id;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static final class EquipmentState {
        private ItemStack helmet;
        private ItemStack chestplate;
        private ItemStack leggings;
        private ItemStack boots;
        private ItemStack mainHand;
        private ItemStack offHand;
        private final EntityEquipment proxy = AnvilHazardOwnershipTest.proxy(EntityEquipment.class,
                (instance, method, arguments) -> switch (method.getName()) {
                    case "getHelmet" -> helmet;
                    case "getChestplate" -> chestplate;
                    case "getLeggings" -> leggings;
                    case "getBoots" -> boots;
                    case "getItemInMainHand" -> mainHand;
                    case "getItemInOffHand" -> offHand;
                    case "setHelmet" -> { helmet = (ItemStack) arguments[0]; yield null; }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static final class DataState {
        private final Map<NamespacedKey, Object> values = new HashMap<>();
        private PersistentDataContainer container;
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
}
