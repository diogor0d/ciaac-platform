package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
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
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArenaProjectileOwnershipTest {
    private static final UUID WORLD_ID = uuid(700);
    private static final Instant CAPTURED_AT = Instant.parse("2026-10-04T11:12:13.123456789Z");

    @TempDir Path temporary;

    @Test
    void preparesOnlyAnUninsertedArrowAndPersistsPendingOwnership() {
        try (Environment environment = environment("prepared")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            ArrowState arrow = new ArrowState(environment.serverState, uuid(40), false);

            environment.ownership.beforeAdd(capture, arrow.arrow);

            assertFalse(arrow.persistent);
            assertEquals(AbstractArrow.PickupStatus.DISALLOWED, arrow.pickupStatus);
            assertEquals(0, arrow.fireTicks);
            assertFalse(arrow.inWorld);
            assertNull(environment.serverState.entities.get(arrow.id));
            assertTrue(environment.ownership.hasLabel(arrow.entity()));
            assertEquals(Set.of(expectedLabel(capture)), Set.copyOf(arrow.data.values.values()));
            assertEquals(List.of(ArenaWorldLedger.EntityStatus.PENDING),
                    environment.ledger.entities(capture).stream().map(ArenaWorldLedger.Entity::status).toList());
        }
    }

    @Test
    void confirmsOnlyTheExactEntityObservedInTheNativeWorld() {
        try (Environment environment = environment("native-add")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            ArrowState arrow = new ArrowState(environment.serverState, uuid(40), false);
            environment.ownership.beforeAdd(capture, arrow.arrow);

            ArrowState sameUuidDifferentInstance = new ArrowState(environment.serverState, arrow.id, false);
            sameUuidDifferentInstance.persistent = false;
            sameUuidDifferentInstance.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED;
            sameUuidDifferentInstance.fireTicks = 0;
            sameUuidDifferentInstance.data.values.putAll(arrow.data.values);
            arrow.inWorld = true;
            environment.serverState.add(sameUuidDifferentInstance);
            assertThrows(IllegalStateException.class, () -> environment.ownership.afterAdd(arrow.entity()));
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    environment.ledger.findEntity(arrow.id).orElseThrow().status());

            environment.serverState.add(arrow);
            environment.ownership.afterAdd(arrow.entity());

            assertEquals(ArenaWorldLedger.EntityStatus.CONFIRMED,
                    environment.ledger.findEntity(arrow.id).orElseThrow().status());
        }
    }

    @Test
    void cancelledSpectralArrowIsRemovedOnlyWhenItNeverEnteredTheWorld() {
        try (Environment environment = environment("cancelled")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            ArrowState arrow = new ArrowState(environment.serverState, uuid(41), true);
            environment.ownership.beforeAdd(capture, arrow.arrow);

            assertEquals(ArenaWorldLedger.EntityType.SPECTRAL_ARROW,
                    environment.ledger.entities(capture).getFirst().type());
            environment.ownership.cancelledBeforeAdd(arrow.entity());

            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED,
                    environment.ledger.findEntity(arrow.id).orElseThrow().status());
            assertFalse(arrow.inWorld);
            assertNull(environment.serverState.entities.get(arrow.id));
        }
    }

    @Test
    void absentPendingArrowBlocksCancellationAndPurgeWithoutMarkingItRemoved() {
        try (Environment environment = environment("unproven-absence")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            ArrowState arrow = new ArrowState(environment.serverState, uuid(40), false);
            environment.ownership.beforeAdd(capture, arrow.arrow);

            arrow.inWorld = true;
            assertThrows(IllegalStateException.class,
                    () -> environment.ownership.cancelledBeforeAdd(arrow.entity()));
            assertThrows(IllegalStateException.class,
                    () -> environment.ownership.purge(purge(capture)));

            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    environment.ledger.findEntity(arrow.id).orElseThrow().status());
            assertEquals(ArenaWorldLedger.Status.ARMED, environment.ledger.requireLease(capture).status());
            assertEquals(0, arrow.removalCalls);
        }
    }

    @Test
    void purgePrevalidatesAllRowsBeforeRemovingAnyOwnedArrow() {
        try (Environment environment = environment("prevalidate")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            ArrowState first = confirmedArrow(environment, capture, uuid(40));
            ArrowState second = confirmedArrow(environment, capture, uuid(41));
            second.persistent = true; // Drift in the later row must be found before the first removal.

            assertThrows(IllegalStateException.class,
                    () -> environment.ownership.purge(purge(capture)));

            assertEquals(0, first.removalCalls);
            assertEquals(0, second.removalCalls);
            assertEquals(ArenaWorldLedger.Status.ARMED, environment.ledger.requireLease(capture).status());
            assertTrue(environment.ledger.entities(capture).stream()
                    .allMatch(row -> row.status() == ArenaWorldLedger.EntityStatus.CONFIRMED));
        }
    }

    @Test
    void purgeRemovesOnlyLedgerOwnedArrowAndPreservesForeignArrow() {
        try (Environment environment = environment("foreign-arrow")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            ArrowState owned = confirmedArrow(environment, capture, uuid(40));
            ArrowState foreign = new ArrowState(environment.serverState, uuid(41), false);
            environment.serverState.add(foreign);

            environment.ownership.purge(purge(capture));

            assertEquals(1, owned.removalCalls);
            assertTrue(owned.inWorld);
            assertFalse(owned.valid);
            assertTrue(owned.dead);
            assertNull(environment.serverState.entities.get(owned.id));
            assertEquals(0, foreign.removalCalls);
            assertTrue(foreign.inWorld);
            assertTrue(environment.serverState.entities.get(foreign.id) == foreign.entity());
            assertFalse(environment.ownership.hasLabel(foreign.entity()));
            assertEquals(ArenaWorldLedger.Status.PURGED, environment.ledger.requireLease(capture).status());
        }
    }

    @Test
    void removalRequiresDeadInvalidAndNullLookupButAllowsHistoricalInWorldFlag() {
        try (Environment environment = environment("remove-proof")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            ArrowState arrow = confirmedArrow(environment, capture, uuid(40));

            arrow.inWorld = false;
            environment.serverState.entities.remove(arrow.id);
            assertThrows(IllegalStateException.class, () -> environment.ownership.afterRemove(arrow.entity()));
            assertEquals(ArenaWorldLedger.EntityStatus.CONFIRMED,
                    environment.ledger.findEntity(arrow.id).orElseThrow().status());

            arrow.valid = false;
            assertThrows(IllegalStateException.class, () -> environment.ownership.afterRemove(arrow.entity()));
            assertEquals(ArenaWorldLedger.EntityStatus.CONFIRMED,
                    environment.ledger.findEntity(arrow.id).orElseThrow().status());

            arrow.dead = true;
            environment.serverState.entities.put(arrow.id, arrow.entity());
            assertThrows(IllegalStateException.class, () -> environment.ownership.afterRemove(arrow.entity()));
            assertEquals(ArenaWorldLedger.EntityStatus.CONFIRMED,
                    environment.ledger.findEntity(arrow.id).orElseThrow().status());

            environment.serverState.entities.remove(arrow.id);
            arrow.inWorld = true; // Paper can retain this historical flag after native discard.
            environment.ownership.afterRemove(arrow.entity());
            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED,
                    environment.ledger.findEntity(arrow.id).orElseThrow().status());
        }
    }

    @Test
    void observedUntrackedArrowRequiresCompleteOwnershipBeforeNativeDiscard() {
        try (Environment environment = environment("untracked")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            ArrowState arrow = confirmedArrow(environment, capture, uuid(40));
            ArrowState foreign = new ArrowState(environment.serverState, uuid(41), false);
            environment.serverState.add(foreign);
            environment.serverState.entities.remove(arrow.id);
            assertThrows(IllegalStateException.class, () -> environment.ownership.afterUntracking(arrow.entity()));
            arrow.valid = false;
            arrow.persistent = true;
            assertThrows(IllegalStateException.class, () -> environment.ownership.afterUntracking(arrow.entity()));
            arrow.persistent = false;
            environment.serverState.entities.put(arrow.id, arrow.entity());
            assertThrows(IllegalStateException.class, () -> environment.ownership.afterUntracking(arrow.entity()));
            assertEquals(0, arrow.removalCalls);
            environment.serverState.entities.remove(arrow.id);

            environment.ownership.afterUntracking(arrow.entity());
            environment.ownership.afterUntracking(arrow.entity());

            assertEquals(1, arrow.removalCalls);
            assertTrue(arrow.inWorld);
            assertTrue(arrow.dead);
            assertFalse(arrow.valid);
            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED, environment.ledger.findEntity(arrow.id).orElseThrow().status());
            assertEquals(0, foreign.removalCalls);
            assertTrue(foreign.valid);
        }
    }

    @Test
    void neverInsertedArrowCannotBeTreatedAsAnObservedTrackingEnd() {
        try (Environment environment = environment("never-tracked")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            ArrowState arrow = new ArrowState(environment.serverState, uuid(40), false);
            environment.ownership.beforeAdd(capture, arrow.arrow);
            arrow.valid = false;

            assertThrows(IllegalStateException.class, () -> environment.ownership.afterUntracking(arrow.entity()));
            assertEquals(0, arrow.removalCalls);
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING, environment.ledger.findEntity(arrow.id).orElseThrow().status());
        }
    }

    @Test
    void removedOwnershipCannotAuthorizeDeletingAnUnexpectedLiveObject() {
        try (Environment environment = environment("removed-but-alive")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            ArrowState arrow = confirmedArrow(environment, capture, uuid(40));
            environment.ownership.removeOwned(arrow.id);
            arrow.dead = false;

            assertThrows(IllegalStateException.class, () -> environment.ownership.afterUntracking(arrow.entity()));
            assertEquals(1, arrow.removalCalls);
        }
    }

    @Test
    void rejectsWrongThreadBeforeChangingArrowOrWritingIntent() {
        try (Environment environment = environment("wrong-thread")) {
            PlayerStateOperation capture = capture(1, 2, 3);
            arm(environment, capture);
            ArrowState arrow = new ArrowState(environment.serverState, uuid(40), false);
            environment.serverState.primaryThread = false;

            assertThrows(IllegalStateException.class,
                    () -> environment.ownership.beforeAdd(capture, arrow.arrow));

            assertEquals(0, arrow.setterCalls);
            assertTrue(arrow.data.values.isEmpty());
            assertTrue(environment.ledger.entities(capture).isEmpty());
        }
    }

    @Test
    void conflictingLeaseCannotRebindAnAlreadyClaimedEntityUuid() {
        try (Environment environment = environment("conflicting-owner")) {
            PlayerStateOperation firstCapture = capture(1, 2, 3);
            PlayerStateOperation secondCapture = capture(4, 5, 6);
            arm(environment, firstCapture);
            arm(environment, secondCapture);
            UUID sharedEntityId = uuid(40);
            ArrowState first = new ArrowState(environment.serverState, sharedEntityId, false);
            ArrowState second = new ArrowState(environment.serverState, sharedEntityId, false);
            environment.ownership.beforeAdd(firstCapture, first.arrow);

            assertThrows(IllegalStateException.class,
                    () -> environment.ownership.beforeAdd(secondCapture, second.arrow));

            assertEquals(firstCapture.sessionId(),
                    environment.ledger.findEntity(sharedEntityId).orElseThrow().sessionId());
            assertTrue(environment.ledger.entities(secondCapture).isEmpty());
            assertEquals(ArenaWorldLedger.Status.ARMED, environment.ledger.requireLease(secondCapture).status());
            assertTrue(second.persistent);
            assertEquals(AbstractArrow.PickupStatus.ALLOWED, second.pickupStatus);
            assertEquals(100, second.fireTicks);
            assertEquals(0, second.setterCalls);
            assertTrue(second.data.values.isEmpty());
        }
    }

    private Environment environment(String directoryName) {
        ServerState serverState = new ServerState();
        Server server = proxy(Server.class, serverState::invoke);
        Plugin plugin = proxy(Plugin.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getServer" -> server;
            case "getName" -> "CIAACPlatform";
            case "namespace" -> "ciaacplatform";
            default -> defaultValue(method.getReturnType());
        });
        ArenaWorldLedger ledger = new ArenaWorldLedger(temporary.resolve(directoryName));
        return new Environment(serverState, new ArenaProjectileOwnership(plugin, ledger), ledger);
    }

    private static void arm(Environment environment, PlayerStateOperation capture) {
        environment.ledger.capture(capture, manifest());
        environment.ledger.arm(capture);
    }

    private static ArrowState confirmedArrow(Environment environment, PlayerStateOperation capture, UUID id) {
        ArrowState arrow = new ArrowState(environment.serverState, id, false);
        environment.ownership.beforeAdd(capture, arrow.arrow);
        environment.serverState.add(arrow);
        environment.ownership.afterAdd(arrow.entity());
        return arrow;
    }

    private static PlayerStateOperation capture(long operation, long session, long player) {
        UUID operationId = uuid(operation);
        UUID connectionId = uuid(100 + player);
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, operationId, operationId,
                uuid(200 + operation), uuid(session), uuid(300 + session), uuid(player), connectionId,
                connectionId, GameKey.ARENA, CAPTURED_AT);
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

    private static byte[] manifest() {
        return new ArenaWorldManifest("Paper test-version", List.of(
                new ProtectedRegion("arena", GameKey.ARENA,
                        new CuboidRegion(WORLD_ID, 0, 0, 0, 2, 2, 2),
                        ProtectedRegionRole.PARTICIPANT_ONLY, true),
                new ProtectedRegion("spectators", GameKey.ARENA,
                        new CuboidRegion(WORLD_ID, 5, 0, 0, 7, 2, 2),
                        ProtectedRegionRole.SPECTATOR_PUBLIC, true))).encode();
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
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
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    private static final class Environment implements AutoCloseable {
        private final ServerState serverState;
        private final ArenaProjectileOwnership ownership;
        private final ArenaWorldLedger ledger;

        private Environment(ServerState serverState, ArenaProjectileOwnership ownership, ArenaWorldLedger ledger) {
            this.serverState = serverState;
            this.ownership = ownership;
            this.ledger = ledger;
        }

        @Override public void close() { ledger.close(); }
    }

    private static final class ServerState {
        private final Map<UUID, Entity> entities = new HashMap<>();
        private boolean primaryThread = true;

        private Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "isPrimaryThread" -> primaryThread;
                case "getEntity" -> entities.get(arguments[0]);
                case "toString" -> "test-server";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> defaultValue(method.getReturnType());
            };
        }

        private void add(ArrowState arrow) {
            arrow.inWorld = true;
            arrow.valid = true;
            entities.put(arrow.id, arrow.entity());
        }
    }

    private static final class ArrowState {
        private final ServerState serverState;
        private final UUID id;
        private final World world;
        private final PersistentDataState data = new PersistentDataState();
        private final AbstractArrow arrow;
        private boolean persistent = true;
        private boolean inWorld;
        private boolean valid = true;
        private boolean dead;
        private int fireTicks = 100;
        private AbstractArrow.PickupStatus pickupStatus = AbstractArrow.PickupStatus.ALLOWED;
        private int setterCalls;
        private int removalCalls;

        private ArrowState(ServerState server, UUID id, boolean spectral) {
            this.serverState = server;
            this.id = id;
            this.world = proxy(World.class, (instance, method, arguments) -> switch (method.getName()) {
                case "getUID" -> WORLD_ID;
                case "getName" -> "test-arena";
                default -> defaultValue(method.getReturnType());
            });
            this.data.container = proxy(PersistentDataContainer.class, data::invoke);
            Class<?> type = spectral ? SpectralArrow.class : Arrow.class;
            this.arrow = (AbstractArrow) proxy(type, this::invoke);
        }

        private Entity entity() {
            return (Entity) arrow;
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
                case "getVehicle" -> null;
                case "getPassengers" -> List.of();
                case "getPersistentDataContainer" -> data.container;
                case "getFireTicks" -> fireTicks;
                case "setFireTicks" -> { fireTicks = (int) arguments[0]; setterCalls++; yield null; }
                case "getPickupStatus" -> pickupStatus;
                case "setPickupStatus" -> {
                    pickupStatus = (AbstractArrow.PickupStatus) arguments[0];
                    setterCalls++;
                    yield null;
                }
                case "remove" -> {
                    removalCalls++;
                    valid = false;
                    dead = true;
                    serverState.entities.remove(id);
                    yield null;
                }
                case "toString" -> "test-arrow-" + id;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static final class PersistentDataState {
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
