package com.ciaac.minecraft.minigames.paper.knockbacksumo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.InMemorySnapshotRepository;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.knockbacksumo.SumoConfig;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.statistics.MatchOutcome;
import com.ciaac.minecraft.minigames.statistics.MatchResult;
import com.ciaac.minecraft.minigames.statistics.StatisticsRepository;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class SumoPaperControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Test
    void admissionTokenLifetimeCoversConfiguredRoundTimeout() {
        assertEquals(Duration.ofMinutes(2).plusSeconds(1),
                SumoPaperController.admissionTokenLifetime(Duration.ofMinutes(1), Duration.ofMinutes(2)));
        assertEquals(Duration.ofMinutes(10),
                SumoPaperController.admissionTokenLifetime(Duration.ofMinutes(10), Duration.ofMinutes(2)));
    }

    @Test
    void admissionTokenCoversRoundDeadlineAndIsRefreshedOnRoundReset() throws Exception {
        RingFixture fixture = new RingFixture(2, Duration.ofMinutes(1), Duration.ofMinutes(2));
        String region = "knockback-sumo.boundary";
        RegionAdmissionToken initial = fixture.admissions.find(fixture.firstState.id, region).orElseThrow();
        assertEquals(NOW.plusSeconds(121), initial.expiresAt());
        assertTrue(initial.validAt(NOW.plusSeconds(120)));
        assertFalse(initial.validAt(NOW.plusSeconds(121)));

        fixture.controller.onRingOut(fixture.firstState.id, UUID.randomUUID());

        RegionAdmissionToken refreshed = fixture.admissions.find(fixture.firstState.id, region).orElseThrow();
        assertNotEquals(initial.tokenId(), refreshed.tokenId());
        assertEquals(NOW.plusSeconds(121), refreshed.expiresAt());
    }

    @Test
    void configuredKnockbackScalesFromLevelTwoAndCapsAtFour() {
        Vector base = new Vector(1.0, 0.35, 0.0);

        assertEquals(base, SumoPaperController.scaleKnockbackImpulse(base, 2));
        assertEquals(new Vector(2.0, 0.7, 0.0), SumoPaperController.scaleKnockbackImpulse(base, 4));
        assertEquals(4.0, SumoPaperController.scaleKnockbackImpulse(new Vector(3, 0, 0), 4).length(), 1.0e-9);
        assertNotEquals(base, SumoPaperController.scaleKnockbackImpulse(base, 1));
    }

    @Test
    void groundedRingOutUsesRoundSpawnAsMoveDestinationAndLeavesNormalMovementAlone() throws Exception {
        RingFixture fixture = new RingFixture(2);
        PlayerMoveEvent ringOut = new PlayerMoveEvent(fixture.first,
                fixture.firstSpawn, new Location(fixture.world, 6.2, 64, 0.5));

        fixture.controller.onMove(ringOut, UUID.randomUUID());

        assertFalse(ringOut.isCancelled());
        assertEquals(fixture.firstSpawn, ringOut.getTo());
        assertEquals(fixture.firstSpawn, fixture.firstState.location);
        assertEquals(fixture.secondSpawn, fixture.secondState.location);
        assertEquals(com.ciaac.minecraft.minigames.knockbacksumo.SumoPhase.RUNNING,
                fixture.controller.status().phase());

        Location ordinaryDestination = new Location(fixture.world, 1.5, 64, 0.5);
        PlayerMoveEvent ordinaryMove = new PlayerMoveEvent(fixture.first,
                fixture.firstSpawn, ordinaryDestination);
        fixture.controller.onMove(ordinaryMove, UUID.randomUUID());

        assertFalse(ordinaryMove.isCancelled());
        assertEquals(ordinaryDestination, ordinaryMove.getTo());
    }

    @Test
    void terminalRingOutUsesRestoredSnapshotLocationAsMoveDestination() throws Exception {
        RingFixture fixture = new RingFixture(1);
        PlayerMoveEvent ringOut = new PlayerMoveEvent(fixture.first,
                fixture.firstSpawn, new Location(fixture.world, 6.2, 64, 0.5));

        fixture.controller.onMove(ringOut, UUID.randomUUID());

        assertFalse(ringOut.isCancelled());
        assertEquals(fixture.firstOriginal, ringOut.getTo());
        assertEquals(fixture.firstOriginal, fixture.firstState.location);
        assertEquals(fixture.secondOriginal, fixture.secondState.location);
        assertEquals(com.ciaac.minecraft.minigames.knockbacksumo.SumoPhase.WAITING,
                fixture.controller.status().phase());
    }

    @Test
    void tickDetectsNativePositionRingOutResetsRoundAndThenRestoresTerminalPlayers() throws Exception {
        RingFixture fixture = new RingFixture(2);
        fixture.firstState.location = new Location(fixture.world, 6.2, 64, 0.5);

        fixture.controller.tick(NOW.plusSeconds(1));

        assertEquals(fixture.firstSpawn, fixture.firstState.location);
        assertEquals(fixture.secondSpawn, fixture.secondState.location);
        assertEquals(com.ciaac.minecraft.minigames.knockbacksumo.SumoPhase.RUNNING,
                fixture.controller.status().phase());
        assertTrue(fixture.matchResults.isEmpty());

        fixture.firstState.location = new Location(fixture.world, 6.2, 64, 0.5);
        fixture.controller.tick(NOW.plusSeconds(2));

        assertEquals(fixture.firstOriginal, fixture.firstState.location);
        assertEquals(fixture.secondOriginal, fixture.secondState.location);
        assertEquals(com.ciaac.minecraft.minigames.knockbacksumo.SumoPhase.WAITING,
                fixture.controller.status().phase());
        assertEquals(1, fixture.matchResults.size());
        assertEquals(MatchOutcome.VICTORY, fixture.matchResults.getFirst().outcome());
    }

    @Test
    void tickLeavesCurrentInRegionPlayerPositionUnchanged() throws Exception {
        RingFixture fixture = new RingFixture(2);
        Location expected = new Location(fixture.world, 1.25, 64, 0.5);
        fixture.firstState.location = expected.clone();

        fixture.controller.tick(NOW.plusSeconds(1));

        assertEquals(expected, fixture.firstState.location);
        assertEquals(com.ciaac.minecraft.minigames.knockbacksumo.SumoPhase.RUNNING,
                fixture.controller.status().phase());
        assertTrue(fixture.matchResults.isEmpty());
    }

    @Test
    void tickRecoversWithoutAwardWhenParticipantMovesToAnotherWorld() throws Exception {
        RingFixture fixture = new RingFixture(2);
        UUID otherWorldId = UUID.randomUUID();
        World otherWorld = proxy(World.class, (method, args) -> method.equals("getUID") ? otherWorldId : null);
        fixture.firstState.location = new Location(otherWorld, 0.5, 64, 0.5);

        fixture.controller.tick(NOW.plusSeconds(1));

        assertEquals(fixture.firstOriginal, fixture.firstState.location);
        assertEquals(fixture.secondOriginal, fixture.secondState.location);
        assertEquals(com.ciaac.minecraft.minigames.knockbacksumo.SumoPhase.WAITING,
                fixture.controller.status().phase());
        assertEquals(1, fixture.matchResults.size());
        MatchResult result = fixture.matchResults.getFirst();
        assertEquals(MatchOutcome.NO_CONTEST, result.outcome());
        assertTrue(result.players().values().stream().noneMatch(value -> value.winner() || value.placement() != 0));
    }

    @Test
    void failedTeleportCleanupRevokesAdmissionAndUsesMatchScopedOperation() {
        UUID playerId = UUID.randomUUID(), connectionId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID(), sessionId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuthenticationRegistry authentication = new AuthenticationRegistry();
        authentication.authenticated(new AuthenticatedSession(UUID.randomUUID(), playerId, connectionId,
                NOW.minusSeconds(1), NOW.plusSeconds(600)));
        SessionRegistry sessions = new SessionRegistry();
        MemorySessions repository = new MemorySessions();
        PlayerStateGateway gateway = proxy(PlayerStateGateway.class, (method, args) -> switch (method) {
            case "supportedFacets" -> EnumSet.allOf(PlayerStateFacet.class);
            case "supports" -> true;
            case "capture" -> new PlayerStateSnapshot(2, (UUID) args[0], (UUID) args[1], (UUID) args[2],
                    (UUID) args[3], (UUID) args[4], (UUID) args[5], (GameKey) args[6],
                    (Instant) args[7], Map.of());
            default -> null;
        });
        SessionCoordinator coordinator = new SessionCoordinator(authentication, sessions, repository,
                new InMemorySnapshotRepository(), gateway, IsolationPolicy.strictNoProgress(),
                new SnapshotEnvelopeCodec(), clock);
        AdmissionRequest request = new AdmissionRequest(UUID.randomUUID(), sessionId, snapshotId, matchId,
                playerId, connectionId, GameKey.KNOCKBACK_SUMO, NOW);
        assertEquals(AdmissionStatus.PREPARED, coordinator.prepare(request).status());
        RegionAdmissionRegistry admissions = new RegionAdmissionRegistry();
        String regionId = "knockback-sumo.boundary";
        admissions.issue(new RegionAdmissionToken(UUID.randomUUID(), sessionId, playerId, regionId,
                NOW, NOW.plusSeconds(600)));

        UUID operation = SumoPaperController.operationId(matchId, "TELEPORT_FAILED");
        assertEquals(AdmissionStatus.RECOVERED,
                SumoPaperController.recoverFailedTeleport(coordinator, admissions, request, operation).status());

        assertTrue(admissions.find(playerId, regionId).isEmpty());
        PlayerSession recovered = repository.find(sessionId).orElseThrow();
        UUID scoped = OperationIds.derive(operation, "SESSION_" + sessionId);
        assertTrue(recovered.transitions().stream().anyMatch(t ->
                t.operationId().equals(OperationIds.derive(scoped, "RECOVERY_BEGIN"))));
    }

    @Test
    void quitWhileOldPlayerStillReportsOnlineDefersRecoveryByObjectIdentity() {
        Player departed = onlinePlayer();
        Set<Player> departures = Collections.newSetFromMap(new IdentityHashMap<>());
        departures.add(departed);

        assertTrue(departed.isOnline());
        assertFalse(SumoPaperController.shouldAttemptRecovery(
                Optional.of(SessionPhase.ACTIVE), departed, departures));
    }

    @Test
    void alreadyClosedSessionAfterAuthenticationRecoveryIsSkipped() {
        Player reconnected = onlinePlayer();

        assertFalse(SumoPaperController.shouldAttemptRecovery(
                Optional.of(SessionPhase.CLOSED), reconnected, Set.of()));
        assertFalse(SumoPaperController.shouldAttemptRecovery(Optional.empty(), reconnected, Set.of()));
        assertTrue(SumoPaperController.shouldAttemptRecovery(
                Optional.of(SessionPhase.ACTIVE), reconnected, Set.of()));
    }

    private static final class MemorySessions implements SessionRepository {
        private final Map<UUID, PlayerSession> values = new HashMap<>();
        @Override public boolean save(PlayerSession session) { values.put(session.sessionId(), session); return true; }
        @Override public Optional<PlayerSession> find(UUID id) { return Optional.ofNullable(values.get(id)); }
        @Override public List<PlayerSession> nonTerminal() {
            return values.values().stream().filter(session -> !session.phase().terminal()).toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (target, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + " fixture";
                    case "hashCode" -> System.identityHashCode(target);
                    case "equals" -> target == args[0];
                    default -> null;
                };
            }
            return invocation.invoke(method.getName(), args == null ? new Object[0] : args);
        });
    }

    @FunctionalInterface private interface Invocation { Object invoke(String method, Object[] args); }

    private static Player onlinePlayer() {
        return proxy(Player.class, (method, args) -> switch (method) {
            case "isOnline", "isValid" -> true;
            default -> null;
        });
    }

    private static final class RingFixture {
        private final World world;
        private final Location firstSpawn;
        private final Location secondSpawn;
        private final Location firstOriginal;
        private final Location secondOriginal;
        private final PlayerState firstState;
        private final PlayerState secondState;
        private final Player first;
        private final Player second;
        private final SumoPaperController controller;
        private final RegionAdmissionRegistry admissions = new RegionAdmissionRegistry();
        private final List<MatchResult> matchResults = new java.util.ArrayList<>();

        private RingFixture(int roundsToWin) throws Exception {
            this(roundsToWin, Duration.ofMinutes(10), Duration.ofMinutes(2));
        }

        private RingFixture(int roundsToWin, Duration tokenTtl, Duration roundTimeout) throws Exception {
            UUID worldId = UUID.randomUUID();
            world = proxy(World.class, (method, args) -> method.equals("getUID") ? worldId : null);
            firstSpawn = new Location(world, 0.5, 64, 0.5);
            secondSpawn = new Location(world, 2.5, 64, 0.5);
            firstOriginal = new Location(world, 30.5, 64, 30.5);
            secondOriginal = new Location(world, 32.5, 64, 30.5);
            UUID firstId = UUID.randomUUID();
            UUID secondId = UUID.randomUUID();
            firstState = new PlayerState(firstId, firstOriginal);
            secondState = new PlayerState(secondId, secondOriginal);
            first = player(firstState);
            second = player(secondState);

            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            AuthenticationRegistry authentication = new AuthenticationRegistry();
            ConnectionRegistry connections = new ConnectionRegistry();
            ConnectionRegistry.Connection firstConnection = connections.begin(first, NOW.minusSeconds(1));
            ConnectionRegistry.Connection secondConnection = connections.begin(second, NOW.minusSeconds(1));
            authentication.authenticated(new AuthenticatedSession(UUID.randomUUID(), firstId,
                    firstConnection.id(), NOW.minusSeconds(1), NOW.plusSeconds(600)));
            authentication.authenticated(new AuthenticatedSession(UUID.randomUUID(), secondId,
                    secondConnection.id(), NOW.minusSeconds(1), NOW.plusSeconds(600)));

            Map<UUID, PlayerState> players = Map.of(firstId, firstState, secondId, secondState);
            Map<UUID, Location> originalLocations = Map.of(firstId, firstOriginal, secondId, secondOriginal);
            PlayerStateGateway gateway = proxy(PlayerStateGateway.class, (method, args) -> switch (method) {
                case "supportedFacets" -> EnumSet.allOf(PlayerStateFacet.class);
                case "supports" -> true;
                case "capture" -> new PlayerStateSnapshot(2, (UUID) args[0], (UUID) args[1],
                        (UUID) args[2], (UUID) args[3], (UUID) args[4], (UUID) args[5],
                        (GameKey) args[6], (Instant) args[7], Map.of());
                case "restore" -> {
                    PlayerStateSnapshot snapshot = (PlayerStateSnapshot) args[1];
                    players.get(snapshot.playerId()).location = originalLocations.get(snapshot.playerId()).clone();
                    yield null;
                }
                default -> null;
            });
            SessionRegistry sessions = new SessionRegistry();
            SessionCoordinator coordinator = new SessionCoordinator(authentication, sessions,
                    new MemorySessions(), new InMemorySnapshotRepository(), gateway,
                    IsolationPolicy.strictNoProgress(), new SnapshotEnvelopeCodec(), clock);
            ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
            regions.register(new ProtectedRegion("knockback-sumo.boundary", GameKey.KNOCKBACK_SUMO,
                    new CuboidRegion(worldId, -5, 0, -5, 5, 100, 5),
                    ProtectedRegionRole.PARTICIPANT_ONLY, true));
            Plugin plugin = proxy(Plugin.class, (method, args) -> switch (method) {
                case "getName" -> "sumo-test";
                case "namespace" -> "sumo-test";
                default -> null;
            });
            SumoPaperSettings settings = withRegisteredStick(() -> new SumoPaperSettings(
                    true, "knockback-sumo.boundary", firstSpawn, secondSpawn,
                    tokenTtl, Material.STICK, 2, 0));
            StatisticsRepository resultRepository = proxy(StatisticsRepository.class, (method, args) -> {
                if (method.equals("record")) {
                    matchResults.add((MatchResult) args[0]);
                    return true;
                }
                return null;
            });
            controller = new SumoPaperController(settings,
                    new SumoConfig("fixture", 2, 2, roundsToWin, roundTimeout), coordinator,
                    regions, admissions, new TemporaryItemTagger(plugin), clock,
                    new StatisticsResultSink(resultRepository));

            UUID matchId = UUID.randomUUID();
            AdmissionRequest firstRequest = request(firstId, firstConnection.id(), matchId);
            AdmissionRequest secondRequest = request(secondId, secondConnection.id(), matchId);
            assertEquals(AdmissionStatus.PREPARED, coordinator.prepare(firstRequest).status());
            assertEquals(AdmissionStatus.PREPARED, coordinator.prepare(secondRequest).status());
            insertWaiting(firstRequest, first);
            insertWaiting(secondRequest, second);
            var start = SumoPaperController.class.getDeclaredMethod("start");
            start.setAccessible(true);
            assertTrue((boolean) start.invoke(controller));
        }

        private void insertWaiting(AdmissionRequest request, Player player) throws Exception {
            var waitingField = SumoPaperController.class.getDeclaredField("waiting");
            waitingField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Object> waiting = (Map<UUID, Object>) waitingField.get(controller);
            Class<?> participantType = Class.forName(
                    "com.ciaac.minecraft.minigames.paper.knockbacksumo.SumoPaperController$Participant");
            var constructor = participantType.getDeclaredConstructor(AdmissionRequest.class, Player.class);
            constructor.setAccessible(true);
            waiting.put(request.playerId(), constructor.newInstance(request, player));
        }

        private static AdmissionRequest request(UUID playerId, UUID connectionId, UUID matchId) {
            return new AdmissionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    matchId, playerId, connectionId, GameKey.KNOCKBACK_SUMO, NOW);
        }
    }

    private static final class PlayerState {
        private final UUID id;
        private Location location;
        private PlayerState(UUID id, Location location) { this.id = id; this.location = location.clone(); }
    }

    private static Player player(PlayerState state) {
        return proxy(Player.class, (method, args) -> switch (method) {
            case "getUniqueId" -> state.id;
            case "isOnline", "isValid" -> true;
            case "getLocation" -> state.location.clone();
            case "teleport" -> { state.location = ((Location) args[0]).clone(); yield true; }
            case "toString" -> "player-" + state.id;
            default -> null;
        });
    }

    private static <T> T withRegisteredStick(java.util.function.Supplier<T> action) {
        try {
            Field itemTypeField = Material.class.getDeclaredField("itemType");
            itemTypeField.setAccessible(true);
            Object original = itemTypeField.get(Material.STICK);
            ItemType fixtureType = proxy(ItemType.class, (method, args) -> null);
            itemTypeField.set(Material.STICK, (java.util.function.Supplier<ItemType>) () -> fixtureType);
            try {
                return action.get();
            } finally {
                itemTypeField.set(Material.STICK, original);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not install isolated Material.STICK fixture", failure);
        }
    }
}
