package com.ciaac.minecraft.minigames.paper.hotpotato;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.hotpotato.HotPotatoGame;
import com.ciaac.minecraft.minigames.hotpotato.HotPotatoMetrics;
import com.ciaac.minecraft.minigames.hotpotato.HotPotatoPhase;
import com.ciaac.minecraft.minigames.hotpotato.MatchResult;
import com.ciaac.minecraft.minigames.hotpotato.OperationId;
import com.ciaac.minecraft.minigames.hotpotato.PassIntent;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Disabled-by-default Paper adapter for one dedicated-world Hot Potato game. */
public final class HotPotatoPaperController {
    public record Status(boolean enabled, HotPotatoPhase phase, int players, String messagePtPt) {}
    private record Participant(AdmissionRequest request, Player player, UUID sessionId) {}
    private final HotPotatoPaperSettings settings;
    private final SessionCoordinator sessions;
    private final SessionRegistry sessionRegistry;
    private final ProtectedRegionRegistry regions;
    private final RegionAdmissionRegistry admissions;
    private final TemporaryItemTagger items;
    private final Clock clock;
    private final StatisticsResultSink statistics;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private final Map<UUID, OperationId> eventOperations = new LinkedHashMap<>();
    private HotPotatoGame game;
    private UUID matchId;
    private Instant phaseDeadline;
    private Instant startedAt;
    private Instant nextHudRefresh;
    private UUID presentedCarrier;
    private long operationSequence;
    private boolean internalTeleport;

    public HotPotatoPaperController(HotPotatoPaperSettings settings, SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                    ProtectedRegionRegistry regions, RegionAdmissionRegistry admissions, TemporaryItemTagger items, Clock clock) {
        this(settings, sessions, sessionRegistry, regions, admissions, items, clock, StatisticsResultSink.unavailable());
    }

    public HotPotatoPaperController(HotPotatoPaperSettings settings, SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                    ProtectedRegionRegistry regions, RegionAdmissionRegistry admissions, TemporaryItemTagger items,
                                    Clock clock, StatisticsResultSink statistics) {
        this.settings = Objects.requireNonNull(settings, "settings"); this.sessions = Objects.requireNonNull(sessions, "sessions"); this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry"); this.regions = Objects.requireNonNull(regions, "regions"); this.admissions = Objects.requireNonNull(admissions, "admissions"); this.items = Objects.requireNonNull(items, "items"); this.clock = Objects.requireNonNull(clock, "clock");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    public synchronized Status status() {
        HotPotatoPhase phase = game == null ? HotPotatoPhase.IDLE : game.phase();
        String message = !settings.enabled() ? "A Batata Quente está fechada."
                : game == null ? "A Batata Quente está à procura de jogadores."
                : "Batata Quente: " + phasePtPt(phase) + ".";
        return new Status(settings.enabled(), phase, participants.size(), message);
    }

    private static String phasePtPt(HotPotatoPhase phase) {
        return switch (phase) {
            case DISABLED -> "fechada";
            case IDLE, WAITING -> "à espera de jogadores";
            case COUNTDOWN -> "a começar";
            case ENTRY_LOCKED -> "entradas fechadas";
            case RUNNING -> "ronda em curso";
            case SUDDEN_DEATH -> "morte súbita";
            case FINISHING -> "a terminar";
            case RESTORING -> "a restaurar os jogadores";
            case RECOVERING -> "em recuperação segura";
            case CLOSED -> "terminada";
        };
    }

    public synchronized AdmissionResult join(Player player, AdmissionRequest request) {
        Objects.requireNonNull(player, "player"); Objects.requireNonNull(request, "request");
        if (!settings.enabled()) return rejected("DISABLED", "A Batata Quente está temporariamente fechada.");
        if (!validGeometry() || !player.isOnline() || !player.isValid() || request.playerId() != null && !request.playerId().equals(player.getUniqueId()) || request.game() != GameKey.HOT_POTATO) return rejected("CONFIGURATION_UNAVAILABLE", "A arena ainda não está pronta.");
        if (participants.containsKey(player.getUniqueId())) return rejected("ALREADY_QUEUED", "Já estás na fila da Batata Quente.");
        if (game == null) { matchId = request.matchId(); eventOperations.clear(); operationSequence = 0; game = new HotPotatoGame(matchId, settings.config(), new Random(matchId.getMostSignificantBits() ^ matchId.getLeastSignificantBits())); game.enable(nextOperation()); game.openQueue(nextOperation()); }
        if (!matchId.equals(request.matchId()) || participants.size() >= settings.config().maximumPlayers()) return rejected("FULL", "A arena está cheia.");
        game.join(player.getUniqueId(), nextOperation()); participants.put(player.getUniqueId(), new Participant(request, player, null));
        return rejected("QUEUED", "Entraste na fila da Batata Quente. Aguarda o início.");
    }

    public synchronized void leave(UUID playerId) { Participant p = participants.remove(Objects.requireNonNull(playerId)); if (p == null || game == null) return; if (game.phase() == HotPotatoPhase.WAITING || game.phase() == HotPotatoPhase.COUNTDOWN) { game.leave(playerId, nextOperation()); if (participants.isEmpty()) { game = null; matchId = null; eventOperations.clear(); operationSequence = 0; } return; } recoverAll("PLAYER_LEFT"); }

    public synchronized void tick(Instant now) {
        Objects.requireNonNull(now); if (!settings.enabled() || game == null) return;
        try {
            if (game.phase() == HotPotatoPhase.WAITING && participants.size() >= settings.config().minimumPlayers()) { game.beginCountdown(nextOperation()); phaseDeadline = now.plus(settings.countdown()); }
            else if (game.phase() == HotPotatoPhase.COUNTDOWN && phaseDeadline != null && !now.isBefore(phaseDeadline)) activate(now);
            else if ((game.phase() == HotPotatoPhase.RUNNING || game.phase() == HotPotatoPhase.SUDDEN_DEATH) && game.timedOut(now)) { game.noContest(now, "MATCH_TIMEOUT", nextOperation()); finish("MATCH_TIMEOUT"); }
            else if ((game.phase() == HotPotatoPhase.RUNNING || game.phase() == HotPotatoPhase.SUDDEN_DEATH) && game.fuseDeadline().filter(deadline -> !now.isBefore(deadline)).isPresent()) {
                UUID expired = game.carrier().orElseThrow();
                game.expireFuse(now, nextOperation());
                markEliminated(expired);
                if (game.phase() == HotPotatoPhase.FINISHING) finish("FUSE");
                else {
                    enterSuddenDeathIfReady(now);
                    refreshCarrierPresentation(now, true);
                }
            }
            if (game != null && (game.phase() == HotPotatoPhase.RUNNING
                    || game.phase() == HotPotatoPhase.SUDDEN_DEATH)
                    && (nextHudRefresh == null || !now.isBefore(nextHudRefresh))) {
                refreshCarrierPresentation(now, false);
            }
        } catch (RuntimeException failure) { if (game != null && game.phase() != HotPotatoPhase.CLOSED) recoverAll("PHASE_FAILURE"); }
    }

    public synchronized void onPass(Player source, Player target, boolean lineOfSight, UUID eventId) {
        if (game == null || source == null || target == null || !participants.containsKey(source.getUniqueId()) || !participants.containsKey(target.getUniqueId())) throw new IllegalArgumentException("players are not in this match");
        if (!inside(source) || !inside(target)) throw new IllegalArgumentException("players are outside the arena");
        double distance = source.getLocation().distance(target.getLocation());
        Instant now = clock.instant();
        game.pass(new PassIntent(source.getUniqueId(), target.getUniqueId(), distance, lineOfSight), now,
                eventOperation(eventId));
        refreshCarrierPresentation(now, true);
    }

    public synchronized void onExplosion(UUID affectedPlayer, UUID eventId) { eliminate(affectedPlayer, eventId, "EXPLOSION"); }
    public synchronized void onElimination(UUID playerId, UUID eventId, String reason) { eliminate(playerId, eventId, reason); }
    public synchronized void onDisconnect(UUID playerId) { if (game == null) return; if (game.phase() == HotPotatoPhase.WAITING || game.phase() == HotPotatoPhase.COUNTDOWN) { leave(playerId); return; } Instant now = clock.instant(); game.forfeit(playerId, now, nextOperation()); if (game.phase() == HotPotatoPhase.FINISHING) finish("DISCONNECT"); else { enterSuddenDeathIfReady(now); refreshCarrierPresentation(now, true); } }
    public synchronized boolean allowTeleport(Player player, Location destination) { if (internalTeleport || game == null || player == null || !participants.containsKey(player.getUniqueId()) || game.phase() == HotPotatoPhase.WAITING) return true; Participant p = participants.get(player.getUniqueId()); return inside(destination) && p.sessionId() != null && admissions.permits(player.getUniqueId(), p.sessionId(), settings.participantRegionId(), clock.instant()); }
    public synchronized boolean allowMove(Player player, Location destination) { return allowTeleport(player, destination); }
    public synchronized void shutdown() { if (game != null) recoverAll("PLUGIN_DISABLED"); }

    private void activate(Instant now) {
        game.lockEntry(nextOperation());
        int index = 0;
        for (UUID playerId : game.roster()) {
            Participant queued = participants.get(playerId); if (queued == null || !queued.player().isOnline() || !queued.player().isValid()) throw new IllegalStateException("PLAYER_OFFLINE");
            AdmissionResult prepared = sessions.prepare(queued.request()); if (prepared.status() != AdmissionStatus.PREPARED) throw new IllegalStateException(prepared.code());
            PlayerSession session = prepared.session().orElseThrow(); Instant expires = now.plus(settings.tokenLifetime()); admissions.issue(new RegionAdmissionToken(UUID.randomUUID(), session.sessionId(), playerId, settings.participantRegionId(), now, expires));
            teleport(queued.player(), settings.spawns().get(index++)); queued.player().getInventory().setItemInMainHand(items.tag(new ItemStack(Material.POTATO), session.sessionId(), GameKey.HOT_POTATO)); sessions.activate(session.sessionId(), OperationIds.derive(queued.request().requestId(), "GAME_ACTIVE"), clock.instant()); participants.put(playerId, new Participant(queued.request(), queued.player(), session.sessionId()));
        }
        game.start(now, nextOperation()); startedAt = now; phaseDeadline = null;
        refreshCarrierPresentation(now, true);
    }

    private void eliminate(UUID playerId, UUID eventId, String reason) { if (game == null) throw new IllegalStateException("no active game"); Instant now = clock.instant(); game.eliminate(playerId, now, eventOperation(eventId)); markEliminated(playerId); if (game.phase() == HotPotatoPhase.FINISHING) finish(reason); else { enterSuddenDeathIfReady(now); refreshCarrierPresentation(now, true); } }

    private void enterSuddenDeathIfReady(Instant now) {
        if (game != null && game.phase() == HotPotatoPhase.RUNNING
                && game.livePlayers().size() == 2) {
            game.enterSuddenDeath(now, nextOperation());
            for (Participant participant : participants.values()) {
                Player player = participant.player();
                if (participant.sessionId() != null && player.isOnline() && player.isValid()) {
                    player.sendMessage("§cMorte súbita: restam dois jogadores e o fusível está no mínimo.");
                }
            }
        }
    }
    private void finish(String reason) {
        if (game == null || matchId == null) return;
        UUID terminalMatchId = matchId;
        Set<UUID> terminalPlayers = new LinkedHashSet<>(participants.keySet());
        MatchResult terminal = game.result().orElse(null);
        HotPotatoMetrics terminalMetrics = game.metrics();
        Instant terminalStartedAt = startedAt;
        if (game.phase() == HotPotatoPhase.FINISHING) game.beginRestore(nextOperation());
        boolean restored = true;
        for (Participant participant : participants.values()) if (participant.sessionId() != null) {
            restored &= sessions.finishAndRestore(participant.sessionId(),
                    OperationIds.derive(terminalMatchId, "FINISH_" + participant.player().getUniqueId()), reason)
                    .status() == AdmissionStatus.RECOVERED;
            admissions.revokeSession(participant.sessionId());
        }
        if (!restored) {
            recoverAll("RESTORE_FAILED");
            return;
        }
        if (game.phase() == HotPotatoPhase.RESTORING) game.completeRestore(nextOperation());
        terminalPlayers.addAll(terminal == null ? game.roster() : terminal.participants());
        if (!terminalPlayers.isEmpty()) {
            if (terminal == null) recordNoContest(terminalMatchId, terminalStartedAt, reason, terminalPlayers);
            else recordResult(terminalMatchId, terminal, terminalMetrics, terminalStartedAt, reason);
        }
        clearControllerState();
    }

    private void recoverAll(String reason) {
        if (game == null || matchId == null) return;
        UUID recoveryMatchId = matchId;
        Set<UUID> recoveryPlayers = new LinkedHashSet<>(participants.keySet());
        recoveryPlayers.addAll(game.roster());
        Set<PlayerSession> sessionsForMatch = sessionRegistry.findByMatch(recoveryMatchId);
        sessionsForMatch.forEach(session -> recoveryPlayers.add(session.playerId()));
        if (game.phase() != HotPotatoPhase.RECOVERING && game.phase() != HotPotatoPhase.CLOSED
                && game.phase() != HotPotatoPhase.DISABLED && game.phase() != HotPotatoPhase.IDLE) {
            game.recover(nextOperation());
        }
        boolean restored = true;
        for (PlayerSession session : sessionsForMatch) {
            if (session.phase() == SessionPhase.QUARANTINED) restored = false;
            if (!session.phase().terminal()) {
                restored &= sessions.recover(session.sessionId(),
                        OperationIds.derive(recoveryMatchId, "RECOVER_" + session.playerId()), reason)
                        .status() == AdmissionStatus.RECOVERED;
            }
            admissions.revokeSession(session.sessionId());
        }
        if (game.phase() == HotPotatoPhase.RECOVERING) game.beginRestore(nextOperation());
        if (game.phase() == HotPotatoPhase.RESTORING) game.completeRestore(nextOperation());
        if (restored && !recoveryPlayers.isEmpty()) {
            recordNoContest(recoveryMatchId, startedAt, reason, recoveryPlayers);
        }
        clearControllerState();
    }

    private void recordResult(UUID terminalMatchId, MatchResult terminal, HotPotatoMetrics metrics,
                              Instant terminalStartedAt, String reason) {
        Instant finishedAt = clock.instant();
        long matchDuration = terminalStartedAt == null ? 0L
                : MatchResultFactory.boundedMillis(Duration.between(terminalStartedAt, finishedAt));
        Map<UUID, PlayerResult> standings = new LinkedHashMap<>();
        for (UUID player : terminal.participants()) {
            boolean winner = terminal.winner().equals(player);
            long survival = MatchResultFactory.boundedMillis(
                    metrics.survivalTimeByPlayer().getOrDefault(player, Duration.ofMillis(matchDuration)));
            standings.put(player, MatchResultFactory.standing(winner ? 1 : 2, winner, false, null,
                    Map.of("wins", winner ? 1L : 0L,
                            "survival_ms", survival,
                            "passes", MatchResultFactory.bounded(
                                    metrics.passesByPlayer().getOrDefault(player, 0)),
                            "eliminated", MatchResultFactory.bounded(
                                    metrics.eliminationsByPlayer().getOrDefault(player, 0)),
                            "forfeits", MatchResultFactory.bounded(
                                    metrics.forfeitsByPlayer().getOrDefault(player, 0)),
                            "carrier_ms", MatchResultFactory.boundedMillis(
                                    metrics.carrierTimeByPlayer().getOrDefault(player, Duration.ZERO)))));
        }
        statistics.record(MatchResultFactory.create(terminalMatchId, GameKey.HOT_POTATO,
                metrics.rulesetRevision(), "ffa", terminalStartedAt, finishedAt,
                MatchOutcome.VICTORY, reason, standings));
    }

    private void recordNoContest(UUID terminalMatchId, Instant terminalStartedAt, String reason,
                                 Set<UUID> players) {
        Instant finishedAt = clock.instant();
        statistics.record(MatchResultFactory.noContest(terminalMatchId, GameKey.HOT_POTATO,
                settings.config().rulesetRevision(), "ffa", terminalStartedAt, finishedAt, reason, players));
    }

    private void clearControllerState() {
        participants.clear();
        game = null;
        matchId = null;
        startedAt = null;
        phaseDeadline = null;
        eventOperations.clear();
        operationSequence = 0;
        nextHudRefresh = null;
        presentedCarrier = null;
    }

    private void refreshCarrierPresentation(Instant now, boolean announceChange) {
        if (game == null) return;
        UUID carrier = game.carrier().orElse(null);
        Instant deadline = game.fuseDeadline().orElse(null);
        if (carrier == null || deadline == null) return;
        boolean changed = !carrier.equals(presentedCarrier);
        String carrierName = participants.containsKey(carrier)
                ? participants.get(carrier).player().getName() : "Outro jogador";
        long remainingMillis = Math.max(0L, Duration.between(now, deadline).toMillis());
        String seconds = String.format(Locale.forLanguageTag("pt-PT"), "%.1f", remainingMillis / 1000.0D);
        for (Map.Entry<UUID, Participant> entry : participants.entrySet()) {
            Participant participant = entry.getValue();
            Player player = participant.player();
            if (participant.sessionId() == null || !player.isOnline() || !player.isValid()) continue;
            boolean hasPotato = entry.getKey().equals(carrier);
            player.getInventory().clear();
            if (hasPotato) {
                player.getInventory().setItemInMainHand(items.tag(
                        new ItemStack(Material.POTATO), participant.sessionId(), GameKey.HOT_POTATO));
                player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0,
                        false, false, true));
            } else {
                player.removePotionEffect(PotionEffectType.GLOWING);
            }
            player.updateInventory();
            player.sendActionBar(Component.text(
                    hasPotato ? "A BATATA É TUA • " + seconds + " s • passa-a!"
                            : carrierName + " tem a batata • " + seconds + " s",
                    hasPotato ? NamedTextColor.RED : NamedTextColor.GOLD));
            if (announceChange && changed) {
                player.showTitle(Title.title(
                        Component.text(hasPotato ? "A batata é tua!" : carrierName + " tem a batata",
                                hasPotato ? NamedTextColor.RED : NamedTextColor.YELLOW),
                        Component.text(hasPotato ? "Clica noutro jogador para a passar." : "Mantém a distância.",
                                NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(900), Duration.ofMillis(250))));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8F,
                        hasPotato ? 1.7F : 1.1F);
            }
        }
        presentedCarrier = carrier;
        nextHudRefresh = now.plusSeconds(1);
    }

    private void markEliminated(UUID playerId) {
        Participant participant = participants.get(playerId);
        if (participant == null || !participant.player().isOnline()) return;
        participant.player().getInventory().clear();
        participant.player().removePotionEffect(PotionEffectType.GLOWING);
        participant.player().setGameMode(GameMode.SPECTATOR);
        participant.player().sendMessage("§eA batata explodiu contigo. Podes assistir até ao fim da ronda.");
    }
    private boolean validGeometry() { return settings.world() != null && regions.find(settings.participantRegionId()).filter(region -> region.game() == GameKey.HOT_POTATO && region.requiresAdmission() && region.bounds().worldId().equals(settings.world().getUID())).isPresent(); }
    private boolean inside(Player player) { return player != null && inside(player.getLocation()); }
    private boolean inside(Location location) { return location != null && location.getWorld() == settings.world() && regions.at(location).map(ProtectedRegion::id).filter(settings.participantRegionId()::equals).isPresent(); }
    private OperationId eventOperation(UUID eventId) { Objects.requireNonNull(eventId); return eventOperations.computeIfAbsent(eventId, ignored -> nextOperation()); }
    private OperationId nextOperation() { return new OperationId(Objects.requireNonNull(matchId), ++operationSequence); }
    private void teleport(Player player, Location destination) { internalTeleport = true; try { if (!player.teleport(destination.clone())) throw new IllegalStateException("TELEPORT_FAILED"); } finally { internalTeleport = false; } }
    private static AdmissionResult rejected(String code, String message) { return AdmissionResult.rejected(code, message); }
}
