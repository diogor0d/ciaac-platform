package com.ciaac.minecraft.minigames.paper.anvildodge;

import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgeGame;
import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgeInput;
import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgePhase;
import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgeResult;
import com.ciaac.minecraft.minigames.hotpotato.OperationId;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
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
import com.ciaac.minecraft.minigames.statistics.MatchResult;
import com.ciaac.minecraft.minigames.statistics.recording.MatchResultFactory;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/** Disabled-by-default Bukkit controller for one Anvil Dodge instance. */
public final class AnvilDodgeController {
    private final UUID matchId;
    private final AnvilDodgeGame game;
    private final AnvilDodgePaperSettings settings;
    private final SessionCoordinator sessions;
    private final SessionRegistry sessionRegistry;
    private final RegionAdmissionRegistry admissions;
    private final ProtectedRegionRegistry regions;
    private final Clock clock;
    private final StatisticsResultSink statistics;
    private final NamespacedKey hazardKey;
    private final Set<ArmorStand> hazards = new LinkedHashSet<>();
    private long operationSequence;
    private Instant waveStarted;
    private int renderedWave;
    private boolean impactResolved;
    private boolean internalTeleport;

    public AnvilDodgeController(UUID matchId, AnvilDodgeGame game, AnvilDodgePaperSettings settings,
                                SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                RegionAdmissionRegistry admissions, ProtectedRegionRegistry regions,
                                Clock clock, Plugin plugin) {
        this(matchId, game, settings, sessions, sessionRegistry, admissions, regions, clock, plugin,
                StatisticsResultSink.unavailable());
    }

    public AnvilDodgeController(UUID matchId, AnvilDodgeGame game, AnvilDodgePaperSettings settings,
                                SessionCoordinator sessions, SessionRegistry sessionRegistry,
                                RegionAdmissionRegistry admissions, ProtectedRegionRegistry regions,
                                Clock clock, Plugin plugin, StatisticsResultSink statistics) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.game = Objects.requireNonNull(game, "game");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.hazardKey = new NamespacedKey(Objects.requireNonNull(plugin, "plugin"), "anvil-dodge-hazard");
    }

    public synchronized AdmissionResult join(Player player, AdmissionRequest request, RegionAdmissionToken token) {
        if (!settings.enabled()) return rejected("DISABLED", "A Fuga às Bigornas está temporariamente fechada.");
        Objects.requireNonNull(player, "player"); Objects.requireNonNull(request, "request"); Objects.requireNonNull(token, "token");
        Instant now = clock.instant();
        if (!ready(player, request, token, now)) return rejected("CONFIGURATION_UNAVAILABLE", "A arena ainda não está pronta.");
        admissions.issue(token);
        AdmissionResult prepared = sessions.prepare(request);
        if (prepared.status() != AdmissionStatus.PREPARED) { admissions.revokeSession(request.sessionId()); return prepared; }
        PlayerSession session = prepared.session().orElseThrow();
        try {
            if (game.phase() == AnvilDodgePhase.DISABLED) game.open(nextOperation());
            teleport(player, settings.start());
            if (!sessions.activate(session.sessionId(), OperationIds.derive(request.requestId(), "GAME_ACTIVE"), now)) {
                throw new IllegalStateException("session activation replay did not apply");
            }
            game.join(player.getUniqueId(), nextOperation());
            player.sendMessage("§aFuga às Bigornas: estás na fila. Aguarda o início.");
            return prepared;
        } catch (RuntimeException failure) {
            admissions.revokeSession(request.sessionId());
            sessions.recover(session.sessionId(), OperationIds.derive(request.requestId(), "JOIN_RECOVERY"), "JOIN_FAILED");
            return rejected("JOIN_FAILED", "Não foi possível preparar a tua entrada; o teu estado foi protegido.");
        }
    }

    /** Leaves the whole not-yet-started instance safely; active matches use the same recovery path. */
    public synchronized AdmissionResult leave(UUID playerId, UUID rootOperationId) {
        Objects.requireNonNull(playerId, "playerId"); Objects.requireNonNull(rootOperationId, "rootOperationId");
        PlayerSession session = sessionRegistry.findByPlayer(playerId).orElseThrow(() -> new IllegalArgumentException("No active session"));
        if (!session.matchId().equals(matchId)) throw new IllegalArgumentException("Session belongs to another match");
        recoverAll(rootOperationId, "PLAYER_LEFT");
        return new AdmissionResult(AdmissionStatus.RECOVERED, "LEFT", "Saíste da Fuga às Bigornas e o teu estado foi restaurado.", java.util.Optional.of(session));
    }

    public synchronized AnvilDodgePaperStatus status() {
        boolean ready = settings.enabled() && settings.world() != null && validRegion();
        String message = !settings.enabled() ? "A Fuga às Bigornas está fechada." : ready ? "A Fuga às Bigornas está disponível." : "A arena está fechada até a configuração ser validada.";
        return new AnvilDodgePaperStatus(settings.enabled(), ready, game.phase(), message);
    }

    public synchronized void tick(Instant now) {
        if (!settings.enabled() || !validRegion()) return;
        if (game.phase() == AnvilDodgePhase.WAITING && game.roster().size() >= settings.config().minimumPlayers()) {
            game.start(now, nextOperation()); waveStarted = now; renderedWave = 0; impactResolved = false;
        }
        if (game.phase() == AnvilDodgePhase.RUNNING) {
            if (renderedWave != game.wave()) { clearHazards(); renderHazards(); renderedWave = game.wave(); impactResolved = false; if (waveStarted == null) waveStarted = now; }
            animateHazards(now);
            if (!impactResolved && waveStarted != null
                    && !now.isBefore(waveStarted.plus(settings.config().waveDuration()))) {
                impactResolved = true;
                resolveImpact(now);
            }
            if (waveStarted != null && !now.isBefore(waveStarted.plus(settings.config().waveDuration()))) {
                if (game.phase() == AnvilDodgePhase.RUNNING) game.completeWave(nextOperation());
                waveStarted = now;
            }
        }
        if (game.phase() == AnvilDodgePhase.FINISHING) { clearHazards(); restoreAll(UUID.nameUUIDFromBytes((matchId + ":ANVIL_RESULT").getBytes(java.nio.charset.StandardCharsets.UTF_8)), "RESULT"); }
    }

    public synchronized void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = sessionRegistry.findByPlayer(player.getUniqueId()).orElse(null);
        if (session == null || !session.matchId().equals(matchId) || session.phase() != SessionPhase.ACTIVE) return;
        Location to = event.getTo();
        if (to == null || !settings.world().equals(to.getWorld()) || !regions.at(to).filter(r -> r.id().equals(settings.participantRegionId())).isPresent()
                || !admissions.permits(player.getUniqueId(), session.sessionId(), settings.participantRegionId(), clock.instant())) {
            event.setCancelled(true);
        }
    }

    public synchronized void onTeleport(PlayerTeleportEvent event) {
        if (!internalTeleport && sessionRegistry.findByPlayer(event.getPlayer().getUniqueId()).filter(s -> s.matchId().equals(matchId) && s.phase() == SessionPhase.ACTIVE).isPresent()) {
            event.setCancelled(true);
        }
    }

    /** Bounded floor-cell event translated by the hazard adapter. */
    public synchronized void onDodge(Player player, int floorCell, Instant now) {
        new AnvilDodgeInput(Objects.requireNonNull(player).getUniqueId(), floorCell);
        if (game.phase() != AnvilDodgePhase.RUNNING || !game.livePlayers().contains(player.getUniqueId())) return;
        game.dodge(player.getUniqueId(), nextOperation());
    }

    /** Controlled hazard impact; normal Bukkit death/drops remain cancelled by the shared listener. */
    public synchronized void onHazardHit(Player player, Instant now) {
        if (game.phase() == AnvilDodgePhase.RUNNING && game.livePlayers().contains(Objects.requireNonNull(player).getUniqueId())) {
            game.eliminate(player.getUniqueId(), Objects.requireNonNull(now), nextOperation());
        }
    }

    public synchronized void onDisconnect(UUID playerId, UUID rootOperationId) { leave(playerId, rootOperationId); }

    public synchronized void shutdown(UUID rootOperationId) { clearHazards(); recoverAll(rootOperationId, "PLUGIN_DISABLED"); }

    private void recoverAll(UUID root, String reason) {
        Set<UUID> participants = sessionRegistry.findByMatch(matchId).stream().map(PlayerSession::playerId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (game.phase() == AnvilDodgePhase.WAITING || game.phase() == AnvilDodgePhase.RUNNING) game.recover(nextOperation());
        if (game.phase() == AnvilDodgePhase.FINISHING || game.phase() == AnvilDodgePhase.RECOVERING) game.close(nextOperation());
        clearHazards();
        boolean restored = true;
        for (PlayerSession session : sessionRegistry.findByMatch(matchId)) {
            if (!session.phase().terminal()) {
                restored &= sessions.recover(session.sessionId(), OperationIds.derive(root, "RESTORE_" + session.playerId()), reason).status() == AdmissionStatus.RECOVERED;
                admissions.revokeSession(session.sessionId());
            }
        }
        if (game.phase() == AnvilDodgePhase.RECOVERING) game.close(nextOperation());
        if (restored && !participants.isEmpty()) recordNoContest(participants, reason);
    }
    private void restoreAll(UUID root, String reason) {
        AnvilDodgeResult terminal = game.result().orElse(null);
        Set<UUID> participants = sessionRegistry.findByMatch(matchId).stream().map(PlayerSession::playerId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        clearHazards();
        boolean restored = true;
        for (PlayerSession session : sessionRegistry.findByMatch(matchId)) {
            if (!session.phase().terminal()) { restored &= sessions.finishAndRestore(session.sessionId(), OperationIds.derive(root, "FINISH_" + session.playerId()), reason).status() == AdmissionStatus.RECOVERED; admissions.revokeSession(session.sessionId()); }
        }
        if (game.phase() == AnvilDodgePhase.FINISHING) game.close(nextOperation());
        if (restored && !participants.isEmpty()) {
            if (terminal == null) recordNoContest(participants, reason);
            else recordResult(terminal, participants);
        }
    }

    private void recordResult(AnvilDodgeResult terminal, Set<UUID> participants) {
        Instant finished = clock.instant();
        boolean completed = terminal.completed();
        Map<UUID, com.ciaac.minecraft.minigames.statistics.PlayerResult> standings = new java.util.LinkedHashMap<>();
        for (UUID player : participants) {
            long dodges = MatchResultFactory.bounded(terminal.dodges().getOrDefault(player, 0));
            boolean winner = completed && terminal.survivors().contains(player);
            Map<String, Long> metrics = Map.of(
                    "survival_ms", MatchResultFactory.boundedMillis(terminal.elapsed()),
                    "waves", MatchResultFactory.bounded(terminal.wavesSurvived()),
                    "dodges", dodges,
                    "wins", winner ? 1L : 0L);
            standings.put(player, new com.ciaac.minecraft.minigames.statistics.PlayerResult(
                    winner ? 1 : completed ? 2 : 0, winner, false,
                    winner ? java.util.Optional.of("survivors") : java.util.Optional.empty(), metrics));
        }
        statistics.record(MatchResultFactory.create(matchId, GameKey.ANVIL_DODGE,
                terminal.rulesetRevision(), "ffa", finished.minus(terminal.elapsed()), finished,
                completed ? com.ciaac.minecraft.minigames.statistics.MatchOutcome.VICTORY
                        : com.ciaac.minecraft.minigames.statistics.MatchOutcome.NO_CONTEST,
                terminal.reasonCode(), standings));
    }

    private void recordNoContest(Set<UUID> participants, String reason) {
        Instant finished = clock.instant();
        MatchResult result = MatchResultFactory.noContest(matchId, GameKey.ANVIL_DODGE,
                settings.config().rulesetRevision(), "ffa", finished, finished, reason, participants);
        statistics.record(result);
    }

    private void renderHazards() {
        var wave = game.plan().get(game.wave() - 1);
        for (int cell : wave.hazardCells()) {
            int x = cell % settings.floorWidth(); int z = cell / settings.floorWidth();
            Location location = settings.floorOrigin().clone().add(x + .5, 8, z + .5);
            ArmorStand stand = settings.world().spawn(location, ArmorStand.class, value -> {
                value.setVisible(false); value.setMarker(true); value.setGravity(false); value.setInvulnerable(true); value.setPersistent(false);
                value.getPersistentDataContainer().set(hazardKey, org.bukkit.persistence.PersistentDataType.STRING, matchId.toString());
                value.getEquipment().setHelmet(new org.bukkit.inventory.ItemStack(Material.ANVIL));
            });
            hazards.add(stand);
            settings.world().spawnParticle(org.bukkit.Particle.DUST,
                    settings.floorOrigin().clone().add(x + .5, 1.05, z + .5), 12,
                    0.28, 0.03, 0.28, 0.0,
                    new org.bukkit.Particle.DustOptions(org.bukkit.Color.RED, 1.4f));
        }
        for (PlayerSession session : sessionRegistry.findByMatch(matchId)) {
            settings.world().getPlayers().stream()
                    .filter(player -> player.getUniqueId().equals(session.playerId()))
                    .findFirst()
                    .ifPresent(player -> player.playSound(player.getLocation(),
                            org.bukkit.Sound.BLOCK_ANVIL_LAND, 0.65f, 1.55f));
        }
    }
    private void animateHazards(Instant now) {
        if (waveStarted == null || hazards.isEmpty()) return;
        Duration fallingWindow = settings.config().waveDuration().minus(settings.config().warning());
        Duration sinceWarning = Duration.between(waveStarted.plus(settings.config().warning()), now);
        double progress = sinceWarning.isNegative() ? 0.0
                : Math.min(1.0, sinceWarning.toNanos() / (double) fallingWindow.toNanos());
        double y = settings.floorOrigin().getY() + 8.0 * (1.0 - progress);
        var wave = game.plan().get(game.wave() - 1);
        int index = 0;
        for (ArmorStand stand : hazards) {
            if (!stand.isValid() || index >= wave.hazardCells().size()) { index++; continue; }
            int cell = wave.hazardCells().get(index++);
            int x = cell % settings.floorWidth(); int z = cell / settings.floorWidth();
            stand.teleport(new Location(settings.world(),
                    settings.floorOrigin().getX() + x + .5, y,
                    settings.floorOrigin().getZ() + z + .5));
        }
    }
    private void resolveImpact(Instant now) {
        Set<Integer> danger = Set.copyOf(game.plan().get(game.wave() - 1).hazardCells());
        for (UUID playerId : Set.copyOf(game.livePlayers())) {
            Player player = settings.world().getPlayers().stream()
                    .filter(candidate -> candidate.getUniqueId().equals(playerId)).findFirst().orElse(null);
            OptionalInt cell = player == null ? OptionalInt.empty() : floorCell(player.getLocation());
            if (cell.isEmpty() || danger.contains(cell.getAsInt())) {
                if (player != null) {
                    player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_LAND, 1.0f, 0.75f);
                    player.sendMessage("§cFoste atingido por uma bigorna e ficaste eliminado desta ronda.");
                }
                if (game.phase() == AnvilDodgePhase.RUNNING && game.livePlayers().contains(playerId)) {
                    game.eliminate(playerId, now, nextOperation());
                }
            } else if (game.phase() == AnvilDodgePhase.RUNNING && game.livePlayers().contains(playerId)) {
                game.dodge(playerId, nextOperation());
            }
        }
        clearHazards();
    }
    private OptionalInt floorCell(Location location) {
        int x = location.getBlockX() - settings.floorOrigin().getBlockX();
        int z = location.getBlockZ() - settings.floorOrigin().getBlockZ();
        if (x < 0 || z < 0 || x >= settings.floorWidth() || z >= settings.floorDepth()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(z * settings.floorWidth() + x);
    }
    private void clearHazards() { hazards.removeIf(h -> { if (h != null && h.isValid()) h.remove(); return true; }); }
    private void teleport(Player player, Location location) { internalTeleport = true; try { if (!player.teleport(location.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN)) throw new IllegalStateException("teleport rejected"); } finally { internalTeleport = false; } }
    private boolean ready(Player player, AdmissionRequest request, RegionAdmissionToken token, Instant now) {
        return player.isOnline() && player.getUniqueId().equals(request.playerId()) && request.game() == GameKey.ANVIL_DODGE
                && request.matchId().equals(matchId) && token.playerId().equals(player.getUniqueId()) && token.sessionId().equals(request.sessionId())
                && token.regionId().equals(settings.participantRegionId()) && token.validAt(now) && settings.world().isChunkLoaded(settings.start().getBlockX() >> 4, settings.start().getBlockZ() >> 4) && validRegion();
    }
    private boolean validRegion() { return regions.find(settings.participantRegionId()).filter(r -> r.game() == GameKey.ANVIL_DODGE && r.requiresAdmission() && r.immutable() && r.bounds().worldId().equals(settings.world().getUID())).isPresent(); }
    private OperationId nextOperation() { return new OperationId(matchId, ++operationSequence); }
    private static AdmissionResult rejected(String code, String message) { return AdmissionResult.rejected(code, message); }
}
