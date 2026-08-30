package com.ciaac.minecraft.minigames.paper.checkpointparkour;

import com.ciaac.minecraft.minigames.checkpointparkour.ParkourConfig;
import com.ciaac.minecraft.minigames.checkpointparkour.ParkourSession;
import com.ciaac.minecraft.minigames.core.GameKey;
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
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Paper adapter for independent ordered, timed runs in the spawn safezone. */
public final class ParkourPaperController {
    public record Status(boolean enabled, int activeRuns, int maximumRuns, String messagePtPt) { }
    private record Active(AdmissionRequest request, Player player, ParkourSession game, int lastCheckpoint) { }

    private final ParkourPaperSettings settings;
    private final ParkourConfig config;
    private final SessionCoordinator sessions;
    private final ProtectedRegionRegistry regions;
    private final RegionAdmissionRegistry admissions;
    private final TemporaryItemTagger items;
    private final Clock clock;
    private final StatisticsResultSink statistics;
    private final Plugin visibilityOwner;
    private final Map<UUID, Active> active = new LinkedHashMap<>();

    public ParkourPaperController(
            ParkourPaperSettings settings, ParkourConfig config, SessionCoordinator sessions,
            ProtectedRegionRegistry regions, RegionAdmissionRegistry admissions,
            TemporaryItemTagger items, Clock clock) {
        this(settings, config, sessions, regions, admissions, items, clock, null,
                StatisticsResultSink.unavailable());
    }

    public ParkourPaperController(
            ParkourPaperSettings settings, ParkourConfig config, SessionCoordinator sessions,
            ProtectedRegionRegistry regions, RegionAdmissionRegistry admissions,
            TemporaryItemTagger items, Clock clock, StatisticsResultSink statistics) {
        this(settings, config, sessions, regions, admissions, items, clock, null, statistics);
    }

    public ParkourPaperController(
            ParkourPaperSettings settings, ParkourConfig config, SessionCoordinator sessions,
            ProtectedRegionRegistry regions, RegionAdmissionRegistry admissions,
            TemporaryItemTagger items, Clock clock, Plugin visibilityOwner,
            StatisticsResultSink statistics) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.config = Objects.requireNonNull(config, "config");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.items = Objects.requireNonNull(items, "items");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.visibilityOwner = visibilityOwner;
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        if (config.checkpointCount() != settings.checkpoints().size()) {
            throw new IllegalArgumentException("checkpoint config and geometry differ");
        }
        if (settings.hideOtherRunners() && visibilityOwner == null) {
            throw new IllegalArgumentException("runner hiding requires a plugin visibility owner");
        }
    }

    public synchronized Status status() {
        boolean ready = settings.enabled() && validRegion();
        String message = !ready ? "Fechado"
                : active.size() >= settings.maximumConcurrentRuns() ? "Percurso cheio"
                : "Livre";
        return new Status(ready, active.size(), settings.maximumConcurrentRuns(), message);
    }

    public synchronized Optional<UUID> runId(UUID playerId) {
        Active run = active.get(Objects.requireNonNull(playerId, "playerId"));
        return run == null ? Optional.empty() : Optional.of(run.request().matchId());
    }

    public synchronized AdmissionResult join(AdmissionRequest request, Player player) {
        if (!settings.enabled()) return rejected("MODULE_DISABLED", "Este minijogo está fechado.");
        if (player == null || !player.isOnline() || !player.isValid()) {
            return rejected("PLAYER_UNAVAILABLE", "O jogador não está disponível.");
        }
        if (!player.getServer().isPrimaryThread()) {
            return rejected("MAIN_THREAD_REQUIRED", "A corrida só pode começar no servidor principal.");
        }
        requireRegion();
        if (active.containsKey(player.getUniqueId())) {
            return rejected("ALREADY_ACTIVE", "Já tens uma corrida ativa.");
        }
        if (active.size() >= settings.maximumConcurrentRuns()) {
            return rejected("COURSE_FULL", "O percurso está cheio; tenta novamente dentro de instantes.");
        }
        AdmissionRequest value = Objects.requireNonNull(request, "request");
        AdmissionResult prepared = sessions.prepare(value);
        if (prepared.status() != AdmissionStatus.PREPARED) return prepared;
        try {
            Instant now = clock.instant();
            issue(value, now);
            if (!player.teleport(settings.gate())) throw new IllegalStateException("TELEPORT_FAILED");
            player.getInventory().setItemInMainHand(items.tag(
                    new ItemStack(Material.FEATHER), value.sessionId(), GameKey.CHECKPOINT_PARKOUR));
            ParkourSession game = new ParkourSession(value.matchId(), config);
            game.start(now, UUID.randomUUID());
            Active run = new Active(value, player, game, -1);
            active.put(player.getUniqueId(), run);
            sessions.activate(value.sessionId(), UUID.randomUUID(), now);
            hideFromOtherRunners(run);
            return prepared;
        } catch (RuntimeException failure) {
            active.remove(player.getUniqueId());
            admissions.revokeSession(value.sessionId());
            revealToOtherRunners(player);
            sessions.recover(value.sessionId(), UUID.randomUUID(), "PREPARATION_FAILED");
            return rejected("PREPARATION_FAILED", "Não foi possível iniciar a corrida em segurança.");
        }
    }

    public synchronized void leave(UUID playerId) {
        Active run = active.remove(Objects.requireNonNull(playerId, "playerId"));
        if (run == null) return;
        revealToOtherRunners(run.player());
        run.game().cancel(UUID.randomUUID());
        admissions.revokeSession(run.request().sessionId());
        if (sessions.recover(run.request().sessionId(), UUID.randomUUID(), "PLAYER_LEFT")
                .status() == AdmissionStatus.RECOVERED) {
            recordNoContest(run, "PLAYER_LEFT");
        }
    }

    public synchronized void onCheckpoint(UUID playerId, int index, UUID eventId) {
        Active run = require(playerId);
        run.game().checkpoint(index, clock.instant(), eventId);
        Active updated = new Active(run.request(), run.player(), run.game(), index);
        active.put(playerId, updated);
        if (run.game().result().isPresent()) complete(updated, "COMPLETED");
    }

    public synchronized void onFallOrLeave(UUID playerId, UUID eventId) {
        Active run = require(playerId);
        run.game().fallOrLeave(clock.instant(), eventId);
        Location restart = run.lastCheckpoint() < 0
                ? settings.gate()
                : settings.checkpoints().get(run.lastCheckpoint());
        run.player().teleport(restart);
        if (run.game().result().isPresent()) complete(run, "TIME_LIMIT");
    }

    public synchronized boolean onMove(Player player, Location destination) {
        Active run = active.get(Objects.requireNonNull(player, "player").getUniqueId());
        if (run == null || destination == null) return true;
        boolean inside = regions.at(destination).map(ProtectedRegion::id)
                .filter(settings.regionId()::equals).isPresent();
        if (!inside) {
            onFallOrLeave(player.getUniqueId(), UUID.randomUUID());
            return false;
        }
        return true;
    }

    public synchronized boolean onTeleport(Player player, Location destination) {
        return onMove(player, destination);
    }

    public synchronized void onDisconnect(UUID playerId) {
        Active run = active.remove(Objects.requireNonNull(playerId, "playerId"));
        if (run == null) return;
        revealToOtherRunners(run.player());
        run.game().cancel(UUID.randomUUID());
        admissions.revokeSession(run.request().sessionId());
        if (sessions.recover(run.request().sessionId(), UUID.randomUUID(), "DISCONNECT")
                .status() == AdmissionStatus.RECOVERED) {
            recordNoContest(run, "DISCONNECT");
        }
    }

    public synchronized void tick(Instant now) {
        Objects.requireNonNull(now, "now");
        for (Active run : List.copyOf(active.values())) {
            if (!run.player().isOnline() || !run.player().isValid()) {
                onDisconnect(run.player().getUniqueId());
                continue;
            }
            run.game().tick(now, UUID.randomUUID());
            if (run.game().result().isPresent()) complete(run, "TIME_LIMIT");
        }
    }

    private void complete(Active run, String reason) {
        active.remove(run.player().getUniqueId());
        revealToOtherRunners(run.player());
        if (sessions.finishAndRestore(run.request().sessionId(), UUID.randomUUID(), reason)
                .status() == AdmissionStatus.RECOVERED) {
            run.game().result().ifPresent(result -> {
                Instant finished = clock.instant();
                Instant started = finished.minus(result.elapsed());
                Map<String, Long> metrics = Map.of(
                        "time_ms", MatchResultFactory.boundedMillis(result.elapsed()),
                        "checkpoints", MatchResultFactory.bounded(result.checkpoints()),
                        "wins", result.valid() ? 1L : 0L);
                statistics.record(MatchResultFactory.create(
                        run.request().matchId(), GameKey.CHECKPOINT_PARKOUR,
                        result.rulesetRevision(), "solo", started, finished,
                        result.valid() ? MatchOutcome.VICTORY : MatchOutcome.NO_CONTEST,
                        result.reasonCode(),
                        Map.of(run.player().getUniqueId(), new PlayerResult(
                                result.valid() ? 1 : 0, result.valid(), false,
                                Optional.empty(), metrics))));
            });
        }
        admissions.revokeSession(run.request().sessionId());
    }

    private void recordNoContest(Active run, String reason) {
        Instant finished = clock.instant();
        statistics.record(MatchResultFactory.noContest(
                run.request().matchId(), GameKey.CHECKPOINT_PARKOUR,
                config.rulesetRevision(), "solo", finished, finished, reason,
                Set.of(run.player().getUniqueId())));
    }

    private void hideFromOtherRunners(Active joining) {
        if (!settings.hideOtherRunners()) return;
        for (Active other : active.values()) {
            if (other == joining || other.player().getUniqueId().equals(joining.player().getUniqueId())) continue;
            joining.player().hidePlayer(visibilityOwner, other.player());
            other.player().hidePlayer(visibilityOwner, joining.player());
        }
    }

    private void revealToOtherRunners(Player leaving) {
        if (!settings.hideOtherRunners()) return;
        for (Active other : active.values()) {
            if (other.player().getUniqueId().equals(leaving.getUniqueId())) continue;
            if (leaving.isOnline()) leaving.showPlayer(visibilityOwner, other.player());
            if (other.player().isOnline()) other.player().showPlayer(visibilityOwner, leaving);
        }
    }

    private Active require(UUID playerId) {
        Active run = active.get(Objects.requireNonNull(playerId, "playerId"));
        if (run == null) throw new IllegalStateException("no active parkour run");
        return run;
    }

    private void issue(AdmissionRequest request, Instant now) {
        admissions.issue(new RegionAdmissionToken(
                UUID.randomUUID(), request.sessionId(), request.playerId(), settings.regionId(),
                now, now.plus(settings.tokenTtl())));
    }

    private boolean validRegion() {
        try {
            requireRegion();
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private void requireRegion() {
        ProtectedRegion region = regions.find(settings.regionId()).orElseThrow(
                () -> new IllegalStateException("Configured parkour region is missing"));
        if (region.game() != GameKey.CHECKPOINT_PARKOUR || !region.requiresAdmission()) {
            throw new IllegalStateException("Parkour region is not admitted");
        }
    }

    private static AdmissionResult rejected(String code, String message) {
        return AdmissionResult.rejected(code, message);
    }
}
