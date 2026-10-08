package com.ciaac.minecraft.minigames.paper.elytrarings;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsGame;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsPhase;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsResult;
import com.ciaac.minecraft.minigames.hotpotato.OperationId;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.statistics.MatchOutcome;
import com.ciaac.minecraft.minigames.statistics.PlayerResult;
import com.ciaac.minecraft.minigames.statistics.recording.MatchResultFactory;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.function.Predicate;
import java.util.OptionalDouble;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Disabled-by-default dedicated-world controller for an ordered Elytra course. */
public final class ElytraRingsController {
    private static final int MAX_ACCEPTED_MOVES = 128;

    private record AcceptedMove(UUID playerId, Location from, Location to, Instant acceptedAt) { }

    private final UUID matchId;
    private final ElytraRingsGame game;
    private final ElytraRingsPaperSettings settings;
    private final SessionCoordinator sessions;
    private final SessionRegistry sessionRegistry;
    private final RegionAdmissionRegistry admissions;
    private final ProtectedRegionRegistry regions;
    private final TemporaryItemTagger temporaryItems;
    private final Clock clock;
    private final StatisticsResultSink statistics;
    private final ElytraChunkPreparation chunkPreparation;
    private final Predicate<PlayerSession> restorationReady;
    private final Set<UUID> departingPlayers = new LinkedHashSet<>();
    private final ArrayDeque<AcceptedMove> acceptedMoves = new ArrayDeque<>();
    private boolean movementOverflow;
    private boolean restorationPending;
    private boolean restoringResult;
    private UUID restorationRoot;
    private String restorationReason;
    private Set<UUID> restorationParticipants = Set.of();
    private ElytraRingsResult restorationResult;
    private long operationSequence;
    private Instant startedAt;
    private boolean internalTeleport;
    private int rocketUses;
    private final Set<UUID> insertedRockets = new LinkedHashSet<>();

    public ElytraRingsController(UUID matchId, ElytraRingsGame game, ElytraRingsPaperSettings settings,
                                 SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                 RegionAdmissionRegistry admissions, ProtectedRegionRegistry regions,
                                 TemporaryItemTagger temporaryItems, Clock clock) {
        this(matchId, game, settings, sessions, sessionRegistry, admissions, regions, temporaryItems, clock,
                StatisticsResultSink.unavailable(),
                ElytraChunkPreparation.unavailable(settings, "CHUNK_PREPARER_UNAVAILABLE"));
    }

    public ElytraRingsController(UUID matchId, ElytraRingsGame game, ElytraRingsPaperSettings settings,
                                 SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                 RegionAdmissionRegistry admissions, ProtectedRegionRegistry regions,
                                 TemporaryItemTagger temporaryItems, Clock clock, StatisticsResultSink statistics) {
        this(matchId, game, settings, sessions, sessionRegistry, admissions, regions, temporaryItems, clock,
                statistics, ElytraChunkPreparation.unavailable(settings, "CHUNK_PREPARER_UNAVAILABLE"));
    }

    /** Paper bootstrap constructor with the default unavailable statistics sink. */
    public ElytraRingsController(UUID matchId, ElytraRingsGame game, ElytraRingsPaperSettings settings,
                                 SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                 RegionAdmissionRegistry admissions, ProtectedRegionRegistry regions,
                                 TemporaryItemTagger temporaryItems, Clock clock, Plugin plugin) {
        this(matchId, game, settings, sessions, sessionRegistry, admissions, regions, temporaryItems, clock,
                StatisticsResultSink.unavailable(), plugin);
    }

    /** Paper bootstrap constructor matching the other controller adapters. */
    public ElytraRingsController(UUID matchId, ElytraRingsGame game, ElytraRingsPaperSettings settings,
                                 SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                 RegionAdmissionRegistry admissions, ProtectedRegionRegistry regions,
                                 TemporaryItemTagger temporaryItems, Clock clock, Plugin plugin,
                                 StatisticsResultSink statistics) {
        this(matchId, game, settings, sessions, sessionRegistry, admissions, regions, temporaryItems, clock,
                statistics, plugin);
    }

    /** Paper bootstrap constructor; the plugin owns the course chunk tickets. */
    public ElytraRingsController(UUID matchId, ElytraRingsGame game, ElytraRingsPaperSettings settings,
                                 SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                 RegionAdmissionRegistry admissions, ProtectedRegionRegistry regions,
                                 TemporaryItemTagger temporaryItems, Clock clock, StatisticsResultSink statistics,
                                 Plugin plugin) {
        this(matchId, game, settings, sessions, sessionRegistry, admissions, regions, temporaryItems, clock,
                statistics, new ElytraChunkPreparation(settings, plugin));
    }

    /** Injection seam for deterministic adapters and lifecycle tests. */
    public ElytraRingsController(UUID matchId, ElytraRingsGame game, ElytraRingsPaperSettings settings,
                                 SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                 RegionAdmissionRegistry admissions, ProtectedRegionRegistry regions,
                                 TemporaryItemTagger temporaryItems, Clock clock, StatisticsResultSink statistics,
                                 ElytraChunkPreparation chunkPreparation) {
        this(matchId, game, settings, sessions, sessionRegistry, admissions, regions, temporaryItems, clock,
                statistics, chunkPreparation, session -> false);
    }

    /** Runtime restoration gate; old constructors remain fail-closed. */
    public ElytraRingsController(UUID matchId, ElytraRingsGame game, ElytraRingsPaperSettings settings,
                                 SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                 RegionAdmissionRegistry admissions, ProtectedRegionRegistry regions,
                                 TemporaryItemTagger temporaryItems, Clock clock, StatisticsResultSink statistics,
                                 ElytraChunkPreparation chunkPreparation,
                                 Predicate<PlayerSession> restorationReady) {
        this.matchId = Objects.requireNonNull(matchId, "matchId"); this.game = Objects.requireNonNull(game, "game"); this.settings = Objects.requireNonNull(settings, "settings");
        this.sessions = Objects.requireNonNull(sessions, "sessions"); this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry"); this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.regions = Objects.requireNonNull(regions, "regions"); this.temporaryItems = Objects.requireNonNull(temporaryItems, "temporaryItems"); this.clock = Objects.requireNonNull(clock, "clock"); this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.chunkPreparation = Objects.requireNonNull(chunkPreparation, "chunkPreparation");
        this.restorationReady = Objects.requireNonNull(restorationReady, "restorationReady");
    }

    public synchronized AdmissionResult join(Player player, AdmissionRequest request, RegionAdmissionToken token) {
        if (!settings.enabled()) return rejected("DISABLED", "Os Anéis de Elytra estão temporariamente fechados.");
        Objects.requireNonNull(player); Objects.requireNonNull(request); Objects.requireNonNull(token); Instant now = clock.instant();
        if (restorationPending || (game.phase() != ElytraRingsPhase.DISABLED
                && game.phase() != ElytraRingsPhase.WAITING)) {
            return rejected("MATCH_UNAVAILABLE", "O percurso já começou ou está a terminar a recuperação.");
        }
        if (!ready(player, request, token, now)) return rejected("CONFIGURATION_UNAVAILABLE", "O percurso de Elytra ainda não está pronto.");
        clearAcceptedMoves();
        admissions.issue(token); AdmissionResult prepared = sessions.prepare(request);
        if (prepared.status() != AdmissionStatus.PREPARED) { admissions.revokeSession(request.sessionId()); return prepared; }
        PlayerSession session = prepared.session().orElseThrow();
        try {
            if (game.phase() == ElytraRingsPhase.DISABLED) game.open(nextOperation());
            teleport(player, settings.start());
            // Paper/Multiverse may apply the destination world's default mode during teleport.
            // The snapshot was captured by prepare(), so restore will still recover the player's original mode.
            player.setGameMode(org.bukkit.GameMode.ADVENTURE);
            sessions.activate(session.sessionId(), OperationIds.derive(request.requestId(), "GAME_ACTIVE"), clock.instant());
            grantTemporaryFlightKit(player, session.sessionId());
            rocketUses = 0;
            insertedRockets.clear();
            game.start(now, nextOperation()); startedAt = now;
            player.sendMessage("§aAnéis de Elytra: atravessa os anéis pela ordem indicada!");
            return prepared;
        } catch (RuntimeException failure) {
            admissions.revokeSession(request.sessionId()); sessions.recover(session.sessionId(), OperationIds.derive(request.requestId(), "JOIN_RECOVERY"), "JOIN_FAILED");
            return rejected("JOIN_FAILED", "Não foi possível preparar o percurso; o teu estado foi protegido.");
        }
    }

    public synchronized AdmissionResult leave(UUID playerId, UUID rootOperationId) {
        PlayerSession session = sessionRegistry.findByPlayer(Objects.requireNonNull(playerId)).orElseThrow(() -> new IllegalArgumentException("No active session"));
        if (!session.matchId().equals(matchId)) throw new IllegalArgumentException("Session belongs to another match");
        recoverAll(rootOperationId, "PLAYER_LEFT");
        if (session.phase() != SessionPhase.CLOSED) {
            return new AdmissionResult(session.phase() == SessionPhase.QUARANTINED
                    ? AdmissionStatus.QUARANTINED : AdmissionStatus.REJECTED,
                    "RECOVERY_PENDING", "Saíste do percurso; o teu estado continua protegido enquanto o restauro termina.",
                    java.util.Optional.of(session));
        }
        return new AdmissionResult(AdmissionStatus.RECOVERED, "LEFT", "Saíste dos Anéis de Elytra e o teu estado foi restaurado.", java.util.Optional.of(session));
    }

    public synchronized ElytraRingsPaperStatus status() {
        ElytraChunkPreparation.Status chunks = chunkPreparation.status();
        boolean regionReady = settings.enabled() && settings.world() != null && validRegion();
        boolean joinPhase = game.phase() == ElytraRingsPhase.DISABLED || game.phase() == ElytraRingsPhase.WAITING;
        boolean ready = regionReady && chunks.admissionReady() && joinPhase && !restorationPending;
        String message = !settings.enabled() ? "Os Anéis de Elytra estão fechados."
                : !regionReady ? "O percurso está fechado até a região ser validada."
                : switch (chunks.phase()) {
                    case PREPARING -> "O percurso de Elytra está a preparar os chunks."
                            + " Aguarda antes de entrar.";
                    case READY -> joinPhase && !restorationPending ? "Os Anéis de Elytra estão disponíveis."
                            : "O percurso já começou ou está a terminar a recuperação.";
                    case FAILED -> "O percurso de Elytra está fechado: preparação falhou ("
                            + chunks.code() + ").";
                    case RELEASED -> "O percurso de Elytra está fechado até os chunks serem preparados novamente.";
                    case DISABLED -> "Os Anéis de Elytra estão fechados.";
                };
        return new ElytraRingsPaperStatus(settings.enabled(), ready, game.phase(), message, chunks);
    }

    /** Current bounded-preload state exposed to the Paper lifecycle adapter. */
    public synchronized ElytraChunkPreparation.Status chunkPreparation() {
        return chunkPreparation.status();
    }

    public synchronized void tick(Instant now) {
        Objects.requireNonNull(now, "now");
        if (restorationPending) {
            retryPendingRestoration();
            return;
        }
        if (movementOverflow) {
            clearAcceptedMoves();
            recoverAll(UUID.randomUUID(), "MOVEMENT_QUEUE_OVERFLOW");
            return;
        }
        chunkPreparation.tick();
        ElytraChunkPreparation.Status chunks = chunkPreparation.status();
        if (!settings.enabled() || !validRegion() || !chunks.admissionReady()) {
            if (game.phase() == ElytraRingsPhase.RUNNING) recoverAll(UUID.randomUUID(), "COURSE_UNAVAILABLE");
            return;
        }
        Player player = participant();
        if (game.phase() == ElytraRingsPhase.RUNNING) {
            if (player != null) {
                PlayerSession session = sessionRegistry.findByPlayer(player.getUniqueId()).orElse(null);
                if (session == null || session.phase() != SessionPhase.ACTIVE || !inside(player, session)) {
                    reset(player, now);
                    return;
                }
            }
            processAcceptedMoves();
            if (game.phase() == ElytraRingsPhase.FINISHING) {
                finish(now, "RESULT");
                return;
            }
            if (startedAt != null && now.isAfter(startedAt.plus(settings.config().timeout()))) {
                game.invalidate(now, nextOperation());
                finish(now, "TIMEOUT");
                return;
            }
        }
    }

    public synchronized void onMove(PlayerMoveEvent event) {
        PlayerSession session = sessionRegistry.findByPlayer(event.getPlayer().getUniqueId()).orElse(null);
        if (session == null || !session.matchId().equals(matchId) || session.phase() != SessionPhase.ACTIVE) return;
        if (event.getTo() == null || !inside(event.getPlayer(), session, event.getTo())) event.setCancelled(true);
    }

    /** Queues an accepted move segment without scoring or mutating the event. */
    public synchronized void onAcceptedMove(PlayerMoveEvent event) {
        if (event == null || event.isCancelled() || event instanceof PlayerTeleportEvent
                || restorationPending || movementOverflow || game.phase() != ElytraRingsPhase.RUNNING) return;
        Player player = event.getPlayer();
        PlayerSession session = sessionRegistry.findByPlayer(player.getUniqueId()).orElse(null);
        if (session == null || !session.matchId().equals(matchId) || session.phase() != SessionPhase.ACTIVE
                || !player.isGliding()) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from == null || to == null || !sameCourseWorld(from.getWorld()) || !sameCourseWorld(to.getWorld())
                || samePosition(from, to)) return;
        if (acceptedMoves.size() >= MAX_ACCEPTED_MOVES) {
            movementOverflow = true;
            return;
        }
        acceptedMoves.addLast(new AcceptedMove(player.getUniqueId(), from.clone(), to.clone(), clock.instant()));
    }
    public synchronized void onTeleport(PlayerTeleportEvent event) { if (!internalTeleport && sessionRegistry.findByPlayer(event.getPlayer().getUniqueId()).filter(s -> s.matchId().equals(matchId) && s.phase() == SessionPhase.ACTIVE).isPresent()) event.setCancelled(true); }

    /** Ender pearls are never a participant-controlled escape path. */
    public synchronized boolean onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl)
                || !(event.getEntity().getShooter() instanceof Player player)
                || activeSession(player.getUniqueId()) == null) return false;
        event.setCancelled(true);
        return true;
    }

    /** Direct block edits are denied for active runners, including edits outside the ring regions. */
    public synchronized boolean onBlockBreak(BlockBreakEvent event) {
        if (!isActivePlayer(event.getPlayer().getUniqueId())) return false;
        event.setCancelled(true);
        return true;
    }

    public synchronized boolean onBlockPlace(BlockPlaceEvent event) {
        if (!isActivePlayer(event.getPlayer().getUniqueId())) return false;
        event.setCancelled(true);
        return true;
    }

    /**
     * Checks the interaction without consuming the budget. Paper may still cancel
     * the boost or projectile insertion after this event.
     */
    public synchronized boolean onRocketUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = activeSession(player.getUniqueId());
        ItemStack item = event.getItem();
        if (session == null || item == null || item.getType() != Material.FIREWORK_ROCKET
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return false;
        boolean allowed = inside(player, session)
                && player.isGliding()
                && settings.grantRockets()
                && rocketUses < settings.rocketCount()
                && temporaryItems.sessionId(item).filter(session.sessionId()::equals).isPresent();
        if (!allowed) {
            event.setCancelled(true);
        }
        return true;
    }

    /** Used by the native pre-insertion boost listener; ground launches have no authority. */
    public synchronized boolean allowsRocketBoost(
            com.destroystokyo.paper.event.player.PlayerElytraBoostEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = activeSession(player.getUniqueId());
        ItemStack item = event.getItemStack();
        return !event.isCancelled() && game.phase() == ElytraRingsPhase.RUNNING
                && session != null && player.isOnline() && player.isValid() && player.isGliding()
                && inside(player, session) && settings.grantRockets() && rocketUses < settings.rocketCount()
                && item != null && item.getType() == Material.FIREWORK_ROCKET
                && temporaryItems.sessionId(item).filter(session.sessionId()::equals).isPresent();
    }

    /** A budget unit is consumed once, only after the exact native insertion is confirmed. */
    public synchronized void rocketInserted(UUID entityId) {
        Objects.requireNonNull(entityId, "entityId");
        if (insertedRockets.contains(entityId)) return;
        if (game.phase() != ElytraRingsPhase.RUNNING || rocketUses >= settings.rocketCount())
            throw new IllegalStateException("Elytra rocket insertion exceeded the active run budget");
        insertedRockets.add(entityId);
        rocketUses++;
    }

    /** Cancels a vanilla death before drops or progress can be committed. */
    public synchronized boolean onDeath(UUID playerId, UUID rootOperationId) {
        if (!isActivePlayer(Objects.requireNonNull(playerId))) return false;
        recoverAll(Objects.requireNonNull(rootOperationId), "DEATH_EVENT");
        return true;
    }

    public synchronized void onDisconnect(UUID playerId, UUID rootOperationId) {
        Objects.requireNonNull(playerId, "playerId");
        if (sessionRegistry.findByPlayer(playerId).filter(session -> session.matchId().equals(matchId)).isEmpty()) return;
        departingPlayers.add(playerId);
        recoverAll(rootOperationId, "PLAYER_DISCONNECTED");
    }
    public synchronized void shutdown(UUID rootOperationId) { recoverAll(rootOperationId, "PLUGIN_DISABLED"); }

    private void reset(Player player, Instant now) {
        clearAcceptedMoves();
        game.reset(now, nextOperation());
        startedAt = now;
        teleport(player, settings.start());
        player.setGameMode(org.bukkit.GameMode.ADVENTURE);
        player.sendMessage("§eO percurso foi reiniciado por saíres dos limites. Volta a atravessar o primeiro anel.");
    }

    private void processAcceptedMoves() {
        List<AcceptedMove> pending = new ArrayList<>(acceptedMoves);
        acceptedMoves.clear();
        for (AcceptedMove move : pending) {
            if (game.phase() != ElytraRingsPhase.RUNNING) return;
            PlayerSession session = sessionRegistry.findByPlayer(move.playerId()).orElse(null);
            if (session == null || !session.matchId().equals(matchId) || session.phase() != SessionPhase.ACTIVE
                    || startedAt == null || move.acceptedAt().isBefore(startedAt)) continue;
            Instant deadline = startedAt.plus(settings.config().timeout());
            if (move.acceptedAt().isAfter(deadline)) continue;
            double previousFraction = -1.0;
            while (game.phase() == ElytraRingsPhase.RUNNING) {
                OptionalDouble fraction = settings.ringCrossing(game.nextRing(), move.from(), move.to());
                if (fraction.isEmpty() || fraction.getAsDouble() < previousFraction) break;
                game.checkpoint(game.nextRing(), move.acceptedAt(), nextOperation());
                previousFraction = fraction.getAsDouble();
            }
        }
    }

    private void clearAcceptedMoves() {
        acceptedMoves.clear();
        movementOverflow = false;
    }

    private boolean sameCourseWorld(org.bukkit.World candidate) {
        if (candidate == null || settings.world() == null) return false;
        String configuredName = settings.world().getName();
        String candidateName = candidate.getName();
        return configuredName != null && candidateName != null
                && settings.world().getUID().equals(candidate.getUID())
                && configuredName.equals(candidateName);
    }

    private static boolean samePosition(Location left, Location right) {
        return Double.compare(left.getX(), right.getX()) == 0
                && Double.compare(left.getY(), right.getY()) == 0
                && Double.compare(left.getZ(), right.getZ()) == 0;
    }

    private void finish(Instant now, String reason) {
        clearAcceptedMoves();
        UUID resultRoot = UUID.nameUUIDFromBytes((matchId + ":RESULT")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!restorationPending) beginPendingRestoration(resultRoot, reason, true);
        retryPendingRestoration();
    }

    private void recoverAll(UUID root, String reason) {
        clearAcceptedMoves();
        if (!restorationPending) {
            if (game.phase() == ElytraRingsPhase.DISABLED || game.phase() == ElytraRingsPhase.CLOSED) return;
            boolean resultRun = game.phase() == ElytraRingsPhase.FINISHING;
            if (!resultRun && game.phase() != ElytraRingsPhase.RECOVERING) game.recover(nextOperation());
            beginPendingRestoration(root, reason, resultRun);
        }
        retryPendingRestoration();
    }

    private void beginPendingRestoration(UUID root, String reason, boolean resultRun) {
        clearAcceptedMoves();
        restorationPending = true;
        restoringResult = resultRun;
        restorationRoot = Objects.requireNonNull(root, "root");
        restorationReason = Objects.requireNonNull(reason, "reason");
        restorationResult = game.result().orElse(null);
        Set<UUID> participants = new LinkedHashSet<>();
        sessionRegistry.findByMatch(matchId).forEach(session -> participants.add(session.playerId()));
        if (restorationResult != null) participants.add(restorationResult.player());
        restorationParticipants = Set.copyOf(participants);
        sessionRegistry.findByMatch(matchId).forEach(session -> admissions.revokeSession(session.sessionId()));
    }

    private void retryPendingRestoration() {
        if (!restorationPending) return;
        boolean restored = true;
        for (PlayerSession session : sessionRegistry.findByMatch(matchId)) {
            admissions.revokeSession(session.sessionId());
            if (session.phase() == SessionPhase.QUARANTINED) {
                restored = false;
            } else if (session.phase() != SessionPhase.CLOSED) {
                if (departingPlayers.contains(session.playerId()) || !restorationAllowed(session)) {
                    restored = false;
                    continue;
                }
                try {
                    AdmissionResult result = restoringResult
                            ? sessions.finishAndRestore(session.sessionId(),
                                    OperationIds.derive(restorationRoot, "FINISH_" + session.playerId()), restorationReason)
                            : sessions.recover(session.sessionId(),
                                    OperationIds.derive(restorationRoot, "RESTORE_" + session.playerId()), restorationReason);
                    restored &= result.status() == AdmissionStatus.RECOVERED
                            && session.phase() == SessionPhase.CLOSED;
                } catch (RuntimeException failure) {
                    restored = false;
                }
            }
        }
        if (!restored) return;
        try {
            chunkPreparation.release();
        } catch (RuntimeException releaseFailure) {
            return;
        }
        if (chunkPreparation.status().ticketedChunks() != 0) return;
        if (restoringResult) {
            if (game.phase() == ElytraRingsPhase.FINISHING) game.close(nextOperation());
            if (!restorationParticipants.isEmpty()) {
                if (restorationResult == null) recordNoContest(restorationReason, restorationParticipants);
                else recordResult(restorationResult);
            }
        } else {
            if (game.phase() == ElytraRingsPhase.RECOVERING) game.close(nextOperation());
            if (!restorationParticipants.isEmpty()) recordNoContest(restorationReason, restorationParticipants);
        }
        restorationPending = false;
        restoringResult = false;
        restorationRoot = null;
        restorationReason = null;
        restorationParticipants = Set.of();
        restorationResult = null;
        startedAt = null;
        rocketUses = 0;
    }

    private boolean restorationAllowed(PlayerSession session) {
        try {
            return restorationReady.test(session);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
    private void recordNoContest(String reason, Set<UUID> participants) { Instant finished = clock.instant(); statistics.record(MatchResultFactory.noContest(matchId, GameKey.ELYTRA_RINGS, game.course().revision(), "solo", finished, finished, reason, participants)); }
    private void recordResult(ElytraRingsResult terminal) { Instant finished = clock.instant(); Instant started = finished.minus(terminal.elapsed()); long rings = MatchResultFactory.bounded(terminal.splits().size()); Map<String, Long> metrics = Map.of("time_ms", MatchResultFactory.boundedMillis(terminal.elapsed()), "rings", rings, "resets", MatchResultFactory.bounded(terminal.resets()), "wins", terminal.valid() ? 1L : 0L); MatchOutcome outcome = terminal.valid() ? MatchOutcome.VICTORY : MatchOutcome.NO_CONTEST; statistics.record(MatchResultFactory.create(matchId, GameKey.ELYTRA_RINGS, terminal.revision(), "solo", started, finished, outcome, terminal.reason(), Map.of(terminal.player(), new PlayerResult(terminal.valid() ? 1 : 0, terminal.valid(), false, Optional.empty(), metrics)))); }
    private void grantTemporaryFlightKit(Player player, UUID sessionId) {
        player.getInventory().setChestplate(temporaryItems.tag(new ItemStack(Material.ELYTRA), sessionId, GameKey.ELYTRA_RINGS));
        if (settings.grantRockets()) {
            ItemStack rockets = new ItemStack(Material.FIREWORK_ROCKET, settings.rocketCount());
            var meta = (org.bukkit.inventory.meta.FireworkMeta) rockets.getItemMeta();
            meta.clearEffects();
            meta.setPower(1);
            rockets.setItemMeta(meta);
            player.getInventory().setItemInOffHand(temporaryItems.tag(rockets, sessionId, GameKey.ELYTRA_RINGS));
        }
        player.updateInventory();
    }
    private Player participant() { return sessionRegistry.findByMatch(matchId).stream().findFirst().flatMap(s -> settings.world().getPlayers().stream().filter(p -> p.getUniqueId().equals(s.playerId())).findFirst()).orElse(null); }
    private boolean inside(Player player, PlayerSession session) { return inside(player, session, player.getLocation()); }
    private boolean inside(Player player, PlayerSession session, Location location) { return location != null && sameCourseWorld(location.getWorld()) && regions.at(location).filter(r -> r.id().equals(settings.participantRegionId())).isPresent() && admissions.permits(player.getUniqueId(), session.sessionId(), settings.participantRegionId(), clock.instant()); }
    private boolean ready(Player player, AdmissionRequest request, RegionAdmissionToken token, Instant now) { return player.isOnline() && player.getUniqueId().equals(request.playerId()) && request.game() == GameKey.ELYTRA_RINGS && request.matchId().equals(matchId) && token.playerId().equals(player.getUniqueId()) && token.sessionId().equals(request.sessionId()) && token.regionId().equals(settings.participantRegionId()) && token.validAt(now) && validRegion() && chunkPreparation.admissionReady(); }
    private boolean validRegion() { return regions.find(settings.participantRegionId()).filter(r -> r.game() == GameKey.ELYTRA_RINGS && r.requiresAdmission() && r.immutable() && r.bounds().worldId().equals(settings.world().getUID())).isPresent(); }
    private PlayerSession activeSession(UUID playerId) { return sessionRegistry.findByPlayer(playerId).filter(s -> s.matchId().equals(matchId) && s.phase() == SessionPhase.ACTIVE).orElse(null); }
    private boolean isActivePlayer(UUID playerId) { return activeSession(playerId) != null; }
    private void teleport(Player player, Location location) { internalTeleport = true; try { if (!player.teleport(location.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN)) throw new IllegalStateException("teleport rejected"); } finally { internalTeleport = false; } }
    private OperationId nextOperation() { return new OperationId(matchId, ++operationSequence); }
    private static AdmissionResult rejected(String code, String message) { return AdmissionResult.rejected(code, message); }
}
