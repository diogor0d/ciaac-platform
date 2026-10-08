package com.ciaac.minecraft.minigames.paper.elytrarings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.elytrarings.ElytraCourseRevision;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsConfig;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsGame;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsPhase;
import com.ciaac.minecraft.minigames.elytrarings.RingCheckpoint;
import com.ciaac.minecraft.minigames.isolation.InMemorySnapshotRepository;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
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
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ElytraRingsMovementTest {
    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private Field bukkitServerField;
    private Object previousBukkitServer;

    @BeforeEach void captureBukkitServer() throws ReflectiveOperationException {
        bukkitServerField = Bukkit.class.getDeclaredField("server");
        bukkitServerField.setAccessible(true);
        previousBukkitServer = bukkitServerField.get(null);
    }

    @AfterEach void restoreBukkitServer() throws IllegalAccessException {
        bukkitServerField.set(null, previousBukkitServer);
    }

    @Test
    void walkingThroughARingDoesNotCreditFlightProgress() {
        Harness harness = new Harness();
        harness.gliding[0] = false;
        harness.accept(.5, 65, .5, 2.5, 65, .5);
        harness.tick(NOW.plusSeconds(1));
        assertEquals(1, harness.game.nextRing());
        harness.gliding[0] = true;
        harness.accept(.5, 65, .5, 2.5, 65, .5);
        harness.tick(NOW.plusSeconds(2));
        assertEquals(2, harness.game.nextRing());
    }

    @Test
    void fastCrossingBetweenTicksCreditsTheRing() {
        Harness harness = new Harness();
        harness.accept(.5, 65, .5, 2.5, 65, .5);

        harness.tick(NOW.plusSeconds(1));

        assertEquals(2, harness.game.nextRing());
        assertEquals(ElytraRingsPhase.RUNNING, harness.game.phase());
    }

    @Test
    void oneSegmentCanCreditSeveralRingsOnlyInIncreasingFractionOrder() {
        Harness forward = new Harness();
        forward.accept(.5, 65, .5, 5.5, 65, .5);
        forward.tick(NOW.plusSeconds(1));
        assertEquals(ElytraRingsPhase.CLOSED, forward.game.phase());
        assertEquals(2, forward.game.result().orElseThrow().splits().size());

        Harness reversed = new Harness();
        reversed.accept(5.5, 65, .5, .5, 65, .5);
        reversed.tick(NOW.plusSeconds(1));
        assertEquals(2, reversed.game.nextRing());
        assertEquals(ElytraRingsPhase.RUNNING, reversed.game.phase());
    }

    @Test
    void bentPathCreditsRingsAcrossSeparateAcceptedSegments() {
        Harness harness = new Harness();
        harness.accept(.5, 65, .5, 2.5, 65, .5);
        harness.tick(NOW.plusSeconds(1));
        assertEquals(2, harness.game.nextRing());

        harness.accept(2.5, 65, .5, 5.5, 65, .5);
        harness.tick(NOW.plusSeconds(2));

        assertEquals(ElytraRingsPhase.CLOSED, harness.game.phase());
        assertEquals(2, harness.game.result().orElseThrow().splits().size());
    }

    @Test
    void cancelledAndTeleportMovesAreNeverQueuedForScoring() {
        Harness harness = new Harness();
        PlayerMoveEvent cancelled = harness.move(.5, 65, .5, 2.5, 65, .5);
        cancelled.setCancelled(true);
        harness.controller.onAcceptedMove(cancelled);

        PlayerTeleportEvent teleport = new PlayerTeleportEvent(harness.player,
                new Location(harness.world.world, .5, 65, .5), new Location(harness.world.world, 5.5, 65, .5),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        harness.controller.onAcceptedMove(teleport);

        World otherWorld = proxy(World.class, (method, args) -> switch (method.getName()) {
            case "getUID" -> uuid(999);
            case "getName" -> "other-world";
            default -> defaultValue(method.getReturnType());
        });
        harness.controller.onAcceptedMove(new PlayerMoveEvent(harness.player,
                new Location(harness.world.world, .5, 65, .5), new Location(otherWorld, 2.5, 65, .5)));

        Player unrelated = player(uuid(998), new Location[]{new Location(harness.world.world, .5, 65, .5)});
        harness.controller.onAcceptedMove(new PlayerMoveEvent(unrelated,
                new Location(harness.world.world, .5, 65, .5), new Location(harness.world.world, 2.5, 65, .5)));
        harness.tick(NOW.plusSeconds(1));

        assertEquals(1, harness.game.nextRing());
        assertEquals(ElytraRingsPhase.RUNNING, harness.game.phase());
    }

    @Test
    void queueOverflowDefersFailClosedRecoveryUntilTick() {
        Harness harness = new Harness();
        for (int index = 0; index < 129; index++) {
            harness.controller.onAcceptedMove(harness.move(.5, 65, .5, 2.5, 65, .5));
        }
        assertEquals(ElytraRingsPhase.RUNNING, harness.game.phase());
        assertEquals(com.ciaac.minecraft.minigames.runtime.SessionPhase.ACTIVE, harness.session.phase());

        harness.tick(NOW.plusSeconds(1));

        assertEquals(ElytraRingsPhase.CLOSED, harness.game.phase());
        assertEquals(com.ciaac.minecraft.minigames.runtime.SessionPhase.CLOSED, harness.session.phase());
        assertEquals(0, harness.world.ticketCount());
    }

    @Test
    void exactDeadlineIsAllowedButLateAcceptedInputDoesNotScore() {
        Harness exact = new Harness();
        Instant deadline = NOW.plus(Duration.ofSeconds(10));
        exact.accept(.5, 65, .5, 2.5, 65, .5);
        exact.tick(NOW.plusSeconds(1));
        assertEquals(2, exact.game.nextRing());
        exact.clock.set(deadline);
        exact.accept(2.5, 65, .5, 5.5, 65, .5);
        exact.tick(deadline);
        assertEquals(ElytraRingsPhase.CLOSED, exact.game.phase());
        assertTrue(exact.game.result().orElseThrow().valid());

        Harness late = new Harness();
        late.accept(.5, 65, .5, 2.5, 65, .5);
        late.tick(NOW.plusSeconds(1));
        assertEquals(2, late.game.nextRing());
        Instant afterDeadline = NOW.plus(Duration.ofSeconds(10)).plusNanos(1);
        late.clock.set(afterDeadline);
        late.accept(2.5, 65, .5, 5.5, 65, .5);
        late.tick(afterDeadline);
        assertEquals(ElytraRingsPhase.CLOSED, late.game.phase());
        assertEquals("INVALIDATED", late.game.result().orElseThrow().reason());
        assertEquals(1, late.game.result().orElseThrow().splits().size());
    }

    @Test
    void resetClearsQueuedSegmentsBeforeTheyCanScore() {
        Harness harness = new Harness();
        harness.accept(.5, 65, .5, 2.5, 65, .5);
        harness.playerLocation[0] = new Location(harness.world.world, 20, 65, 20);
        harness.tick(NOW.plusSeconds(1));
        assertEquals(1, harness.game.nextRing());
        assertEquals(ElytraRingsPhase.RUNNING, harness.game.phase());

        harness.playerLocation[0] = harness.start.clone();
        harness.tick(NOW.plusSeconds(2));
        assertEquals(1, harness.game.nextRing());
    }

    @Test
    void internalCourseTeleportReappliesAdventureModeAfterDestinationDefaultsToSurvival() {
        Harness harness = new Harness();
        harness.gameMode[0] = GameMode.ADVENTURE;

        harness.playerLocation[0] = new Location(harness.world.world, 20, 65, 20);
        harness.tick(NOW.plusSeconds(1));

        assertEquals(com.ciaac.minecraft.minigames.runtime.SessionPhase.ACTIVE, harness.session.phase());
        assertEquals(GameMode.ADVENTURE, harness.gameMode[0]);
    }

    private static UUID uuid(long value) { return new UUID(0L, value); }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        @SuppressWarnings("unchecked")
        T proxy = (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (target, method, args) -> invocation.invoke(method, args));
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

    private static final class Gateway implements PlayerStateGateway {
        @Override public Set<PlayerStateFacet> supportedFacets() { return EnumSet.allOf(PlayerStateFacet.class); }
        @Override public PlayerStateSnapshot capture(UUID snapshotId, UUID operationId, UUID sessionId,
                UUID matchId, UUID playerId, UUID connectionId, GameKey game, Instant capturedAt) {
            return new PlayerStateSnapshot(2, snapshotId, operationId, sessionId, matchId,
                    playerId, connectionId, game, capturedAt, Map.of());
        }
        @Override public void enterTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {}
        @Override public void purgeTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {}
        @Override public void restore(UUID operationId, PlayerStateSnapshot snapshot) {}
    }

    private static final class MutableClock extends Clock {
        private Instant current = NOW;
        private void set(Instant value) { current = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }

    private static final class TicketWorld {
        private final Set<String> tickets = new HashSet<>();
        private final UUID id;
        private List<Player> players = List.of();
        private final World world;
        private TicketWorld(UUID id) {
            this.id = id;
            world = proxy(World.class, (method, args) -> switch (method.getName()) {
                case "getUID" -> this.id;
                case "getName" -> "elytra-movement-world";
                case "isChunkLoaded" -> true;
                case "getPlayers" -> players;
                case "addPluginChunkTicket" -> { tickets.add(args[0] + ":" + args[1]); yield true; }
                case "removePluginChunkTicket" -> { tickets.remove(args[0] + ":" + args[1]); yield true; }
                default -> defaultValue(method.getReturnType());
            });
        }
        private int ticketCount() { return tickets.size(); }
    }

    private final class Harness {
        private final UUID matchId = UUID.randomUUID();
        private final UUID playerId = uuid(42);
        private final UUID worldId = UUID.randomUUID();
        private final TicketWorld world = new TicketWorld(worldId);
        private final Location start = new Location(world.world, .5, 65, .5);
        private final Location[] playerLocation = {start.clone()};
        private final boolean[] gliding = {true};
        private final GameMode[] gameMode = {GameMode.ADVENTURE};
        private final Player player = player(playerId, playerLocation, gliding, gameMode);
        private final SessionRegistry sessions = new SessionRegistry();
        private final AuthenticationRegistry authentication = new AuthenticationRegistry();
        private final MemorySessionRepository repository = new MemorySessionRepository();
        private final RegionAdmissionRegistry admissions = new RegionAdmissionRegistry();
        private final MutableClock clock = new MutableClock();
        private final SessionCoordinator coordinator;
        private final ElytraRingsGame game;
        private final ElytraRingsController controller;
        private final PlayerSession session;

        private Harness() {
            Server server = proxy(Server.class, (method, args) -> method.getName().equals("isPrimaryThread")
                    ? true : defaultValue(method.getReturnType()));
            installBukkitServer(server);
            Plugin plugin = proxy(Plugin.class, (method, args) -> switch (method.getName()) {
                case "namespace" -> "ciaacplatform";
                case "getName" -> "test-plugin";
                case "getServer" -> server;
                default -> defaultValue(method.getReturnType());
            });
            var course = new ElytraCourseRevision("v1", worldId.toString(), List.of(
                    new RingCheckpoint(1, 1.5, 65, .5), new RingCheckpoint(2, 4.5, 65, .5)));
            var config = ElytraRingsConfig.dedicatedWorld(Duration.ofSeconds(10), course);
            var ringRegions = List.of(
                    new CuboidRegion(worldId, 1, 64, 0, 1, 66, 1),
                    new CuboidRegion(worldId, 4, 64, 0, 4, 66, 1));
            var settings = new ElytraRingsPaperSettings(true, config, world.world, "elytra-boundary",
                    start, 3.0, 0, 0, ringRegions);
            ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
            regions.register(new ProtectedRegion("elytra-boundary", GameKey.ELYTRA_RINGS,
                    new CuboidRegion(worldId, -10, 0, -10, 10, 200, 10),
                    ProtectedRegionRole.PARTICIPANT_ONLY, true));
            coordinator = new SessionCoordinator(authentication, sessions, repository,
                    new InMemorySnapshotRepository(), new Gateway(), IsolationPolicy.strictNoProgress(),
                    new SnapshotEnvelopeCodec(), clock);
            game = new ElytraRingsGame(matchId, playerId, config);
            TemporaryItemTagger tagger = new TemporaryItemTagger(plugin);
            ElytraChunkPreparation chunks = new ElytraChunkPreparation(settings, plugin);
            controller = new ElytraRingsController(matchId, game, settings, coordinator, sessions,
                    admissions, regions, tagger, clock, StatisticsResultSink.unavailable(), chunks, ignored -> true);
            chunks.tick();
            assertTrue(chunks.admissionReady());
            UUID connectionId = uuid(43);
            UUID requestId = uuid(44);
            UUID sessionId = uuid(45);
            UUID snapshotId = uuid(46);
            authentication.authenticated(new AuthenticatedSession(uuid(47), playerId, connectionId,
                    NOW.minusSeconds(1), NOW.plusSeconds(600)));
            AdmissionRequest request = new AdmissionRequest(requestId, sessionId, snapshotId,
                    matchId, playerId, connectionId, GameKey.ELYTRA_RINGS, NOW);
            AdmissionResult prepared = coordinator.prepare(request);
            assertEquals(AdmissionStatus.PREPARED, prepared.status());
            assertTrue(coordinator.activate(sessionId, OperationIds.derive(requestId, "GAME_ACTIVE"), NOW));
            session = prepared.session().orElseThrow();
            admissions.issue(new RegionAdmissionToken(uuid(48), sessionId, playerId,
                    "elytra-boundary", NOW, NOW.plusSeconds(60)));
            game.open(new com.ciaac.minecraft.minigames.hotpotato.OperationId(matchId, 1));
            game.start(NOW, new com.ciaac.minecraft.minigames.hotpotato.OperationId(matchId, 2));
            setField(controller, "operationSequence", 2L);
            setField(controller, "startedAt", NOW);
            world.players = List.of(player);
        }

        private void accept(double fx, double fy, double fz, double tx, double ty, double tz) {
            controller.onAcceptedMove(move(fx, fy, fz, tx, ty, tz));
        }

        private PlayerMoveEvent move(double fx, double fy, double fz, double tx, double ty, double tz) {
            return new PlayerMoveEvent(player, new Location(world.world, fx, fy, fz),
                    new Location(world.world, tx, ty, tz));
        }

        private void tick(Instant now) { controller.tick(now); }
    }

    private static Player player(UUID id, Location[] location) {
        return player(id, location, new boolean[]{true}, new GameMode[]{GameMode.ADVENTURE});
    }

    private static Player player(UUID id, Location[] location, boolean[] gliding) {
        return player(id, location, gliding, new GameMode[]{GameMode.ADVENTURE});
    }

    private static Player player(UUID id, Location[] location, boolean[] gliding, GameMode[] gameMode) {
        return proxy(Player.class, (method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "isOnline", "isValid" -> true;
            case "isGliding" -> gliding[0];
            case "getLocation" -> location[0].clone();
            case "getGameMode" -> gameMode[0];
            case "setGameMode" -> { gameMode[0] = (GameMode) args[0]; yield null; }
            case "teleport" -> { gameMode[0] = GameMode.SURVIVAL; yield true; }
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

    @FunctionalInterface private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
