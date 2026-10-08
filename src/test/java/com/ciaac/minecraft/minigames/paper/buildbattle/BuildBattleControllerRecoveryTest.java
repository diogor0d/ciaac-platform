package com.ciaac.minecraft.minigames.paper.buildbattle;

import static org.junit.jupiter.api.Assertions.*;

import com.ciaac.minecraft.minigames.buildbattle.*;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.*;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.region.*;
import com.ciaac.minecraft.minigames.runtime.*;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

class BuildBattleControllerRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private Field bukkitServerField;
    private Object previousBukkitServer;

    @BeforeEach void captureServer() throws Exception {
        bukkitServerField = Bukkit.class.getDeclaredField("server");
        bukkitServerField.setAccessible(true);
        previousBukkitServer = bukkitServerField.get(null);
        bukkitServerField.set(null, proxy(Server.class, method -> switch (method.getName()) {
            case "isPrimaryThread" -> true;
            default -> defaultValue(method.getReturnType());
        }));
    }

    @AfterEach void restoreServer() throws Exception { bukkitServerField.set(null, previousBukkitServer); }

    @Test void resetMustCompleteBeforeSessionsRestoreAndPendingHandleIsRetriedInPlace() throws Exception {
        Harness harness = new Harness(true);
        PlayerSession session = harness.activeSession(10);
        harness.installRecoveryMatch(session.playerId());

        harness.controller.shutdown();

        assertEquals(1, harness.reset.beginCalls);
        assertEquals(0, harness.gateway.restoreCalls);
        assertEquals(SessionPhase.ACTIVE, session.phase());
        assertEquals(BuildBattlePhase.RECOVERING, harness.controller.status().phase());

        harness.controller.tick(NOW.plusSeconds(1));
        assertSame(harness.reset.handle, harness.reset.lastPolled);
        assertEquals(0, harness.gateway.restoreCalls);
        harness.controller.tick(NOW.plusSeconds(2));
        assertSame(harness.reset.handle, harness.reset.lastPolled);
        assertEquals(1, harness.gateway.restoreCalls);
        assertEquals(SessionPhase.RECOVERING, session.phase());

        harness.controller.tick(NOW.plusSeconds(3));
        assertEquals(2, harness.gateway.restoreCalls);
        assertEquals(List.of(harness.gateway.restoreOperations.getFirst(), harness.gateway.restoreOperations.getFirst()),
                harness.gateway.restoreOperations);
        assertEquals(SessionPhase.CLOSED, session.phase());
        assertEquals(BuildBattlePhase.IDLE, harness.controller.status().phase());
        assertEquals(1, harness.reset.beginCalls);
    }

    @Test void restorationGateIsRecheckedAndQuarantineDoesNotCloseMatch() throws Exception {
        boolean[] ready = {false};
        Harness harness = new Harness(false, ignored -> ready[0]);
        PlayerSession session = harness.activeSession(20);
        harness.installRecoveryMatch(session.playerId());

        harness.controller.shutdown();
        assertEquals(0, harness.gateway.restoreCalls);
        assertEquals(SessionPhase.ACTIVE, session.phase());
        assertEquals(BuildBattlePhase.RECOVERING, harness.controller.status().phase());

        ready[0] = true;
        harness.controller.tick(NOW.plusSeconds(1));
        harness.controller.tick(NOW.plusSeconds(2));
        harness.controller.tick(NOW.plusSeconds(3));
        assertEquals(SessionPhase.CLOSED, session.phase());
        assertEquals(BuildBattlePhase.IDLE, harness.controller.status().phase());
    }

    @Test void failedOwnedResetKeepsSessionProtectedWithoutStartingAnotherReset() throws Exception {
        Harness harness = new Harness(false);
        harness.reset.fail = true;
        PlayerSession session = harness.activeSession(30);
        harness.installRecoveryMatch(session.playerId());

        harness.controller.shutdown();
        harness.controller.tick(NOW.plusSeconds(1));
        harness.controller.tick(NOW.plusSeconds(2));

        assertEquals(1, harness.reset.beginCalls);
        assertEquals(SessionPhase.ACTIVE, session.phase());
        assertEquals(0, harness.gateway.restoreCalls);
        assertEquals(BuildBattlePhase.RECOVERING, harness.controller.status().phase());
        assertFalse(harness.controller.status().ready());
    }

    @Test void lateJoinDuringRecoveryIsRejectedBeforePreparingANewSession() throws Exception {
        Harness harness = new Harness(false);
        PlayerSession existing = harness.activeSession(40);
        harness.installRecoveryMatch(existing.playerId());
        UUID playerId = uuid(50);
        UUID connectionId = uuid(51);
        AdmissionRequest request = new AdmissionRequest(uuid(52), uuid(53), uuid(54), harness.matchId,
                playerId, connectionId, GameKey.BUILD_BATTLE, NOW);

        AdmissionResult rejected = harness.controller.join(player(playerId), request);

        assertEquals(AdmissionStatus.REJECTED, rejected.status());
        assertEquals("RECOVERY_PENDING", rejected.code());
        assertEquals(1, harness.repository.rows.size());
        assertTrue(harness.registry.findByPlayer(playerId).isEmpty());
    }

    @Test void onlyMatchingRecoveringOrRestoringBuildBattleSessionsMayTeleportDuringRecovery() throws Exception {
        Harness harness = new Harness(false);
        PlayerSession session = harness.activeSession(60);
        harness.installRecoveryMatch(session.playerId());
        Player player = player(session.playerId());
        harness.installTeleportParticipant(session, player);
        Location destination = new Location(harness.world, 0, 64, 0);

        assertFalse(harness.controller.allowTeleport(player, destination),
                "an ACTIVE participant remains blocked while reset/recovery is pending");

        session.transition(uuid(600), SessionPhase.ACTIVE, SessionPhase.RECOVERING,
                NOW.plusSeconds(1), "TEST_RECOVERY");
        assertTrue(harness.controller.allowTeleport(player, destination));

        session.transition(uuid(601), SessionPhase.RECOVERING, SessionPhase.RESTORING,
                NOW.plusSeconds(2), "TEST_RESTORE");
        assertTrue(harness.controller.allowTeleport(player, destination));
    }

    @Test void recoveryTeleportFailsClosedForSessionFromAnotherMatch() throws Exception {
        Harness harness = new Harness(false);
        UUID playerId = uuid(70);
        PlayerSession wrongMatch = recoveringSession(playerId, UUID.randomUUID(), GameKey.BUILD_BATTLE);
        assertTrue(harness.registry.register(wrongMatch));
        harness.installRecoveryMatch(playerId);
        Player player = player(playerId);
        harness.installTeleportParticipant(wrongMatch, player);

        assertFalse(harness.controller.allowTeleport(player, new Location(harness.world, 0, 64, 0)));
    }

    @Test void normalResultResetAllowsOnlyTheRestoringSessionTeleport() throws Exception {
        Harness harness = new Harness(false);
        PlayerSession session = harness.activeSession(80);
        harness.installRecoveryMatch(session.playerId());
        Field matchField = BuildBattlePaperController.class.getDeclaredField("match");
        matchField.setAccessible(true);
        set(matchField.get(harness.controller), "phase", BuildBattlePhase.RESETTING);
        Player player = player(session.playerId());
        harness.installTeleportParticipant(session, player);
        Location destination = new Location(harness.world, 0, 64, 0);

        assertFalse(harness.controller.allowTeleport(player, destination),
                "RESETTING must keep an ACTIVE player behind the owned reset gate");

        session.transition(uuid(800), SessionPhase.ACTIVE, SessionPhase.FINISHING,
                NOW.plusSeconds(1), "TEST_RESULT");
        session.transition(uuid(801), SessionPhase.FINISHING, SessionPhase.RESTORING,
                NOW.plusSeconds(2), "TEST_RESTORE");
        assertTrue(harness.controller.allowTeleport(player, destination));
    }

    @Test void repeatedRecoverySkipsClosedParticipantWithoutTouchingRestoredState() throws Exception {
        Harness harness = new Harness(false);
        PlayerSession restored = harness.activeSession(90);
        PlayerSession remaining = harness.activeSession(100);
        harness.installRecoveryMatch(restored.playerId());
        MutationCounts restoredChanges = new MutationCounts();
        MutationCounts remainingChanges = new MutationCounts();
        Player restoredPlayer = player(restored.playerId(), restoredChanges);
        Player remainingPlayer = player(remaining.playerId(), remainingChanges);
        harness.installTeleportParticipant(restored, restoredPlayer);
        harness.installTeleportParticipant(remaining, remainingPlayer);
        restored.transition(uuid(900), SessionPhase.ACTIVE, SessionPhase.FINISHING,
                NOW.plusSeconds(1), "TEST_RESTORE");
        restored.transition(uuid(901), SessionPhase.FINISHING, SessionPhase.RESTORING,
                NOW.plusSeconds(2), "TEST_RESTORE");
        restored.transition(uuid(902), SessionPhase.RESTORING, SessionPhase.CLOSED,
                NOW.plusSeconds(3), "TEST_RESTORE");

        harness.controller.shutdown();
        harness.controller.shutdown();

        assertEquals(0, restoredChanges.gameModeChanges);
        assertEquals(0, restoredChanges.flightChanges);
        assertEquals(0, restoredChanges.teleports);
        assertTrue(harness.admissions.find(restored.playerId(), "participant").isEmpty());
        assertTrue(remainingChanges.gameModeChanges > 0, "an ACTIVE peer may still move to waiting");
        assertTrue(remainingChanges.teleports > 0, "an ACTIVE peer may still be teleported to waiting");
        assertTrue(harness.admissions.find(remaining.playerId(), "participant").isPresent());
    }

    private static final class Harness {
        final UUID matchId = UUID.randomUUID();
        final UUID worldId = UUID.randomUUID();
        final World world = proxy(World.class, method -> switch (method.getName()) {
            case "getUID" -> worldId;
            case "getName" -> "build-battle-test";
            default -> defaultValue(method.getReturnType());
        });
        final SessionRegistry registry = new SessionRegistry();
        final RegionAdmissionRegistry admissions = new RegionAdmissionRegistry();
        final MemoryRepository repository = new MemoryRepository();
        final AuthenticationRegistry authentication = new AuthenticationRegistry();
        final Gateway gateway;
        final SessionCoordinator coordinator;
        final FakeReset reset;
        final BuildBattlePaperController controller;
        final BuildBattleConfig config = new BuildBattleConfig(2, 2, 1, 5, new BuildBattleTheme("base", "Base"));
        final List<BuildBattlePlot> plots = List.of(new BuildBattlePlot("one"), new BuildBattlePlot("two"));

        Harness(boolean pendingRestore) { this(pendingRestore, ignored -> true); }
        Harness(boolean pendingRestore, Predicate<PlayerSession> ready) {
            gateway = new Gateway(pendingRestore ? 1 : 0);
            coordinator = new SessionCoordinator(authentication, registry, repository,
                    new InMemorySnapshotRepository(), gateway, IsolationPolicy.strictNoProgress(),
                    new SnapshotEnvelopeCodec(), Clock.fixed(NOW, ZoneOffset.UTC));
            reset = new FakeReset();
            Plugin plugin = proxy(Plugin.class, method -> switch (method.getName()) {
                case "getName" -> "test-plugin";
                case "namespace" -> "ciaacplatform";
                default -> defaultValue(method.getReturnType());
            });
            BuildBattlePaperSettings settings = new BuildBattlePaperSettings(true, config,
                    new BuildBattleThemePool(List.of(config.theme())), world, "participant",
                    new org.bukkit.Location(world, 0, 64, 0),
                    Map.of(plots.get(0), new BuildBattlePaperSettings.PlotSettings("plot-one", new org.bukkit.Location(world, 0, 64, 0)),
                           plots.get(1), new BuildBattlePaperSettings.PlotSettings("plot-two", new org.bukkit.Location(world, 1, 64, 0))),
                    Duration.ofSeconds(5), Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMinutes(10));
            controller = new BuildBattlePaperController(settings, coordinator, registry,
                    new ProtectedRegionRegistry(), admissions, new TemporaryItemTagger(plugin),
                    Optional.of(reset), Clock.fixed(NOW, ZoneOffset.UTC), StatisticsResultSink.unavailable(), ready);
        }

        PlayerSession activeSession(long seed) {
            UUID playerId = uuid(seed), connectionId = uuid(seed + 1), requestId = uuid(seed + 2);
            authentication.authenticated(new AuthenticatedSession(uuid(seed + 3), playerId, connectionId,
                    NOW.minusSeconds(1), NOW.plusSeconds(600)));
            AdmissionRequest request = new AdmissionRequest(requestId, uuid(seed + 4), uuid(seed + 5),
                    matchId, playerId, connectionId, GameKey.BUILD_BATTLE, NOW);
            AdmissionResult prepared = coordinator.prepare(request);
            assertEquals(AdmissionStatus.PREPARED, prepared.status());
            PlayerSession session = prepared.session().orElseThrow();
            assertTrue(coordinator.activate(session.sessionId(), uuid(seed + 6), NOW));
            return session;
        }

        void installRecoveryMatch(UUID playerId) throws Exception {
            BuildBattleMatch match = new BuildBattleMatch(config, plots);
            match.openWaiting(matchId);
            assertTrue(match.join(playerId));
            match.beginRecovery();
            set(controller, "match", match);
            set(controller, "matchId", matchId);
            set(controller, "startedAt", NOW.minusSeconds(30));
        }

        void installTeleportParticipant(PlayerSession session, Player player) throws Exception {
            AdmissionRequest request = new AdmissionRequest(UUID.randomUUID(), session.sessionId(), UUID.randomUUID(),
                    session.matchId(), session.playerId(), UUID.randomUUID(), session.game(), NOW);
            Class<?> participantType = Class.forName(BuildBattlePaperController.class.getName() + "$Participant");
            Constructor<?> constructor = participantType.getDeclaredConstructor(
                    AdmissionRequest.class, Player.class, UUID.class, BuildBattlePlot.class);
            constructor.setAccessible(true);
            Object participant = constructor.newInstance(request, player, session.sessionId(), plots.getFirst());
            Field field = BuildBattlePaperController.class.getDeclaredField("participants");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Object> participants = (Map<UUID, Object>) field.get(controller);
            participants.put(player.getUniqueId(), participant);
        }
    }

    private static PlayerSession recoveringSession(UUID playerId, UUID matchId, GameKey game) {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), matchId, playerId, game, NOW);
        session.transition(uuid(710), SessionPhase.REQUESTED, SessionPhase.SNAPSHOTTING, NOW, "TEST");
        session.transition(uuid(711), SessionPhase.SNAPSHOTTING, SessionPhase.SNAPSHOT_COMMITTED, NOW, "TEST");
        session.transition(uuid(712), SessionPhase.SNAPSHOT_COMMITTED, SessionPhase.PREPARING, NOW, "TEST");
        session.transition(uuid(713), SessionPhase.PREPARING, SessionPhase.ACTIVE, NOW, "TEST");
        session.transition(uuid(714), SessionPhase.ACTIVE, SessionPhase.RECOVERING, NOW, "TEST");
        return session;
    }

    private static final class FakeReset implements BuildBattleResetPort {
        final ResetHandle handle = new ResetHandle(UUID.randomUUID(), UUID.randomUUID());
        int beginCalls;
        int polls;
        boolean fail;
        ResetHandle lastPolled;
        public boolean available() { return true; }
        public ResetResult reset(UUID matchId, World world) { return new ResetResult(false, "UNEXPECTED_SYNC"); }
        public ResetStart beginReset(UUID matchId, World world) { beginCalls++; return ResetStart.pending(handle); }
        public ResetProgress pollReset(ResetHandle value) {
            lastPolled = value;
            if (++polls < 2) return ResetProgress.running(0, 1);
            return fail ? ResetProgress.failed("TEST_RESET_FAILED") : ResetProgress.completed(1);
        }
    }

    private static final class Gateway implements PlayerStateGateway {
        int pendingRestores;
        int restoreCalls;
        final List<UUID> restoreOperations = new ArrayList<>();
        Gateway(int pendingRestores) { this.pendingRestores = pendingRestores; }
        public Set<PlayerStateFacet> supportedFacets() { return EnumSet.allOf(PlayerStateFacet.class); }
        public PlayerStateSnapshot capture(UUID snapshotId, UUID operationId, UUID sessionId, UUID matchId,
                UUID playerId, UUID connectionId, GameKey game, Instant at) {
            return new PlayerStateSnapshot(2, snapshotId, operationId, sessionId, matchId, playerId, connectionId, game, at, Map.of());
        }
        public void enterTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {}
        public void purgeTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {}
        public void restore(UUID operationId, PlayerStateSnapshot snapshot) {
            restoreCalls++; restoreOperations.add(operationId);
            if (pendingRestores-- > 0) throw new WorldRecoveryPendingException();
        }
    }

    private static final class MemoryRepository implements SessionRepository {
        final Map<UUID, PlayerSession> rows = new HashMap<>();
        public boolean save(PlayerSession session) { rows.put(session.sessionId(), session); return true; }
        public Optional<PlayerSession> find(UUID id) { return Optional.ofNullable(rows.get(id)); }
        public List<PlayerSession> nonTerminal() { return rows.values().stream().filter(s -> !s.phase().terminal()).toList(); }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static UUID uuid(long id) { return new UUID(0, id); }
    private static final class MutationCounts {
        int gameModeChanges;
        int flightChanges;
        int teleports;
    }
    private static org.bukkit.entity.Player player(UUID id) {
        return proxy(org.bukkit.entity.Player.class, method -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "isOnline", "isValid" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }
    private static org.bukkit.entity.Player player(UUID id, MutationCounts counts) {
        return proxy(org.bukkit.entity.Player.class, method -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "isOnline", "isValid" -> true;
            case "setGameMode" -> { counts.gameModeChanges++; yield null; }
            case "setAllowFlight", "setFlying" -> { counts.flightChanges++; yield null; }
            case "teleport" -> { counts.teleports++; yield true; }
            default -> defaultValue(method.getReturnType());
        });
    }
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method)));
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
    @FunctionalInterface private interface Invocation { Object invoke(Method method) throws Throwable; }
}
