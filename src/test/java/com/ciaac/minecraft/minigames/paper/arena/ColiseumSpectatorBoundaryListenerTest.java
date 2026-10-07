package com.ciaac.minecraft.minigames.paper.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.arena.ArenaFormat;
import com.ciaac.minecraft.minigames.arena.ArenaFormatPolicy;
import com.ciaac.minecraft.minigames.arena.ArenaKitMode;
import com.ciaac.minecraft.minigames.arena.ArenaLocationPolicy;
import com.ciaac.minecraft.minigames.arena.ArenaMatch;
import com.ciaac.minecraft.minigames.arena.ArenaMatchRegistry;
import com.ciaac.minecraft.minigames.arena.ArenaPhase;
import com.ciaac.minecraft.minigames.arena.PlayerStateOperation;
import com.ciaac.minecraft.minigames.arena.TeamRoster;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.SnapshotRepository;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.paper.event.MinigameEventRouter;
import com.ciaac.minecraft.minigames.paper.module.ColiseumEquipmentPort;
import com.ciaac.minecraft.minigames.paper.module.ColiseumModule;
import com.ciaac.minecraft.minigames.paper.module.ModuleIdentity;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequestFactory;
import com.ciaac.minecraft.minigames.runtime.CombatPolicyRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionViolation;
import com.ciaac.minecraft.minigames.paper.RegionProtectionListener;
import com.ciaac.minecraft.minigames.module.MinigameModule;
import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import com.ciaac.minecraft.minigames.module.UnavailableMinigameModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

class ColiseumSpectatorBoundaryListenerTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void eliminatedPlayerCannotTeleportOrPortalBackToFloorIncludingSameBlockTeleport() throws Exception {
        Fixture fixture = new Fixture();
        Player spectator = fixture.players.getFirst();
        fixture.eliminateInDomain(spectator);
        ColiseumSpectatorBoundaryListener listener = fixture.listener();

        PlayerTeleportEvent sameBlockFloorTeleport = new PlayerTeleportEvent(
                spectator, fixture.floor(4, 64, 4), fixture.floor(4, 64, 4));
        invokeHandler(listener, "onTeleport", sameBlockFloorTeleport);
        assertTrue(sameBlockFloorTeleport.isCancelled());

        PlayerPortalEvent portalToFloor = new PlayerPortalEvent(
                spectator, fixture.bench(25, 64, 4), fixture.floor(5, 64, 5));
        invokeHandler(listener, "onPortal", portalToFloor);
        assertTrue(portalToFloor.isCancelled());
        assertEquals(ArenaPhase.ACTIVE, fixture.match.phase());
        assertEquals(Set.of(spectator.getUniqueId()), fixture.match.forfeits().keySet());
    }

    @Test
    void fighterTeleportEscapeQueuesOneForfeitWithoutRunningIt() throws Exception {
        Fixture fixture = new Fixture();
        Player fighter = fixture.players.getFirst();
        ColiseumSpectatorBoundaryListener listener = fixture.listener();
        PlayerTeleportEvent escape = new PlayerTeleportEvent(
                fighter, fixture.floor(4, 64, 4), fixture.bench(25, 64, 4));
        PlayerTeleportEvent repeatedEscape = new PlayerTeleportEvent(
                fighter, fixture.floor(4, 64, 4), fixture.bench(26, 64, 4));

        invokeHandler(listener, "onTeleport", escape);
        invokeHandler(listener, "onTeleport", repeatedEscape);

        assertTrue(escape.isCancelled());
        assertTrue(repeatedEscape.isCancelled());
        assertTrue(fixture.match.forfeits().isEmpty());
        assertEquals(ArenaPhase.ACTIVE, fixture.match.phase());
        assertTrue(fixture.match.activePlayers().contains(fighter.getUniqueId()));
        assertEquals(1, fixture.scheduledTasks.size());
    }

    @Test
    void legalRegionsForeignPlayersAndAlreadyCancelledTeleportsArePreserved() throws Exception {
        Fixture fixture = new Fixture();
        ColiseumSpectatorBoundaryListener listener = fixture.listener();

        PlayerTeleportEvent fighterWithinFloor = new PlayerTeleportEvent(
                fixture.players.getFirst(), fixture.floor(4, 64, 4), fixture.floor(5, 64, 5));
        invokeHandler(listener, "onTeleport", fighterWithinFloor);
        assertFalse(fighterWithinFloor.isCancelled());

        PlayerTeleportEvent foreignPlayer = new PlayerTeleportEvent(
                fixture.outsider, fixture.bench(25, 64, 4), fixture.floor(5, 64, 5));
        invokeHandler(listener, "onTeleport", foreignPlayer);
        assertFalse(foreignPlayer.isCancelled());

        PlayerTeleportEvent alreadyCancelled = new PlayerTeleportEvent(
                fixture.players.getFirst(), fixture.floor(4, 64, 4), fixture.bench(25, 64, 4));
        alreadyCancelled.setCancelled(true);
        invokeHandler(listener, "onTeleport", alreadyCancelled);
        assertTrue(alreadyCancelled.isCancelled());
        assertTrue(fixture.match.forfeits().isEmpty());
    }

    @Test
    void eliminatedParticipantMayTeleportWithinPublicSpectatorRegion() throws Exception {
        Fixture fixture = new Fixture();
        Player spectator = fixture.players.getFirst();
        fixture.eliminateInDomain(spectator);
        PlayerTeleportEvent withinBenches = new PlayerTeleportEvent(
                spectator, fixture.bench(25, 64, 4), fixture.bench(26, 64, 4));

        invokeHandler(fixture.listener(), "onTeleport", withinBenches);

        assertFalse(withinBenches.isCancelled());
        assertEquals(ArenaPhase.ACTIVE, fixture.match.phase());
    }

    @Test
    void arenaBoundaryRunsBeforeGenericTransportAndPreventsViolationCallback() throws Exception {
        Fixture fixture = new Fixture();
        Player spectator = fixture.players.getFirst();
        UUID sessionId = fixture.sessionIds.get(spectator.getUniqueId());
        fixture.eliminateInDomain(spectator);
        PlayerSession session = fixture.activeSession(sessionId, spectator.getUniqueId());
        List<SessionViolation> violations = new ArrayList<>();
        RegionProtectionListener generic = new RegionProtectionListener(
                fixture.regions, fixture.admissions, fixture.sessions,
                (player, active, violation) -> violations.add(violation), Clock.fixed(NOW, ZoneOffset.UTC));
        ColiseumSpectatorBoundaryListener arena = fixture.listener();
        PlayerTeleportEvent attempt = new PlayerTeleportEvent(
                spectator, fixture.bench(25, 64, 4), fixture.floor(5, 64, 5));

        invokeHandler(arena, "onTeleport", attempt);
        invokeHandler(generic, "onTeleport", attempt);

        assertTrue(attempt.isCancelled());
        assertTrue(violations.isEmpty());
        assertEquals(SessionPhase.ACTIVE, session.phase());
        assertEquals(ArenaPhase.ACTIVE, fixture.match.phase());
        assertEquals(EventPriority.HIGH, ColiseumSpectatorBoundaryListener.class
                .getMethod("onTeleport", PlayerTeleportEvent.class).getAnnotation(EventHandler.class).priority());
    }

    @Test
    void routerDefersActiveArenaMovementToRosterBoundaryAndGenericGuard() throws Exception {
        Fixture fixture = new Fixture();
        Player fighter = fixture.players.getFirst();
        fixture.activeSession(fixture.sessionIds.get(fighter.getUniqueId()), fighter.getUniqueId());
        List<SessionViolation> violations = new ArrayList<>();
        RegionProtectionListener generic = new RegionProtectionListener(
                fixture.regions, fixture.admissions, fixture.sessions,
                (player, active, violation) -> violations.add(violation), Clock.fixed(NOW, ZoneOffset.UTC));
        MinigameEventRouter router = fixture.router();
        ColiseumSpectatorBoundaryListener arena = fixture.listener();
        PlayerMoveEvent escape = new PlayerMoveEvent(
                fighter, fixture.floor(0, 64, 4), fixture.floor(-1, 64, 4));

        invokeHandler(router, "onMove", escape);
        assertFalse(escape.isCancelled(), "the LOWEST router should defer the Coliseum boundary");
        invokeHandler(arena, "onMove", escape);
        invokeHandler(generic, "onMove", escape);

        assertTrue(escape.isCancelled());
        assertEquals(1, fixture.scheduledTasks.size());
        assertTrue(violations.isEmpty());
        assertEquals(ArenaPhase.ACTIVE, fixture.match.phase());
        assertTrue(fixture.match.activePlayers().contains(fighter.getUniqueId()));
    }

    @Test
    void routerKeepsExternalCommandTeleportDeniedButDefersPluginTransferToArena() throws Exception {
        Fixture commandFixture = new Fixture();
        Player fighter = commandFixture.players.getFirst();
        MinigameEventRouter commandRouter = commandFixture.router();
        ColiseumSpectatorBoundaryListener commandArena = commandFixture.listener();
        PlayerTeleportEvent commandTeleport = new PlayerTeleportEvent(
                fighter, commandFixture.floor(4, 64, 4), commandFixture.floor(-1, 64, 4),
                PlayerTeleportEvent.TeleportCause.COMMAND);

        invokeHandler(commandRouter, "onTeleport", commandTeleport);
        invokeHandler(commandArena, "onTeleport", commandTeleport);

        assertTrue(commandTeleport.isCancelled());
        assertTrue(commandFixture.scheduledTasks.isEmpty());
        assertTrue(commandFixture.match.forfeits().isEmpty());

        Fixture pluginFixture = new Fixture();
        Player pluginFighter = pluginFixture.players.getFirst();
        MinigameEventRouter pluginRouter = pluginFixture.router();
        ColiseumSpectatorBoundaryListener pluginArena = pluginFixture.listener();
        PlayerTeleportEvent pluginTeleport = new PlayerTeleportEvent(
                pluginFighter, pluginFixture.floor(4, 64, 4), pluginFixture.floor(-1, 64, 4),
                PlayerTeleportEvent.TeleportCause.PLUGIN);

        invokeHandler(pluginRouter, "onTeleport", pluginTeleport);
        assertFalse(pluginTeleport.isCancelled(), "plugin transfer should reach the arena roster boundary");
        invokeHandler(pluginArena, "onTeleport", pluginTeleport);

        assertTrue(pluginTeleport.isCancelled());
        assertEquals(1, pluginFixture.scheduledTasks.size());
        assertTrue(pluginFixture.match.forfeits().isEmpty());
        assertEquals(ArenaPhase.ACTIVE, pluginFixture.match.phase());
    }

    private static void invokeHandler(Object listener, String name, Object event) throws Exception {
        Method handler = null;
        for (Method candidate : listener.getClass().getMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterCount() == 1
                    && candidate.getParameterTypes()[0].isInstance(event)) {
                handler = candidate;
                break;
            }
        }
        assertNotNull(handler, "Missing event boundary handler " + name + " for " + event.getClass().getSimpleName());
        handler.invoke(listener, event);
    }

    private static final class Fixture {
        private final UUID worldId = UUID.randomUUID();
        private final World world = proxy(World.class, (method, args) -> switch (method.getName()) {
            case "getUID" -> worldId;
            case "getName" -> "world";
            default -> primitiveDefault(method.getReturnType());
        });
        private final Server server;
        private final Plugin plugin;
        private final ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
        private final RegionAdmissionRegistry admissions = new RegionAdmissionRegistry();
        private final SessionRegistry sessions = new SessionRegistry();
        private final ColiseumController controller;
        private final ArenaMatch match;
        private final List<Player> players = new ArrayList<>();
        private final List<Runnable> scheduledTasks = new ArrayList<>();
        private final Player outsider;
        private final java.util.Map<UUID, UUID> sessionIds = new java.util.LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        private Fixture() throws Exception {
            BukkitTask task = proxy(BukkitTask.class, (method, args) -> primitiveDefault(method.getReturnType()));
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (method, args) -> {
                if (method.getName().equals("runTask") && args[1] instanceof Runnable runnable) {
                    scheduledTasks.add(runnable);
                    return task;
                }
                return primitiveDefault(method.getReturnType());
            });
            server = proxy(Server.class, (method, args) -> switch (method.getName()) {
                case "isPrimaryThread" -> true;
                case "getScheduler" -> scheduler;
                case "getWorld" -> world;
                default -> primitiveDefault(method.getReturnType());
            });
            plugin = proxy(Plugin.class, (method, args) -> switch (method.getName()) {
                case "getName" -> "arena-test";
                case "namespace" -> "arena-test";
                case "getServer" -> server;
                default -> primitiveDefault(method.getReturnType());
            });
            for (int index = 0; index < 4; index++) players.add(player(UUID.randomUUID()));
            outsider = player(UUID.randomUUID());
            regions.register(new ProtectedRegion("combat-floor", GameKey.ARENA,
                    new CuboidRegion(worldId, 0, 0, 0, 10, 255, 10), ProtectedRegionRole.PARTICIPANT_ONLY, true));
            regions.register(new ProtectedRegion("spectator-benches", GameKey.ARENA,
                    new CuboidRegion(worldId, 20, 0, 0, 30, 255, 10), ProtectedRegionRole.SPECTATOR_PUBLIC, false));

            SessionCoordinator coordinator = new SessionCoordinator(
                    new AuthenticationRegistry(), sessions,
                    proxy(SessionRepository.class, (method, args) -> method.getName().equals("save") ? true : null),
                    proxy(SnapshotRepository.class, (method, args) -> null),
                    proxy(PlayerStateGateway.class, (method, args) -> null),
                    IsolationPolicy.strictNoProgress(), new SnapshotEnvelopeCodec(), Clock.fixed(NOW, ZoneOffset.UTC));
            ArenaFormatPolicy formats = new ArenaFormatPolicy(3, true, Set.of(
                    ArenaFormat.parse("2v3", true), ArenaFormat.parse("3v2", true)));
            ColiseumSettings settings = new ColiseumSettings(true, Optional.of(worldId),
                    Optional.of(new ArenaLocationPolicy("world", "combat-floor", "spectator-benches",
                            "team-a", "team-b", "fallback")),
                    Optional.of(floor(1, 64, 1)), Optional.of(floor(9, 64, 9)),
                    Optional.of(bench(25, 64, 4)), formats,
                    Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ZERO,
                    false, Set.of(), false, Set.of(), Set.of(), Duration.ofSeconds(60), "REFUND", "arena-test-v1");
            controller = new ColiseumController(settings, server, coordinator,
                    player -> Optional.of(UUID.randomUUID()), ignored -> Optional.empty(), new TemporaryItemTagger(plugin),
                    regions, admissions, new CombatPolicyRegistry(), Optional.empty(), Clock.fixed(NOW, ZoneOffset.UTC));

            Field matchesField = ColiseumController.class.getDeclaredField("matches");
            matchesField.setAccessible(true);
            ArenaMatchRegistry registry = (ArenaMatchRegistry) matchesField.get(controller);
            Set<UUID> teamA = Set.of(players.get(0).getUniqueId(), players.get(1).getUniqueId());
            Set<UUID> teamB = Set.of(players.get(2).getUniqueId(), players.get(3).getUniqueId());
            match = registry.reserve(UUID.randomUUID(), ArenaFormat.standard(2), ArenaKitMode.FIXED,
                    TeamRoster.of(teamA.toArray(UUID[]::new)), TeamRoster.of(teamB.toArray(UUID[]::new)), formats);
            LinkedHashSet<UUID> participants = new LinkedHashSet<>(teamA);
            participants.addAll(teamB);
            for (UUID participant : participants) {
                match.markReady(participant);
                match.recordSnapshot(new PlayerStateOperation(participant, UUID.randomUUID(), NOW));
                UUID sessionId = UUID.randomUUID();
                sessionIds.put(participant, sessionId);
                admissions.issue(new RegionAdmissionToken(UUID.randomUUID(), sessionId, participant,
                        "combat-floor", NOW, NOW.plus(Duration.ofMinutes(5))));
            }
            match.transitionTo(ArenaPhase.ADMITTING);
            match.transitionTo(ArenaPhase.ACTIVE);
            Field sessionMap = ColiseumController.class.getDeclaredField("sessionByPlayer");
            sessionMap.setAccessible(true);
            ((java.util.Map<UUID, UUID>) sessionMap.get(controller)).putAll(sessionIds);
        }

        private ColiseumSpectatorBoundaryListener listener() {
            return new ColiseumSpectatorBoundaryListener(controllerSettings(), regions, controller, plugin);
        }

        private MinigameEventRouter router() {
            ConnectionRegistry identityConnections = new ConnectionRegistry();
            AuthenticationRegistry identityAuthentication = new AuthenticationRegistry();
            ModuleIdentity identity = new ModuleIdentity(identityConnections,
                    new AdmissionRequestFactory(identityConnections, identityAuthentication,
                            Clock.fixed(NOW, ZoneOffset.UTC)));
            ColiseumEquipmentPort equipment = (player, mode) ->
                    com.ciaac.minecraft.minigames.arena.ArenaEquipmentContract.fixed("fixture");
            ColiseumModule arenaModule = new ColiseumModule(
                    controller, identity, equipment, Clock.fixed(NOW, ZoneOffset.UTC));
            List<MinigameModule> modules = new ArrayList<>();
            for (GameKey game : GameKey.values()) {
                modules.add(game == GameKey.ARENA
                        ? arenaModule : new UnavailableMinigameModule(game, "fixture unavailable"));
            }
            return MinigameEventRouter.fromRegistry(new MinigameModuleRegistry(modules),
                    Clock.fixed(NOW, ZoneOffset.UTC), MinigameEventRouter.Policies.failClosed());
        }

        private void eliminateInDomain(Player player) {
            UUID playerId = player.getUniqueId();
            match.forfeit(playerId, "PLAYER_LEFT");
            admissions.revokeSession(sessionIds.get(playerId));
        }

        private PlayerSession activeSession(UUID sessionId, UUID playerId) {
            PlayerSession session = new PlayerSession(sessionId, match.id(), playerId, GameKey.ARENA, NOW);
            session.transition(UUID.randomUUID(), SessionPhase.REQUESTED, SessionPhase.SNAPSHOTTING, NOW, "TEST");
            session.transition(UUID.randomUUID(), SessionPhase.SNAPSHOTTING, SessionPhase.SNAPSHOT_COMMITTED, NOW,
                    "TEST");
            session.transition(UUID.randomUUID(), SessionPhase.SNAPSHOT_COMMITTED, SessionPhase.PREPARING, NOW,
                    "TEST");
            session.transition(UUID.randomUUID(), SessionPhase.PREPARING, SessionPhase.ACTIVE, NOW, "TEST");
            sessions.register(session);
            return session;
        }

        private ColiseumSettings controllerSettings() {
            try {
                Field value = ColiseumController.class.getDeclaredField("settings");
                value.setAccessible(true);
                return (ColiseumSettings) value.get(controller);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }

        private Location floor(double x, double y, double z) { return new Location(world, x, y, z); }
        private Location bench(double x, double y, double z) { return new Location(world, x, y, z); }

        private Player player(UUID id) {
            AttributeInstance maxHealth = proxy(AttributeInstance.class, (method, args) ->
                    method.getName().equals("getValue") ? 20.0D : primitiveDefault(method.getReturnType()));
            return proxy(Player.class, (method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getServer" -> server;
                case "getAttribute" -> args[0] == Attribute.MAX_HEALTH ? maxHealth : null;
                case "isOnline", "isValid" -> true;
                case "getName" -> "player";
                case "teleport" -> true;
                case "getGameMode" -> GameMode.SURVIVAL;
                default -> primitiveDefault(method.getReturnType());
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName() + " fixture";
                    default -> null;
                };
            }
            return invocation.call(method, args == null ? new Object[0] : args);
        });
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(Method method, Object[] args) throws Throwable;
    }
}
