package com.ciaac.minecraft.minigames.paper.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.arena.ArenaEquipmentContract;
import com.ciaac.minecraft.minigames.arena.ArenaFormatPolicy;
import com.ciaac.minecraft.minigames.arena.ArenaLocationPolicy;
import com.ciaac.minecraft.minigames.arena.ArenaPhase;
import com.ciaac.minecraft.minigames.arena.TeamRoster;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.InMemorySnapshotRepository;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.SnapshotRepository;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.paper.arena.ColiseumController;
import com.ciaac.minecraft.minigames.paper.arena.ColiseumSettings;
import com.ciaac.minecraft.minigames.paper.recovery.SessionRecoveryService;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequestFactory;
import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.CombatPolicyRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.statistics.MatchResult;
import com.ciaac.minecraft.minigames.statistics.MatchOutcome;
import com.ciaac.minecraft.minigames.statistics.InMemoryStatisticsRepository;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import com.ciaac.minecraft.minigames.module.ModuleAvailability;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class ColiseumReadinessFlowTest {
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void queueReservationNotifiesBothAndOnlySecondReadyStartsCombat() {
        Fixture f = new Fixture();
        assertEquals("QUEUED", f.module.join(f.first.player, "1v1 equipamento").code());
        assertEquals("QUEUED", f.module.join(f.second.player, "1v1 equipamento").code());

        f.module.tick(NOW);

        assertEquals(ArenaPhase.RESERVED_READY, f.controller.currentMatch().orElseThrow().phase());
        assertEquals(2, f.module.status().participants());
        assertReadinessPrompt(f.first.messages);
        assertReadinessPrompt(f.second.messages);
        assertEquals(1, readinessPromptCount(f.first.messages));
        assertEquals(1, readinessPromptCount(f.second.messages));
        assertEquals(0, f.teleports);
        assertEquals(0, f.inventoryMutations);
        assertEquals(0, f.gateway.captures);

        assertEquals("READY_RECORDED", f.module.ready(f.first.player).code());
        assertEquals(ArenaPhase.RESERVED_READY, f.controller.currentMatch().orElseThrow().phase());
        assertEquals(0, f.teleports);
        assertEquals(0, f.inventoryMutations);

        assertEquals("COMBAT_STARTED", f.module.ready(f.second.player).code());
        assertEquals(ArenaPhase.ACTIVE, f.controller.currentMatch().orElseThrow().phase());
        assertEquals(2, f.teleports);
        assertEquals(8, f.inventoryMutations);
        assertEquals(2, f.gateway.captures);
        assertEquals(1, f.first.teleports);
        assertEquals(1, f.second.teleports);
    }

    @Test
    void acceptedDirectChallengeNotifiesEveryParticipant() {
        Fixture f = new Fixture();
        assertEquals("CHALLENGE_CREATED", f.module.action(f.first.player, "desafiar",
                List.of("Second", "1v1", "equipamento")).code());
        assertEquals("CHALLENGE_ACCEPTED", f.module.action(f.second.player, "aceitar", List.of("First")).code());

        assertReadinessPrompt(f.first.messages);
        assertReadinessPrompt(f.second.messages);
        assertEquals(2, f.module.status().participants());
        assertEquals(ArenaPhase.RESERVED_READY, f.controller.currentMatch().orElseThrow().phase());
        assertEquals(0, f.gateway.captures);
        assertEquals(0, f.teleports);
    }

    @Test
    void preCombatCancellationDoesNotCaptureRestoreOrTeleportPlayers() {
        Fixture f = new Fixture();
        f.module.join(f.first.player, "1v1 equipamento");
        f.module.join(f.second.player, "1v1 equipamento");
        f.module.tick(NOW);

        var result = f.module.leave(f.first.player);

        assertTrue(result.accepted());
        assertEquals("PRESTART_CANCELLED", result.code());
        assertTrue(result.messagePtPt().contains("cancelada antes do combate"));
        assertTrue(f.controller.currentMatch().isEmpty());
        assertEquals(0, f.gateway.captures);
        assertEquals(0, f.gateway.restores);
        assertEquals(0, f.teleports);
        assertEquals(0, f.inventoryMutations);
    }

    @Test
    void readyTimeoutNotifiesParticipantsAndClearsQueuePartyLock() {
        Fixture f = new Fixture();
        f.module.join(f.first.player, "1v1 equipamento");
        f.module.join(f.second.player, "1v1 equipamento");
        f.module.tick(NOW);
        f.first.messages.clear();
        f.second.messages.clear();

        f.module.tick(NOW.plusSeconds(61));

        assertTrue(f.controller.currentMatch().isEmpty());
        assertTrue(f.first.messages.stream().anyMatch(message -> message.toString().contains("tempo para confirmar terminou")));
        assertTrue(f.second.messages.stream().anyMatch(message -> message.toString().contains("tempo para confirmar terminou")));
        assertEquals("PARTY_CREATED", f.module.action(f.first.player, "grupo", List.of("criar")).code());
        assertEquals("PARTY_CREATED", f.module.action(f.second.player, "grupo", List.of("criar")).code());
        assertEquals(0, f.teleports);
        assertEquals(0, f.gateway.captures);
    }

    @Test
    void terminalMatchReconciliationClearsRestoredPlayersFromPartyLock() {
        Fixture f = new Fixture();
        f.module.join(f.first.player, "1v1 equipamento");
        f.module.join(f.second.player, "1v1 equipamento");
        f.module.tick(NOW);
        f.module.ready(f.first.player);
        assertEquals("COMBAT_STARTED", f.module.ready(f.second.player).code());

        f.module.tick(NOW.plus(Duration.ofMinutes(5)));

        assertTrue(f.controller.currentMatch().isEmpty());
        assertEquals(2, f.gateway.restores);
        assertEquals("PARTY_CREATED", f.module.action(f.first.player, "grupo", List.of("criar")).code());
        assertEquals("PARTY_CREATED", f.module.action(f.second.player, "grupo", List.of("criar")).code());
    }

    @Test
    void readinessMessageDeliveryFailureDoesNotChangeReservation() {
        Fixture f = new Fixture();
        f.module.join(f.first.player, "1v1 equipamento");
        f.module.join(f.second.player, "1v1 equipamento");
        f.first.rejectMessages = true;

        f.module.tick(NOW);

        assertEquals(ArenaPhase.RESERVED_READY, f.controller.currentMatch().orElseThrow().phase());
        assertEquals(ModuleAvailability.STARTING, f.module.status().availability());
        assertReadinessPrompt(f.second.messages);
        assertEquals(0, f.gateway.captures);
    }

    @Test
    void failedFallbackDuringStartRecoveryLeavesMatchQuarantined() {
        Fixture f = new Fixture();
        f.module.join(f.first.player, "1v1 equipamento");
        f.module.join(f.second.player, "1v1 equipamento");
        f.module.tick(NOW);
        f.module.ready(f.first.player);
        f.first.rejectTeleports = true;

        var result = f.module.ready(f.second.player);

        assertEquals("RECOVERY_QUARANTINED", result.code());
        assertFalse(result.accepted());
        assertEquals(ArenaPhase.RECOVERING, f.controller.currentMatch().orElseThrow().phase());
        assertEquals(2, f.gateway.captures);
    }

    @Test
    void failedPreparationWithoutSnapshotsIsNotReportedAsUserCancellation() {
        Fixture f = new Fixture();
        f.module.join(f.first.player, "1v1 equipamento");
        f.module.join(f.second.player, "1v1 equipamento");
        f.module.tick(NOW);
        f.module.ready(f.first.player);
        f.gateway.isolationUnavailable = true;

        var result = f.module.ready(f.second.player);

        assertEquals("RECOVERED", result.code());
        assertFalse(result.accepted());
        assertEquals(0, f.gateway.captures);
        assertEquals(0, f.gateway.restores);
    }

    @Test
    void departingOnlineActorIsDeferredWhilePeerRestoresOnceUntilAuthenticatedReconnect() {
        Fixture f = new Fixture();
        f.startCombat();
        f.first.rejectTeleports = true; // Paper can still report online/valid inside quit/kick callbacks.
        UUID firstSessionId = f.sessions.findByPlayer(f.first.id).orElseThrow().sessionId();

        assertEquals("RECOVERY_PENDING", f.controller.disconnect(f.first.player).code());
        assertEquals(1, f.first.teleports, "No departing fallback teleport is attempted");
        assertEquals(2, f.second.teleports);
        assertEquals(1, f.gateway.restores);
        assertTrue(f.gateway.temporaryPlayers.contains(f.first.id));
        assertFalse(f.gateway.temporaryPlayers.contains(f.second.id));
        assertEquals(Set.of(f.second.id), f.controller.currentMatch().orElseThrow().restoredPlayers());
        assertTrue(f.sessions.findByPlayer(f.second.id).isEmpty());
        assertEquals(SessionPhase.ACTIVE, f.sessions.findById(firstSessionId).orElseThrow().phase());
        assertEquals(0, f.results.size());

        assertEquals("RECOVERY_PENDING", f.controller.disconnect(f.first.player).code());
        assertEquals("RECOVERY_PENDING", f.controller.leave(f.second.player).code());
        assertEquals("MATCH_ACTIVE", f.module.join(f.first.player, "1v1 equipamento").code());
        assertEquals("NO_QUEUE_MATCH", f.controller.tick(NOW.plusSeconds(500)).code());
        assertEquals(1, f.gateway.restores);
        assertEquals(2, f.second.teleports, "Closed peer session is never teleported or restored again");

        assertEquals("RECOVERY_PENDING", f.controller.resumeAfterAuthentication(f.first.player).code(),
                "Still-departing connection cannot use its old authentication evidence");
        assertEquals("RECOVERY_PENDING", f.recovery.onAuthenticated(f.first.player).code(),
                "Service must not bypass the controller's pending departure gate");
        assertEquals(SessionPhase.ACTIVE, f.sessions.findById(firstSessionId).orElseThrow().phase());
        f.authentication.invalidatePlayer(f.first.id);
        f.connections.end(f.first.player);
        f.connections.begin(f.first.player, NOW);
        f.first.rejectTeleports = false;
        assertEquals("AUTHENTICATION_REQUIRED", f.controller.resumeAfterAuthentication(f.first.player).code());
        assertEquals("AUTHENTICATION_REQUIRED", f.recovery.onAuthenticated(f.first.player).code());
        assertEquals(1, f.gateway.restores);

        f.authenticate(f.first);
        assertEquals("RESTORED", f.recovery.onAuthenticated(f.first.player).code());
        assertTrue(f.controller.currentMatch().isEmpty());
        assertTrue(f.sessions.findById(firstSessionId).isEmpty());
        assertEquals(2, f.gateway.restores);
        assertEquals(Map.of(f.first.id, 1, f.second.id, 1), f.gateway.restoresByPlayer);
        assertTrue(f.gateway.temporaryPlayers.isEmpty());
        assertEquals(1, f.results.size());
        assertEquals("DISCONNECT", f.results.getFirst().reasonCode());
        assertEquals(MatchOutcome.VICTORY, f.results.getFirst().outcome());
        assertTrue(f.results.getFirst().players().get(f.second.id).winner());
        assertEquals("NO_RECOVERY_PENDING", f.recovery.onAuthenticated(f.first.player).code());
        assertEquals(2, f.gateway.restores);
    }

    @Test
    void genuineOnlineFallbackRejectionStillBlocksRestoration() {
        Fixture f = new Fixture();
        f.startCombat();
        f.second.rejectTeleports = true;

        assertEquals("RESTORE_QUARANTINED", f.controller.disconnect(f.first.player).code());
        assertEquals(ArenaPhase.RESTORING, f.controller.currentMatch().orElseThrow().phase());
        assertEquals(0, f.gateway.restores);
        assertEquals(0, f.results.size());
        assertEquals(1, f.first.teleports);

        f.connections.end(f.first.player);
        f.connections.begin(f.first.player, NOW);
        f.authenticate(f.first);
        assertEquals("RECOVERY_PENDING", f.recovery.onAuthenticated(f.first.player).code(),
                "A rejected fallback cannot fall through to standalone coordinator restoration");
        assertEquals(0, f.gateway.restores);
        assertEquals(SessionPhase.ACTIVE, f.sessions.findByPlayer(f.first.id).orElseThrow().phase());
        assertTrue(f.controller.currentMatch().isPresent());
    }

    @Test
    void onlinePeerWithoutCurrentAuthenticationIsDeferredUntilProviderCompletion() {
        Fixture f = new Fixture();
        f.startCombat();
        f.authentication.invalidatePlayer(f.second.id);

        assertEquals("RECOVERY_PENDING", f.controller.disconnect(f.first.player).code());
        assertEquals(0, f.gateway.restores);
        assertEquals(1, f.second.teleports);
        assertEquals(SessionPhase.ACTIVE, f.sessions.findByPlayer(f.second.id).orElseThrow().phase());
        assertEquals("AUTHENTICATION_REQUIRED", f.recovery.onAuthenticated(f.second.player).code());

        f.authenticate(f.second);
        assertEquals("RESTORED", f.recovery.onAuthenticated(f.second.player).code());
        assertEquals(1, f.gateway.restores);
        assertEquals(Set.of(f.second.id), f.controller.currentMatch().orElseThrow().restoredPlayers());
        assertTrue(f.sessions.findByPlayer(f.first.id).isPresent());
        assertEquals(0, f.results.size(), "Restoring only this actor does not finalize the whole match");
    }

    @Test
    void shutdownRestoresConnectedPlayersWithoutMarkingThemDeparting() {
        Fixture f = new Fixture();
        f.startCombat();

        f.module.shutdown();
        assertTrue(f.controller.currentMatch().isEmpty());
        assertEquals(2, f.gateway.restores);
        assertEquals(1, f.results.size());
    }

    private static void assertReadinessPrompt(List<net.kyori.adventure.text.Component> messages) {
        assertTrue(messages.stream().anyMatch(message -> hasCommand(message, "/coliseu pronto")));
    }

    private static long readinessPromptCount(List<net.kyori.adventure.text.Component> messages) {
        return messages.stream().filter(message -> hasCommand(message, "/coliseu pronto")).count();
    }

    private static boolean hasCommand(net.kyori.adventure.text.Component component, String command) {
        var click = component.style().clickEvent();
        if (click != null && click.toString().contains(command)) return true;
        return component.children().stream().anyMatch(child -> hasCommand(child, command));
    }

    private static final class Fixture {
        private final UUID worldId = UUID.randomUUID();
        private final World world = proxy(World.class, method -> switch (method.getName()) {
            case "getUID" -> worldId;
            case "getName" -> "fixture";
            default -> defaultValue(method.getReturnType());
        });
        private final Map<UUID, Player> players = new LinkedHashMap<>();
        private final Map<String, Player> names = new LinkedHashMap<>();
        private final Server server = proxy(Server.class, method -> switch (method.getName()) {
            case "getPlayer" -> players.get(method.args[0]);
            case "getPlayerExact" -> names.get(method.args[0]);
            case "isPrimaryThread" -> true;
            default -> defaultValue(method.getReturnType());
        });
        private final Plugin plugin = proxy(Plugin.class, method -> switch (method.getName()) {
            case "getName" -> "Fixture";
            case "namespace" -> "fixture";
            default -> defaultValue(method.getReturnType());
        });
        private final AuthenticationRegistry authentication = new AuthenticationRegistry();
        private final ConnectionRegistry connections = new ConnectionRegistry();
        private final SessionRegistry sessions = new SessionRegistry();
        private final RecordingGateway gateway = new RecordingGateway();
        private final List<MatchResult> results = new java.util.ArrayList<>();
        private final TrackedPlayer first = player("First");
        private final TrackedPlayer second = player("Second");
        private final ColiseumController controller;
        private final ColiseumModule module;
        private final SessionRecoveryService recovery;
        private int teleports;
        private int inventoryMutations;

        private Fixture() {
            var settings = new ColiseumSettings(true, Optional.of(worldId),
                    Optional.of(new ArenaLocationPolicy("fixture", "floor", "benches", "a", "b", "exit")),
                    Optional.of(location(5, 80, 10)), Optional.of(location(35, 80, 10)),
                    Optional.of(location(-5, 80, 10)), ArenaFormatPolicy.defaultPolicy(),
                    Duration.ofSeconds(60), Duration.ofSeconds(30), false, Set.of());
            SessionRepository sessionsRepo = new MemorySessions();
            SnapshotRepository snapshots = new InMemorySnapshotRepository();
            var coordinator = new SessionCoordinator(authentication, sessions, sessionsRepo, snapshots,
                    gateway, IsolationPolicy.strictNoProgress(), new SnapshotEnvelopeCodec(), CLOCK);
            ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
            regions.register(new ProtectedRegion("floor", GameKey.ARENA,
                    new CuboidRegion(worldId, 0, 70, 0, 40, 90, 20),
                    ProtectedRegionRole.PARTICIPANT_ONLY, true));
            regions.register(new ProtectedRegion("benches", GameKey.ARENA,
                    new CuboidRegion(worldId, -10, 70, 0, -2, 90, 20),
                    ProtectedRegionRole.SPECTATOR_PUBLIC, true));
            controller = new ColiseumController(settings, server, coordinator,
                    player -> connections.current(player.getUniqueId()).map(ConnectionRegistry.Connection::id),
                    ignored -> Optional.empty(), new TemporaryItemTagger(plugin), regions,
                    new RegionAdmissionRegistry(), new CombatPolicyRegistry(), Optional.empty(), CLOCK,
                    new StatisticsResultSink(new InMemoryStatisticsRepository(), results::add),
                    authentication, connections);
            var identity = new ModuleIdentity(connections, new AdmissionRequestFactory(connections, authentication, CLOCK));
            module = new ColiseumModule(controller, identity,
                    (player, mode) -> ArenaEquipmentContract.protectedCopy("test-loadout"), CLOCK);
            recovery = new SessionRecoveryService(sessionsRepo, sessions, coordinator, event -> true,
                    authentication, connections, CLOCK,
                    session -> module.resumeAfterAuthentication(players.get(session.playerId())));
            for (TrackedPlayer player : List.of(first, second)) authenticate(player);
        }

        private void startCombat() {
            module.join(first.player, "1v1 equipamento");
            module.join(second.player, "1v1 equipamento");
            module.tick(NOW);
            module.ready(first.player);
            assertEquals("COMBAT_STARTED", module.ready(second.player).code());
        }

        private Location location(double x, double y, double z) { return new Location(world, x, y, z); }

        private TrackedPlayer player(String name) {
            UUID id = UUID.randomUUID();
            TrackedPlayer tracked = new TrackedPlayer(name, id);
            players.put(id, tracked.player);
            names.put(name, tracked.player);
            connections.begin(tracked.player, NOW.minusSeconds(2));
            return tracked;
        }

        private void authenticate(TrackedPlayer player) {
            authentication.authenticated(new AuthenticatedSession(UUID.randomUUID(), player.id,
                    connections.current(player.id).orElseThrow().id(), NOW.minusSeconds(1), NOW.plusSeconds(60)));
        }

        private final class TrackedPlayer {
            private final UUID id;
            private final String name;
            private final List<net.kyori.adventure.text.Component> messages = new java.util.ArrayList<>();
            private final Location[] currentLocation = {location(-5, 80, 10)};
            private int teleports;
            private boolean rejectMessages;
            private boolean rejectTeleports;
            private final PlayerInventory inventory;
            private final Player player;

            private TrackedPlayer(String name, UUID id) {
                this.name = name;
                this.id = id;
                inventory = proxy(PlayerInventory.class, method -> switch (method.getName()) {
                    case "getStorageContents" -> new ItemStack[36];
                    case "getArmorContents" -> new ItemStack[4];
                    case "getExtraContents" -> new ItemStack[3];
                    case "setStorageContents", "setArmorContents", "setExtraContents", "setContents",
                            "setHelmet", "setChestplate", "setLeggings", "setBoots", "setItemInOffHand" -> {
                        inventoryMutations++;
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
                player = proxy(Player.class, method -> switch (method.getName()) {
                    case "getUniqueId" -> this.id;
                    case "getName" -> this.name;
                    case "getServer" -> server;
                    case "isOnline", "isValid" -> true;
                    case "getInventory" -> inventory;
                    case "sendMessage" -> {
                        if (rejectMessages) throw new IllegalStateException("delivery failed");
                        if (method.args.length > 0
                                && method.args[0] instanceof net.kyori.adventure.text.Component component) {
                            messages.add(component);
                        }
                        yield null;
                    }
                    case "teleport" -> {
                        teleports++;
                        Fixture.this.teleports++;
                        if (rejectTeleports) yield false;
                        currentLocation[0] = ((Location) method.args[0]).clone();
                        yield true;
                    }
                    case "getLocation" -> currentLocation[0].clone();
                    case "updateInventory" -> { inventoryMutations++; yield null; }
                    default -> defaultValue(method.getReturnType());
                });
            }
        }
    }

    private static final class RecordingGateway implements PlayerStateGateway {
        private final Set<PlayerStateFacet> facets = Set.of(PlayerStateFacet.values());
        private int captures;
        private int restores;
        private final Map<UUID, Integer> restoresByPlayer = new LinkedHashMap<>();
        private final Set<UUID> temporaryPlayers = new java.util.HashSet<>();
        private boolean isolationUnavailable;

        @Override public Set<PlayerStateFacet> supportedFacets() { return facets; }
        @Override public Set<PlayerStateFacet> supportedFacets(GameKey game) {
            if (!isolationUnavailable) return facets;
            var unsupported = new java.util.HashSet<>(facets);
            unsupported.remove(PlayerStateFacet.INVENTORY);
            return Set.copyOf(unsupported);
        }

        @Override
        public PlayerStateSnapshot capture(UUID snapshotId, UUID operationId, UUID sessionId, UUID matchId,
                UUID playerId, UUID connectionId, GameKey game, Instant capturedAt) {
            captures++;
            Map<PlayerStateFacet, byte[]> payloads = new EnumMap<>(PlayerStateFacet.class);
            facets.forEach(facet -> payloads.put(facet, new byte[0]));
            return new PlayerStateSnapshot(2, snapshotId, operationId, sessionId, matchId, playerId,
                    connectionId, game, capturedAt, payloads);
        }

        @Override public void enterTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {
            temporaryPlayers.add(snapshot.playerId());
        }
        @Override public void purgeTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {}
        @Override public void restore(UUID operationId, PlayerStateSnapshot snapshot) {
            restores++;
            restoresByPlayer.merge(snapshot.playerId(), 1, Integer::sum);
            temporaryPlayers.remove(snapshot.playerId());
        }
    }

    private static final class MemorySessions implements SessionRepository {
        private final Map<UUID, PlayerSession> sessions = new LinkedHashMap<>();
        @Override public boolean save(PlayerSession session) { sessions.put(session.sessionId(), session); return true; }
        @Override public Optional<PlayerSession> find(UUID sessionId) { return Optional.ofNullable(sessions.get(sessionId)); }
        @Override public List<PlayerSession> nonTerminal() {
            return sessions.values().stream().filter(session -> !session.phase().terminal()).toList();
        }
    }

    private interface Invocation { Object invoke(Call method); }

    private record Call(String name, Class<?> returnType, Object[] args) {
        String getName() { return name; }
        Class<?> getReturnType() { return returnType; }
    }

    private static <T> T proxy(Class<T> type, Invocation call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> call.invoke(new Call(method.getName(), method.getReturnType(),
                        args == null ? new Object[0] : args))));
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
