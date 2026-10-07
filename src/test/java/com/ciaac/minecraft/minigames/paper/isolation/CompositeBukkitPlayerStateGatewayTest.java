package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import java.time.Clock;
import java.time.ZoneOffset;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.junit.jupiter.api.Test;

class CompositeBukkitPlayerStateGatewayTest {
    @Test
    void validatesEveryPayloadBeforeAnyEarlierHandlerRestores() {
        CountingHandler first = new CountingHandler(PlayerStateFacet.EXPERIENCE, false);
        CountingHandler later = new CountingHandler(PlayerStateFacet.HEALTH, true);
        AtomicInteger playerLookups = new AtomicInteger();
        Identity identity = identity();
        Player player = identity.player();
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(), new Class<?>[] {Server.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isPrimaryThread")) return true;
                    if (method.getName().equals("getPlayer")) {
                        playerLookups.incrementAndGet();
                        return player;
                    }
                    return null;
                });
        CompositeBukkitPlayerStateGateway gateway =
                gateway(server, List.of(first, later), identity);

        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
                2, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                player.getUniqueId(), identity.connectionId(), GameKey.ARENA, Instant.EPOCH,
                Map.of(PlayerStateFacet.EXPERIENCE, new byte[] {1}, PlayerStateFacet.HEALTH, new byte[] {2}));

        assertThrows(IllegalArgumentException.class, () -> gateway.restore(UUID.randomUUID(), snapshot));
        assertThrows(IllegalArgumentException.class, () -> gateway.purgeTemporaryState(UUID.randomUUID(), snapshot));
        assertThrows(IllegalArgumentException.class, () -> gateway.enterTemporaryState(UUID.randomUUID(), snapshot));
        assertEquals(0, first.restored);
        assertEquals(0, first.purged);
        assertEquals(0, first.entered);
        assertEquals(0, playerLookups.get());
    }

    @Test
    void rejectsIncompleteConflictingOrUnsupportedFacetsBeforeAnyMutation() {
        CountingHandler shared = new CountingHandler(
                Set.of(PlayerStateFacet.EXPERIENCE, PlayerStateFacet.HEALTH), false);
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                new Class<?>[] {Server.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isPrimaryThread")) return true;
                    throw new AssertionError("Invalid snapshot must fail before player lookup");
                });
        Identity identity = identity();
        CompositeBukkitPlayerStateGateway gateway = gateway(server, List.of(shared), identity);
        List<Map<PlayerStateFacet, byte[]>> invalid = List.of(
                Map.of(PlayerStateFacet.EXPERIENCE, new byte[] {1}),
                Map.of(PlayerStateFacet.EXPERIENCE, new byte[] {1}, PlayerStateFacet.HEALTH, new byte[] {2}),
                Map.of(PlayerStateFacet.EXPERIENCE, new byte[] {1}, PlayerStateFacet.HEALTH, new byte[] {1},
                        PlayerStateFacet.INVENTORY, new byte[] {1}));
        for (Map<PlayerStateFacet, byte[]> payloads : invalid) {
            PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
                    2, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    identity.player().getUniqueId(), identity.connectionId(), GameKey.ARENA, Instant.EPOCH, payloads);
            assertThrows(IllegalStateException.class, () -> gateway.enterTemporaryState(UUID.randomUUID(), snapshot));
            assertThrows(IllegalStateException.class, () -> gateway.purgeTemporaryState(UUID.randomUUID(), snapshot));
            assertThrows(IllegalStateException.class, () -> gateway.restore(UUID.randomUUID(), snapshot));
        }
        assertEquals(0, shared.entered);
        assertEquals(0, shared.purged);
        assertEquals(0, shared.restored);
    }

    @Test
    void rejectsGameMissingOneFacetBeforePlayerLookup() {
        Identity identity = identity();
        CountingHandler common = new CountingHandler(PlayerStateFacet.EXPERIENCE, false);
        CountingHandler arenaOnly = new CountingHandler(PlayerStateFacet.HEALTH, false);
        arenaOnly.supportedGames = Set.of(GameKey.ARENA);
        AtomicInteger playerLookups = new AtomicInteger();
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                new Class<?>[] {Server.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isPrimaryThread")) return true;
                    if (method.getName().equals("getPlayer")) {
                        playerLookups.incrementAndGet();
                        return identity.player();
                    }
                    return primitiveDefault(method.getReturnType());
                });
        CompositeBukkitPlayerStateGateway gateway = gateway(server, List.of(common, arenaOnly), identity);

        assertEquals(Set.of(PlayerStateFacet.EXPERIENCE, PlayerStateFacet.HEALTH),
                gateway.supportedFacets(GameKey.ARENA));
        assertEquals(Set.of(PlayerStateFacet.EXPERIENCE), gateway.supportedFacets(GameKey.BUILD_BATTLE));
        assertThrows(IllegalStateException.class, () -> gateway.capture(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), identity.player().getUniqueId(), identity.connectionId(),
                GameKey.BUILD_BATTLE, Instant.EPOCH));
        PlayerStateSnapshot foreignGameSnapshot = new PlayerStateSnapshot(
                2, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                identity.player().getUniqueId(), identity.connectionId(), GameKey.BUILD_BATTLE, Instant.EPOCH,
                Map.of(PlayerStateFacet.EXPERIENCE, new byte[] {1}, PlayerStateFacet.HEALTH, new byte[] {2}));
        assertThrows(IllegalStateException.class, () -> gateway.restore(UUID.randomUUID(), foreignGameSnapshot));
        assertEquals(0, playerLookups.get());
        assertEquals(0, common.captured);
        assertEquals(0, arenaOnly.captured);
        assertEquals(0, common.validated);
        assertEquals(0, arenaOnly.validated);
    }

    @Test
    void offThreadOperationsCannotReachProviderValidationOrMutation() {
        CountingHandler handler = new CountingHandler(PlayerStateFacet.EXPERIENCE, false);
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                new Class<?>[] {Server.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isPrimaryThread")) return false;
                    throw new AssertionError("Off-thread operation reached the server");
                });
        CompositeBukkitPlayerStateGateway gateway = gateway(server, List.of(handler), identity());
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
                1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), GameKey.ARENA, Instant.EPOCH,
                Map.of(PlayerStateFacet.EXPERIENCE, new byte[] {1}));

        assertThrows(IllegalStateException.class, () -> gateway.capture(snapshot.snapshotId(), snapshot.operationId(),
                snapshot.sessionId(), snapshot.matchId(), snapshot.playerId(), UUID.randomUUID(), snapshot.game(), snapshot.capturedAt()));
        assertThrows(IllegalStateException.class, () -> gateway.enterTemporaryState(UUID.randomUUID(), snapshot));
        assertThrows(IllegalStateException.class, () -> gateway.purgeTemporaryState(UUID.randomUUID(), snapshot));
        assertThrows(IllegalStateException.class, () -> gateway.restore(UUID.randomUUID(), snapshot));
        assertEquals(0, handler.validated);
        assertEquals(0, handler.entered);
        assertEquals(0, handler.purged);
        assertEquals(0, handler.restored);
    }

    @Test
    void propagatesOperationIdentityAndAllowsOnlyAuthenticatedRecoveryAcrossConnections() {
        Identity identity = identity();
        CountingHandler handler = new CountingHandler(PlayerStateFacet.EXPERIENCE, false);
        CompositeBukkitPlayerStateGateway gateway = gateway(onlineServer(identity.player()), List.of(handler), identity);
        UUID capture = UUID.randomUUID(), snapshotId = UUID.randomUUID(), session = UUID.randomUUID(), match = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-10-04T01:00:00.123456789Z");
        PlayerStateSnapshot snapshot = gateway.capture(snapshotId, capture, session, match,
                identity.player().getUniqueId(), identity.connectionId(), GameKey.ARENA, timestamp);
        assertEquals(2, snapshot.schemaVersion());
        assertEquals(identity.connectionId(), snapshot.capturedConnectionId());
        assertEquals(capture, handler.context.operationId());
        assertEquals(session, handler.context.sessionId());
        assertEquals(match, handler.context.matchId());
        assertEquals(timestamp, handler.context.capturedAt());

        UUID enter = UUID.randomUUID();
        gateway.enterTemporaryState(enter, snapshot);
        assertEquals(enter, handler.context.operationId());
        assertEquals(PlayerStateOperation.Kind.ENTER, handler.context.kind());

        var reconnected = identity.connections().begin(identity.player(), Instant.EPOCH);
        assertThrows(IllegalStateException.class, () -> gateway.restore(UUID.randomUUID(), snapshot));
        assertEquals(0, handler.restored);
        authenticate(identity, reconnected.id());
        assertThrows(IllegalStateException.class, () -> gateway.enterTemporaryState(UUID.randomUUID(), snapshot));
        assertEquals(1, handler.entered);
        UUID purge = UUID.randomUUID(), restore = UUID.randomUUID();
        gateway.purgeTemporaryState(purge, snapshot);
        assertEquals(purge, handler.context.operationId());
        gateway.restore(restore, snapshot);
        assertEquals(restore, handler.context.operationId());
        assertEquals(capture, handler.context.captureOperationId());
        assertEquals(identity.connectionId(), handler.context.capturedConnectionId());
        assertEquals(reconnected.id(), handler.context.connectionId());
        assertEquals(1, handler.restored);
    }

    @Test
    void rejectsUnauthenticatedCaptureAndReplacedPlayerEntityBeforeAnyHandlerCapture() {
        Identity identity = identity();
        CountingHandler handler = new CountingHandler(PlayerStateFacet.EXPERIENCE, false);
        CompositeBukkitPlayerStateGateway gateway = gateway(onlineServer(identity.player()), List.of(handler), identity);
        identity.authentication().invalidatePlayer(identity.player().getUniqueId());
        assertThrows(IllegalStateException.class, () -> capture(gateway, identity));
        assertEquals(0, handler.captured);
        authenticate(identity, identity.connectionId());
        Player replacement = player(identity.player().getUniqueId());
        var current = identity.connections().begin(replacement, Instant.EPOCH);
        authenticate(identity, current.id());
        assertThrows(IllegalStateException.class, () -> gateway.capture(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), replacement.getUniqueId(), current.id(), GameKey.ARENA, Instant.EPOCH));
        assertEquals(0, handler.captured);
    }

    @Test
    void authenticationRevokedByOneHandlerCannotReachTheNextHandler() {
        for (PlayerStateOperation.Kind phase : PlayerStateOperation.Kind.values()) {
            Identity identity = identity();
            CountingHandler first = new CountingHandler(PlayerStateFacet.EXPERIENCE, false);
            CountingHandler later = new CountingHandler(PlayerStateFacet.HEALTH, false);
            var gateway = gateway(onlineServer(identity.player()), List.of(first, later), identity);
            var snapshot = capture(gateway, identity);
            int capturesBefore = later.captured;
            first.afterOperation = () -> identity.authentication().invalidatePlayer(identity.player().getUniqueId());
            assertThrows(IllegalStateException.class, () -> {
                switch (phase) {
                    case CAPTURE -> capture(gateway, identity);
                    case ENTER -> gateway.enterTemporaryState(UUID.randomUUID(), snapshot);
                    case PURGE -> gateway.purgeTemporaryState(UUID.randomUUID(), snapshot);
                    case RESTORE -> gateway.restore(UUID.randomUUID(), snapshot);
                }
            }, phase.name());
            assertEquals(capturesBefore, later.captured, phase.name());
            assertEquals(0, later.entered, phase.name());
            assertEquals(0, later.purged, phase.name());
            assertEquals(0, later.restored, phase.name());
        }
    }

    private static PlayerStateSnapshot capture(CompositeBukkitPlayerStateGateway gateway, Identity identity) {
        return gateway.capture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                identity.player().getUniqueId(), identity.connectionId(), GameKey.ARENA, Instant.EPOCH);
    }

    private static CompositeBukkitPlayerStateGateway gateway(Server server, List<FacetSnapshotHandler> handlers, Identity identity) {
        return new CompositeBukkitPlayerStateGateway(server, handlers, identity.authentication(), identity.connections(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private static Identity identity() {
        Player player = player(UUID.randomUUID());
        ConnectionRegistry connections = new ConnectionRegistry();
        UUID epoch = connections.begin(player, Instant.EPOCH).id();
        Identity identity = new Identity(player, new AuthenticationRegistry(), connections, epoch);
        authenticate(identity, epoch);
        return identity;
    }

    private static void authenticate(Identity identity, UUID epoch) {
        identity.authentication().authenticated(new AuthenticatedSession(UUID.randomUUID(), identity.player().getUniqueId(),
                epoch, Instant.EPOCH, Instant.EPOCH.plusSeconds(3600)));
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "isOnline", "isValid" -> true;
                    default -> primitiveDefault(method.getReturnType());
                });
    }

    private static Server onlineServer(Player player) {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isPrimaryThread" -> true;
                    case "getPlayer" -> player;
                    default -> primitiveDefault(method.getReturnType());
                });
    }

    private record Identity(Player player, AuthenticationRegistry authentication, ConnectionRegistry connections, UUID connectionId) {}

    @Test
    void vitalsRoundTripNormalNegativeFireTimerAndNativeAirAndExhaustion() {
        for (float exhaustion : new float[] {0.0F, 4.5F, 40.0F}) {
            java.util.Map<String, Object> restored = new java.util.HashMap<>();
            Player player = vitalsPlayer(-20, -10, exhaustion, restored);
            VitalsFacetHandler handler = new VitalsFacetHandler(ignored -> 20.0D);
            byte[] captured = handler.capture(player);
            handler.validateRestore(captured);
            handler.restore(player, captured);
            assertEquals(-20, restored.get("setFireTicks"));
            assertEquals(-10, restored.get("setRemainingAir"));
            assertEquals(exhaustion, restored.get("setExhaustion"));
            assertEquals(20.0D, restored.get("setHealth"));
        }
    }

    @Test
    void vitalsRejectNonFiniteNegativeAndOverLimitExhaustion() {
        for (float exhaustion : new float[] {-1.0F, 40.1F, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new VitalsFacetHandler(ignored -> 20.0D)
                    .capture(vitalsPlayer(-20, 300, exhaustion, new java.util.HashMap<>())));
        }
    }

    private static Player vitalsPlayer(int fireTicks, int air, float exhaustion,
            java.util.Map<String, Object> restored) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, arguments) -> {
                    if (method.getName().startsWith("set")) {
                        restored.put(method.getName(), arguments[0]);
                        return primitiveDefault(method.getReturnType());
                    }
                    return switch (method.getName()) {
                        case "getHealth" -> 20.0D;
                        case "getFoodLevel" -> 20;
                        case "getSaturation" -> 5.0F;
                        case "getExhaustion" -> exhaustion;
                        case "getFireTicks" -> fireTicks;
                        case "getRemainingAir" -> air;
                        case "getMaximumAir" -> 300;
                        case "getActivePotionEffects" -> java.util.List.of();
                        default -> primitiveDefault(method.getReturnType());
                    };
                });
    }

    @Test
    void temporaryVitalsResetReadsMaximumHealthAfterClearingHealthBoost() {
        AtomicReference<Double> maximumHealth = new AtomicReference<>(40.0D);
        AtomicReference<Double> restoredHealth = new AtomicReference<>();
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] { Player.class }, (proxy, method, arguments) -> {
                    if (method.getName().equals("clearActivePotionEffects")) {
                        maximumHealth.set(20.0D);
                        return primitiveDefault(method.getReturnType());
                    }
                    if (method.getName().equals("setHealth")) {
                        double requested = (double) arguments[0];
                        if (requested > maximumHealth.get()) {
                            throw new IllegalArgumentException("health exceeds post-effect maximum");
                        }
                        restoredHealth.set(requested);
                        return null;
                    }
                    if (method.getName().equals("getMaximumAir")) return 300;
                    return primitiveDefault(method.getReturnType());
                });

        assertDoesNotThrow(() -> new VitalsFacetHandler(ignored -> maximumHealth.get())
                .enterTemporaryState(player));
        assertEquals(20.0D, restoredHealth.get());
    }

    @Test
    void vitalsAcceptInfinitePotionDurationButRejectOtherNegativeDurations() {
        assertTrue(VitalsFacetHandler.validPotionDuration(PotionEffect.INFINITE_DURATION));
        assertTrue(VitalsFacetHandler.validPotionDuration(0));
        assertFalse(VitalsFacetHandler.validPotionDuration(-2));
    }

    @Test
    void vitalsRemainReadableFromVersionOneSnapshots() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(1);
            output.writeFloat(0.5F);
            output.writeInt(2);
            output.writeInt(10);
            output.writeDouble(20.0D);
            output.writeDouble(0.0D);
            output.writeInt(20);
            output.writeFloat(5.0F);
            output.writeFloat(0.0F);
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(300);
            output.writeInt(0);
        }

        assertDoesNotThrow(() -> new VitalsFacetHandler(ignored -> 20.0D)
                .validateRestore(bytes.toByteArray()));
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        throw new AssertionError("Unknown primitive return type: " + type);
    }

    private static final class CountingHandler implements FacetSnapshotHandler {
        private final Set<PlayerStateFacet> facets;
        private final boolean reject;
        private Set<GameKey> supportedGames = Set.copyOf(java.util.EnumSet.allOf(GameKey.class));
        private int restored;
        private int entered;
        private int purged;
        private int validated;
        private int captured;
        private PlayerStateOperation context;
        private Runnable afterOperation = () -> {};

        private CountingHandler(PlayerStateFacet facet, boolean reject) {
            this(Set.of(facet), reject);
        }

        private CountingHandler(Set<PlayerStateFacet> facets, boolean reject) {
            this.facets = facets;
            this.reject = reject;
        }

        @Override public Set<PlayerStateFacet> facets() { return facets; }
        @Override public Set<GameKey> supportedGames() { return supportedGames; }
        @Override public byte[] capture(Player player) { captured++; afterOperation.run(); return new byte[] {1}; }
        @Override public byte[] capture(Player player, PlayerStateOperation context) { this.context = context; return capture(player); }
        @Override public void enterTemporaryState(Player player, PlayerStateOperation context) { this.context = context; enterTemporaryState(player); }
        @Override public void purgeTemporaryState(Player player, PlayerStateOperation context) { this.context = context; purgeTemporaryState(player); }
        @Override public void restore(Player player, PlayerStateOperation context, byte[] payload) { this.context = context; restore(player, payload); }
        @Override public void validateRestore(byte[] payload) {
            validated++;
            if (reject) throw new IllegalArgumentException("malformed payload");
        }
        @Override public void enterTemporaryState(Player player) { entered++; afterOperation.run(); }
        @Override public void purgeTemporaryState(Player player) { purged++; afterOperation.run(); }
        @Override public void restore(Player player, byte[] payload) { restored++; afterOperation.run(); }
    }
}
