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
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.event.inventory.InventoryCreativeEvent;
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
    private final Predicate<PlayerSession> restorationReady;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private final Set<UUID> departingPlayers = new LinkedHashSet<>();
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
    private boolean pendingResetComplete;
    private boolean pendingResetFailed;
    private UUID pendingRecoveryRoot;
    private String pendingRecoveryReason;
    private Set<UUID> pendingRecoveryPlayers = Set.of();

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
        this(settings, sessions, sessionRegistry, regions, admissions, items, resetPort, clock,
                statistics, session -> false);
    }

    /** Runtime restoration gate; existing constructors remain fail-closed. */
    public BuildBattlePaperController(
            BuildBattlePaperSettings settings,
            SessionCoordinator sessions,
            SessionRegistry sessionRegistry,
            ProtectedRegionRegistry regions,
            RegionAdmissionRegistry admissions,
            TemporaryItemTagger items,
            Optional<BuildBattleResetPort> resetPort,
            Clock clock,
            StatisticsResultSink statistics,
            Predicate<PlayerSession> restorationReady) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.items = Objects.requireNonNull(items, "items");
        this.resetPort = Objects.requireNonNull(resetPort, "resetPort");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.restorationReady = Objects.requireNonNull(restorationReady, "restorationReady");
    }

    public synchronized Status status() {
        boolean ready = settings.enabled() && !recoveryPending && settings.themes().themes().size() >= 3
                && safeCommandInputs()
                && resetPort.isPresent() && resetPort.orElseThrow().available() && validGeometry()
                && (match == null || match.phase() == BuildBattlePhase.WAITING);
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

    /** Reads the owned phase without re-running world readiness scans. */
    public synchronized Optional<BuildBattlePhase> phaseForMatch(UUID expectedMatchId) {
        return match != null && Objects.equals(matchId, expectedMatchId)
                ? Optional.of(match.phase()) : Optional.empty();
    }

    /** Queues a player; snapshots are taken only when the roster locks. */
    public synchronized AdmissionResult join(Player player, AdmissionRequest request) {
        Objects.requireNonNull(player, "player"); Objects.requireNonNull(request, "request");
        if (!settings.enabled()) return rejected("DISABLED", "O Build Battle está temporariamente fechado.");
        if (recoveryPending || pendingReset != null || pendingResetFailed
                || pendingResult != null || !pendingRecoveryPlayers.isEmpty()
                || pendingRecoveryReason != null) {
            return rejected("RECOVERY_PENDING",
                    "O Build Battle continua fechado até a recuperação anterior terminar.");
        }
        if (match != null && match.phase() != BuildBattlePhase.WAITING) {
            return rejected(match.phase() == BuildBattlePhase.RECOVERING
                            || match.phase() == BuildBattlePhase.RESETTING
                            ? "RECOVERY_PENDING" : "ROSTER_LOCKED",
                    "A lista de jogadores já foi fechada para esta partida.");
        }
        if (!status().ready()) return rejected("CONFIGURATION_UNAVAILABLE", "A arena ainda não está pronta.");
        if (!player.isOnline() || !player.isValid() || request.playerId() == null || !request.playerId().equals(player.getUniqueId()) || request.game() != GameKey.BUILD_BATTLE) {
            return rejected("PLAYER_INVALID", "Não foi possível validar a tua sessão.");
        }
        if (match == null) {
            matchId = request.matchId();
            eventOperations.clear(); operationSequence = 0;
            match = new BuildBattleMatch(settings.config(), new ArrayList<>(settings.plots().keySet()));
            match.openWaiting(matchId);
            phaseDeadline = clock.instant().plus(settings.countdown());
        } else if (!matchId.equals(request.matchId())) {
            return rejected("MATCH_BUSY", "A arena já está reservada para outra partida.");
        }
        if (!match.join(player.getUniqueId(), new BuildBattleOperationId(matchId, ++operationSequence))) {
            return rejected("ALREADY_QUEUED", "Já estás na fila do Build Battle.");
        }
        participants.put(player.getUniqueId(), new Participant(request, player, null, null));
        if (participants.size() >= settings.config().maximumPlayers()) phaseDeadline = clock.instant();
        return rejected("QUEUED", "Entraste na fila do Build Battle. Aguarda o início.");
    }

    public synchronized AdmissionResult leave(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Participant participant = participants.get(playerId);
        if (participant == null || match == null) return rejected("NOT_PARTICIPATING", "Não estás numa partida do Build Battle.");
        if (match.phase() == BuildBattlePhase.WAITING) {
            if (!match.leave(playerId, new BuildBattleOperationId(matchId, ++operationSequence)) ) {
                return rejected("NOT_PARTICIPATING", "Não estás na fila do Build Battle.");
            }
            participants.remove(playerId);
            if (participants.isEmpty()) { match.cancelWaiting(); match = null; matchId = null; phaseDeadline = null; eventOperations.clear(); operationSequence = 0; }
            return new AdmissionResult(AdmissionStatus.RECOVERED, "LEFT", "Saíste da fila do Build Battle.", Optional.empty());
        }
        UUID leavingMatchId = matchId;
        recoverAll("PLAYER_LEFT");
        PlayerSession session = sessionRegistry.findByPlayer(playerId)
                .filter(value -> value.matchId().equals(leavingMatchId)).orElse(null);
        if (session == null) {
            return new AdmissionResult(AdmissionStatus.RECOVERED, "LEFT", "Saíste do Build Battle; o teu estado não foi alterado.", Optional.empty());
        }
        if (session.phase() == SessionPhase.CLOSED) {
            return new AdmissionResult(AdmissionStatus.RECOVERED, "LEFT", "Saíste do Build Battle e o teu estado foi restaurado.", Optional.of(session));
        }
        if (session.phase() == SessionPhase.QUARANTINED) {
            return new AdmissionResult(AdmissionStatus.QUARANTINED, "RECOVERY_PENDING",
                    "Saíste da partida; o teu estado continua protegido enquanto o restauro é revisto.", Optional.of(session));
        }
        return new AdmissionResult(AdmissionStatus.REJECTED, "RECOVERY_PENDING",
                "Saíste da partida; o teu estado continua protegido enquanto o restauro termina.", Optional.of(session));
    }

    public synchronized void tick(Instant now) {
        Objects.requireNonNull(now, "now");
        if (pendingReset != null && !pendingResetFailed) {
            pollPendingReset(now);
            return;
        }
        if (recoveryPending || pendingResult != null || pendingResetFailed) {
            if (pendingResetFailed) return;
            if (!pendingResetComplete) startPendingReset();
            retryPendingRecovery();
            return;
        }
        if (!settings.enabled() || !validGeometry() || match == null) return;
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
    /** Accepts only fresh, ordinary construction blocks from the native creative inventory. */
    public synchronized void onCreativeInventory(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null || participant.sessionId() == null) return;
        if (event.isCancelled()) return;
        PlayerSession session = sessionRegistry.findById(participant.sessionId()).orElse(null);
        if (participant.player() != player || session == null || session.phase() != SessionPhase.ACTIVE
                || !restorationReady.test(session) || !canBuild(player, player.getLocation())
                || event.getRawSlot() < 9 || event.getRawSlot() > 45) {
            event.setCancelled(true);
            return;
        }
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.isEmpty()) return;
        if (!BuildBattleBlockPolicy.allows(cursor.getType())) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Escolhe blocos de construção sem inventários, líquidos ou mecanismos.", NamedTextColor.RED));
            return;
        }
        event.setCursor(items.tag(new ItemStack(cursor.getType(), Math.min(64, Math.max(1, cursor.getAmount()))),
                participant.sessionId(), GameKey.BUILD_BATTLE));
    }

    private static void stopBuildingAbilities(Player player) {
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setGameMode(GameMode.ADVENTURE);
    }

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
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null || match.phase() == BuildBattlePhase.WAITING) return true;
        if ((match.phase() != BuildBattlePhase.RECOVERING && match.phase() != BuildBattlePhase.RESETTING)
                || participant.sessionId() == null || matchId == null) {
            return false;
        }
        PlayerSession session = sessionRegistry.findById(participant.sessionId()).orElse(null);
        return session != null && session.game() == GameKey.BUILD_BATTLE
                && session.matchId().equals(matchId)
                && session.playerId().equals(player.getUniqueId())
                && (session.phase() == SessionPhase.RECOVERING || session.phase() == SessionPhase.RESTORING);
    }

    public synchronized boolean allowMove(Player player, Location destination) {
        if (match == null || player == null || !participants.containsKey(player.getUniqueId())) return true;
        if (match.phase() == BuildBattlePhase.WAITING) return true;
        if (match.phase() == BuildBattlePhase.BUILDING) return canBuild(player, destination);
        if (destination == null || !sameWorld(destination.getWorld(), settings.world())) return false;
        Participant participant = participants.get(player.getUniqueId());
        String regionId;
        if (match.phase() == BuildBattlePhase.REVIEWING || match.phase() == BuildBattlePhase.VOTING) {
            BuildBattlePlot plot = currentReviewPlot(player.getUniqueId());
            if (plot == null) return false;
            regionId = settings.plots().get(plot).regionId();
        } else if (match.phase() == BuildBattlePhase.RESETTING || match.phase() == BuildBattlePhase.RECOVERING) {
            regionId = settings.participantRegionId();
        } else return false;
        return participant.sessionId() != null && regions.find(regionId).filter(region -> region.bounds().contains(destination)).isPresent()
                && admissions.permits(player.getUniqueId(), participant.sessionId(), regionId, clock.instant());
    }

    public synchronized void disconnect(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!participants.containsKey(playerId) || match == null) return;
        departingPlayers.add(playerId);
        if (match.phase() == BuildBattlePhase.WAITING) {
            leave(playerId);
            departingPlayers.remove(playerId);
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
            sessions.activate(session.sessionId(), OperationIds.derive(queued.request().requestId(), "GAME_ACTIVE"), clock.instant());
            participants.put(queued.player().getUniqueId(), new Participant(queued.request(), queued.player(), session.sessionId(), assignment.plot()));
            queued.player().setGameMode(GameMode.CREATIVE);
        }
        phaseDeadline = now.plus(settings.buildDuration());
    }

    private void finishResult() {
        BuildBattleResult terminal = match.finalizeResult();
        UUID terminalMatchId = Objects.requireNonNull(matchId, "matchId");
        Instant terminalStartedAt = startedAt;
        match.beginResetting();
        pendingResult = terminal;
        pendingResultStartedAt = terminalStartedAt;
        pendingResetComplete = false;
        pendingRecoveryRoot = terminalMatchId;
        pendingRecoveryReason = "RESULT";
        pendingRecoveryPlayers = Set.copyOf(participants.keySet());
        recoveryPending = true;
        participants.values().forEach(participant -> {
            if (participant.sessionId() != null) admissions.revokeSession(participant.sessionId());
        });
        if (settings.waitingSpawn() != null) {
            for (Participant participant : participants.values()) {
                if (participant.sessionId() != null) moveToWaiting(participant);
            }
        }
        phaseDeadline = null;
        startPendingReset();
    }

    private void recoverAll(String reason) {
        if (match == null || matchId == null) return;
        UUID recoveryMatchId = matchId;
        if (pendingRecoveryRoot == null) {
            Set<UUID> recoveryPlayers = new LinkedHashSet<>(participants.keySet());
            recoveryPlayers.addAll(match.roster());
            sessionRegistry.findByMatch(recoveryMatchId).forEach(session -> recoveryPlayers.add(session.playerId()));
            pendingRecoveryPlayers = Set.copyOf(recoveryPlayers);
            pendingRecoveryRoot = recoveryMatchId;
            pendingRecoveryReason = Objects.requireNonNull(reason, "reason");
            pendingResult = null;
            pendingResultStartedAt = null;
            pendingResetComplete = false;
            pendingResetFailed = false;
        }
        recoveryPending = true;
        if (pendingResult == null && match.phase() != BuildBattlePhase.RECOVERING
                && match.phase() != BuildBattlePhase.RESETTING && match.phase() != BuildBattlePhase.IDLE
                && match.phase() != BuildBattlePhase.CLOSED) match.beginRecovery();
        participants.values().forEach(participant -> {
            if (participant.sessionId() != null) admissions.revokeSession(participant.sessionId());
        });
        if (startedAt != null && settings.waitingSpawn() != null) {
            for (Participant participant : participants.values()) {
                if (participant.sessionId() != null && participant.player().isOnline() && participant.player().isValid()) {
                    try {
                        moveToWaiting(participant);
                    } catch (RuntimeException ignored) { /* Recovery remains pending and movement stays blocked. */ }
                }
            }
        }
        phaseDeadline = null;
        if (pendingReset == null && !pendingResetComplete && !pendingResetFailed) startPendingReset();
        retryPendingRecovery();
    }

    private void startPendingReset() {
        if (pendingResetComplete || pendingResetFailed || pendingReset != null) return;
        UUID root = pendingRecoveryRoot;
        if (root == null || !needsTemplateReset()) {
            pendingResetComplete = true;
            retryPendingRecovery();
            return;
        }
        BuildBattleResetPort port = resetPort.filter(BuildBattleResetPort::available).orElse(null);
        if (port == null) return;
        try {
            BuildBattleResetPort.ResetStart reset = port.beginReset(root, settings.world());
            if (reset.completed().isPresent()) {
                if (reset.completed().orElseThrow().successful()) pendingResetComplete = true;
                else pendingResetFailed = true;
            } else pendingReset = reset.pending().orElseThrow();
        } catch (RuntimeException ambiguousStart) {
            // A throwing begin may have started mutation; do not start a duplicate reset.
            pendingResetFailed = true;
        }
    }

    private void pollPendingReset(Instant now) {
        BuildBattleResetPort port = resetPort.filter(BuildBattleResetPort::available).orElse(null);
        if (port == null) return;
        BuildBattleResetPort.ResetProgress progress;
        try {
            progress = port.pollReset(pendingReset);
        } catch (RuntimeException failure) {
            return;
        }
        if (!progress.complete()) return;
        if (!progress.successful()) {
            pendingResetFailed = true;
            return;
        }
        pendingReset = null;
        pendingResetComplete = true;
        retryPendingRecovery();
    }

    private void retryPendingRecovery() {
        if (!recoveryPending || !pendingResetComplete || pendingResetFailed || pendingReset != null) return;
        UUID recoveryMatchId = Objects.requireNonNull(matchId, "matchId");
        boolean allClosed = true;
        for (PlayerSession session : sessionRegistry.findByMatch(recoveryMatchId)) {
            admissions.revokeSession(session.sessionId());
            if (session.phase() == SessionPhase.CLOSED) continue;
            allClosed = false;
            if (session.phase() == SessionPhase.QUARANTINED || departingPlayers.contains(session.playerId())) continue;
            boolean ready;
            try { ready = restorationReady.test(session); }
            catch (RuntimeException unavailable) { ready = false; }
            if (!ready) continue;
            try {
                if (pendingResult != null) {
                    sessions.finishAndRestore(session.sessionId(),
                            OperationIds.derive(pendingRecoveryRoot, "RESULT_" + session.playerId()), "RESULT");
                } else {
                    sessions.recover(session.sessionId(),
                            OperationIds.derive(pendingRecoveryRoot, "RECOVER_" + session.playerId()), pendingRecoveryReason);
                }
            } catch (RuntimeException ignored) { /* Retry with the same operation ID on tick. */ }
        }
        allClosed = sessionRegistry.findByMatch(recoveryMatchId).stream().allMatch(s -> s.phase() == SessionPhase.CLOSED);
        if (!allClosed) return;
        if (pendingResult != null) {
            recordResult(recoveryMatchId, pendingResult, Objects.requireNonNull(pendingResultStartedAt), clock.instant());
            if (match != null && match.phase() == BuildBattlePhase.RESETTING) match.completeReset();
        } else {
            if (match != null && match.phase() == BuildBattlePhase.RECOVERING) match.completeRecovery();
            if (!pendingRecoveryPlayers.isEmpty()) statistics.record(MatchResultFactory.noContest(recoveryMatchId,
                    GameKey.BUILD_BATTLE, "build-battle-v1", "solo", startedAt, clock.instant(),
                    pendingRecoveryReason == null ? "RECOVERY" : pendingRecoveryReason, pendingRecoveryPlayers));
        }
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
        pendingResetComplete = false;
        pendingResetFailed = false;
        pendingRecoveryRoot = null;
        pendingRecoveryReason = null;
        pendingRecoveryPlayers = Set.of();
        departingPlayers.clear();
    }

    private void teleport(Player player, Location destination) { internalTeleport = true; try { if (!player.teleport(destination.clone())) throw new IllegalStateException("TELEPORT_FAILED"); } finally { internalTeleport = false; } }
    private void moveToWaiting(Participant participant) {
        if (participant == null || participant.player() == null || participant.sessionId() == null
                || match == null || matchId == null) return;
        Player player = participant.player();
        UUID playerId = player.getUniqueId();
        if (departingPlayers.contains(playerId) || participants.get(playerId) != participant) return;
        PlayerSession session = sessionRegistry.findById(participant.sessionId()).orElse(null);
        if (session == null || session.game() != GameKey.BUILD_BATTLE
                || !session.matchId().equals(matchId) || !session.playerId().equals(playerId)
                || session.phase() != SessionPhase.ACTIVE) return;

        stopBuildingAbilities(player);
        Instant now = clock.instant();
        admissions.revokeSession(participant.sessionId());
        admissions.issue(new RegionAdmissionToken(UUID.randomUUID(), participant.sessionId(),
                playerId, settings.participantRegionId(), now,
                now.plus(settings.tokenLifetime())));
        teleport(player, settings.waitingSpawn());
    }
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
            stopBuildingAbilities(participant.player());
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
        admissions.revokeSession(participant.sessionId());
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
