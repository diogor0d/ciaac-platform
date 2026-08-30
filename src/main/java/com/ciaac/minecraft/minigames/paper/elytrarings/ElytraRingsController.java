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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    private long operationSequence;
    private Instant startedAt;
    private boolean internalTeleport;
    private int rocketUses;

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
        this.matchId = Objects.requireNonNull(matchId, "matchId"); this.game = Objects.requireNonNull(game, "game"); this.settings = Objects.requireNonNull(settings, "settings");
        this.sessions = Objects.requireNonNull(sessions, "sessions"); this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry"); this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.regions = Objects.requireNonNull(regions, "regions"); this.temporaryItems = Objects.requireNonNull(temporaryItems, "temporaryItems"); this.clock = Objects.requireNonNull(clock, "clock"); this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.chunkPreparation = Objects.requireNonNull(chunkPreparation, "chunkPreparation");
    }

    public synchronized AdmissionResult join(Player player, AdmissionRequest request, RegionAdmissionToken token) {
        if (!settings.enabled()) return rejected("DISABLED", "Os Anéis de Elytra estão temporariamente fechados.");
        Objects.requireNonNull(player); Objects.requireNonNull(request); Objects.requireNonNull(token); Instant now = clock.instant();
        if (!ready(player, request, token, now)) return rejected("CONFIGURATION_UNAVAILABLE", "O percurso de Elytra ainda não está pronto.");
        admissions.issue(token); AdmissionResult prepared = sessions.prepare(request);
        if (prepared.status() != AdmissionStatus.PREPARED) { admissions.revokeSession(request.sessionId()); return prepared; }
        PlayerSession session = prepared.session().orElseThrow();
        try {
            if (game.phase() == ElytraRingsPhase.DISABLED) game.open(nextOperation());
            teleport(player, settings.start());
            sessions.activate(session.sessionId(), OperationIds.derive(request.requestId(), "GAME_ACTIVE"), now);
            grantTemporaryFlightKit(player, session.sessionId());
            rocketUses = 0;
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
        return new AdmissionResult(AdmissionStatus.RECOVERED, "LEFT", "Saíste dos Anéis de Elytra e o teu estado foi restaurado.", java.util.Optional.of(session));
    }

    public synchronized ElytraRingsPaperStatus status() {
        ElytraChunkPreparation.Status chunks = chunkPreparation.status();
        boolean regionReady = settings.enabled() && settings.world() != null && validRegion();
        boolean ready = regionReady && chunks.admissionReady();
        String message = !settings.enabled() ? "Os Anéis de Elytra estão fechados."
                : !regionReady ? "O percurso está fechado até a região ser validada."
                : switch (chunks.phase()) {
                    case PREPARING -> "O percurso de Elytra está a preparar os chunks."
                            + " Aguarda antes de entrar.";
                    case READY -> "Os Anéis de Elytra estão disponíveis.";
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
        chunkPreparation.tick();
        ElytraChunkPreparation.Status chunks = chunkPreparation.status();
        if (!settings.enabled() || !validRegion() || !chunks.admissionReady()) {
            if (game.phase() == ElytraRingsPhase.RUNNING) recoverAll(UUID.randomUUID(), "COURSE_UNAVAILABLE");
            return;
        }
        Player player = participant();
        if (game.phase() == ElytraRingsPhase.RUNNING && player != null) {
            PlayerSession session = sessionRegistry.findByPlayer(player.getUniqueId()).orElse(null);
            if (session == null || session.phase() != SessionPhase.ACTIVE || !inside(player, session)) { reset(player, now); return; }
            if (startedAt != null && !now.isBefore(startedAt.plus(settings.config().timeout()))) { game.invalidate(now, nextOperation()); finish(now, "TIMEOUT"); return; }
            if (settings.reachedRing(game.nextRing(), player.getLocation())) {
                game.checkpoint(game.nextRing(), now, nextOperation());
                player.sendMessage("§bCheckpoint " + (game.nextRing() - 1) + "/" + settings.config().course().rings().size() + " concluído.");
            }
            if (game.phase() == ElytraRingsPhase.FINISHING) finish(now, "RESULT");
        }
    }

    public synchronized void onMove(PlayerMoveEvent event) {
        PlayerSession session = sessionRegistry.findByPlayer(event.getPlayer().getUniqueId()).orElse(null);
        if (session == null || !session.matchId().equals(matchId) || session.phase() != SessionPhase.ACTIVE) return;
        if (event.getTo() == null || !inside(event.getPlayer(), session, event.getTo())) event.setCancelled(true);
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
     * Accepts only server-tagged rockets from this session and counts each accepted
     * use independently of the client-visible stack count.
     */
    public synchronized boolean onRocketUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = activeSession(player.getUniqueId());
        ItemStack item = event.getItem();
        if (session == null || item == null || item.getType() != Material.FIREWORK_ROCKET
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return false;
        boolean allowed = inside(player, session)
                && settings.grantRockets()
                && rocketUses < settings.rocketCount()
                && temporaryItems.sessionId(item).filter(session.sessionId()::equals).isPresent();
        if (!allowed) {
            event.setCancelled(true);
        } else {
            rocketUses++;
        }
        return true;
    }

    /** Cancels a vanilla death before drops or progress can be committed. */
    public synchronized boolean onDeath(UUID playerId, UUID rootOperationId) {
        if (!isActivePlayer(Objects.requireNonNull(playerId))) return false;
        recoverAll(Objects.requireNonNull(rootOperationId), "DEATH_EVENT");
        return true;
    }

    public synchronized void onDisconnect(UUID playerId, UUID rootOperationId) { leave(playerId, rootOperationId); }
    public synchronized void shutdown(UUID rootOperationId) { recoverAll(rootOperationId, "PLUGIN_DISABLED"); }

    private void reset(Player player, Instant now) { game.reset(now, nextOperation()); teleport(player, settings.start()); player.sendMessage("§eO percurso foi reiniciado por saíres dos limites. Volta a atravessar o primeiro anel."); }
    private void finish(Instant now, String reason) {
        ElytraRingsResult terminal = game.result().orElse(null);
        Set<UUID> participants = sessionRegistry.findByMatch(matchId).stream()
                .map(PlayerSession::playerId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean restored = true;
        UUID resultRoot = UUID.nameUUIDFromBytes((matchId + ":RESULT")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            for (PlayerSession session : sessionRegistry.findByMatch(matchId)) {
                if (!session.phase().terminal()) {
                    restored &= sessions.finishAndRestore(session.sessionId(),
                            OperationIds.derive(resultRoot, "FINISH_" + session.playerId()), reason)
                            .status() == AdmissionStatus.RECOVERED;
                    admissions.revokeSession(session.sessionId());
                }
            }
            if (game.phase() == ElytraRingsPhase.FINISHING) game.close(nextOperation());
            if (restored && !participants.isEmpty()) {
                if (terminal == null) recordNoContest(reason, participants);
                else recordResult(terminal);
            }
        } finally {
            chunkPreparation.release();
        }
    }

    private void recoverAll(UUID root, String reason) {
        Set<UUID> participants = sessionRegistry.findByMatch(matchId).stream()
                .map(PlayerSession::playerId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean restored = true;
        try {
            if (game.phase() != ElytraRingsPhase.DISABLED && game.phase() != ElytraRingsPhase.CLOSED
                    && game.phase() != ElytraRingsPhase.FINISHING
                    && game.phase() != ElytraRingsPhase.RECOVERING) game.recover(nextOperation());
            for (PlayerSession session : sessionRegistry.findByMatch(matchId)) {
                if (!session.phase().terminal()) {
                    restored &= sessions.recover(session.sessionId(),
                            OperationIds.derive(root, "RESTORE_" + session.playerId()), reason)
                            .status() == AdmissionStatus.RECOVERED;
                    admissions.revokeSession(session.sessionId());
                }
            }
            if (game.phase() == ElytraRingsPhase.RECOVERING) game.close(nextOperation());
            if (restored && !participants.isEmpty()) recordNoContest(reason, participants);
        } finally {
            chunkPreparation.release();
        }
    }
    private void recordNoContest(String reason, Set<UUID> participants) { Instant finished = clock.instant(); statistics.record(MatchResultFactory.noContest(matchId, GameKey.ELYTRA_RINGS, game.course().revision(), "solo", finished, finished, reason, participants)); }
    private void recordResult(ElytraRingsResult terminal) { Instant finished = clock.instant(); Instant started = finished.minus(terminal.elapsed()); long rings = MatchResultFactory.bounded(terminal.splits().size()); Map<String, Long> metrics = Map.of("time_ms", MatchResultFactory.boundedMillis(terminal.elapsed()), "rings", rings, "resets", MatchResultFactory.bounded(terminal.resets()), "wins", terminal.valid() ? 1L : 0L); MatchOutcome outcome = terminal.valid() ? MatchOutcome.VICTORY : MatchOutcome.NO_CONTEST; statistics.record(MatchResultFactory.create(matchId, GameKey.ELYTRA_RINGS, terminal.revision(), "solo", started, finished, outcome, terminal.reason(), Map.of(terminal.player(), new PlayerResult(terminal.valid() ? 1 : 0, terminal.valid(), false, Optional.empty(), metrics)))); }
    private void grantTemporaryFlightKit(Player player, UUID sessionId) { player.getInventory().setChestplate(temporaryItems.tag(new ItemStack(Material.ELYTRA), sessionId, GameKey.ELYTRA_RINGS)); if (settings.grantRockets()) player.getInventory().setItemInOffHand(temporaryItems.tag(new ItemStack(Material.FIREWORK_ROCKET, settings.rocketCount()), sessionId, GameKey.ELYTRA_RINGS)); player.updateInventory(); }
    private Player participant() { return sessionRegistry.findByMatch(matchId).stream().findFirst().flatMap(s -> settings.world().getPlayers().stream().filter(p -> p.getUniqueId().equals(s.playerId())).findFirst()).orElse(null); }
    private boolean inside(Player player, PlayerSession session) { return inside(player, session, player.getLocation()); }
    private boolean inside(Player player, PlayerSession session, Location location) { return settings.world().equals(location.getWorld()) && regions.at(location).filter(r -> r.id().equals(settings.participantRegionId())).isPresent() && admissions.permits(player.getUniqueId(), session.sessionId(), settings.participantRegionId(), clock.instant()); }
    private boolean ready(Player player, AdmissionRequest request, RegionAdmissionToken token, Instant now) { return player.isOnline() && player.getUniqueId().equals(request.playerId()) && request.game() == GameKey.ELYTRA_RINGS && request.matchId().equals(matchId) && token.playerId().equals(player.getUniqueId()) && token.sessionId().equals(request.sessionId()) && token.regionId().equals(settings.participantRegionId()) && token.validAt(now) && validRegion() && chunkPreparation.admissionReady(); }
    private boolean validRegion() { return regions.find(settings.participantRegionId()).filter(r -> r.game() == GameKey.ELYTRA_RINGS && r.requiresAdmission() && r.immutable() && r.bounds().worldId().equals(settings.world().getUID())).isPresent(); }
    private PlayerSession activeSession(UUID playerId) { return sessionRegistry.findByPlayer(playerId).filter(s -> s.matchId().equals(matchId) && s.phase() == SessionPhase.ACTIVE).orElse(null); }
    private boolean isActivePlayer(UUID playerId) { return activeSession(playerId) != null; }
    private void teleport(Player player, Location location) { internalTeleport = true; try { if (!player.teleport(location.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN)) throw new IllegalStateException("teleport rejected"); } finally { internalTeleport = false; } }
    private OperationId nextOperation() { return new OperationId(matchId, ++operationSequence); }
    private static AdmissionResult rejected(String code, String message) { return AdmissionResult.rejected(code, message); }
}
