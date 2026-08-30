package com.ciaac.minecraft.minigames.paper.buildbattle;

import com.ciaac.minecraft.minigames.buildbattle.BuildBattleMatch;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleOperationId;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattlePhase;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattlePlot;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattlePlotAssignment;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleResult;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleScore;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleTheme;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.statistics.MatchOutcome;
import com.ciaac.minecraft.minigames.statistics.PlayerResult;
import com.ciaac.minecraft.minigames.statistics.recording.MatchResultFactory;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Paper-facing orchestration for one dedicated Build Battle slot. It never
 * resets a world itself; the reset port is an explicit, validated boundary.
 */
public final class BuildBattlePaperController {
    public record Status(boolean enabled, boolean ready, BuildBattlePhase phase, int players, String messagePtPt) {}
    private record Participant(AdmissionRequest request, Player player, UUID sessionId, BuildBattlePlot plot) {}

    private final BuildBattlePaperSettings settings;
    private final SessionCoordinator sessions;
    private final SessionRegistry sessionRegistry;
    private final ProtectedRegionRegistry regions;
    private final RegionAdmissionRegistry admissions;
    private final TemporaryItemTagger items;
    private final Optional<BuildBattleResetPort> resetPort;
    private final Clock clock;
    private final StatisticsResultSink statistics;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private final Map<UUID, BuildBattleOperationId> eventOperations = new LinkedHashMap<>();
    private final Map<UUID, Integer> reviewIndexes = new HashMap<>();
    private BuildBattleMatch match;
    private UUID matchId;
    private Instant phaseDeadline;
    private Instant startedAt;
    private long operationSequence;
    private boolean internalTeleport;
    private boolean recoveryPending;
    private BuildBattleResetPort.ResetHandle pendingReset;
    private BuildBattleResult pendingResult;
    private Instant pendingResultStartedAt;
    private boolean pendingResetRecovery;
    private String pendingRecoveryReason;
    private Set<UUID> pendingRecoveryPlayers = Set.of();
    private boolean pendingRecoverySessionsRestored;

    public BuildBattlePaperController(
            BuildBattlePaperSettings settings,
            SessionCoordinator sessions,
            SessionRegistry sessionRegistry,
            ProtectedRegionRegistry regions,
            RegionAdmissionRegistry admissions,
            TemporaryItemTagger items,
            Optional<BuildBattleResetPort> resetPort,
            Clock clock) {
        this(settings, sessions, sessionRegistry, regions, admissions, items, resetPort, clock,
                StatisticsResultSink.unavailable());
    }

    public BuildBattlePaperController(
            BuildBattlePaperSettings settings,
            SessionCoordinator sessions,
            SessionRegistry sessionRegistry,
            ProtectedRegionRegistry regions,
            RegionAdmissionRegistry admissions,
            TemporaryItemTagger items,
            Optional<BuildBattleResetPort> resetPort,
            Clock clock,
            StatisticsResultSink statistics) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.items = Objects.requireNonNull(items, "items");
        this.resetPort = Objects.requireNonNull(resetPort, "resetPort");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    public synchronized Status status() {
        boolean ready = settings.enabled() && !recoveryPending && settings.themes().themes().size() >= 3
                && safeCommandInputs()
                && resetPort.isPresent() && resetPort.orElseThrow().available() && validGeometry()
                && (match == null || (match.phase() != BuildBattlePhase.RESETTING
                        && match.phase() != BuildBattlePhase.RECOVERING));
        String message = !settings.enabled() ? "O Build Battle está fechado."
                : recoveryPending ? "O Build Battle está fechado enquanto a recuperação da arena é revista."
                : settings.themes().themes().size() < 3 ? "O Build Battle está fechado: são precisos três temas válidos."
                : !safeCommandInputs() ? "O Build Battle está fechado: há IDs de tema ou parcela inválidos."
                : !ready ? "O Build Battle está fechado até a configuração e recuperação serem validadas."
                : match == null ? "O Build Battle está à procura de jogadores."
                : phaseMessage(match.phase());
        return new Status(settings.enabled(), ready, match == null ? BuildBattlePhase.IDLE : match.phase(), participants.size(), message);
    }

    public synchronized Optional<UUID> currentMatchId() {
        return Optional.ofNullable(matchId);
    }

    /** Queues a player; snapshots are taken only when the roster locks. */
    public synchronized AdmissionResult join(Player player, AdmissionRequest request) {
        Objects.requireNonNull(player, "player"); Objects.requireNonNull(request, "request");
        if (!settings.enabled()) return rejected("DISABLED", "O Build Battle está temporariamente fechado.");
        if (!status().ready()) return rejected("CONFIGURATION_UNAVAILABLE", "A arena ainda não está pronta.");
        if (!player.isOnline() || !player.isValid() || request.playerId() == null || !request.playerId().equals(player.getUniqueId()) || request.game() != GameKey.BUILD_BATTLE) {
            return rejected("PLAYER_INVALID", "Não foi possível validar a tua sessão.");
        }
        if (match == null) {
            if (recoveryPending || pendingReset != null || pendingResetRecovery
                    || pendingResult != null || !pendingRecoveryPlayers.isEmpty()
                    || pendingRecoveryReason != null) {
                return rejected("RECOVERY_PENDING",
                        "O Build Battle continua fechado até a recuperação anterior terminar.");
            }
            matchId = request.matchId();
            eventOperations.clear(); operationSequence = 0;
            match = new BuildBattleMatch(settings.config(), new ArrayList<>(settings.plots().keySet()));
            match.openWaiting(matchId);
            phaseDeadline = clock.instant().plus(settings.countdown());
        } else if (!matchId.equals(request.matchId())) {
            return rejected("MATCH_BUSY", "A arena já está reservada para outra partida.");
        } else if (match.phase() != BuildBattlePhase.WAITING) {
            return rejected("ROSTER_LOCKED", "A lista de jogadores já foi fechada para esta partida.");
        }
        if (!match.join(player.getUniqueId(), new BuildBattleOperationId(matchId, ++operationSequence))) {
            return rejected("ALREADY_QUEUED", "Já estás na fila do Build Battle.");
        }
        participants.put(player.getUniqueId(), new Participant(request, player, null, null));
        if (participants.size() >= settings.config().maximumPlayers()) phaseDeadline = clock.instant();
        return rejected("QUEUED", "Entraste na fila do Build Battle. Aguarda o início.");
    }

    public synchronized void leave(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Participant participant = participants.get(playerId);
        if (participant == null || match == null) return;
        if (match.phase() == BuildBattlePhase.WAITING) {
            if (!match.leave(playerId, new BuildBattleOperationId(matchId, ++operationSequence))) return;
            participants.remove(playerId);
            if (participants.isEmpty()) { match.cancelWaiting(); match = null; matchId = null; phaseDeadline = null; eventOperations.clear(); operationSequence = 0; }
            return;
        }
        recoverAll("PLAYER_LEFT");
    }

    public synchronized void tick(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!settings.enabled() || !validGeometry() || match == null) return;
        if (pendingReset != null) {
            pollPendingReset(now);
            return;
        }
        if (phaseDeadline == null || now.isBefore(phaseDeadline)) return;
        try {
            switch (match.phase()) {
                case WAITING -> {
                    if (participants.size() >= settings.config().minimumPlayers()) beginThemeVoting(now);
                    else phaseDeadline = now.plus(settings.countdown());
                }
                case THEME_VOTING -> {
                    BuildBattleTheme selected = match.lockTheme();
                    announceThemeLocked(selected);
                    startCountdown(now);
                }
                case COUNTDOWN -> activateBuilding(now);
                case BUILDING -> {
                    match.beginReview();
                    initializeReview();
                    phaseDeadline = now.plus(settings.reviewDuration());
                }
                case REVIEWING -> {
                    match.beginVoting();
                    sendVotingUi();
                    phaseDeadline = now.plus(settings.voteDuration());
                }
                // Strict voting never fills missing ballots with defaults: timeout is a no-contest.
                case VOTING -> recoverAll("VOTING_TIMEOUT");
                case RESETTING, RECOVERING -> { }
                default -> { }
            }
        } catch (RuntimeException failure) {
            if (match != null && match.phase() != BuildBattlePhase.IDLE && match.phase() != BuildBattlePhase.CLOSED) recoverAll("PHASE_FAILURE");
        }
    }

    public synchronized void startCountdown(Instant now) {
        if (match == null) throw new IllegalStateException("no Build Battle queue");
        match.beginCountdown();
        Duration countdown = settings.countdown().compareTo(Duration.ofSeconds(10)) > 0
                ? Duration.ofSeconds(10) : settings.countdown();
        phaseDeadline = Objects.requireNonNull(now).plus(countdown);
    }

    private void beginThemeVoting(Instant now) {
        long seed = matchId.getMostSignificantBits() ^ Long.rotateLeft(matchId.getLeastSignificantBits(), 17);
        match.beginThemeVoting(settings.themes().options(seed, 3));
        phaseDeadline = now.plus(settings.themeVoteDuration());
        sendThemeUi();
    }

    public synchronized void vote(UUID voter, BuildBattlePlot plot, int score, UUID eventId) {
        if (match == null || match.phase() != BuildBattlePhase.VOTING) throw new IllegalStateException("voting is not active");
        if (!participants.containsKey(voter)) throw new IllegalArgumentException("voter is not in this match");
        Objects.requireNonNull(plot, "plot");
        if (eventOperations.containsKey(Objects.requireNonNull(eventId, "eventId"))) return;
        BuildBattlePlot current = currentReviewPlot(voter);
        if (current != null && !current.equals(plot)) {
            throw new IllegalArgumentException("vote must follow the review order");
        }
        match.castVote(voter, plot, score, eventOperation(eventId));
        if (current != null) {
            List<BuildBattlePlot> targets = reviewTargets(voter);
            int next = reviewIndexes.getOrDefault(voter, 0) + 1;
            reviewIndexes.put(voter, next);
            if (next < targets.size()) {
                Participant participant = participants.get(voter);
                if (participant != null) {
                    admitReviewPlot(participant, targets.get(next));
                    teleport(participant.player(), settings.plots().get(targets.get(next)).spawn());
                    sendVotingUi(participant.player());
                }
            }
        }
        try {
            match.beginResults();
            finishResult();
        } catch (IllegalStateException incomplete) {
            if (match != null && match.phase() != BuildBattlePhase.VOTING
                    && match.phase() != BuildBattlePhase.IDLE && match.phase() != BuildBattlePhase.CLOSED) {
                recoverAll("RESULT_FAILURE");
            }
        }
    }

    /** Command-facing theme vote; event IDs make repeated clickable commands safe. */
    public synchronized void voteTheme(UUID voter, String themeId, UUID eventId) {
        if (match == null || match.phase() != BuildBattlePhase.THEME_VOTING) {
            throw new IllegalStateException("theme voting is not active");
        }
        Objects.requireNonNull(voter, "voter");
        Objects.requireNonNull(themeId, "themeId");
        Objects.requireNonNull(eventId, "eventId");
        if (eventOperations.containsKey(eventId)) return;
        BuildBattleTheme theme = match.themeOptions().stream()
                .filter(option -> option.id().equals(themeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("theme is not one of the offered options"));
        match.voteTheme(voter, theme, eventOperation(eventId));
        Participant participant = participants.get(voter);
        if (participant != null) participant.player().sendMessage(Component.text(
                "Voto registado: " + theme.displayName() + ".", NamedTextColor.GREEN));
    }

    public synchronized List<BuildBattleTheme> themeOptions() {
        return match == null ? List.of() : match.themeOptions();
    }

    public synchronized List<String> voteScoreOptions() {
        List<String> scores = new ArrayList<>();
        for (int score = settings.config().minimumVote(); score <= settings.config().maximumVote(); score++) {
            scores.add(Integer.toString(score));
        }
        return List.copyOf(scores);
    }

    public synchronized BuildBattlePlot currentReviewPlot(UUID playerId) {
        if (match == null || !participants.containsKey(Objects.requireNonNull(playerId, "playerId"))) return null;
        List<BuildBattlePlot> targets = reviewTargets(playerId);
        int index = reviewIndexes.getOrDefault(playerId, 0);
        return index >= 0 && index < targets.size() ? targets.get(index) : null;
    }

    /** Re-sends the bounded vote action for the current plot. */
    public synchronized void showCurrentReview(UUID playerId) {
        Participant participant = participants.get(Objects.requireNonNull(playerId, "playerId"));
        if (participant == null) throw new IllegalArgumentException("player is not in this match");
        BuildBattlePlot plot = currentReviewPlot(playerId);
        if (plot == null) throw new IllegalStateException("no plot is awaiting review");
        if (match.phase() == BuildBattlePhase.VOTING) sendVotingUi(participant.player());
        else participant.player().sendMessage(Component.text(
                "A observar o próximo lote: " + plot.id() + ".", NamedTextColor.AQUA));
    }

    /** True only for the owner of the registered plot region during building. */
    public synchronized boolean canBuild(Player player, Location location) {
        if (match == null || match.phase() != BuildBattlePhase.BUILDING || player == null || location == null
                || !sameWorld(location.getWorld(), settings.world())) return false;
        BuildBattlePlot plot = match.plotOf(player.getUniqueId());
        if (plot == null) return false;
        BuildBattlePaperSettings.PlotSettings configured = settings.plots().get(plot);
        Participant participant = participants.get(player.getUniqueId());
        if (configured == null || participant == null || participant.sessionId() == null) return false;
        return configured != null && regions.at(location).map(ProtectedRegion::id).filter(configured.regionId()::equals).isPresent()
                && admissions.find(player.getUniqueId(), configured.regionId()).map(token -> token.sessionId().equals(participant.sessionId()) && token.validAt(clock.instant())).orElse(false);
    }

    public synchronized boolean allowTeleport(Player player, Location destination) {
        if (internalTeleport || match == null || player == null) return true;
        return !participants.containsKey(player.getUniqueId()) || match.phase() == BuildBattlePhase.WAITING;
    }

    public synchronized boolean allowMove(Player player, Location destination) {
        if (match == null || player == null || !participants.containsKey(player.getUniqueId())) return true;
        return match.phase() == BuildBattlePhase.WAITING || canBuild(player, destination);
    }

    public synchronized void disconnect(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!participants.containsKey(playerId) || match == null) return;
        if (match.phase() == BuildBattlePhase.WAITING) {
            leave(playerId);
            return;
        }
        if (match.phase() != BuildBattlePhase.RECOVERING) {
            UUID eventId = UUID.nameUUIDFromBytes(("disconnect:" + matchId + ":" + playerId)
                    .getBytes(StandardCharsets.UTF_8));
            try {
                if (!eventOperations.containsKey(eventId)) {
                    match.disconnect(playerId, eventOperation(eventId));
                }
            } catch (IllegalStateException ignored) {
                // The recovery path below remains the single fail-closed outcome.
            }
        }
        recoverAll("DISCONNECT");
    }
    public synchronized void shutdown() { if (match != null) recoverAll("PLUGIN_DISABLED"); }

    private void activateBuilding(Instant now) {
        match.beginBuilding();
        startedAt = now;
        for (var assignment : match.assignments()) {
            Participant queued = participants.get(assignment.owner());
            if (queued == null || !queued.player().isOnline() || !queued.player().isValid()) throw new IllegalStateException("PLAYER_OFFLINE");
            AdmissionResult prepared = sessions.prepare(queued.request());
            if (prepared.status() != AdmissionStatus.PREPARED) throw new IllegalStateException(prepared.code());
            PlayerSession session = prepared.session().orElseThrow();
            BuildBattlePaperSettings.PlotSettings plot = settings.plots().get(assignment.plot());
            validatePlotRegion(plot.regionId());
            Instant expires = now.plus(settings.tokenLifetime());
            admissions.issue(new RegionAdmissionToken(
                    OperationIds.derive(queued.request().requestId(), "REGION_ADMISSION"),
                    session.sessionId(), queued.player().getUniqueId(), plot.regionId(), now, expires));
            teleport(queued.player(), plot.spawn());
            queued.player().getInventory().setItemInMainHand(items.tag(new ItemStack(Material.BRICKS), session.sessionId(), GameKey.BUILD_BATTLE));
            sessions.activate(session.sessionId(), OperationIds.derive(queued.request().requestId(), "GAME_ACTIVE"), now);
            participants.put(queued.player().getUniqueId(), new Participant(queued.request(), queued.player(), session.sessionId(), assignment.plot()));
        }
        phaseDeadline = now.plus(settings.buildDuration());
    }

    private void finishResult() {
        BuildBattleResult terminal = match.finalizeResult();
        UUID terminalMatchId = Objects.requireNonNull(matchId, "matchId");
        Instant terminalStartedAt = startedAt;
        match.beginResetting();
        if (settings.waitingSpawn() == null) { recoverAll("RESET_SPAWN_UNAVAILABLE"); return; }
        for (Participant participant : participants.values()) if (participant.sessionId() != null) teleport(participant.player(), settings.waitingSpawn());
        BuildBattleResetPort port = resetPort.filter(BuildBattleResetPort::available).orElse(null);
        if (port == null) { recoverAll("RESET_UNAVAILABLE"); return; }
        BuildBattleResetPort.ResetStart reset;
        try {
            reset = port.beginReset(terminalMatchId, settings.world());
        } catch (RuntimeException failure) {
            recoverAll("RESET_START_FAILED");
            return;
        }
        if (reset.completed().isPresent()) {
            if (!reset.completed().orElseThrow().successful()) { recoverAll("RESET_FAILED"); return; }
            completeResultAfterReset(terminal, terminalMatchId, terminalStartedAt);
            return;
        }
        pendingReset = reset.pending().orElseThrow();
        pendingResult = terminal;
        pendingResultStartedAt = terminalStartedAt;
        pendingResetRecovery = false;
        phaseDeadline = null;
    }

    private void completeResultAfterReset(BuildBattleResult terminal, UUID terminalMatchId, Instant terminalStartedAt) {
        boolean restored = true;
        for (Participant participant : participants.values()) {
            if (participant.sessionId() != null) {
                try {
                    restored &= sessions.finishAndRestore(participant.sessionId(),
                            OperationIds.derive(terminalMatchId, "RESULT_" + participant.player().getUniqueId()), "RESULT")
                            .status() == AdmissionStatus.RECOVERED;
                } catch (RuntimeException failure) {
                    restored = false;
                }
                admissions.revokeSession(participant.sessionId());
            }
        }
        if (!restored) { recoverAll("RESTORE_FAILED"); return; }
        recordResult(terminalMatchId, terminal, terminalStartedAt, clock.instant());
        match.completeReset();
        clearControllerState();
    }

    private void recoverAll(String reason) {
        if (match == null || matchId == null) return;
        UUID recoveryMatchId = matchId;
        Set<UUID> recoveryPlayers = new LinkedHashSet<>(participants.keySet());
        recoveryPlayers.addAll(match.roster());
        recoveryPlayers.addAll(pendingRecoveryPlayers);
        Set<PlayerSession> sessionsForMatch = sessionRegistry.findByMatch(recoveryMatchId);
        sessionsForMatch.forEach(session -> recoveryPlayers.add(session.playerId()));
        if (match.phase() != BuildBattlePhase.RECOVERING && match.phase() != BuildBattlePhase.IDLE && match.phase() != BuildBattlePhase.CLOSED) match.beginRecovery();
        boolean restored = pendingRecoveryReason == null ? true : pendingRecoverySessionsRestored;
        if (startedAt != null && settings.waitingSpawn() != null) {
            for (Participant participant : participants.values()) {
                if (participant.sessionId() != null && participant.player().isOnline() && participant.player().isValid()) {
                    try {
                        teleport(participant.player(), settings.waitingSpawn());
                    } catch (RuntimeException failedTeleport) {
                        restored = false;
                    }
                }
            }
        }
        for (PlayerSession session : sessionsForMatch) {
            if (session.phase() == SessionPhase.QUARANTINED) restored = false;
            if (!session.phase().terminal()) {
                try {
                    restored &= sessions.recover(session.sessionId(), OperationIds.derive(recoveryMatchId, "RECOVER_" + session.playerId()), reason)
                            .status() == AdmissionStatus.RECOVERED;
                } catch (RuntimeException failure) {
                    restored = false;
                }
            }
            admissions.revokeSession(session.sessionId());
        }
        pendingRecoveryPlayers = Set.copyOf(recoveryPlayers);
        pendingRecoveryReason = reason;
        pendingRecoverySessionsRestored = restored;
        pendingResult = null;
        pendingResultStartedAt = null;
        pendingResetRecovery = true;
        if (pendingReset != null) {
            recoveryPending = !restored;
            phaseDeadline = null;
            return;
        }
        if (!needsTemplateReset()) {
            completeRecoveryAfterReset();
            return;
        }
        BuildBattleResetPort port = resetPort.filter(BuildBattleResetPort::available).orElse(null);
        if (port == null) {
            recoveryPending = true;
            phaseDeadline = null;
            return;
        }
        BuildBattleResetPort.ResetStart reset;
        try {
            reset = port.beginReset(recoveryMatchId, settings.world());
        } catch (RuntimeException failure) {
            recoveryPending = true;
            phaseDeadline = null;
            return;
        }
        if (reset.completed().isPresent()) {
            if (!reset.completed().orElseThrow().successful()) {
                recoveryPending = true;
                phaseDeadline = null;
                return;
            }
            completeRecoveryAfterReset();
            return;
        }
        pendingReset = reset.pending().orElseThrow();
        recoveryPending = !restored;
        phaseDeadline = null;
    }

    private void pollPendingReset(Instant now) {
        BuildBattleResetPort port = resetPort.filter(BuildBattleResetPort::available).orElse(null);
        if (port == null) {
            pendingReset = null;
            if (pendingResetRecovery) {
                recoveryPending = true;
                phaseDeadline = null;
            } else {
                recoverAll("RESET_UNAVAILABLE");
            }
            return;
        }
        BuildBattleResetPort.ResetProgress progress;
        try {
            progress = port.pollReset(pendingReset);
        } catch (RuntimeException failure) {
            pendingReset = null;
            if (pendingResetRecovery) {
                recoveryPending = true;
                phaseDeadline = null;
            } else {
                recoverAll("RESET_POLL_FAILED");
            }
            return;
        }
        if (!progress.complete()) return;
        if (!progress.successful()) {
            pendingReset = null;
            if (pendingResetRecovery) {
                recoveryPending = true;
                phaseDeadline = null;
            } else {
                recoverAll("RESET_FAILED");
            }
            return;
        }
        pendingReset = null;
        if (pendingResetRecovery) {
            completeRecoveryAfterReset();
        } else {
            BuildBattleResult result = Objects.requireNonNull(pendingResult, "pending result");
            Instant resultStartedAt = Objects.requireNonNull(pendingResultStartedAt, "pending result start");
            pendingResult = null;
            pendingResultStartedAt = null;
            completeResultAfterReset(result, Objects.requireNonNull(matchId, "matchId"), resultStartedAt);
        }
    }

    private void completeRecoveryAfterReset() {
        if (!pendingRecoverySessionsRestored) {
            recoveryPending = true;
            phaseDeadline = null;
            return;
        }
        UUID recoveryMatchId = Objects.requireNonNull(matchId, "matchId");
        if (match != null && match.phase() == BuildBattlePhase.RECOVERING) match.completeRecovery();
        if (!pendingRecoveryPlayers.isEmpty()) {
            statistics.record(MatchResultFactory.noContest(recoveryMatchId, GameKey.BUILD_BATTLE,
                    "build-battle-v1", "solo", startedAt, clock.instant(),
                    pendingRecoveryReason == null ? "RECOVERY" : pendingRecoveryReason,
                    pendingRecoveryPlayers));
        }
        recoveryPending = false;
        clearControllerState();
    }

    private void recordResult(UUID terminalMatchId, BuildBattleResult terminal, Instant terminalStartedAt,
                              Instant finishedAt) {
        Map<BuildBattlePlot, BuildBattleScore> scores = terminal.scores();
        List<BuildBattlePlotAssignment> ordered = new ArrayList<>(terminal.assignments());
        ordered.sort(Comparator.comparing(BuildBattlePlotAssignment::plot, (left, right) -> {
            BuildBattleScore leftScore = scores.get(left);
            BuildBattleScore rightScore = scores.get(right);
            int average = Long.compare(rightScore.total() * (long) leftScore.votes(),
                    leftScore.total() * (long) rightScore.votes());
            if (average != 0) return average;
            int total = Long.compare(rightScore.total(), leftScore.total());
            return total != 0 ? total : left.id().compareTo(right.id());
        }));
        Map<UUID, PlayerResult> standings = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            BuildBattlePlotAssignment assignment = ordered.get(index);
            BuildBattleScore score = scores.get(assignment.plot());
            boolean winner = assignment.plot().equals(terminal.winner());
            standings.put(assignment.owner(), MatchResultFactory.standing(index + 1, winner, false, null,
                    Map.of("wins", winner ? 1L : 0L,
                            "score", MatchResultFactory.bounded(score.total()),
                            "votes", MatchResultFactory.bounded(score.votes()))));
        }
        statistics.record(MatchResultFactory.create(terminalMatchId, GameKey.BUILD_BATTLE,
                "build-battle-v1", "solo", terminalStartedAt, finishedAt,
                MatchOutcome.VICTORY, "RESULT", standings));
    }

    private void clearControllerState() {
        participants.clear();
        match = null;
        matchId = null;
        phaseDeadline = null;
        startedAt = null;
        eventOperations.clear();
        reviewIndexes.clear();
        operationSequence = 0;
        recoveryPending = false;
        pendingReset = null;
        pendingResult = null;
        pendingResultStartedAt = null;
        pendingResetRecovery = false;
        pendingRecoveryReason = null;
        pendingRecoveryPlayers = Set.of();
        pendingRecoverySessionsRestored = false;
    }

    private void teleport(Player player, Location destination) { internalTeleport = true; try { if (!player.teleport(destination.clone())) throw new IllegalStateException("TELEPORT_FAILED"); } finally { internalTeleport = false; } }
    private BuildBattleOperationId eventOperation(UUID eventId) { Objects.requireNonNull(eventId); return eventOperations.computeIfAbsent(eventId, ignored -> new BuildBattleOperationId(matchId, ++operationSequence)); }
    private boolean validGeometry() {
        if (settings.world() == null || settings.waitingSpawn() == null) return false;
        try {
            validatePlotRegion(settings.participantRegionId());
            for (var plot : settings.plots().values()) validatePlotRegion(plot.regionId());
            return sameWorld(settings.waitingSpawn().getWorld(), settings.world());
        } catch (RuntimeException invalid) { return false; }
    }
    private boolean safeCommandInputs() {
        return settings.themes().themes().stream().allMatch(theme -> theme.id().matches("[a-z0-9][a-z0-9_.-]{0,63}"))
                && settings.plots().keySet().stream().allMatch(plot -> plot.id().matches("[a-z0-9][a-z0-9_.-]{0,31}"));
    }
    private boolean needsTemplateReset() {
        return startedAt != null && match != null && switch (match.phase()) {
            case BUILDING, REVIEWING, VOTING, RESULTS, RESETTING, RECOVERING -> true;
            default -> false;
        };
    }
    private void validatePlotRegion(String id) { ProtectedRegion region = regions.find(id).orElseThrow(() -> new IllegalStateException("missing Build Battle region " + id)); if (region.game() != GameKey.BUILD_BATTLE || !region.requiresAdmission()) throw new IllegalStateException("invalid Build Battle region " + id); }
    private static boolean sameWorld(World actual, World expected) {
        return actual != null && expected != null && actual.getUID().equals(expected.getUID())
                && actual.getName().equals(expected.getName());
    }
    private static String phaseMessage(BuildBattlePhase phase) {
        return switch (phase) {
            case WAITING -> "O Build Battle está a formar uma fila.";
            case THEME_VOTING -> "Escolhe um dos três temas apresentados.";
            case COUNTDOWN -> "A fila está fechada; prepara-te para construir.";
            case BUILDING -> "A construção está a decorrer na tua parcela.";
            case REVIEWING -> "Está a começar a visita às parcelas.";
            case VOTING -> "Avalia cada parcela dentro da escala configurada, sem votar na tua.";
            case RESULTS -> "Os resultados estão a ser preparados.";
            case RESETTING -> "A arena está a ser restaurada.";
            case RECOVERING -> "A partida foi cancelada e está em recuperação.";
            case IDLE -> "O Build Battle está à procura de jogadores.";
            case CLOSED -> "O Build Battle está fechado.";
        };
    }
    private static AdmissionResult rejected(String code, String message) { return AdmissionResult.rejected(code, message); }

    private void initializeReview() {
        reviewIndexes.clear();
        for (Participant participant : participants.values()) {
            if (!participant.player().isOnline() || !participant.player().isValid()) {
                throw new IllegalStateException("PLAYER_OFFLINE");
            }
            List<BuildBattlePlot> targets = reviewTargets(participant.player().getUniqueId());
            if (targets.isEmpty()) throw new IllegalStateException("NO_REVIEW_TARGETS");
            reviewIndexes.put(participant.player().getUniqueId(), 0);
            admitReviewPlot(participant, targets.get(0));
            teleport(participant.player(), settings.plots().get(targets.get(0)).spawn());
            participant.player().sendMessage(Component.text(
                    "A construção terminou. Vais visitar todas as parcelas dos outros jogadores.",
                    NamedTextColor.AQUA));
        }
    }

    private void admitReviewPlot(Participant participant, BuildBattlePlot plot) {
        if (participant.sessionId() == null) throw new IllegalStateException("SESSION_UNAVAILABLE");
        BuildBattlePaperSettings.PlotSettings target = settings.plots().get(plot);
        if (target == null) throw new IllegalStateException("PLOT_UNAVAILABLE");
        Instant now = clock.instant();
        admissions.issue(new RegionAdmissionToken(UUID.randomUUID(), participant.sessionId(),
                participant.player().getUniqueId(), target.regionId(), now,
                now.plus(settings.tokenLifetime())));
    }

    private List<BuildBattlePlot> reviewTargets(UUID playerId) {
        if (match == null) return List.of();
        return match.assignments().stream()
                .filter(assignment -> !assignment.owner().equals(playerId))
                .map(BuildBattlePlotAssignment::plot)
                .toList();
    }

    private void sendThemeUi() {
        if (match == null) return;
        for (Participant participant : participants.values()) {
            if (!participant.player().isOnline()) continue;
            participant.player().sendMessage(Component.text(
                    "Tema: escolhe uma opção (a votação fecha automaticamente).", NamedTextColor.GOLD));
            for (BuildBattleTheme theme : match.themeOptions()) {
                String command = "/buildbattle tema " + theme.id();
                participant.player().sendMessage(Component.text("[" + theme.displayName() + "]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand(command)));
            }
        }
    }

    private void announceThemeLocked(BuildBattleTheme theme) {
        for (Participant participant : participants.values()) {
            if (participant.player().isOnline()) participant.player().sendMessage(Component.text(
                    "Tema escolhido: " + theme.displayName() + ".", NamedTextColor.GOLD));
        }
    }

    private void sendVotingUi() {
        for (Participant participant : participants.values()) sendVotingUi(participant.player());
    }

    private void sendVotingUi(Player player) {
        if (match == null || player == null || !player.isOnline()) return;
        BuildBattlePlot plot = currentReviewPlot(player.getUniqueId());
        if (plot == null) return;
        player.sendMessage(Component.text("Avalia a parcela " + plot.id() + ":", NamedTextColor.AQUA));
        for (int score = settings.config().minimumVote(); score <= settings.config().maximumVote(); score++) {
            String command = "/buildbattle avaliar " + plot.id() + " " + score;
            player.sendMessage(Component.text("[" + score + "]", NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand(command)));
        }
    }
}
