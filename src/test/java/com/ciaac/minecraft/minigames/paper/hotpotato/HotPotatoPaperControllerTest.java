package com.ciaac.minecraft.minigames.paper.hotpotato;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.hotpotato.HotPotatoConfig;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.SnapshotRepository;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class HotPotatoPaperControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void admissionTokenCoversEntireConfiguredMatch() {
        assertEquals(Duration.ofHours(1).plusSeconds(1),
                HotPotatoPaperController.admissionTokenLifetime(Duration.ofMinutes(10), Duration.ofHours(1)));
        assertEquals(Duration.ofMinutes(10),
                HotPotatoPaperController.admissionTokenLifetime(Duration.ofMinutes(10), Duration.ofSeconds(90)));
    }

    @Test
    void queuedDisconnectRemovesPlayerBeforeSessionAdmission() {
        UUID worldId = UUID.randomUUID();
        World world = proxy(World.class, method -> method.equals("getUID") ? worldId : null);
        Player player = proxy(Player.class, method -> switch (method) {
            case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000001");
            case "isOnline", "isValid" -> true;
            default -> null;
        });
        HotPotatoPaperSettings settings = new HotPotatoPaperSettings(true,
                new HotPotatoConfig(2, 2, Duration.ofSeconds(12), Duration.ofSeconds(4),
                        Duration.ZERO, 4, Duration.ofSeconds(90), "fixture"),
                world, "hot-potato-arena",
                List.of(new Location(world, 1, 65, 1), new Location(world, 2, 65, 2)),
                Duration.ofSeconds(20), Duration.ofMinutes(10));
        SessionRegistry sessions = new SessionRegistry();
        SessionCoordinator coordinator = new SessionCoordinator(new AuthenticationRegistry(), sessions,
                unused(SessionRepository.class), unused(SnapshotRepository.class),
                unused(PlayerStateGateway.class), IsolationPolicy.strictNoProgress(),
                new SnapshotEnvelopeCodec(), CLOCK);
        ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
        regions.register(new ProtectedRegion("hot-potato-arena", GameKey.HOT_POTATO,
                new CuboidRegion(worldId, 0, 60, 0, 10, 80, 10),
                ProtectedRegionRole.PARTICIPANT_ONLY, true));
        Plugin plugin = proxy(Plugin.class, method -> switch (method) {
            case "getName" -> "hot-potato-test";
            case "namespace" -> "hot-potato-test";
            default -> null;
        });
        HotPotatoPaperController controller = new HotPotatoPaperController(settings, coordinator,
                sessions, regions, new RegionAdmissionRegistry(), new TemporaryItemTagger(plugin), CLOCK);
        UUID playerId = (UUID) player.getUniqueId();
        UUID matchId = UUID.randomUUID();
        AdmissionRequest request = new AdmissionRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), matchId, playerId, UUID.randomUUID(), GameKey.HOT_POTATO, NOW);

        assertEquals(AdmissionStatus.REJECTED, controller.join(player, request).status());
        assertTrue(controller.hasParticipant(playerId));
        controller.onDisconnect(playerId);

        assertFalse(controller.hasParticipant(playerId));
        assertEquals(0, controller.status().players());
    }

    private static <T> T unused(Class<T> type) {
        return proxy(type, method -> {
            throw new AssertionError("Unexpected dependency call: " + method);
        });
    }

    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + " test proxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    return handler.apply(method.getName());
                }));
    }
}
