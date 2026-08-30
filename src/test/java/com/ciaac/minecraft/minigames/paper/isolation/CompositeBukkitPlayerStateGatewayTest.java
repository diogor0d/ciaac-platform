package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
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
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> null);
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
                new CompositeBukkitPlayerStateGateway(server, List.of(first, later));

        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
                1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), GameKey.ARENA, Instant.EPOCH,
                Map.of(PlayerStateFacet.EXPERIENCE, new byte[] {1}, PlayerStateFacet.HEALTH, new byte[] {2}));

        assertThrows(IllegalArgumentException.class, () -> gateway.restore(UUID.randomUUID(), snapshot));
        assertEquals(0, first.restored);
        assertEquals(0, playerLookups.get());
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
        private int restored;

        private CountingHandler(PlayerStateFacet facet, boolean reject) {
            this.facets = Set.of(facet);
            this.reject = reject;
        }

        @Override public Set<PlayerStateFacet> facets() { return facets; }
        @Override public byte[] capture(Player player) { return new byte[] {1}; }
        @Override public void validateRestore(byte[] payload) {
            if (reject) throw new IllegalArgumentException("malformed payload");
        }
        @Override public void enterTemporaryState(Player player) {}
        @Override public void purgeTemporaryState(Player player) {}
        @Override public void restore(Player player, byte[] payload) { restored++; }
    }
}
