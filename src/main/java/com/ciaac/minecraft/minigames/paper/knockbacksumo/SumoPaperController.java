package com.ciaac.minecraft.minigames.paper.knockbacksumo;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.knockbacksumo.SumoConfig;
import com.ciaac.minecraft.minigames.knockbacksumo.SumoPhase;
import com.ciaac.minecraft.minigames.knockbacksumo.SumoResult;
import com.ciaac.minecraft.minigames.knockbacksumo.SumoSession;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.statistics.MatchOutcome;
import com.ciaac.minecraft.minigames.statistics.PlayerResult;
import com.ciaac.minecraft.minigames.statistics.recording.MatchResultFactory;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/** Disabled-by-default Paper adapter boundary for the spawn-safezone Sumo game. */
public final class SumoPaperController {
    public record Status(boolean enabled, SumoPhase phase, int participants, String messagePtPt) {}

    private record Participant(AdmissionRequest request, Player player) {}

    private final SumoPaperSettings settings;
    private final SumoConfig config;
    private final SessionCoordinator sessions;
    private final ProtectedRegionRegistry regions;
    private final RegionAdmissionRegistry admissions;
    private final TemporaryItemTagger items;
    private final Clock clock;
    private final StatisticsResultSink statistics;
    private final Map<UUID, Participant> waiting = new LinkedHashMap<>();
    private final Set<Player> departingPlayers = Collections.newSetFromMap(new IdentityHashMap<>());
    private SumoSession game;
    private UUID matchId;
    private String pendingRecoveryReason;

    public SumoPaperController(SumoPaperSettings settings, SumoConfig config, SessionCoordinator sessions,
                               ProtectedRegionRegistry regions, RegionAdmissionRegistry admissions,
                               TemporaryItemTagger items, Clock clock) {
        this(settings, config, sessions, regions, admissions, items, clock, StatisticsResultSink.unavailable());
    }

    public SumoPaperController(SumoPaperSettings settings, SumoConfig config, SessionCoordinator sessions,
                               ProtectedRegionRegistry regions, RegionAdmissionRegistry admissions,
                               TemporaryItemTagger items, Clock clock, StatisticsResultSink statistics) {
        this.settings = Objects.requireNonNull(settings);
        this.config = Objects.requireNonNull(config);
        this.sessions = Objects.requireNonNull(sessions);
        this.regions = Objects.requireNonNull(regions);
        this.admissions = Objects.requireNonNull(admissions);
        this.items = Objects.requireNonNull(items);
        this.clock = Objects.requireNonNull(clock);
        this.statistics = Objects.requireNonNull(statistics);
    }

    public synchronized Status status() {
        SumoPhase phase = pendingRecoveryReason != null ? SumoPhase.CANCELLED
                : game == null ? SumoPhase.WAITING : game.phase();
        String message = !settings.enabled() ? "Fechado"
                : pendingRecoveryReason != null ? "A recuperar sessões anteriores"
                : phase == SumoPhase.RUNNING ? "Combate em curso"
                : phase == SumoPhase.FINISHING ? "A terminar o combate"
                : "À espera de jogadores";
        return new Status(settings.enabled(), phase, waiting.size(), message);
    }

    public synchronized AdmissionResult join(AdmissionRequest request, Player player) {
        if (!settings.enabled()) return AdmissionResult.rejected("MODULE_DISABLED", "Este minijogo está fechado.");
        if (player == null || !player.isOnline()) return AdmissionResult.rejected("PLAYER_UNAVAILABLE", "O jogador não está disponível.");
        if (pendingRecoveryReason != null) return AdmissionResult.rejected("RECOVERY_PENDING", "A arena aguarda a recuperação das sessões anteriores.");
        requireRegion();
        if (game != null || waiting.size() >= 2) return AdmissionResult.rejected("FULL", "A arena está cheia.");

        AdmissionRequest admissionRequest = Objects.requireNonNull(request);
        if (matchId != null && !matchId.equals(admissionRequest.matchId())) {
            return AdmissionResult.rejected("STALE_MATCH", "Este convite já não pertence ao combate atual.");
        }
        if (waiting.containsKey(player.getUniqueId())) {
            return AdmissionResult.rejected("ALREADY_JOINED", "Já estás inscrito neste combate.");
        }
        if (!waiting.isEmpty() && waiting.values().stream()
                .anyMatch(value -> !value.request().matchId().equals(admissionRequest.matchId()))) {
            return AdmissionResult.rejected("STALE_MATCH", "Este convite já não pertence ao combate atual.");
        }
        boolean firstAdmission = waiting.isEmpty() && game == null;
        if (firstAdmission) matchId = admissionRequest.matchId();
        AdmissionResult prepared = sessions.prepare(admissionRequest);
        if (prepared.status() != AdmissionStatus.PREPARED) {
            if (firstAdmission) matchId = null;
            return prepared;
        }
        waiting.put(player.getUniqueId(), new Participant(admissionRequest, player));
        issue(admissionRequest, clock.instant());
        if (!player.teleport(settings.firstSpawn().clone())) {
            waiting.remove(player.getUniqueId());
            recoverFailedTeleport(sessions, admissions, admissionRequest, operation("TELEPORT_FAILED"));
            if (waiting.isEmpty()) matchId = null;
            return AdmissionResult.rejected("TELEPORT_FAILED", "Não foi possível entrar na arena.");
        }
        ItemStack knockbackItem = new ItemStack(settings.knockbackItem());
        knockbackItem.addUnsafeEnchantment(Enchantment.KNOCKBACK, settings.knockbackLevel());
        player.getInventory().setItemInMainHand(items.tag(knockbackItem, admissionRequest.sessionId(), GameKey.KNOCKBACK_SUMO));
        if (waiting.size() == 2 && !start()) {
            return AdmissionResult.rejected("START_FAILED", "O combate não pôde começar em segurança.");
        }
        return prepared;
    }

    public synchronized void leave(UUID playerId) {
        UUID id = Objects.requireNonNull(playerId);
        if (pendingRecoveryReason != null) {
            continuePendingRecovery();
            return;
        }
        if (game != null && (game.phase() == SumoPhase.RUNNING || game.phase() == SumoPhase.FINISHING)) {
            finishRecovery("PLAYER_LEFT");
            return;
        }
        Participant participant = waiting.remove(id);
        if (participant != null) {
            admissions.revokeSession(participant.request().sessionId());
            sessions.recover(participant.request().sessionId(), operation("PLAYER_LEFT_" + id), "PLAYER_LEFT");
            if (waiting.isEmpty()) matchId = null;
        }
    }

    public synchronized void onRingOut(UUID playerId, UUID eventId) {
        if (game == null) throw new IllegalStateException("no active game");
        game.ringOut(Objects.requireNonNull(playerId), clock.instant(), Objects.requireNonNull(eventId));
        if (game.phase() == SumoPhase.FINISHING) {
            finish("RING_OUT");
        } else if (!resetRoundPositions()) {
            finishRecovery("ROUND_RESET_FAILED");
        }
    }

    public synchronized void onMove(PlayerMoveEvent event, UUID eventId) {
        if (game == null || game.phase() != SumoPhase.RUNNING || event.getTo() == null
                || !waiting.containsKey(event.getPlayer().getUniqueId())) return;
        Location to = event.getTo();
        boolean below = to.getBlockY() <= settings.fallThresholdY();
        boolean escaped = regions.find(settings.regionId()).map(region -> !region.bounds().contains(to)).orElse(true);
        if (below || escaped) {
            onRingOut(event.getPlayer().getUniqueId(), Objects.requireNonNull(eventId));
            // Paper rolls a cancelled move back to its old origin. Preserve the
            // round-reset spawn or terminal snapshot location instead.
            event.setTo(event.getPlayer().getLocation().clone());
        }
    }

    /** Applies only a bounded server-computed impulse; clients never supply velocity directly. */
    public synchronized void onKnockback(Player source, Player target, Vector impulse) {
        if (game == null || game.phase() != SumoPhase.RUNNING) throw new IllegalStateException("no active sumo round");
        if (source == null || target == null || !waiting.containsKey(source.getUniqueId())
                || !waiting.containsKey(target.getUniqueId()) || source.equals(target)) {
            throw new IllegalArgumentException("players are not in this match");
        }
        Vector value = scaleKnockbackImpulse(impulse, settings.knockbackLevel());
        target.setVelocity(value);
    }

    /** Applies the configured level to a server-computed base impulse, retaining level 2 behavior. */
    public static Vector scaleKnockbackImpulse(Vector impulse, int knockbackLevel) {
        if (knockbackLevel < 1 || knockbackLevel > 10) throw new IllegalArgumentException("invalid knockback level");
        Vector value = Objects.requireNonNull(impulse, "impulse").clone();
        if (!Double.isFinite(value.getX()) || !Double.isFinite(value.getY()) || !Double.isFinite(value.getZ())) {
            throw new IllegalArgumentException("invalid impulse");
        }
        value.multiply(knockbackLevel / 2.0D);
        if (value.lengthSquared() > 16.0D) value.normalize().multiply(4.0D);
        return value;
    }

    public synchronized boolean combatAllowed(UUID attacker, UUID victim) {
        return game != null && game.phase() == SumoPhase.RUNNING && waiting.containsKey(attacker)
                && waiting.containsKey(victim) && !attacker.equals(victim);
    }

    public synchronized void onDisconnect(UUID playerId, UUID eventId) {
        UUID id = Objects.requireNonNull(playerId);
        Participant participant = waiting.get(id);
        if (participant == null) {
            if (pendingRecoveryReason != null) continuePendingRecovery();
            return;
        }
        departingPlayers.add(participant.player());
        if (game != null && game.phase() == SumoPhase.RUNNING) {
            game.disconnect(id, Objects.requireNonNull(eventId));
        }
        finishRecovery("DISCONNECT");
    }

    /** Recovers online participants on disable while preserving real quit deferrals. */
    public synchronized void shutdown() {
        if (pendingRecoveryReason != null) continuePendingRecovery();
        else if (!waiting.isEmpty()) finishRecovery("PLUGIN_DISABLED");
    }

    /** Enforces {@link SumoConfig#roundTimeout()} at the adapter tick boundary. */
    public synchronized void tick(Instant now) {
        Instant current = Objects.requireNonNull(now);
        if (pendingRecoveryReason != null) {
            continuePendingRecovery();
            return;
        }
        if (game == null) return;
        if (game.phase() == SumoPhase.RUNNING && game.roundTimedOut(current)) {
            game.timeout(current, operation("ROUND_TIMEOUT"));
            finish("ROUND_TIMEOUT");
            return;
        }
        if (game.phase() == SumoPhase.RUNNING && !enforceAuthoritativePositions()) return;
        if (game != null && game.phase() == SumoPhase.FINISHING) {
            finish(game.result().map(SumoResult::reasonCode).orElse("COMPLETED"));
        }
    }

    /** Server knockback can move a player without producing a usable PlayerMoveEvent. */
    private boolean enforceAuthoritativePositions() {
        ProtectedRegion region;
        try {
            region = regions.find(settings.regionId()).orElse(null);
        } catch (RuntimeException unavailable) {
            finishRecovery("BOUNDARY_UNAVAILABLE");
            return false;
        }
        if (region == null || region.game() != GameKey.KNOCKBACK_SUMO || !region.requiresAdmission()) {
            finishRecovery("BOUNDARY_UNAVAILABLE");
            return false;
        }
        UUID regionWorld = region.bounds().worldId();
        for (Participant participant : waiting.values()) {
            Player player = participant.player();
            if (departingPlayers.contains(player) || !isCurrentPlayerOnline(player)) continue;
            Location location;
            try {
                location = player.getLocation();
            } catch (RuntimeException unavailable) {
                finishRecovery("POSITION_UNAVAILABLE");
                return false;
            }
            if (location == null || location.getWorld() == null) {
                finishRecovery("POSITION_WORLD_MISMATCH");
                return false;
            }
            try {
                if (!regionWorld.equals(location.getWorld().getUID())) {
                    finishRecovery("POSITION_WORLD_MISMATCH");
                    return false;
                }
            } catch (RuntimeException unavailable) {
                finishRecovery("POSITION_WORLD_MISMATCH");
                return false;
            }
            if (!Double.isFinite(location.getX()) || !Double.isFinite(location.getY())
                    || !Double.isFinite(location.getZ())) {
                finishRecovery("POSITION_UNAVAILABLE");
                return false;
            }
            boolean belowFloor;
            boolean insideBoundary;
            try {
                belowFloor = location.getBlockY() <= settings.fallThresholdY();
                insideBoundary = region.bounds().contains(location);
            } catch (RuntimeException unavailable) {
                finishRecovery("BOUNDARY_UNAVAILABLE");
                return false;
            }
            if (belowFloor || !insideBoundary) {
                onRingOut(player.getUniqueId(), UUID.randomUUID());
                return false;
            }
        }
        return true;
    }

    private boolean start() {
        Participant[] values = waiting.values().toArray(Participant[]::new);
        Instant now = clock.instant();
        matchId = values[0].request().matchId();
        game = new SumoSession(matchId, config, values[0].player().getUniqueId(), values[1].player().getUniqueId());
        try {
            game.start(now, operation("START"));
            for (Participant participant : values) {
                sessions.activate(participant.request().sessionId(),
                        operation("ACTIVE_" + participant.player().getUniqueId()), now);
            }
            if (!resetRoundPositions()) throw new IllegalStateException("round spawn teleport failed");
            return true;
        } catch (RuntimeException failure) {
            finishRecovery("START_FAILED");
            return false;
        }
    }

    private boolean resetRoundPositions() {
        Participant[] values = waiting.values().toArray(Participant[]::new);
        if (values.length != 2) return false;
        for (int i = 0; i < values.length; i++) {
            Player player = values[i].player();
            if (!player.isOnline() || !player.isValid()) return false;
            player.setVelocity(new Vector());
            player.setFallDistance(0.0f);
            issue(values[i].request(), clock.instant());
            if (!player.teleport((i == 0 ? settings.firstSpawn() : settings.secondSpawn()).clone())) {
                return false;
            }
            player.sendMessage("§eNova ronda de Sumo. Prepara-te!");
        }
        return true;
    }

    private void finish(String reason) {
        if (game == null) return;
        SumoResult terminal = game.result().orElse(null);
        game.close(operation("CLOSE_" + reason));
        boolean restored = true;
        for (Participant participant : waiting.values()) restored &= finishOne(participant, reason);
        if (!restored) {
            finishRecovery("RESTORE_FAILED");
            return;
        }
        if (terminal != null) record(terminal);
        waiting.clear();
        game = null;
        matchId = null;
    }

    private void finishRecovery(String reason) {
        if (pendingRecoveryReason == null) pendingRecoveryReason = Objects.requireNonNull(reason);
        if (game != null) {
            if (game.phase() == SumoPhase.RUNNING || game.phase() == SumoPhase.WAITING) {
                game.cancel(operation("CANCEL_" + reason));
            }
            if (game.phase() == SumoPhase.CANCELLED || game.phase() == SumoPhase.FINISHING) {
                game.close(operation("CLOSE_" + reason));
            }
        }
        continuePendingRecovery();
    }

    private void continuePendingRecovery() {
        if (pendingRecoveryReason == null) return;
        boolean restored = true;
        for (Participant participant : waiting.values()) {
            UUID sessionId = participant.request().sessionId();
            UUID playerId = participant.player().getUniqueId();
            admissions.revokeSession(sessionId);
            var session = sessions.findSession(sessionId);
            if (session.isEmpty() || session.orElseThrow().phase() == com.ciaac.minecraft.minigames.runtime.SessionPhase.CLOSED) {
                continue;
            }
            if (!shouldAttemptRecovery(Optional.of(session.orElseThrow().phase()),
                    participant.player(), departingPlayers)) {
                restored = false;
                continue;
            }
            try {
                restored &= sessions.recover(sessionId,
                        operation("RESTORE_" + playerId), pendingRecoveryReason).status() == AdmissionStatus.RECOVERED;
            } catch (RuntimeException failure) {
                restored = false;
            }
            var remaining = sessions.findSession(sessionId);
            if (remaining.isPresent() && remaining.orElseThrow().phase() != com.ciaac.minecraft.minigames.runtime.SessionPhase.CLOSED) {
                restored = false;
            }
        }
        if (restored) {
            if (!waiting.isEmpty()) recordNoContest(pendingRecoveryReason);
            clearRecoveryState();
        }
    }

    private static boolean isCurrentPlayerOnline(Player player) {
        try {
            return player.isOnline() && player.isValid();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    static boolean shouldAttemptRecovery(Optional<com.ciaac.minecraft.minigames.runtime.SessionPhase> phase,
                                         Player player, Set<Player> departingPlayers) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(departingPlayers, "departingPlayers");
        return phase.isPresent()
                && phase.orElseThrow() != com.ciaac.minecraft.minigames.runtime.SessionPhase.CLOSED
                && phase.orElseThrow() != com.ciaac.minecraft.minigames.runtime.SessionPhase.QUARANTINED
                && !departingPlayers.contains(player)
                && isCurrentPlayerOnline(player);
    }

    private void clearRecoveryState() {
        waiting.clear();
        game = null;
        matchId = null;
        pendingRecoveryReason = null;
        departingPlayers.clear();
    }

    private boolean finishOne(Participant participant, String reason) {
        boolean restored = sessions.finishAndRestore(participant.request().sessionId(),
                operation("FINISH_" + participant.player().getUniqueId()), reason).status() == AdmissionStatus.RECOVERED;
        admissions.revokeSession(participant.request().sessionId());
        return restored;
    }

    private void record(SumoResult terminal) {
        Instant finished = clock.instant();
        long rounds = MatchResultFactory.bounded(terminal.rounds());
        Map<UUID, PlayerResult> standings = new LinkedHashMap<>();
        if (terminal.draw()) {
            standings.put(terminal.winner(), new PlayerResult(1, false, false, Optional.empty(),
                    Map.of("wins", 0L, "draws", 1L, "rounds", rounds)));
            standings.put(terminal.loser(), new PlayerResult(1, false, false, Optional.empty(),
                    Map.of("wins", 0L, "draws", 1L, "rounds", rounds)));
            statistics.record(MatchResultFactory.create(matchId, GameKey.KNOCKBACK_SUMO, terminal.rulesetRevision(),
                    "1v1", finished, finished, MatchOutcome.DRAW, terminal.reasonCode(), standings));
            return;
        }
        standings.put(terminal.winner(), new PlayerResult(1, true, false, Optional.empty(),
                Map.of("wins", 1L, "rounds", rounds)));
        standings.put(terminal.loser(), new PlayerResult(2, false, false, Optional.empty(),
                Map.of("wins", 0L, "rounds", rounds)));
        statistics.record(MatchResultFactory.create(matchId, GameKey.KNOCKBACK_SUMO, terminal.rulesetRevision(),
                "1v1", finished, finished, MatchOutcome.VICTORY, terminal.reasonCode(), standings));
    }

    private void recordNoContest(String reason) {
        Instant finished = clock.instant();
        statistics.record(MatchResultFactory.noContest(matchId, GameKey.KNOCKBACK_SUMO,
                config.rulesetRevision(), "1v1", finished, finished, reason, waiting.keySet()));
    }

    private void issue(AdmissionRequest request, Instant now) {
        admissions.issue(new RegionAdmissionToken(UUID.randomUUID(), request.sessionId(), request.playerId(),
                settings.regionId(), now, now.plus(admissionTokenLifetime(settings.tokenTtl(), config.roundTimeout()))));
    }

    static Duration admissionTokenLifetime(Duration configuredLifetime, Duration roundTimeout) {
        Duration minimum = Objects.requireNonNull(roundTimeout, "roundTimeout").plusSeconds(1);
        Duration configured = Objects.requireNonNull(configuredLifetime, "configuredLifetime");
        return configured.compareTo(minimum) >= 0 ? configured : minimum;
    }

    private UUID operation(String purpose) {
        return operationId(matchId, purpose);
    }

    static UUID operationId(UUID matchId, String purpose) {
        UUID root = matchId == null
                ? UUID.nameUUIDFromBytes("sumo-unmatched".getBytes(StandardCharsets.UTF_8))
                : matchId;
        return UUID.nameUUIDFromBytes((root + ":" + purpose).getBytes(StandardCharsets.UTF_8));
    }

    static AdmissionResult recoverFailedTeleport(
            SessionCoordinator sessions,
            RegionAdmissionRegistry admissions,
            AdmissionRequest request,
            UUID operationId) {
        admissions.revokeSession(request.sessionId());
        return sessions.recover(request.sessionId(), operationId, "TELEPORT_FAILED");
    }

    private void requireRegion() {
        ProtectedRegion region = regions.find(settings.regionId())
                .orElseThrow(() -> new IllegalStateException("Configured Sumo region is missing"));
        if (region.game() != GameKey.KNOCKBACK_SUMO || !region.requiresAdmission()) {
            throw new IllegalStateException("Sumo region is not an admitted participant region");
        }
        if (!region.bounds().contains(settings.firstSpawn())
                || !region.bounds().contains(settings.secondSpawn())
                || settings.firstSpawn().getBlockY() <= settings.fallThresholdY()
                || settings.secondSpawn().getBlockY() <= settings.fallThresholdY()) {
            throw new IllegalStateException("Sumo spawns must be inside the boundary and above the fall threshold");
        }
    }
}
