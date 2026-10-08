package com.ciaac.minecraft.minigames.paper.colorfloor;

import com.ciaac.minecraft.minigames.colorfloor.ColorFloorGame;
import com.ciaac.minecraft.minigames.colorfloor.ColorFloorPhase;
import com.ciaac.minecraft.minigames.colorfloor.ColorFloorResult;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.hotpotato.OperationId;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import pt.ciaac.minigames.paper.template.BlockMutationBatch;

/**
 * Disabled-by-default controller with an immutable, fail-closed floor
 * template. All live block changes are main-thread, no-physics, bounded
 * batches; an unexpected block or unloaded chunk leaves the match in recovery.
 */
public final class ColorFloorController {
    private static final int BLOCKS_PER_TICK = 256;
    private static final Duration RESTORE_DELAY = Duration.ofMillis(500);

    private final UUID matchId;
    private final ColorFloorGame game;
    private final ColorFloorPaperSettings settings;
    private final SessionCoordinator sessions;
    private final SessionRegistry sessionRegistry;
    private final RegionAdmissionRegistry admissions;
    private final ProtectedRegionRegistry regions;
    private final Clock clock;
    private final StatisticsResultSink statistics;
    private final Predicate<PlayerSession> restorationReady;
    private final Set<UUID> departingPlayers = new LinkedHashSet<>();
    private final Map<ColorFloorCell, BlockData> mutated = new HashMap<>();
    private long operationSequence;
    private Instant announceStarted;
    private Instant reactionStarted;
    private Instant restoreAt;
    private boolean cueSent;
    private boolean internalTeleport;
    private int admittedPlayers;
    private BlockMutationBatch pendingMutation;
    private BlockMutationBatch pendingRestore;
    private Set<UUID> pendingStanding = Set.of();
    private boolean recoveryPending;
    private boolean recoverySessionsRestored = true;
    private Set<UUID> recoveryPlayers = Set.of();
    private String recoveryReason;

    public ColorFloorController(UUID matchId, ColorFloorGame game, ColorFloorPaperSettings settings,
                                SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                RegionAdmissionRegistry admissions, ProtectedRegionRegistry regions,
                                Clock clock) {
        this(matchId, game, settings, sessions, sessionRegistry, admissions, regions, clock,
                StatisticsResultSink.unavailable());
    }

    public ColorFloorController(UUID matchId, ColorFloorGame game, ColorFloorPaperSettings settings,
                                SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                RegionAdmissionRegistry admissions, ProtectedRegionRegistry regions,
                                Clock clock, StatisticsResultSink statistics) {
        this(matchId, game, settings, sessions, sessionRegistry, admissions, regions, clock, statistics,
                session -> false);
    }

    public ColorFloorController(UUID matchId, ColorFloorGame game, ColorFloorPaperSettings settings,
                                SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                RegionAdmissionRegistry admissions, ProtectedRegionRegistry regions,
                                Clock clock, StatisticsResultSink statistics, Predicate<PlayerSession> restorationReady) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.game = Objects.requireNonNull(game, "game");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.restorationReady = Objects.requireNonNull(restorationReady, "restorationReady");
    }

    public synchronized AdmissionResult join(Player player, AdmissionRequest request, RegionAdmissionToken token) {
        if (!settings.enabled()) return rejected("DISABLED", "O Chão de Cores está temporariamente fechado.");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(token, "token");
        Instant now = clock.instant();
        if (!ready(player, request, token, now)) {
            return rejected("CONFIGURATION_UNAVAILABLE", "O Chão de Cores ainda não está pronto.");
        }
        admissions.issue(token);
        AdmissionResult prepared = sessions.prepare(request);
        if (prepared.status() != AdmissionStatus.PREPARED) {
            admissions.revokeSession(request.sessionId());
            return prepared;
        }
        PlayerSession session = prepared.session().orElseThrow();
        try {
            if (game.phase() == ColorFloorPhase.DISABLED) game.open(nextOperation());
            teleport(player, settings.start());
            if (!sessions.activate(session.sessionId(),
                    OperationIds.derive(request.requestId(), "GAME_ACTIVE"), clock.instant())) {
                throw new IllegalStateException("session activation was not applied");
            }
            game.join(player.getUniqueId(), nextOperation());
            admittedPlayers++;
            player.sendMessage("§aChão de Cores: estás na fila. Prepara-te!");
            return prepared;
        } catch (RuntimeException failure) {
            admissions.revokeSession(request.sessionId());
            try {
                sessions.recover(session.sessionId(), OperationIds.derive(request.requestId(), "JOIN_RECOVERY"), "JOIN_FAILED");
            } catch (RuntimeException ignored) {
                // Admission remains closed; the session coordinator owns quarantine.
            }
            return rejected("JOIN_FAILED", "Não foi possível preparar a tua entrada; o teu estado foi protegido.");
        }
    }

    public synchronized AdmissionResult leave(UUID playerId, UUID rootOperationId) {
        PlayerSession session = sessionRegistry.findByPlayer(Objects.requireNonNull(playerId))
                .orElseThrow(() -> new IllegalArgumentException("No active session"));
        if (!session.matchId().equals(matchId)) throw new IllegalArgumentException("Session belongs to another match");
        recoverAll(rootOperationId, "PLAYER_LEFT");
        if (session.phase() != SessionPhase.CLOSED) {
            return new AdmissionResult(session.phase() == SessionPhase.QUARANTINED
                    ? AdmissionStatus.QUARANTINED : AdmissionStatus.REJECTED, "RECOVERY_PENDING",
                    "Saíste da partida; o teu estado continua protegido enquanto o restauro termina.", Optional.of(session));
        }
        return new AdmissionResult(AdmissionStatus.RECOVERED, "LEFT",
                "Saíste do Chão de Cores e o teu estado foi restaurado.", Optional.of(session));
    }

    public synchronized ColorFloorPaperStatus status() {
        boolean ready = settings.enabled() && !recoveryPending && settings.world() != null && validRegion()
                && game.phase() != ColorFloorPhase.RECOVERING && game.phase() != ColorFloorPhase.CLOSED;
        return new ColorFloorPaperStatus(settings.enabled(), ready, game.phase(),
                !settings.enabled() ? "O Chão de Cores está fechado."
                        : recoveryPending ? "O Chão de Cores está fechado enquanto a recuperação é revista."
                        : ready ? "O Chão de Cores está disponível."
                        : "A arena está fechada até a configuração ser validada.");
    }

    public synchronized void tick(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!settings.enabled() || !validRegion()) return;
        if (pendingMutation != null) {
            applyMutation(now, false);
            return;
        }
        if (pendingRestore != null) {
            applyMutation(now, true);
            return;
        }
        if (restoreAt != null) {
            if (now.isBefore(restoreAt)) return;
            if (!ensureRestoreBatch()) {
                recoverAll(UUID.randomUUID(), "FLOOR_RESTORE_UNSAFE");
                return;
            }
            if (pendingRestore != null) return;
            completeRestoration(now);
            return;
        }
        if (recoveryPending) {
            if (!ensureRestoreBatch()) return;
            if (pendingRestore != null) return;
            recoverAll(UUID.nameUUIDFromBytes((matchId + ":COLOR_RECOVERY").getBytes(StandardCharsets.UTF_8)),
                    recoveryReason == null ? "RECOVERY" : recoveryReason);
            return;
        }
        if (game.phase() == ColorFloorPhase.WAITING
                && admittedPlayers >= settings.config().minimumPlayers()) {
            game.start(nextOperation());
            announceStarted = now;
            reactionStarted = null;
            cueSent = false;
        }
        if (game.phase() == ColorFloorPhase.REACTION) {
            if (!cueSent) {
                cue();
                cueSent = true;
            }
            if (announceStarted == null) announceStarted = now;
            if (reactionStarted == null
                    && !now.isBefore(announceStarted.plus(settings.announceDuration()))) {
                reactionStarted = now;
            }
            if (reactionStarted != null
                    && !now.isBefore(reactionStarted.plus(settings.config().reactionWindow()))) {
                resolveRound(now);
            }
        }
        if (game.phase() == ColorFloorPhase.FINISHING) {
            if (!ensureRestoreBatch()) {
                recoverAll(UUID.randomUUID(), "FLOOR_RESTORE_UNSAFE");
                return;
            }
            if (pendingRestore == null) restoreAll(
                    UUID.nameUUIDFromBytes((matchId + ":COLOR_RESULT").getBytes(StandardCharsets.UTF_8)),
                    "RESULT");
        }
    }

    public synchronized void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = sessionRegistry.findByPlayer(player.getUniqueId()).orElse(null);
        if (session == null || !session.matchId().equals(matchId)) return;
        if (session.phase() != SessionPhase.ACTIVE) {
            event.setCancelled(true);
            return;
        }
        Location to = event.getTo();
        if (to == null || !sameWorld(to.getWorld(), settings.world())
                || regions.at(to).filter(r -> r.id().equals(settings.participantRegionId())).isEmpty()
                || !admissions.permits(player.getUniqueId(), session.sessionId(), settings.participantRegionId(), clock.instant())) {
            event.setCancelled(true);
        }
    }

    public synchronized void onTeleport(PlayerTeleportEvent event) {
        if (internalTeleport) return;
        sessionRegistry.findByPlayer(event.getPlayer().getUniqueId())
                .filter(s -> s.matchId().equals(matchId) && !s.phase().terminal())
                .filter(s -> event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN
                        || (s.phase() != SessionPhase.RESTORING && s.phase() != SessionPhase.RECOVERING))
                .ifPresent(s -> event.setCancelled(true));
    }

    public synchronized void onBlockBreak(BlockBreakEvent event) {
        if (inParticipantRegion(event.getBlock())) event.setCancelled(true);
    }

    public synchronized void onBlockPlace(BlockPlaceEvent event) {
        if (inParticipantRegion(event.getBlockPlaced())) event.setCancelled(true);
    }

    public synchronized void onDisconnect(UUID playerId, UUID rootOperationId) {
        Objects.requireNonNull(playerId, "playerId");
        if (sessionRegistry.findByPlayer(playerId).filter(s -> s.matchId().equals(matchId)).isEmpty()) return;
        departingPlayers.add(playerId);
        recoverAll(rootOperationId, "PLAYER_DISCONNECTED");
    }

    /** Cancels the vanilla death path and enters the same durable recovery boundary as a disconnect. */
    public synchronized boolean onDeath(UUID playerId, UUID rootOperationId) {
        PlayerSession session = sessionRegistry.findByPlayer(Objects.requireNonNull(playerId)).orElse(null);
        if (session == null || !session.matchId().equals(matchId) || session.phase().terminal()) return false;
        recoverAll(Objects.requireNonNull(rootOperationId), "DEATH_EVENT");
        return true;
    }

    public synchronized void shutdown(UUID rootOperationId) {
        if (game.phase() != ColorFloorPhase.DISABLED && game.phase() != ColorFloorPhase.CLOSED) {
            recoverAll(rootOperationId, "PLUGIN_DISABLED");
        }
    }

    private void resolveRound(Instant now) {
        Set<UUID> standing = new LinkedHashSet<>();
        Set<UUID> live = game.livePlayers();
        for (PlayerSession session : sessionRegistry.findByMatch(matchId)) {
            if (!live.contains(session.playerId()) || session.phase() != SessionPhase.ACTIVE) continue;
            Player player = findPlayer(session.playerId()).orElse(null);
            if (player == null || !player.isOnline() || !sameWorld(player.getWorld(), settings.world())) continue;
            ColorFloorCell cell = new ColorFloorCell(player.getLocation().getBlockX(),
                    player.getLocation().getBlockY() - 1, player.getLocation().getBlockZ());
            if (settings.colors().get(cell) == game.target()) standing.add(player.getUniqueId());
        }
        if (!startMutation()) {
            recoverAll(UUID.randomUUID(), "FLOOR_TEMPLATE_CHANGED");
            return;
        }
        if (pendingMutation == null) {
            commitResolution(standing, now);
        } else {
            pendingStanding = Set.copyOf(standing);
        }
    }

    private boolean startMutation() {
        if (!mainThread() || pendingMutation != null || pendingRestore != null) return false;
        List<BlockMutationBatch.Mutation> mutations = new ArrayList<>();
        try {
            settings.colors().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(ColorFloorCell::x)
                            .thenComparingInt(ColorFloorCell::y).thenComparingInt(ColorFloorCell::z)))
                    .forEach(entry -> {
                        if (entry.getValue() == game.target()) return;
                        ColorFloorCell cell = entry.getKey();
                        Block block = loadedBlock(cell);
                        BlockData template = settings.template().get(cell);
                        if (block == null || template == null) throw new UnsafeFloorException();
                        BlockData current = block.getBlockData();
                        if (!current.equals(template)) throw new UnsafeFloorException();
                        mutated.putIfAbsent(cell, current.clone());
                        mutations.add(new BlockMutationBatch.Mutation(cell.x(), cell.y(), cell.z(), current,
                                Material.AIR.createBlockData()));
                    });
        } catch (RuntimeException invalid) {
            return false;
        }
        if (!mutations.isEmpty()) {
            pendingMutation = new BlockMutationBatch(settings.world().getUID(), settings.world().getName(), mutations);
        }
        return true;
    }

    private void applyMutation(Instant now, boolean restoring) {
        BlockMutationBatch batch = restoring ? pendingRestore : pendingMutation;
        if (batch == null) return;
        BlockMutationBatch.Progress progress = batch.apply(settings.world(), BLOCKS_PER_TICK);
        if (!progress.successful()) {
            if (restoring) pendingRestore = null;
            else { pendingMutation = null; pendingStanding = Set.of(); }
            recoverAll(UUID.randomUUID(), restoring ? "FLOOR_RESTORE_FAILED" : "FLOOR_MUTATION_FAILED");
            return;
        }
        if (!progress.complete()) return;
        if (restoring) {
            pendingRestore = null;
            if (!ensureRestoreBatch()) {
                recoverAll(UUID.randomUUID(), "FLOOR_RESTORE_UNSAFE");
                return;
            }
            if (pendingRestore == null) completeRestoration(now);
        } else {
            pendingMutation = null;
            Set<UUID> standing = pendingStanding;
            pendingStanding = Set.of();
            commitResolution(standing, now);
        }
    }

    private void commitResolution(Set<UUID> standing, Instant now) {
        try {
            Set<UUID> eliminated = new LinkedHashSet<>(game.livePlayers());
            eliminated.removeAll(standing);
            game.resolve(standing, nextOperation());
            for (UUID playerId : eliminated) {
                findPlayer(playerId).ifPresent(player -> {
                    player.getInventory().clear();
                    player.setGameMode(GameMode.SPECTATOR);
                    player.sendMessage("§eFoste eliminado nesta ronda. Podes assistir até ao fim.");
                });
            }
            reactionStarted = null;
            cueSent = false;
            restoreAt = now.plus(RESTORE_DELAY);
        } catch (RuntimeException failure) {
            recoverAll(UUID.randomUUID(), "ROUND_RESOLUTION_FAILED");
        }
    }

    /** Builds a restore batch only after verifying every live block is safe. */
    private boolean ensureRestoreBatch() {
        if (!mainThread()) return false;
        if (pendingRestore != null || mutated.isEmpty()) return true;
        List<BlockMutationBatch.Mutation> mutations = new ArrayList<>();
        List<Map.Entry<ColorFloorCell, BlockData>> ordered = new ArrayList<>(mutated.entrySet());
        ordered.sort(Map.Entry.comparingByKey(Comparator.comparingInt(ColorFloorCell::x)
                .thenComparingInt(ColorFloorCell::y).thenComparingInt(ColorFloorCell::z)));
        for (Map.Entry<ColorFloorCell, BlockData> entry : ordered) {
            ColorFloorCell cell = entry.getKey();
            Block block = loadedBlock(cell);
            BlockData template = settings.template().get(cell);
            if (block == null || template == null) return false;
            BlockData current = block.getBlockData();
            if (current.equals(template)) {
                mutated.remove(cell);
                continue;
            }
            if (current.getMaterial() != Material.AIR) return false;
            mutations.add(new BlockMutationBatch.Mutation(cell.x(), cell.y(), cell.z(), current, entry.getValue()));
        }
        if (!mutations.isEmpty()) {
            pendingRestore = new BlockMutationBatch(settings.world().getUID(), settings.world().getName(), mutations);
        }
        return true;
    }

    private void completeRestoration(Instant now) {
        if (!mutated.isEmpty() || pendingRestore != null) return;
        restoreAt = null;
        if (recoveryPending) {
            completeRecovery();
        } else if (game.phase() == ColorFloorPhase.FINISHING) {
            restoreAll(UUID.nameUUIDFromBytes((matchId + ":COLOR_RESULT").getBytes(StandardCharsets.UTF_8)), "RESULT");
        } else {
            announceStarted = now;
            reactionStarted = null;
            cueSent = false;
        }
    }

    private void cue() {
        String message = "§eCor segura: §f" + colorName(game.target()) + " §7— segue o texto e o som.";
        for (PlayerSession session : sessionRegistry.findByMatch(matchId)) {
            findPlayer(session.playerId()).ifPresent(player -> {
                player.sendMessage(message);
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
            });
        }
    }

    private static String colorName(com.ciaac.minecraft.minigames.colorfloor.FloorColor color) {
        return switch (color) {
            case RED -> "vermelho";
            case BLUE -> "azul";
            case GREEN -> "verde";
            case YELLOW -> "amarelo";
            case PURPLE -> "roxo";
            case ORANGE -> "laranja";
            case CYAN -> "ciano";
        };
    }

    private Block loadedBlock(ColorFloorCell cell) {
        World world = settings.world();
        if (world == null || !world.isChunkLoaded(cell.x() >> 4, cell.z() >> 4)) return null;
        return world.getBlockAt(cell.x(), cell.y(), cell.z());
    }

    private boolean mainThread() {
        return settings.world() != null && Bukkit.isPrimaryThread();
    }

    private Optional<Player> findPlayer(UUID playerId) {
        World world = settings.world();
        return world == null ? Optional.empty() : world.getPlayers().stream()
                .filter(player -> player.getUniqueId().equals(playerId)).findFirst();
    }

    private boolean inParticipantRegion(Block block) {
        return sameWorld(block.getWorld(), settings.world())
                && regions.at(block).map(r -> r.id().equals(settings.participantRegionId())
                        && r.bounds().worldId().equals(settings.world().getUID())).orElse(false);
    }

    private void teleport(Player player, Location location) {
        internalTeleport = true;
        try {
            if (!player.teleport(location.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                throw new IllegalStateException("teleport rejected");
            }
        } finally {
            internalTeleport = false;
        }
    }

    private boolean ready(Player player, AdmissionRequest request, RegionAdmissionToken token, Instant now) {
        return player.isOnline() && player.isValid() && player.getUniqueId().equals(request.playerId())
                && request.game() == GameKey.COLOR_FLOOR && request.matchId().equals(matchId)
                && token.playerId().equals(player.getUniqueId()) && token.sessionId().equals(request.sessionId())
                && token.regionId().equals(settings.participantRegionId()) && token.validAt(now)
                && !recoveryPending && (game.phase() == ColorFloorPhase.DISABLED || game.phase() == ColorFloorPhase.WAITING)
                && admittedPlayers < settings.config().maximumPlayers() && validRegion() && settings.start() != null
                && settings.world().isChunkLoaded(settings.start().getBlockX() >> 4, settings.start().getBlockZ() >> 4);
    }

    private boolean validRegion() {
        World world = settings.world();
        return world != null && settings.start() != null
                && sameWorld(settings.start().getWorld(), world)
                && regions.find(settings.participantRegionId())
                        .filter(r -> r.game() == GameKey.COLOR_FLOOR && r.requiresAdmission() && r.immutable()
                                && r.bounds().worldId().equals(world.getUID()))
                        .isPresent();
    }

    private void recoverAll(UUID root, String reason) {
        Objects.requireNonNull(root, "root");
        if (game.phase() == ColorFloorPhase.DISABLED || game.phase() == ColorFloorPhase.CLOSED) return;
        Set<UUID> players = new LinkedHashSet<>(recoveryPlayers);
        Set<PlayerSession> activeSessions = sessionRegistry.findByMatch(matchId);
        activeSessions.forEach(session -> players.add(session.playerId()));
        pendingMutation = null;
        pendingStanding = Set.of();
        restoreAt = null;
        if (game.phase() != ColorFloorPhase.RECOVERING) {
            try {
                game.recover(nextOperation());
            } catch (RuntimeException failure) {
                recoveryPending = true;
                recoverySessionsRestored = false;
                recoveryPlayers = Set.copyOf(players);
                recoveryReason = reason;
                return;
            }
        }
        recoveryPlayers = Set.copyOf(players);
        if (recoveryReason == null) recoveryReason = reason;
        recoveryPending = true;
        admittedPlayers = 0;
        activeSessions.forEach(session -> admissions.revokeSession(session.sessionId()));
        // Finish the owned floor before releasing any player's survival state.
        if (!ensureRestoreBatch() || pendingRestore != null) return;
        boolean restored = true;
        for (PlayerSession session : activeSessions) {
            if (session.phase() == SessionPhase.QUARANTINED) {
                restored = false;
            } else if (!session.phase().terminal()) {
                if (departingPlayers.contains(session.playerId()) || !restorationReady.test(session)) {
                    restored = false;
                    continue;
                }
                try {
                    restored &= sessions.recover(session.sessionId(), OperationIds.derive(root, "RECOVER_" + session.playerId()), reason)
                            .status() == AdmissionStatus.RECOVERED;
                } catch (RuntimeException failure) {
                    restored = false;
                }
            }
            admissions.revokeSession(session.sessionId());
        }
        recoverySessionsRestored = restored;
        completeRecovery();
    }

    private void completeRecovery() {
        if (!recoveryPending || pendingRestore != null || !mutated.isEmpty() || !recoverySessionsRestored) return;
        if (game.phase() == ColorFloorPhase.RECOVERING) game.close(nextOperation());
        if (!recoveryPlayers.isEmpty()) {
            Instant finished = clock.instant();
            statistics.record(MatchResultFactory.noContest(matchId, GameKey.COLOR_FLOOR,
                    settings.config().rulesetRevision(), "ffa", finished, finished,
                    recoveryReason == null ? "RECOVERY" : recoveryReason, recoveryPlayers));
        }
        recoveryPending = false;
        recoverySessionsRestored = true;
        recoveryPlayers = Set.of();
        recoveryReason = null;
        operationSequence = 0;
        announceStarted = null;
        reactionStarted = null;
        cueSent = false;
    }

    private void restoreAll(UUID root, String reason) {
        ColorFloorResult terminal = game.result().orElse(null);
        Set<UUID> participants = new LinkedHashSet<>();
        Set<PlayerSession> sessionsForMatch = sessionRegistry.findByMatch(matchId);
        sessionsForMatch.forEach(session -> participants.add(session.playerId()));
        boolean restored = true;
        for (PlayerSession session : sessionsForMatch) {
            if (session.phase() == SessionPhase.QUARANTINED) {
                restored = false;
            } else if (!session.phase().terminal()) {
                if (departingPlayers.contains(session.playerId()) || !restorationReady.test(session)) {
                    restored = false;
                    continue;
                }
                try {
                    restored &= sessions.finishAndRestore(session.sessionId(),
                                    OperationIds.derive(root, "FINISH_" + session.playerId()), reason)
                            .status() == AdmissionStatus.RECOVERED;
                } catch (RuntimeException failure) {
                    restored = false;
                }
            }
            admissions.revokeSession(session.sessionId());
        }
        if (!restored) {
            recoverAll(root, "RESTORE_FAILED");
            return;
        }
        if (game.phase() == ColorFloorPhase.FINISHING) game.close(nextOperation());
        if (!participants.isEmpty()) {
            if (terminal == null) recordNoContest(reason, participants);
            else recordResult(terminal, participants);
        }
        admittedPlayers = 0;
        announceStarted = null;
        reactionStarted = null;
        cueSent = false;
    }

    private void recordNoContest(String reason, Set<UUID> participants) {
        Instant finished = clock.instant();
        statistics.record(MatchResultFactory.noContest(matchId, GameKey.COLOR_FLOOR,
                settings.config().rulesetRevision(), "ffa", finished, finished, reason, participants));
    }

    private void recordResult(ColorFloorResult terminal, Set<UUID> participants) {
        Instant finished = clock.instant();
        Set<UUID> winners = terminal.winners();
        MatchOutcome outcome = winners.isEmpty() ? MatchOutcome.NO_CONTEST
                : winners.size() == 1 ? MatchOutcome.VICTORY : MatchOutcome.DRAW;
        Map<UUID, PlayerResult> standings = new HashMap<>();
        for (UUID player : participants) {
            int eliminated = terminal.eliminations().getOrDefault(player, 0);
            long rounds = MatchResultFactory.bounded(Math.max(0, terminal.roundsSurvived() - eliminated));
            boolean winner = outcome == MatchOutcome.VICTORY && winners.contains(player);
            int placement = outcome == MatchOutcome.NO_CONTEST ? 0 : winners.contains(player) ? 1 : 2;
            standings.put(player, new PlayerResult(placement, winner, false, Optional.empty(),
                    Map.of("wins", winner ? 1L : 0L, "rounds_survived", rounds)));
        }
        statistics.record(MatchResultFactory.create(matchId, GameKey.COLOR_FLOOR, terminal.revision(),
                "ffa", finished, finished, outcome, terminal.reason(), standings));
    }

    private OperationId nextOperation() {
        return new OperationId(matchId, ++operationSequence);
    }

    private static boolean sameWorld(World actual, World expected) {
        return actual != null && expected != null && actual.getUID().equals(expected.getUID())
                && actual.getName().equals(expected.getName());
    }

    private static AdmissionResult rejected(String code, String message) {
        return AdmissionResult.rejected(code, message);
    }

    private static final class UnsafeFloorException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
