package com.ciaac.minecraft.minigames.paper.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.arena.ArenaEquipmentContract;
import com.ciaac.minecraft.minigames.arena.ArenaFormatPolicy;
import com.ciaac.minecraft.minigames.arena.ArenaLocationPolicy;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.SnapshotRepository;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.paper.module.ColiseumEquipmentPort;
import com.ciaac.minecraft.minigames.paper.module.ColiseumModule;
import com.ciaac.minecraft.minigames.paper.module.ModuleIdentity;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequestFactory;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.CombatPolicyRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class ColiseumAuthenticatedOccupantRelocationTest {
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void authenticatedModuleRelocatesUnaffiliatedFloorOccupantWithoutChangingGameMode() {
        Fixture fixture = new Fixture(true);
        Player occupant = fixture.player;

        assertTrue(fixture.module.relocateUnaffiliatedFloorOccupant(occupant));

        assertEquals(1, fixture.teleports);
        assertEquals(fixture.fallback, fixture.currentLocation);
        assertEquals(PlayerTeleportEvent.TeleportCause.PLUGIN, fixture.lastCause);
        assertEquals(GameMode.SURVIVAL, fixture.gameMode);
    }

    @Test
    void outsideFloorAndDisabledArenaAreSuccessfulNoOps() {
        Fixture outside = new Fixture(true);
        outside.currentLocation = outside.location(50, 80, 10);
        assertTrue(outside.controller.relocateUnaffiliatedFloorOccupant(outside.player));
        assertEquals(0, outside.teleports);

        Fixture disabled = new Fixture(false);
        assertTrue(disabled.controller.relocateUnaffiliatedFloorOccupant(disabled.player));
        assertEquals(0, disabled.teleports);
    }

    @Test
    void currentMatchParticipantIsNotRelocated() {
        Fixture fixture = new Fixture(true);
        var challenge = fixture.controller.challenge(fixture.player, fixture.opponent, "1v1",
                ArenaEquipmentContract.fixed("fixture"), NOW.plusSeconds(60));
        assertEquals("CHALLENGE_CREATED", challenge.code());
        assertEquals("CHALLENGE_ACCEPTED", fixture.controller.acceptChallenge(
                fixture.opponent, challenge.matchId().orElseThrow(), NOW).code());

        assertTrue(fixture.controller.relocateUnaffiliatedFloorOccupant(fixture.player));

        assertEquals(0, fixture.teleports);
    }

    @Test
    void rejectedTeleportIsReturnedAsFailureForRuntimeDisconnect() {
        Fixture fixture = new Fixture(true);
        fixture.acceptTeleport = false;

        assertFalse(fixture.controller.relocateUnaffiliatedFloorOccupant(fixture.player));

        assertEquals(1, fixture.teleports);
        assertEquals(fixture.location(5, 80, 10), fixture.currentLocation);
    }

    private static final class Fixture {
        private final UUID worldId = UUID.randomUUID();
        private final World world = proxy(World.class, (method, returnType, args) -> switch (method) {
            case "getUID" -> worldId;
            case "getName" -> "fixture";
            default -> defaultValue(returnType);
        });
        private final Server server = proxy(Server.class, (method, returnType, args) -> switch (method) {
            case "isPrimaryThread" -> true;
            default -> defaultValue(returnType);
        });
        private final Plugin plugin = proxy(Plugin.class, (method, returnType, args) -> switch (method) {
            case "getName" -> "fixture";
            case "namespace" -> "fixture";
            default -> defaultValue(returnType);
        });
        private final UUID playerId = UUID.randomUUID();
        private final UUID opponentId = UUID.randomUUID();
        private Location currentLocation;
        private int teleports;
        private boolean acceptTeleport = true;
        private PlayerTeleportEvent.TeleportCause lastCause;
        private final GameMode gameMode = GameMode.SURVIVAL;
        private final Player player = player(playerId);
        private final Player opponent = player(opponentId);
        private final Location fallback = location(-5, 80, 10);
        private final ColiseumController controller;
        private final ColiseumModule module;

        private Fixture(boolean enabled) {
            currentLocation = location(5, 80, 10);
            var settings = new ColiseumSettings(enabled, Optional.of(worldId),
                    Optional.of(new ArenaLocationPolicy("fixture", "combat-floor", "spectator-benches",
                            "team-a", "team-b", "recovery")),
                    Optional.of(location(5, 80, 10)), Optional.of(location(35, 80, 10)),
                    Optional.of(fallback), ArenaFormatPolicy.defaultPolicy(),
                    Duration.ofSeconds(60), Duration.ofSeconds(30), false, Set.of());
            ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
            regions.register(new ProtectedRegion("combat-floor", GameKey.ARENA,
                    new CuboidRegion(worldId, 0, 70, 0, 40, 90, 20),
                    ProtectedRegionRole.PARTICIPANT_ONLY, true));
            regions.register(new ProtectedRegion("spectator-benches", GameKey.ARENA,
                    new CuboidRegion(worldId, -10, 70, 0, -2, 90, 20),
                    ProtectedRegionRole.SPECTATOR_PUBLIC, true));
            var coordinator = new SessionCoordinator(new AuthenticationRegistry(),
                    new com.ciaac.minecraft.minigames.runtime.SessionRegistry(),
                    unused(SessionRepository.class), unused(SnapshotRepository.class), unused(PlayerStateGateway.class),
                    IsolationPolicy.strictNoProgress(), new SnapshotEnvelopeCodec(), CLOCK);
            controller = new ColiseumController(settings, server, coordinator,
                    ignored -> Optional.of(UUID.randomUUID()), ignored -> Optional.of(List.of()),
                    new TemporaryItemTagger(plugin), regions, new RegionAdmissionRegistry(),
                    new CombatPolicyRegistry(), Optional.empty(), CLOCK);
            ConnectionRegistry connections = new ConnectionRegistry();
            AuthenticationRegistry authentication = new AuthenticationRegistry();
            ModuleIdentity identity = new ModuleIdentity(connections,
                    new AdmissionRequestFactory(connections, authentication, CLOCK));
            ColiseumEquipmentPort equipment = (ignored, mode) -> ArenaEquipmentContract.fixed("fixture");
            module = new ColiseumModule(controller, identity, equipment, CLOCK);
        }

        private Location location(double x, double y, double z) { return new Location(world, x, y, z); }

        private Player player(UUID id) {
            return proxy(Player.class, (method, returnType, args) -> switch (method) {
                case "getUniqueId" -> id;
                case "getName" -> id.equals(playerId) ? "Occupant" : "Opponent";
                case "getServer" -> server;
                case "isOnline", "isValid" -> true;
                case "getLocation" -> currentLocation.clone();
                case "getGameMode" -> gameMode;
                case "teleport" -> {
                    teleports++;
                    lastCause = (PlayerTeleportEvent.TeleportCause) args[1];
                    if (acceptTeleport) currentLocation = ((Location) args[0]).clone();
                    yield acceptTeleport;
                }
                default -> defaultValue(returnType);
            });
        }
    }

    private interface Invocation { Object invoke(String method, Class<?> returnType, Object[] args); }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> invocation.invoke(method.getName(), method.getReturnType(),
                        args == null ? new Object[0] : args)));
    }

    private static <T> T unused(Class<T> type) {
        return proxy(type, (method, returnType, args) -> defaultValue(returnType));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return '\0';
        return null;
    }
}
