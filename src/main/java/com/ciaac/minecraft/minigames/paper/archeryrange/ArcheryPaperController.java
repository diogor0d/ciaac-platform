package com.ciaac.minecraft.minigames.paper.archeryrange;

import com.ciaac.minecraft.minigames.archeryrange.ArcheryConfig;
import com.ciaac.minecraft.minigames.archeryrange.ArcheryPhase;
import com.ciaac.minecraft.minigames.archeryrange.ArcheryResult;
import com.ciaac.minecraft.minigames.archeryrange.ArcherySession;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Paper-neutral lifecycle facade for a configured multi-lane archery range.
 *
 * <p>All admission, projectile and target marks are server-owned. The
 * existing event router can continue to pass UUIDs, while a later listener
 * may use the typed {@link ProjectileMark} and {@link TargetMark} accessors or
 * the entity overload of {@link #launch(Player, Projectile)}. The controller
 * never accepts a score that is not present in the resolved score-band map.</p>
 */
public final class ArcheryPaperController {
    private static final NamespacedKey PROJECTILE_SESSION_KEY =
            new NamespacedKey("ciaac", "archery-session");
    private static final NamespacedKey PROJECTILE_LANE_KEY =
            new NamespacedKey("ciaac", "archery-lane");
    private static final NamespacedKey TARGET_LANE_KEY =
            new NamespacedKey("ciaac", "archery-target-lane");
    private static final NamespacedKey TARGET_BAND_KEY =
            new NamespacedKey("ciaac", "archery-target-band");

    /** Stable status view for the shared module facade. */
    public record Status(
            boolean enabled,
            int activeLanes,
            int configuredLanes,
            String messagePtPt) {
        public Status(boolean enabled, int activeLanes, String messagePtPt) {
            this(enabled, activeLanes, activeLanes, messagePtPt);
        }
    }

    /** Server-created projectile mark used for owner, session and lane checks. */
    public record ProjectileMark(
            UUID projectileId,
            UUID sessionId,
            UUID playerId,
            int laneId) {
        public ProjectileMark {
            Objects.requireNonNull(projectileId, "projectileId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(playerId, "playerId");
            if (laneId < 0 || laneId > 1024) {
                throw new IllegalArgumentException("laneId is invalid");
            }
        }
    }

    /** Server-resolved target mark. A null band is only supported by the legacy overload. */
    public record TargetMark(
            UUID targetId,
            int laneId,
            String band,
            int points) {
        public TargetMark {
            Objects.requireNonNull(targetId, "targetId");
            if (laneId < 0 || laneId > 1024) {
                throw new IllegalArgumentException("laneId is invalid");
            }
            if (band != null && !band.matches("[a-z][a-z0-9_.-]{0,31}")) {
                throw new IllegalArgumentException("band is invalid");
            }
            if (points < 0 || points > 100) {
                throw new IllegalArgumentException("points are invalid");
            }
        }
    }

    private record Active(
            AdmissionRequest request,
            Player player,
            ArcherySession game,
            ArcheryPaperSettings.LaneSettings lane,
            Instant startedAt,
            Instant deadline) {
    }

    private final ArcheryPaperSettings settings;
    private final Map<Integer, ArcheryPaperSettings.LaneSettings> lanes;
    private final Map<Integer, ArcheryConfig> configs;
    private final Map<String, Integer> scoreBands;
    private final SessionCoordinator sessions;
    private final ProtectedRegionRegistry regions;
    private final RegionAdmissionRegistry admissions;
    private final TemporaryItemTagger items;
    private final Clock clock;
    private final StatisticsResultSink statistics;
    private final Map<UUID, Active> activeByPlayer = new LinkedHashMap<>();
    private final Map<Integer, UUID> activeByLane = new LinkedHashMap<>();
    private final Map<UUID, ProjectileMark> projectiles = new HashMap<>();
    private final Map<UUID, TargetMark> targets = new HashMap<>();

    /** Compatibility constructor for the original one-lane assembler. */
    public ArcheryPaperController(
            ArcheryPaperSettings settings,
            ArcheryConfig config,
            SessionCoordinator sessions,
            ProtectedRegionRegistry regions,
            RegionAdmissionRegistry admissions,
            TemporaryItemTagger items,
            Clock clock) {
        this(settings, config, sessions, regions, admissions, items, clock,
                StatisticsResultSink.unavailable());
    }

    /** Compatibility constructor with a durable statistics sink. */
    public ArcheryPaperController(
            ArcheryPaperSettings settings,
            ArcheryConfig config,
            SessionCoordinator sessions,
            ProtectedRegionRegistry regions,
            RegionAdmissionRegistry admissions,
            TemporaryItemTagger items,
            Clock clock,
            StatisticsResultSink statistics) {
        this(settings,
                Map.of(Objects.requireNonNull(config, "config").laneId(), config),
                sessions,
                regions,
                admissions,
                items,
                clock,
                statistics,
                true);
    }

    /** Preferred constructor for all resolved lanes and server-owned scores. */
    public ArcheryPaperController(
            ArcheryPaperSettings settings,
            Map<Integer, ArcheryConfig> configs,
            SessionCoordinator sessions,
            ProtectedRegionRegistry regions,
            RegionAdmissionRegistry admissions,
            TemporaryItemTagger items,
            Clock clock) {
        this(settings, configs, sessions, regions, admissions, items, clock,
                StatisticsResultSink.unavailable(), false);
    }

    /** Preferred constructor with a durable statistics sink. */
    public ArcheryPaperController(
            ArcheryPaperSettings settings,
            Map<Integer, ArcheryConfig> configs,
            SessionCoordinator sessions,
            ProtectedRegionRegistry regions,
            RegionAdmissionRegistry admissions,
            TemporaryItemTagger items,
            Clock clock,
            StatisticsResultSink statistics) {
        this(settings, configs, sessions, regions, admissions, items, clock, statistics, false);
    }

    private ArcheryPaperController(
            ArcheryPaperSettings settings,
            Map<Integer, ArcheryConfig> configs,
            SessionCoordinator sessions,
            ProtectedRegionRegistry regions,
            RegionAdmissionRegistry admissions,
            TemporaryItemTagger items,
            Clock clock,
            StatisticsResultSink statistics,
            boolean legacySingleLane) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.lanes = sortedLanes(settings.lanes());
        this.configs = validateConfigs(settings, configs);
        this.scoreBands = resolveScoreBands(settings, this.configs, legacySingleLane);
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.items = Objects.requireNonNull(items, "items");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    public synchronized List<String> menuLanes() {
        return lanes.keySet().stream().sorted().map(String::valueOf).toList();
    }

    public synchronized Status status() {
        boolean ready = settings.enabled() && allRegionsValid() && allTargetsReady();
        String message = ready ? "Livre" : "Fechado";
        return new Status(ready, activeByPlayer.size(), lanes.size(), message);
    }

    /**
     * Joins the first free configured lane in ascending numeric order.
     */
    public synchronized AdmissionResult join(AdmissionRequest request, Player player) {
        return join(request, player, OptionalInt.empty());
    }

    /**
     * Joins a requested lane when present, otherwise the first free lane.
     * Requested lane selection is bounded to the resolved lane map.
     */
    public synchronized AdmissionResult join(
            AdmissionRequest request, Player player, OptionalInt requestedLane) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(requestedLane, "requestedLane");
        if (!settings.enabled()) {
            return AdmissionResult.rejected("MODULE_DISABLED", "Este minijogo está fechado.");
        }
        if (!player.isOnline() || !player.getUniqueId().equals(request.playerId())) {
            return AdmissionResult.rejected(
                    "PLAYER_UNAVAILABLE", "O jogador não está disponível para esta sessão.");
        }
        if (request.game() != GameKey.ARCHERY_RANGE) {
            return AdmissionResult.rejected("GAME_MISMATCH", "A sessão de tiro não é válida.");
        }
        if (!allRegionsValid() || !allTargetsReady()) {
            return AdmissionResult.rejected(
                    "CONFIGURATION_UNAVAILABLE",
                    "O campo de tiro está fechado até todas as lanes e os quatro alvos serem validados.");
        }
        UUID playerId = player.getUniqueId();
        if (activeByPlayer.containsKey(playerId)) {
            return AdmissionResult.rejected(
                    "ALREADY_ACTIVE", "Já tens uma sessão de tiro ativa.");
        }
        int laneId = requestedLane.isPresent()
                ? requestedLane.getAsInt()
                : firstFreeLane().orElse(-1);
        ArcheryPaperSettings.LaneSettings lane = lanes.get(laneId);
        if (lane == null) {
            return AdmissionResult.rejected(
                    "LANE_INVALID", "Essa lane de tiro não está configurada.");
        }
        UUID occupiedBy = activeByLane.get(laneId);
        if (occupiedBy != null) {
            return AdmissionResult.rejected(
                    "LANE_BUSY", "Essa lane está ocupada; escolhe outra lane livre.");
        }

        AdmissionResult prepared = sessions.prepare(request);
        if (prepared.status() != AdmissionStatus.PREPARED) {
            return prepared;
        }
        try {
            Instant now = clock.instant();
            issue(request, lane.regionId(), now);
            if (!player.teleport(lane.spawn().clone())) {
                throw new IllegalStateException("teleport rejected");
            }
            grantTemporaryKit(player, request.sessionId());
            ArcherySession game = new ArcherySession(
                    request.matchId(), playerId, configs.get(laneId));
            game.start(UUID.randomUUID());
            if (!sessions.activate(request.sessionId(), UUID.randomUUID(), now)) {
                throw new IllegalStateException("session activation was not applied");
            }
            Active active = new Active(
                    request,
                    player,
                    game,
                    lane,
                    now,
                    plus(now, settings.attemptTimeout()));
            activeByPlayer.put(playerId, active);
            activeByLane.put(laneId, playerId);
            return prepared;
        } catch (RuntimeException failure) {
            admissions.revokeSession(request.sessionId());
            recoverAfterJoinFailure(request);
            return AdmissionResult.rejected(
                    "JOIN_FAILED",
                    "Não foi possível preparar o campo de tiro; o teu estado foi protegido.");
        }
    }

    /** Convenience overload for a bounded integer lane argument. */
    public synchronized AdmissionResult join(
            AdmissionRequest request, Player player, int requestedLane) {
        if (requestedLane < 0 || requestedLane > 1024) {
            return AdmissionResult.rejected("LANE_INVALID", "Essa lane de tiro não é válida.");
        }
        return join(request, player, OptionalInt.of(requestedLane));
    }

    /** Leaves a run and restores the player's state without recording a win. */
    public synchronized void leave(UUID playerId) {
        abort(playerId, "PLAYER_LEFT");
    }

    /** Marks a server-created projectile for one active lane. */
    public synchronized void launch(UUID playerId, UUID projectileId) {
        Active active = require(playerId);
        UUID id = Objects.requireNonNull(projectileId, "projectileId");
        ProjectileMark previous = projectiles.get(id);
        if (previous != null) {
            if (!previous.sessionId().equals(active.request().sessionId())) {
                throw new IllegalArgumentException("projectile belongs to another session");
            }
            return;
        }
        projectiles.put(id, new ProjectileMark(
                id, active.request().sessionId(), playerId, active.lane().id()));
    }

    /** Entity overload for a future PDC-backed Paper listener. */
    public synchronized void launch(Player player, Projectile projectile) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(projectile, "projectile");
        launch(player.getUniqueId(), projectile.getUniqueId());
        ProjectileMark mark = projectiles.get(projectile.getUniqueId());
        if (mark == null) {
            throw new IllegalStateException("projectile mark was not created");
        }
        projectile.getPersistentDataContainer().set(
                PROJECTILE_SESSION_KEY, PersistentDataType.STRING, mark.sessionId().toString());
        projectile.getPersistentDataContainer().set(
                PROJECTILE_LANE_KEY, PersistentDataType.INTEGER, mark.laneId());
    }

    /** Server-side projectile mark for later event routing. */
    public synchronized Optional<ProjectileMark> projectile(UUID projectileId) {
        return Optional.ofNullable(projectiles.get(Objects.requireNonNull(projectileId, "projectileId")));
    }

    /** Registers a target with a server-resolved score band. */
    public synchronized void registerTarget(UUID targetId, int laneId, String band) {
        UUID id = Objects.requireNonNull(targetId, "targetId");
        ArcheryPaperSettings.LaneSettings lane = requireLane(laneId);
        String normalized = normalizeBand(band);
        int points = scoreForBand(normalized);
        TargetMark previous = targets.put(id, new TargetMark(id, lane.id(), normalized, points));
        if (previous != null && previous.laneId() != lane.id()) {
            targets.put(id, previous);
            throw new IllegalStateException("target is already assigned to another lane");
        }
    }

    /**
     * Registers a target when the adapter can also prove the configured
     * logical target identity. This is the preferred overload for a real
     * Bukkit entity listener.
     */
    public synchronized void registerTarget(
            UUID targetId, int laneId, String configuredTargetId, String band) {
        ArcheryPaperSettings.LaneSettings lane = requireLane(laneId);
        if (!lane.targetId().equals(Objects.requireNonNull(configuredTargetId, "configuredTargetId"))) {
            throw new IllegalArgumentException("target is not configured for this lane");
        }
        registerTarget(targetId, laneId, band);
    }

    /** Registers and tags a live target entity; the in-memory mark remains authoritative. */
    public synchronized void registerTarget(
            UUID targetId, int laneId, String band, Entity targetEntity) {
        Objects.requireNonNull(targetEntity, "targetEntity");
        if (!targetEntity.getUniqueId().equals(Objects.requireNonNull(targetId, "targetId"))) {
            throw new IllegalArgumentException("target identity does not match the entity");
        }
        ArcheryPaperSettings.LaneSettings lane = requireLane(laneId);
        if (!insideRegion(lane, targetEntity.getLocation())) {
            throw new IllegalArgumentException("target entity is outside its configured lane");
        }
        registerTarget(targetId, laneId, band);
        targetEntity.getPersistentDataContainer().set(
                TARGET_LANE_KEY, PersistentDataType.INTEGER, laneId);
        targetEntity.getPersistentDataContainer().set(
                TARGET_BAND_KEY, PersistentDataType.STRING, normalizeBand(band));
    }

    /** Compatibility registration for a target resolved by the old router. */
    public synchronized void registerTarget(UUID targetId, int laneId) {
        UUID id = Objects.requireNonNull(targetId, "targetId");
        requireLane(laneId);
        TargetMark previous = targets.put(id, new TargetMark(id, laneId, null, 0));
        if (previous != null && previous.laneId() != laneId) {
            targets.put(id, previous);
            throw new IllegalStateException("target is already assigned to another lane");
        }
    }

    public synchronized Optional<TargetMark> target(UUID targetId) {
        return Optional.ofNullable(targets.get(Objects.requireNonNull(targetId, "targetId")));
    }

    /**
     * Existing router contract. Points are still checked against server
     * score bands before the pure domain receives the bounded event.
     */
    public synchronized void onProjectileHit(
            UUID playerId,
            UUID projectileId,
            int targetLane,
            int points,
            boolean bullseye,
            UUID eventId) {
        Active active = require(playerId);
        if (targetLane != active.lane().id()) {
            discardProjectile(projectileId);
            throw new IllegalArgumentException("target is outside the assigned lane");
        }
        String band = uniqueBandFor(points, bullseye);
        processHit(active, projectileId, targetLane, points, bullseye, band, eventId);
    }

    /** Preferred target-identity contract for a Paper listener. */
    public synchronized void onProjectileHit(
            UUID playerId,
            UUID projectileId,
            UUID targetId,
            int points,
            boolean bullseye,
            UUID eventId) {
        Active active = require(playerId);
        TargetMark target = targets.get(Objects.requireNonNull(targetId, "targetId"));
        if (target == null || target.laneId() != active.lane().id()) {
            discardProjectile(projectileId);
            throw new IllegalArgumentException("target is not assigned to the player's lane");
        }
        String band = target.band();
        if (band == null) {
            band = uniqueBandFor(points, bullseye);
        } else if (target.points() != points || band.equals("bullseye") != bullseye) {
            discardProjectile(projectileId);
            throw new IllegalArgumentException("target score does not match server configuration");
        }
        processHit(active, projectileId, target.laneId(), points, bullseye, band, eventId);
    }

    /** Entity-aware hit overload that verifies the PDC tag before score checks. */
    public synchronized void onProjectileHit(
            Player player,
            Projectile projectile,
            UUID targetId,
            int points,
            boolean bullseye,
            UUID eventId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(projectile, "projectile");
        verifyProjectileTag(player, projectile);
        onProjectileHit(player.getUniqueId(), projectile.getUniqueId(), targetId,
                points, bullseye, eventId);
    }

    public synchronized void onProjectileCleanup(UUID projectileId) {
        discardProjectile(projectileId);
    }

    public synchronized void onProjectileCleanup(Projectile projectile) {
        Objects.requireNonNull(projectile, "projectile");
        discardProjectile(projectile.getUniqueId());
        projectile.getPersistentDataContainer().remove(PROJECTILE_SESSION_KEY);
        projectile.getPersistentDataContainer().remove(PROJECTILE_LANE_KEY);
    }

    /** Escape is terminal for this run; state restoration is not optional. */
    public synchronized boolean onMove(Player player, Location destination) {
        Objects.requireNonNull(player, "player");
        Active active = activeByPlayer.get(player.getUniqueId());
        if (active != null && !inside(active, destination, clock.instant())) {
            abort(active.player().getUniqueId(), "ESCAPED");
            return false;
        }
        return true;
    }

    public synchronized boolean onTeleport(Player player, Location destination) {
        return onMove(player, destination);
    }

    public synchronized void onDisconnect(UUID playerId) {
        abort(playerId, "DISCONNECT");
    }

    /** Completes shots, enforces timeout, and detects offline/out-of-bounds players. */
    public synchronized void tick(Instant now) {
        Objects.requireNonNull(now, "now");
        for (Active active : List.copyOf(activeByPlayer.values())) {
            if (active.game().result().isPresent()) {
                complete(active, "COMPLETED", now);
            } else if (!active.player().isOnline()) {
                abort(active.player().getUniqueId(), "DISCONNECT");
            } else if (!inside(active, active.player().getLocation(), now)) {
                abort(active.player().getUniqueId(), "ESCAPED");
            } else if (!now.isBefore(active.deadline())) {
                abort(active.player().getUniqueId(), "TIMEOUT");
            }
        }
    }

    public synchronized OptionalInt laneId(UUID playerId) {
        Active active = activeByPlayer.get(Objects.requireNonNull(playerId, "playerId"));
        return active == null ? OptionalInt.empty() : OptionalInt.of(active.lane().id());
    }

    /** Per-run identity used by the command/display and central event router. */
    public synchronized Optional<UUID> runId(UUID playerId) {
        Active active = activeByPlayer.get(Objects.requireNonNull(playerId, "playerId"));
        return active == null ? Optional.empty() : Optional.of(active.request().matchId());
    }

    public synchronized Optional<UUID> sessionId(UUID playerId) {
        Active active = activeByPlayer.get(Objects.requireNonNull(playerId, "playerId"));
        return active == null ? Optional.empty() : Optional.of(active.request().sessionId());
    }

    public synchronized Set<UUID> activePlayers() {
        return Set.copyOf(new LinkedHashSet<>(activeByPlayer.keySet()));
    }

    private boolean allTargetsReady() {
        try {
            return lanes.values().stream().allMatch(lane -> PaperArcheryTargetProbe.ready(
                    lane, regions.find(lane.regionId()).orElse(null), scoreBands));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void processHit(
            Active active,
            UUID projectileId,
            int targetLane,
            int points,
            boolean bullseye,
            String band,
            UUID eventId) {
        ProjectileMark projectile = consumeProjectile(active, projectileId);
        if (projectile.laneId() != targetLane || !projectile.playerId().equals(active.player().getUniqueId())) {
            throw new IllegalArgumentException("projectile is not a valid lane projectile");
        }
        validateScore(band, points, bullseye);
        active.game().shot(active.player().getUniqueId(), points, bullseye, eventId);
        if (active.game().result().isPresent()) {
            complete(active, "COMPLETED", clock.instant());
        }
    }

    private void complete(Active active, String reason, Instant finishedAt) {
        if (!removeActive(active)) {
            return;
        }
        cleanup(active.player().getUniqueId());
        AdmissionResult restored = restore(active, reason, false);
        tryClose(active.game());
        if (restored.status() == AdmissionStatus.RECOVERED) {
            ArcheryResult result = active.game().result().orElse(null);
            if (result == null) {
                recordNoContest(active, reason, finishedAt);
            } else {
                recordResult(active, result, finishedAt);
            }
        }
        admissions.revokeSession(active.request().sessionId());
    }

    private void abort(UUID playerId, String reason) {
        Active active = activeByPlayer.remove(Objects.requireNonNull(playerId, "playerId"));
        if (active == null) {
            return;
        }
        activeByLane.remove(active.lane().id(), playerId);
        cleanup(playerId);
        if (active.game().result().isEmpty()
                && active.game().phase() != ArcheryPhase.CLOSED) {
            try {
                active.game().cancel(UUID.randomUUID());
            } catch (RuntimeException ignored) {
                // The durable session remains the restoration authority.
            }
        }
        AdmissionResult restored = restore(active, reason, true);
        tryClose(active.game());
        if (restored.status() == AdmissionStatus.RECOVERED) {
            recordNoContest(active, reason, clock.instant());
        }
        admissions.revokeSession(active.request().sessionId());
    }

    private AdmissionResult restore(Active active, String reason, boolean recovery) {
        try {
            return recovery
                    ? sessions.recover(active.request().sessionId(), UUID.randomUUID(), reason)
                    : sessions.finishAndRestore(active.request().sessionId(), UUID.randomUUID(), reason);
        } catch (RuntimeException failure) {
            return AdmissionResult.rejected(
                    "RESTORE_FAILED",
                    "A recuperação da sessão precisa de revisão; o acesso ficou bloqueado.");
        }
    }

    private void recoverAfterJoinFailure(AdmissionRequest request) {
        try {
            sessions.recover(request.sessionId(), UUID.randomUUID(), "JOIN_FAILED");
        } catch (RuntimeException ignored) {
            // SessionCoordinator quarantines when durable restoration is ambiguous.
        }
    }

    private boolean removeActive(Active active) {
        UUID playerId = active.player().getUniqueId();
        if (activeByPlayer.get(playerId) != active) {
            return false;
        }
        activeByPlayer.remove(playerId);
        activeByLane.remove(active.lane().id(), playerId);
        return true;
    }

    private void grantTemporaryKit(Player player, UUID sessionId) {
        player.getInventory().setItemInMainHand(items.tag(
                new ItemStack(settings.bowMaterial()), sessionId, GameKey.ARCHERY_RANGE));
        player.getInventory().setItemInOffHand(items.tag(
                new ItemStack(Material.ARROW, settings.shotCount()), sessionId, GameKey.ARCHERY_RANGE));
        player.updateInventory();
    }

    private ProjectileMark consumeProjectile(Active active, UUID projectileId) {
        UUID id = Objects.requireNonNull(projectileId, "projectileId");
        ProjectileMark mark = projectiles.remove(id);
        if (mark == null
                || !mark.sessionId().equals(active.request().sessionId())
                || !mark.playerId().equals(active.player().getUniqueId())) {
            throw new IllegalArgumentException("projectile is not a server-tagged session projectile");
        }
        return mark;
    }

    private void discardProjectile(UUID projectileId) {
        if (projectileId != null) {
            projectiles.remove(projectileId);
        }
    }

    private void verifyProjectileTag(Player player, Projectile projectile) {
        ProjectileMark mark = projectiles.get(projectile.getUniqueId());
        String session = projectile.getPersistentDataContainer().get(
                PROJECTILE_SESSION_KEY, PersistentDataType.STRING);
        Integer lane = projectile.getPersistentDataContainer().get(
                PROJECTILE_LANE_KEY, PersistentDataType.INTEGER);
        if (mark == null
                || !mark.playerId().equals(player.getUniqueId())
                || !mark.sessionId().toString().equals(session)
                || !Integer.valueOf(mark.laneId()).equals(lane)) {
            discardProjectile(projectile.getUniqueId());
            throw new IllegalArgumentException("projectile is not a tagged session projectile");
        }
    }

    private void cleanup(UUID playerId) {
        projectiles.entrySet().removeIf(entry -> entry.getValue().playerId().equals(playerId));
    }

    private Active require(UUID playerId) {
        Active active = activeByPlayer.get(Objects.requireNonNull(playerId, "playerId"));
        if (active == null) {
            throw new IllegalStateException("no active archery lane");
        }
        return active;
    }

    private ArcheryPaperSettings.LaneSettings requireLane(int laneId) {
        ArcheryPaperSettings.LaneSettings lane = lanes.get(laneId);
        if (lane == null) {
            throw new IllegalArgumentException("unknown archery lane");
        }
        return lane;
    }

    private OptionalInt firstFreeLane() {
        return lanes.keySet().stream()
                .filter(id -> !activeByLane.containsKey(id))
                .mapToInt(Integer::intValue)
                .findFirst();
    }

    private boolean inside(Active active, Location location, Instant now) {
        return insideRegion(active.lane(), location)
                && admissions.permits(
                        active.player().getUniqueId(),
                        active.request().sessionId(),
                        active.lane().regionId(),
                        now);
    }

    private boolean insideRegion(ArcheryPaperSettings.LaneSettings lane, Location location) {
        return location != null
                && regions.at(location)
                        .map(ProtectedRegion::id)
                        .filter(lane.regionId()::equals)
                        .isPresent();
    }

    private boolean allRegionsValid() {
        for (ArcheryPaperSettings.LaneSettings lane : lanes.values()) {
            Optional<ProtectedRegion> region = regions.find(lane.regionId());
            if (region.isEmpty()
                    || region.orElseThrow().game() != GameKey.ARCHERY_RANGE
                    || !region.orElseThrow().requiresAdmission()
                    || !insideRegion(lane, lane.spawn())) {
                return false;
            }
        }
        return true;
    }

    private void issue(AdmissionRequest request, String regionId, Instant now) {
        admissions.issue(new RegionAdmissionToken(
                UUID.randomUUID(),
                request.sessionId(),
                request.playerId(),
                regionId,
                now,
                now.plus(settings.tokenTtl())));
    }

    private String uniqueBandFor(int points, boolean bullseye) {
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : scoreBands.entrySet()) {
            if (entry.getValue() == points && entry.getKey().equals("bullseye") == bullseye) {
                candidates.add(entry.getKey());
            }
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException("score is not uniquely defined by server configuration");
        }
        return candidates.get(0);
    }

    private int scoreForBand(String band) {
        Integer score = scoreBands.get(normalizeBand(band));
        if (score == null) {
            throw new IllegalArgumentException("score band is not configured");
        }
        return score;
    }

    private void validateScore(String band, int points, boolean bullseye) {
        if (band == null
                || scoreBands.get(band) == null
                || scoreBands.get(band) != points
                || band.equals("bullseye") != bullseye) {
            throw new IllegalArgumentException("score does not match server configuration");
        }
    }

    private String normalizeBand(String band) {
        String normalized = Objects.requireNonNull(band, "band").trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9_.-]{0,31}")) {
            throw new IllegalArgumentException("score band is invalid");
        }
        return normalized;
    }

    private void recordResult(Active active, ArcheryResult result, Instant finishedAt) {
        long elapsed = MatchResultFactory.boundedMillis(durationBetween(active.startedAt(), finishedAt));
        Map<String, Long> metrics = Map.of(
                "lane", MatchResultFactory.bounded(result.laneId()),
                "score", MatchResultFactory.bounded(result.score()),
                "shots", MatchResultFactory.bounded(result.shots()),
                "bullseyes", MatchResultFactory.bounded(result.bullseyes()),
                "time_ms", elapsed,
                "wins", 1L);
        statistics.record(MatchResultFactory.create(
                active.request().matchId(),
                GameKey.ARCHERY_RANGE,
                result.rulesetRevision(),
                "solo-lane-" + result.laneId(),
                active.startedAt(),
                finishedAt,
                MatchOutcome.VICTORY,
                result.reasonCode(),
                Map.of(active.player().getUniqueId(), new PlayerResult(
                        1, true, false, Optional.empty(), metrics))));
    }

    private void recordNoContest(Active active, String reason, Instant finishedAt) {
        ArcheryConfig config = configs.get(active.lane().id());
        Map<String, Long> metrics = Map.of(
                "lane", MatchResultFactory.bounded(active.lane().id()),
                "shots", 0L,
                "score", 0L,
                "no_contest", 1L);
        statistics.record(MatchResultFactory.create(
                active.request().matchId(),
                GameKey.ARCHERY_RANGE,
                config.rulesetRevision(),
                "solo-lane-" + active.lane().id(),
                active.startedAt(),
                finishedAt,
                MatchOutcome.NO_CONTEST,
                reason,
                Map.of(active.player().getUniqueId(), new PlayerResult(
                        0, false, true, Optional.empty(), metrics))));
    }

    private static Map<Integer, ArcheryPaperSettings.LaneSettings> sortedLanes(
            Map<Integer, ArcheryPaperSettings.LaneSettings> input) {
        LinkedHashMap<Integer, ArcheryPaperSettings.LaneSettings> result = new LinkedHashMap<>();
        input.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    private static Map<Integer, ArcheryConfig> validateConfigs(
            ArcheryPaperSettings settings,
            Map<Integer, ArcheryConfig> input) {
        Objects.requireNonNull(input, "configs");
        if (!input.keySet().equals(settings.lanes().keySet())) {
            throw new IllegalArgumentException("every configured lane needs one domain ruleset");
        }
        LinkedHashMap<Integer, ArcheryConfig> result = new LinkedHashMap<>();
        int maxConfiguredScore = settings.scoreBands().values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        for (Map.Entry<Integer, ArcheryConfig> entry : input.entrySet()) {
            ArcheryConfig config = Objects.requireNonNull(entry.getValue(), "lane config");
            if (entry.getKey() != config.laneId()
                    || config.shotCount() != settings.shotCount()
                    || config.maxScorePerShot() < maxConfiguredScore) {
                throw new IllegalArgumentException("lane domain does not match resolved archery settings");
            }
            result.put(entry.getKey(), config);
        }
        return Map.copyOf(result);
    }

    private static Map<String, Integer> resolveScoreBands(
            ArcheryPaperSettings settings,
            Map<Integer, ArcheryConfig> configs,
            boolean legacySingleLane) {
        if (!settings.scoreBands().isEmpty()) {
            return settings.scoreBands();
        }
        if (!legacySingleLane || configs.size() != 1) {
            throw new IllegalArgumentException("multi-lane archery requires configured score bands");
        }
        return Map.of("bullseye", configs.values().iterator().next().maxScorePerShot());
    }

    private static Instant plus(Instant instant, Duration duration) {
        try {
            return instant.plus(duration);
        } catch (ArithmeticException overflow) {
            return Instant.MAX;
        }
    }

    private static Duration durationBetween(Instant start, Instant end) {
        if (end.isBefore(start)) {
            return Duration.ZERO;
        }
        try {
            return Duration.between(start, end);
        } catch (ArithmeticException overflow) {
            return Duration.ofMillis(Long.MAX_VALUE);
        }
    }

    private static void tryClose(ArcherySession game) {
        if (game.phase() == ArcheryPhase.FINISHED || game.phase() == ArcheryPhase.CANCELLED) {
            try {
                game.close(UUID.randomUUID());
            } catch (RuntimeException ignored) {
                // Durable restoration remains authoritative if the domain close is replayed.
            }
        }
    }
}
