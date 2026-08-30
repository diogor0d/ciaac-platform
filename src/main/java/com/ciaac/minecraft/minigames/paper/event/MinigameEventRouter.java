package com.ciaac.minecraft.minigames.paper.event;

import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgePhase;
import com.ciaac.minecraft.minigames.arena.ArenaMatch;
import com.ciaac.minecraft.minigames.arena.ArenaPhase;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattlePhase;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattlePlot;
import com.ciaac.minecraft.minigames.hotpotato.HotPotatoPhase;
import com.ciaac.minecraft.minigames.paper.anvildodge.AnvilDodgeController;
import com.ciaac.minecraft.minigames.paper.archeryrange.ArcheryPaperController;
import com.ciaac.minecraft.minigames.paper.colorfloor.ColorFloorController;
import com.ciaac.minecraft.minigames.paper.elytrarings.ElytraRingsController;
import com.ciaac.minecraft.minigames.paper.module.AnvilDodgeModule;
import com.ciaac.minecraft.minigames.paper.module.ArcheryModule;
import com.ciaac.minecraft.minigames.paper.module.BuildBattleModule;
import com.ciaac.minecraft.minigames.paper.module.ColiseumModule;
import com.ciaac.minecraft.minigames.paper.module.ColorFloorModule;
import com.ciaac.minecraft.minigames.paper.module.ElytraRingsModule;
import com.ciaac.minecraft.minigames.paper.module.HotPotatoModule;
import com.ciaac.minecraft.minigames.paper.module.ParkourModule;
import com.ciaac.minecraft.minigames.paper.module.SumoModule;
import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

/**
 * Bukkit event boundary for the nine canonical minigame module wrappers.
 *
 * <p>The listener deliberately does not discover game geometry or infer plot,
 * target, or player ownership from mutable world state. Those values are
 * supplied by the runtime through {@link Policies}; the default constructor
 * uses {@link Policies#failClosed()} so an unconfigured listener cannot grant
 * an escape, a build, a vote, or a scored hit.</p>
 */
public final class MinigameEventRouter implements Listener {
    private static final Duration FEEDBACK_COOLDOWN = Duration.ofSeconds(2);

    @FunctionalInterface
    public interface BoundaryPolicy {
        boolean contains(Player player, Location destination);
    }

    @FunctionalInterface
    public interface PlayerScope {
        boolean contains(Player player);
    }

    @FunctionalInterface
    public interface CheckpointResolver {
        OptionalInt resolve(Player player, Location destination);
    }

    @FunctionalInterface
    public interface ArcheryHitResolver {
        Optional<ArcheryHit> resolve(Player shooter, ProjectileHitEvent event);
    }

    @FunctionalInterface
    public interface BuildBattleVoteResolver {
        /**
         * Resolves a vote interaction. Exactly one of {@code clickedEntity}
         * and {@code clickedBlock} may be non-null; an entity interaction has
         * a null {@code action}.
         */
        Optional<BuildBattleVote> resolve(
                Player voter, Entity clickedEntity, Block clickedBlock, Action action);
    }

    @FunctionalInterface
    public interface BuildBattleVoteCommandResolver {
        /** Returns a vote only for a fully parsed, authenticated vote command. */
        Optional<BuildBattleVote> resolve(Player voter, String rawCommand);
    }

    @FunctionalInterface
    public interface AnvilHazardResolver {
        boolean isArenaHazard(EntityDamageEvent event);
    }

    /** Reports cleanup failures without exposing controller diagnostics to players. */
    @FunctionalInterface
    public interface FailureReporter {
        void report(String route);

        static FailureReporter noop() { return route -> { }; }
    }

    @FunctionalInterface
    public interface AnvilCellResolver {
        OptionalInt resolve(Player player, Location destination);
    }

    @FunctionalInterface
    interface HeldItemRoute {
        boolean route(PlayerInteractEvent event);
    }

    public record ArcheryHit(int lane, int points, boolean bullseye) {
        public ArcheryHit {
            if (lane < 0 || points < 0) throw new IllegalArgumentException("invalid archery hit");
        }
    }

    public record BuildBattleVote(BuildBattlePlot plot, int score) {
        public BuildBattleVote {
            Objects.requireNonNull(plot, "plot");
            if (score < 0) throw new IllegalArgumentException("score must not be negative");
        }
    }

    /** Runtime-owned mappings and geometry needed by event-only adapters. */
    public record Policies(
            BoundaryPolicy coliseumFloor,
            BoundaryPolicy parkourLane,
            BoundaryPolicy archeryLane,
            PlayerScope sumoParticipants,
            CheckpointResolver parkourCheckpoints,
            ArcheryHitResolver archeryHits,
            PlayerScope buildBattleParticipants,
            BuildBattleVoteResolver buildBattleVotes,
            BuildBattleVoteCommandResolver buildBattleVoteCommands,
            PlayerScope hotPotatoParticipants,
            PlayerScope anvilParticipants,
            AnvilCellResolver anvilCells,
            AnvilHazardResolver anvilHazards) {
        public Policies {
            Objects.requireNonNull(coliseumFloor, "coliseumFloor");
            Objects.requireNonNull(parkourLane, "parkourLane");
            Objects.requireNonNull(archeryLane, "archeryLane");
            Objects.requireNonNull(sumoParticipants, "sumoParticipants");
            Objects.requireNonNull(parkourCheckpoints, "parkourCheckpoints");
            Objects.requireNonNull(archeryHits, "archeryHits");
            Objects.requireNonNull(buildBattleParticipants, "buildBattleParticipants");
            Objects.requireNonNull(buildBattleVotes, "buildBattleVotes");
            Objects.requireNonNull(buildBattleVoteCommands, "buildBattleVoteCommands");
            Objects.requireNonNull(hotPotatoParticipants, "hotPotatoParticipants");
            Objects.requireNonNull(anvilParticipants, "anvilParticipants");
            Objects.requireNonNull(anvilCells, "anvilCells");
            Objects.requireNonNull(anvilHazards, "anvilHazards");
        }

        /**
         * Safe default for registration before the runtime has supplied its
         * region, roster, target, and plot adapters.
         */
        public static Policies failClosed() {
            return new Policies(
                    (player, destination) -> false,
                    (player, destination) -> false,
                    (player, destination) -> false,
                    player -> false,
                    (player, destination) -> OptionalInt.empty(),
                    (player, event) -> Optional.empty(),
                    player -> false,
                    (voter, entity, block, action) -> Optional.empty(),
                    (voter, rawCommand) -> Optional.empty(),
                    player -> false,
                    player -> false,
                    (player, destination) -> OptionalInt.empty(),
                    event -> false);
        }
    }

    private final ColiseumModule coliseum;
    private final SumoModule sumo;
    private final ParkourModule parkour;
    private final ArcheryModule archery;
    private final BuildBattleModule buildBattle;
    private final HotPotatoModule hotPotato;
    private final AnvilDodgeModule anvilDodge;
    private final ColorFloorModule colorFloor;
    private final ElytraRingsModule elytraRings;
    private final Clock clock;
    private final Policies policies;
    private final FailureReporter failureReporter;
    private final Map<UUID, UUID> parkourCheckpointRuns = new HashMap<>();
    private final Map<UUID, Integer> parkourLastCheckpoints = new HashMap<>();
    private final Map<UUID, Integer> anvilLastCells = new HashMap<>();
    private final Map<UUID, Instant> lastFeedback = new HashMap<>();
    private long eventSequence;

    public MinigameEventRouter(
            ColiseumModule coliseum,
            SumoModule sumo,
            ParkourModule parkour,
            ArcheryModule archery,
            BuildBattleModule buildBattle,
            HotPotatoModule hotPotato,
            AnvilDodgeModule anvilDodge,
            ColorFloorModule colorFloor,
            ElytraRingsModule elytraRings) {
        this(coliseum, sumo, parkour, archery, buildBattle, hotPotato,
                anvilDodge, colorFloor, elytraRings, Clock.systemUTC(), Policies.failClosed(),
                FailureReporter.noop());
    }

    /** Builds one modular listener; disabled modules are represented by null-safe absent routes. */
    public static MinigameEventRouter fromRegistry(
            MinigameModuleRegistry modules,
            Clock clock,
            Policies policies) {
        return fromRegistry(modules, clock, policies, FailureReporter.noop());
    }

    public static MinigameEventRouter fromRegistry(
            MinigameModuleRegistry modules,
            Clock clock,
            Policies policies,
            FailureReporter failureReporter) {
        Objects.requireNonNull(modules, "modules");
        return new MinigameEventRouter(
                typed(modules, GameKey.ARENA, ColiseumModule.class),
                typed(modules, GameKey.KNOCKBACK_SUMO, SumoModule.class),
                typed(modules, GameKey.CHECKPOINT_PARKOUR, ParkourModule.class),
                typed(modules, GameKey.ARCHERY_RANGE, ArcheryModule.class),
                typed(modules, GameKey.BUILD_BATTLE, BuildBattleModule.class),
                typed(modules, GameKey.HOT_POTATO, HotPotatoModule.class),
                typed(modules, GameKey.ANVIL_DODGE, AnvilDodgeModule.class),
                typed(modules, GameKey.COLOR_FLOOR, ColorFloorModule.class),
                typed(modules, GameKey.ELYTRA_RINGS, ElytraRingsModule.class),
                clock, policies, failureReporter, true);
    }

    public MinigameEventRouter(
            ColiseumModule coliseum,
            SumoModule sumo,
            ParkourModule parkour,
            ArcheryModule archery,
            BuildBattleModule buildBattle,
            HotPotatoModule hotPotato,
            AnvilDodgeModule anvilDodge,
            ColorFloorModule colorFloor,
            ElytraRingsModule elytraRings,
            Clock clock,
            Policies policies) {
        this(coliseum, sumo, parkour, archery, buildBattle, hotPotato,
                anvilDodge, colorFloor, elytraRings, clock, policies, FailureReporter.noop());
    }

    public MinigameEventRouter(
            ColiseumModule coliseum,
            SumoModule sumo,
            ParkourModule parkour,
            ArcheryModule archery,
            BuildBattleModule buildBattle,
            HotPotatoModule hotPotato,
            AnvilDodgeModule anvilDodge,
            ColorFloorModule colorFloor,
            ElytraRingsModule elytraRings,
            Clock clock,
            Policies policies,
            FailureReporter failureReporter) {
        this.coliseum = Objects.requireNonNull(coliseum, "coliseum");
        this.sumo = Objects.requireNonNull(sumo, "sumo");
        this.parkour = Objects.requireNonNull(parkour, "parkour");
        this.archery = Objects.requireNonNull(archery, "archery");
        this.buildBattle = Objects.requireNonNull(buildBattle, "buildBattle");
        this.hotPotato = Objects.requireNonNull(hotPotato, "hotPotato");
        this.anvilDodge = Objects.requireNonNull(anvilDodge, "anvilDodge");
        this.colorFloor = Objects.requireNonNull(colorFloor, "colorFloor");
        this.elytraRings = Objects.requireNonNull(elytraRings, "elytraRings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    private MinigameEventRouter(
            ColiseumModule coliseum,
            SumoModule sumo,
            ParkourModule parkour,
            ArcheryModule archery,
            BuildBattleModule buildBattle,
            HotPotatoModule hotPotato,
            AnvilDodgeModule anvilDodge,
            ColorFloorModule colorFloor,
            ElytraRingsModule elytraRings,
            Clock clock,
            Policies policies,
            FailureReporter failureReporter,
            boolean allowMissingModules) {
        if (!allowMissingModules) throw new IllegalArgumentException("modular constructor flag is required");
        this.coliseum = coliseum;
        this.sumo = sumo;
        this.parkour = parkour;
        this.archery = archery;
        this.buildBattle = buildBattle;
        this.hotPotato = hotPotato;
        this.anvilDodge = anvilDodge;
        this.colorFloor = colorFloor;
        this.elytraRings = elytraRings;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    private static <T> T typed(MinigameModuleRegistry modules, GameKey key, Class<T> type) {
        Object module = modules.get(key);
        return type.isInstance(module) ? type.cast(module) : null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        if (event.isCancelled() || event instanceof PlayerTeleportEvent || !event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        Location destination = event.getTo();
        if (destination == null) {
            event.setCancelled(true);
            feedback(player, "O movimento foi recusado para proteger a tua sessão.");
            return;
        }

        routeColiseumBoundary(event, player, destination);
        if (event.isCancelled()) return;
        routeSumoBoundary(event, player);
        if (event.isCancelled()) return;
        routeParkour(event, player, destination);
        if (event.isCancelled()) return;
        routeArchery(event, player, destination);
        if (event.isCancelled()) return;
        routeBuildBattleMovement(event, player, destination);
        if (event.isCancelled()) return;
        routeHotPotatoMovement(event, player, destination);
        if (event.isCancelled()) return;
        routeAnvilDodge(event, player, destination);
        if (event.isCancelled()) return;
        routeColorFloor(event, player);
        if (event.isCancelled()) return;
        routeElytraRings(event, player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        Player player = event.getPlayer();
        Location destination = event.getTo();
        if (destination == null) {
            event.setCancelled(true);
            feedback(player, "O teleporte foi recusado para proteger a tua sessão.");
            return;
        }

        routeColiseumBoundary(event, player, destination);
        if (event.isCancelled()) return;
        routeSumoBoundary(event, player);
        if (event.isCancelled()) return;
        routeParkourTeleport(event, player, destination);
        if (event.isCancelled()) return;
        routeArcheryTeleport(event, player, destination);
        if (event.isCancelled()) return;
        routeBuildBattleTeleport(event, player, destination);
        if (event.isCancelled()) return;
        routeHotPotatoTeleport(event, player, destination);
        if (event.isCancelled()) return;
        routeAnvilDodgeTeleport(event);
        if (event.isCancelled()) return;
        routeColorFloorTeleport(event);
        if (event.isCancelled()) return;
        routeElytraTeleport(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Player victim)) return;
        if (routeArcheryDamage(event)) return;
        if (routeColiseumLethalDamage(event, victim)) return;
        if (event instanceof EntityDamageByEntityEvent byEntity
                && routeSumoCombat(event, byEntity, victim)) return;
        if (routeSumoFall(event, victim)) return;
        if (routeAnvilHazard(event, victim)) return;
        routeHotPotatoExplosion(event, victim);
    }

    private boolean routeArcheryDamage(EntityDamageEvent event) {
        if (archery == null || !(event instanceof EntityDamageByEntityEvent byEntity)
                || !(byEntity.getDamager() instanceof Projectile projectile)
                || !(projectile.getShooter() instanceof Player shooter)
                || archery.runId(shooter.getUniqueId()).isEmpty()) return false;
        event.setCancelled(true);
        feedback(shooter, "O dano do projétil foi anulado; o resultado depende do alvo validado.");
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Optional<ArenaMatch> current = currentColiseumMatch();
        if (current.isPresent() && current.orElseThrow().participants().contains(player.getUniqueId())) {
            ArenaPhase phase = current.orElseThrow().phase();
            if (phase != ArenaPhase.ACTIVE && phase != ArenaPhase.FINISHING
                    && phase != ArenaPhase.RESTORING && phase != ArenaPhase.RECOVERING) return;
            protectDeath(event, player);
            if (phase == ArenaPhase.ACTIVE && current.orElseThrow().activePlayers().contains(player.getUniqueId())) {
                try {
                    coliseum.controller().eliminate(player, "DEATH_EVENT");
                } catch (RuntimeException failure) {
                    feedback(player, "A eliminação foi protegida; o estado do Coliseu será recuperado.");
                }
            }
            return;
        }
        if (sumoRunning() && inScope(policies.sumoParticipants(), player)) {
            protectDeath(event, player);
            try {
                sumo.controller().onRingOut(player.getUniqueId(), eventId("sumo-death", player));
            } catch (RuntimeException ignored) {
                // A controlled death remains cancelled even if the match is already terminal.
            }
            feedback(player, "A morte foi anulada; saíste do ringue de Sumo.");
            return;
        }
        if (hotPotatoRunning() && inScope(policies.hotPotatoParticipants(), player)) {
            protectDeath(event, player);
            try {
                hotPotato.controller().onElimination(
                        player.getUniqueId(), eventId("hot-potato-death", player), "DEATH_EVENT");
            } catch (RuntimeException ignored) {
                // Recovery remains authoritative when the game has already finished.
            }
            feedback(player, "A morte foi anulada; a Batata Quente vai resolver a eliminação.");
            return;
        }
        AnvilDodgeController anvil = anvilDodge == null ? null : anvilDodge.controller();
        if (anvil != null && inScope(policies.anvilParticipants(), player)
                && anvilRunning(anvil)) {
            protectDeath(event, player);
            try {
                anvil.onHazardHit(player, clock.instant());
            } catch (RuntimeException ignored) {
                // Do not restore vanilla drops after a controlled death.
            }
            feedback(player, "A morte foi anulada; a Fuga às Bigornas vai resolver o impacto.");
            return;
        }
        if (parkour != null && parkour.runId(player.getUniqueId()).isPresent()) {
            protectDeath(event, player);
            try {
                parkour.controller().onFallOrLeave(
                        player.getUniqueId(), eventId("parkour-death", player));
            } catch (RuntimeException ignored) {
                // The event remains cancelled if the run is already recovering.
            }
            feedback(player, "A morte foi anulada; a corrida de parkour foi protegida.");
            return;
        }
        if (archery != null && archery.runId(player.getUniqueId()).isPresent()) {
            protectDeath(event, player);
            try {
                archery.controller().onDisconnect(player.getUniqueId());
            } catch (RuntimeException ignored) {
                // The event remains cancelled if the lane is already recovering.
            }
            feedback(player, "A morte foi anulada; a sessão de tiro foi protegida.");
            return;
        }
        ColorFloorController color = colorFloor == null ? null : colorFloor.controller();
        if (color != null) {
            try {
                if (color.onDeath(player.getUniqueId(), eventId("color-floor-death", player))) {
                    protectDeath(event, player);
                    feedback(player, "A morte foi anulada; o Chão de Cores vai recuperar a sessão.");
                    return;
                }
            } catch (RuntimeException failure) {
                protectDeath(event, player);
                feedback(player, "A morte foi anulada; o Chão de Cores vai proteger o teu estado.");
                return;
            }
        }
        ElytraRingsController elytra = elytraRings == null ? null : elytraRings.controller();
        if (elytra != null) {
            try {
                if (elytra.onDeath(player.getUniqueId(), eventId("elytra-death", player))) {
                    protectDeath(event, player);
                    feedback(player, "A morte foi anulada; os Anéis de Elytra vão recuperar a sessão.");
                    return;
                }
            } catch (RuntimeException failure) {
                protectDeath(event, player);
                feedback(player, "A morte foi anulada; os Anéis de Elytra vão proteger o teu estado.");
                return;
            }
        }
        if (buildBattleActive() && inScope(policies.buildBattleParticipants(), player)) {
            protectDeath(event, player);
            try {
                buildBattle.controller().disconnect(player.getUniqueId());
            } catch (RuntimeException ignored) {
                // The event remains cancelled while the Build Battle recovers.
            }
            feedback(player, "A morte foi anulada; o Build Battle vai recuperar a arena.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (isColiseumParticipant(event.getPlayer())) {
            event.setCancelled(true);
            feedback(event.getPlayer(), "Não podes largar itens durante o combate do Coliseu.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        ColorFloorController color = colorFloor == null ? null : colorFloor.controller();
        if (color != null) {
            try {
                color.onBlockBreak(event);
            } catch (RuntimeException failure) {
                event.setCancelled(true);
            }
        }
        if (event.isCancelled()) return;
        ElytraRingsController elytra = elytraRings == null ? null : elytraRings.controller();
        if (elytra != null) {
            try {
                if (elytra.onBlockBreak(event)) return;
            } catch (RuntimeException failure) {
                event.setCancelled(true);
                return;
            }
        }
        enforceBuildBattle(event.getPlayer(), event.getBlock().getLocation(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        ColorFloorController color = colorFloor == null ? null : colorFloor.controller();
        if (color != null) {
            try {
                color.onBlockPlace(event);
            } catch (RuntimeException failure) {
                event.setCancelled(true);
            }
        }
        if (event.isCancelled()) return;
        ElytraRingsController elytra = elytraRings == null ? null : elytraRings.controller();
        if (elytra != null) {
            try {
                if (elytra.onBlockPlace(event)) return;
            } catch (RuntimeException failure) {
                event.setCancelled(true);
                return;
            }
        }
        enforceBuildBattle(event.getPlayer(), event.getBlockPlaced().getLocation(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!event.isCancelled()) {
            enforceBuildBattle(event.getPlayer(), event.getBlock().getLocation(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!event.isCancelled()) {
            enforceBuildBattle(event.getPlayer(), event.getBlock().getLocation(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.isCancelled() || !(event.getEntity().getShooter() instanceof Player shooter)) return;
        if (archery != null && archery.runId(shooter.getUniqueId()).isPresent()) {
            try {
                archery.controller().launch(shooter.getUniqueId(), event.getEntity().getUniqueId());
            } catch (RuntimeException failure) {
                event.setCancelled(true);
                feedback(shooter, "O disparo foi recusado para proteger a sessão de tiro.");
            }
            return;
        }
        ElytraRingsController elytra = elytraRings == null ? null : elytraRings.controller();
        if (elytra != null) {
            try {
                if (elytra.onProjectileLaunch(event)) {
                    feedback(shooter, "Não podes usar pérolas do End durante os Anéis de Elytra.");
                }
            } catch (RuntimeException failure) {
                event.setCancelled(true);
                feedback(shooter, "O projétil foi recusado para proteger os Anéis de Elytra.");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (archery == null) return;
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player shooter)
                || archery.runId(shooter.getUniqueId()).isEmpty()) return;
        ArcheryPaperController controller = archery.controller();
        Optional<ArcheryHit> hit;
        try {
            Optional<ArcheryHit> resolved = policies.archeryHits().resolve(shooter, event);
            hit = resolved == null ? Optional.empty() : resolved;
        } catch (RuntimeException failure) {
            hit = Optional.empty();
        }
        if (hit.isEmpty()) {
            cleanupProjectile(controller, projectile.getUniqueId());
            event.setCancelled(true);
            feedback(shooter, "O alvo do disparo não foi reconhecido; o tiro foi anulado.");
            return;
        }
        try {
            ArcheryHit result = hit.orElseThrow();
            controller.onProjectileHit(shooter.getUniqueId(), projectile.getUniqueId(), result.lane(),
                    result.points(), result.bullseye(), eventId("archery-hit", shooter));
        } catch (RuntimeException failure) {
            cleanupProjectile(controller, projectile.getUniqueId());
            event.setCancelled(true);
            feedback(shooter, "O resultado do disparo não foi validado; o tiro foi anulado.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.isCancelled()) return;
        Player source = event.getPlayer();
        if (event.getRightClicked() instanceof Player target
                && isHotPotatoPassAttempt(source, event.getHand())
                && inScope(policies.hotPotatoParticipants(), source)
                && inScope(policies.hotPotatoParticipants(), target)
                && hotPotatoRunning()) {
            try {
                hotPotato.controller().onPass(source, target, source.hasLineOfSight(target),
                        eventId("hot-potato-pass", source));
                event.setCancelled(true);
                return;
            } catch (RuntimeException failure) {
                event.setCancelled(true);
                feedback(source, "A passagem da batata não foi aceite.");
                return;
            }
        }
        if (!buildBattleVoting() || !inScope(policies.buildBattleParticipants(), source)) return;
        event.setCancelled(true);
        resolveBuildBattleVote(source, event.getRightClicked(), null, null);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        ElytraRingsController elytra = elytraRings == null ? null : elytraRings.controller();
        if (elytra != null) {
            try {
                if (routeHeldItem(event, elytra::onRocketUse)) {
                    if (!heldItemUseAvailable(event)) {
                        feedback(event.getPlayer(), "Este foguete não pertence à tua sessão de Elytra.");
                    }
                    return;
                }
            } catch (RuntimeException failure) {
                event.setCancelled(true);
                feedback(event.getPlayer(), "O uso do foguete foi recusado para proteger os Anéis de Elytra.");
                return;
            }
        }
        if (!blockUseAvailable(event)) return;
        if (!buildBattleVoting()
                || !inScope(policies.buildBattleParticipants(), event.getPlayer())) return;
        event.setCancelled(true);
        resolveBuildBattleVote(event.getPlayer(), null, event.getClickedBlock(), event.getAction());
    }

    static boolean heldItemUseAvailable(PlayerInteractEvent event) {
        return event.useItemInHand() != org.bukkit.event.Event.Result.DENY;
    }

    static boolean routeHeldItem(PlayerInteractEvent event, HeldItemRoute route) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(route, "route");
        return heldItemUseAvailable(event) && route.route(event);
    }

    static boolean blockUseAvailable(PlayerInteractEvent event) {
        return event.useInteractedBlock() != org.bukkit.event.Event.Result.DENY;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onQuit(PlayerQuitEvent event) {
        disconnect(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onKick(PlayerKickEvent event) {
        if (!event.isCancelled()) disconnect(event.getPlayer());
    }

    private void routeColiseumBoundary(PlayerMoveEvent event, Player player, Location destination) {
        Optional<ArenaMatch> match = currentColiseumMatch();
        if (match.isEmpty() || match.orElseThrow().phase() != ArenaPhase.ACTIVE
                || !match.orElseThrow().activePlayers().contains(player.getUniqueId())) return;
        if (event instanceof PlayerTeleportEvent teleport
                && teleport.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            event.setCancelled(true);
            feedback(player, "Não podes usar teleporte externo durante o combate do Coliseu.");
            return;
        }
        if (!contains(policies.coliseumFloor(), player, destination)) {
            event.setCancelled(true);
            feedback(player, "Não podes sair do piso do Coliseu.");
        }
    }

    private void routeSumoBoundary(PlayerMoveEvent event, Player player) {
        if (sumo == null) return;
        try {
            sumo.controller().onMove(event, eventId("sumo-move", player));
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(player, "O movimento foi recusado para proteger o ringue de Sumo.");
            return;
        }
        if (event.isCancelled()) {
            feedback(player, "Saíste do ringue de Sumo e foste eliminado.");
        } else if (event instanceof PlayerTeleportEvent teleport
                && teleport.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN
                && inScope(policies.sumoParticipants(), player)) {
            event.setCancelled(true);
            feedback(player, "Não podes usar teleporte externo durante o Sumo.");
        }
    }

    private void routeParkour(PlayerMoveEvent event, Player player, Location destination) {
        if (parkour == null) return;
        if (parkour.runId(player.getUniqueId()).isEmpty()) return;
        if (!contains(policies.parkourLane(), player, destination)) {
            event.setCancelled(true);
            feedback(player, "Não podes sair do percurso de parkour.");
        }
        OptionalInt checkpoint;
        try {
            OptionalInt resolved = policies.parkourCheckpoints().resolve(player, destination);
            checkpoint = resolved == null ? OptionalInt.empty() : resolved;
        } catch (RuntimeException failure) {
            checkpoint = OptionalInt.empty();
        }
        if (checkpoint.isPresent() && checkpoint.getAsInt() >= 0
                && isNewParkourCheckpoint(player, checkpoint.getAsInt())) {
            try {
                parkour.controller().onCheckpoint(player.getUniqueId(), checkpoint.getAsInt(),
                        eventId("parkour-checkpoint", player));
                rememberParkourCheckpoint(player, checkpoint.getAsInt());
            } catch (RuntimeException failure) {
                event.setCancelled(true);
                feedback(player, "Esse checkpoint não foi validado; a corrida permanece protegida.");
                return;
            }
        }
        try {
            if (!parkour.controller().onMove(player, destination)) event.setCancelled(true);
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(player, "O movimento foi recusado para proteger a corrida.");
        }
    }

    private void routeArchery(PlayerMoveEvent event, Player player, Location destination) {
        if (archery == null) return;
        if (archery.runId(player.getUniqueId()).isEmpty()) return;
        if (!contains(policies.archeryLane(), player, destination)) {
            event.setCancelled(true);
            feedback(player, "Não podes sair do campo de tiro.");
        }
        try {
            if (!archery.controller().onMove(player, destination)) event.setCancelled(true);
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(player, "O movimento foi recusado para proteger o campo de tiro.");
        }
    }

    private void routeBuildBattleMovement(PlayerMoveEvent event, Player player, Location destination) {
        if (buildBattle == null) return;
        try {
            if (!buildBattle.controller().allowMove(player, destination)) {
                event.setCancelled(true);
                feedback(player, "Não podes sair do teu lote durante o Build Battle.");
            }
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(player, "O movimento foi recusado para proteger o Build Battle.");
        }
    }

    private void routeHotPotatoMovement(PlayerMoveEvent event, Player player, Location destination) {
        if (hotPotato == null) return;
        try {
            if (!hotPotato.controller().allowMove(player, destination)) {
                event.setCancelled(true);
                feedback(player, "Não podes sair da arena da Batata Quente.");
            }
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(player, "O movimento foi recusado para proteger a Batata Quente.");
        }
    }

    private void routeAnvilDodge(PlayerMoveEvent event, Player player, Location destination) {
        AnvilDodgeController controller = anvilDodge == null ? null : anvilDodge.controller();
        if (controller == null) return;
        try {
            routeAnvilCell(controller, player, destination);
            controller.onMove(event);
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(player, "O movimento foi recusado para proteger a Fuga às Bigornas.");
        }
    }

    private void routeAnvilCell(AnvilDodgeController controller, Player player, Location destination) {
        if (controller.status().phase() != AnvilDodgePhase.RUNNING) return;
        OptionalInt cell;
        try {
            OptionalInt resolved = policies.anvilCells().resolve(player, destination);
            cell = resolved == null ? OptionalInt.empty() : resolved;
        } catch (RuntimeException failure) {
            cell = OptionalInt.empty();
        }
        if (cell.isEmpty() || cell.getAsInt() < 0
                || cell.getAsInt() > 4095
                || cell.getAsInt() == anvilLastCells.getOrDefault(player.getUniqueId(), -1)) return;
        try {
            controller.onDodge(player, cell.getAsInt(), clock.instant());
            anvilLastCells.put(player.getUniqueId(), cell.getAsInt());
        } catch (RuntimeException failure) {
            feedback(player, "O movimento não foi validado para a Fuga às Bigornas.");
        }
    }

    private void routeColorFloor(PlayerMoveEvent event, Player player) {
        ColorFloorController controller = colorFloor == null ? null : colorFloor.controller();
        if (controller == null) return;
        try {
            controller.onMove(event);
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(player, "O movimento foi recusado para proteger o Chão de Cores.");
        }
    }

    private void routeElytraRings(PlayerMoveEvent event, Player player) {
        ElytraRingsController controller = elytraRings == null ? null : elytraRings.controller();
        if (controller == null) return;
        try {
            controller.onMove(event);
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(player, "O movimento foi recusado para proteger os Anéis de Elytra.");
        }
    }

    private void routeSumoBoundary(PlayerTeleportEvent event, Player player) {
        routeSumoBoundary((PlayerMoveEvent) event, player);
    }

    private void routeColiseumBoundary(PlayerTeleportEvent event, Player player, Location destination) {
        routeColiseumBoundary((PlayerMoveEvent) event, player, destination);
    }

    private void routeParkourTeleport(PlayerTeleportEvent event, Player player, Location destination) {
        if (parkour == null) return;
        if (parkour.runId(player.getUniqueId()).isEmpty()) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            event.setCancelled(true);
            feedback(player, "Não podes usar teleporte externo durante a corrida.");
        }
        try {
            if (!parkour.controller().onTeleport(player, destination)) event.setCancelled(true);
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(player, "O teleporte foi recusado para proteger a corrida.");
        }
    }

    private void routeArcheryTeleport(PlayerTeleportEvent event, Player player, Location destination) {
        if (archery == null) return;
        if (archery.runId(player.getUniqueId()).isEmpty()) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            event.setCancelled(true);
            feedback(player, "Não podes usar teleporte externo no campo de tiro.");
        }
        try {
            if (!archery.controller().onTeleport(player, destination)) event.setCancelled(true);
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(player, "O teleporte foi recusado para proteger o campo de tiro.");
        }
    }

    private void routeBuildBattleTeleport(PlayerTeleportEvent event, Player player, Location destination) {
        if (buildBattle == null) return;
        try {
            if (!buildBattle.controller().allowTeleport(player, destination)) {
                event.setCancelled(true);
                feedback(player, "Não podes sair do lote durante o Build Battle.");
            }
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(player, "O teleporte foi recusado para proteger o Build Battle.");
        }
    }

    private void routeHotPotatoTeleport(PlayerTeleportEvent event, Player player, Location destination) {
        if (hotPotato == null) return;
        try {
            if (!hotPotato.controller().allowTeleport(player, destination)) {
                event.setCancelled(true);
                feedback(player, "Não podes sair da arena da Batata Quente.");
            }
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(player, "O teleporte foi recusado para proteger a Batata Quente.");
        }
    }

    private void routeAnvilDodgeTeleport(PlayerTeleportEvent event) {
        AnvilDodgeController controller = anvilDodge == null ? null : anvilDodge.controller();
        if (controller == null) return;
        try {
            controller.onTeleport(event);
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(event.getPlayer(), "O teleporte foi recusado para proteger a Fuga às Bigornas.");
        }
    }

    private void routeColorFloorTeleport(PlayerTeleportEvent event) {
        ColorFloorController controller = colorFloor == null ? null : colorFloor.controller();
        if (controller == null) return;
        try {
            controller.onTeleport(event);
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(event.getPlayer(), "O teleporte foi recusado para proteger o Chão de Cores.");
        }
    }

    private void routeElytraTeleport(PlayerTeleportEvent event) {
        ElytraRingsController controller = elytraRings == null ? null : elytraRings.controller();
        if (controller == null) return;
        try {
            controller.onTeleport(event);
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(event.getPlayer(), "O teleporte foi recusado para proteger os Anéis de Elytra.");
        }
    }

    private boolean routeColiseumLethalDamage(EntityDamageEvent event, Player victim) {
        Optional<ArenaMatch> match = currentColiseumMatch();
        if (match.isEmpty() || match.orElseThrow().phase() != ArenaPhase.ACTIVE
                || !match.orElseThrow().activePlayers().contains(victim.getUniqueId())) return false;
        double damage = event.getFinalDamage();
        if (damage <= 0.0D || Double.isNaN(damage)
                || damage < victim.getHealth()) return false;
        event.setCancelled(true);
        try {
            coliseum.controller().eliminate(victim, "LETHAL_DAMAGE");
        } catch (RuntimeException failure) {
            feedback(victim, "O dano letal foi bloqueado; o Coliseu vai recuperar o teu estado.");
        }
        return true;
    }

    private boolean routeSumoCombat(EntityDamageEvent event, EntityDamageByEntityEvent byEntity, Player victim) {
        if (sumo == null) return false;
        Player attacker = attackingPlayer(byEntity.getDamager());
        if (attacker == null || !inScope(policies.sumoParticipants(), attacker)
                || !inScope(policies.sumoParticipants(), victim)
                || !sumo.controller().combatAllowed(attacker.getUniqueId(), victim.getUniqueId())) return false;
        event.setCancelled(true);
        try {
            sumo.controller().onKnockback(attacker, victim, knockback(attacker, victim, event.getFinalDamage()));
        } catch (RuntimeException failure) {
            feedback(attacker, "O golpe não foi aceite; o ringue permanece protegido.");
        }
        return true;
    }

    private boolean routeSumoFall(EntityDamageEvent event, Player victim) {
        if (!sumoRunning() || !inScope(policies.sumoParticipants(), victim)
                || (event.getCause() != EntityDamageEvent.DamageCause.FALL
                && event.getCause() != EntityDamageEvent.DamageCause.VOID)) return false;
        event.setCancelled(true);
        try {
            sumo.controller().onRingOut(victim.getUniqueId(), eventId("sumo-fall", victim));
        } catch (RuntimeException ignored) {
            // Stale events are rejected; cancellation still prevents vanilla death.
        }
        feedback(victim, "Caíste do ringue de Sumo e foste eliminado.");
        return true;
    }

    private boolean routeAnvilHazard(EntityDamageEvent event, Player victim) {
        AnvilDodgeController controller = anvilDodge == null ? null : anvilDodge.controller();
        if (controller == null || !inScope(policies.anvilParticipants(), victim)) return false;
        try {
            if (controller.status().phase() != AnvilDodgePhase.RUNNING
                    || !isHazard(policies.anvilHazards(), event)) return false;
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            feedback(victim, "O impacto foi bloqueado enquanto a Fuga às Bigornas é validada.");
            return true;
        }
        event.setCancelled(true);
        try {
            controller.onHazardHit(victim, clock.instant());
        } catch (RuntimeException failure) {
            feedback(victim, "O impacto foi bloqueado para proteger a Fuga às Bigornas.");
        }
        return true;
    }

    private boolean routeHotPotatoExplosion(EntityDamageEvent event, Player victim) {
        if (!hotPotatoRunning() || !inScope(policies.hotPotatoParticipants(), victim)
                || (event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                && event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)) return false;
        event.setCancelled(true);
        try {
            hotPotato.controller().onExplosion(victim.getUniqueId(), eventId("hot-potato-explosion", victim));
        } catch (RuntimeException failure) {
            feedback(victim, "A explosão foi bloqueada para proteger a Batata Quente.");
        }
        return true;
    }

    private void enforceBuildBattle(Player player, Location location, org.bukkit.event.Cancellable event) {
        if (buildBattle == null) return;
        if (!buildBattleBuilding()) return;
        if (!inScope(policies.buildBattleParticipants(), player)) return;
        boolean allowed;
        try {
            allowed = buildBattle.controller().canBuild(player, location);
        } catch (RuntimeException failure) {
            allowed = false;
        }
        if (!allowed) {
            event.setCancelled(true);
            if (event instanceof BlockBreakEvent breakEvent) breakEvent.setDropItems(false);
            feedback(player, "Só podes construir dentro do teu lote no Build Battle.");
        }
    }

    private void resolveBuildBattleVote(Player voter, Entity entity, Block block, Action action) {
        Optional<BuildBattleVote> vote;
        try {
            Optional<BuildBattleVote> resolved = policies.buildBattleVotes()
                    .resolve(voter, entity, block, action);
            vote = resolved == null ? Optional.empty() : resolved;
        } catch (RuntimeException failure) {
            vote = Optional.empty();
        }
        if (vote.isEmpty()) {
            feedback(voter, "A votação ainda não está ligada a esta interação.");
            return;
        }
        try {
            BuildBattleVote value = vote.orElseThrow();
            buildBattle.controller().vote(voter.getUniqueId(), value.plot(), value.score(),
                    eventId("build-battle-vote", voter));
        } catch (RuntimeException failure) {
            feedback(voter, "Esse voto não foi aceite.");
        }
    }

    /**
     * Consumes a verified vote even when SessionIsolationListener has already
     * cancelled the raw command; it never re-enables vanilla dispatch.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBuildBattleVoteCommand(PlayerCommandPreprocessEvent event) {
        Player voter = event.getPlayer();
        if (!buildBattleVoting()) return;
        Optional<BuildBattleVote> vote;
        try {
            Optional<BuildBattleVote> resolved = policies.buildBattleVoteCommands()
                    .resolve(voter, event.getMessage());
            vote = resolved == null ? Optional.empty() : resolved;
        } catch (RuntimeException failure) {
            vote = Optional.empty();
        }
        if (vote.isEmpty()) {
            if (looksLikeBuildBattleVoteCommand(event.getMessage())) {
                event.setCancelled(true);
                feedback(voter, "Esse voto não foi reconhecido e não foi executado.");
            }
            return;
        }
        event.setCancelled(true);
        if (!inScope(policies.buildBattleParticipants(), voter)) {
            feedback(voter, "A tua sessão de Build Battle não foi validada; o voto foi recusado.");
            return;
        }
        try {
            BuildBattleVote value = vote.orElseThrow();
            buildBattle.controller().vote(voter.getUniqueId(), value.plot(), value.score(),
                    eventId("build-battle-vote-command", voter));
        } catch (RuntimeException failure) {
            feedback(voter, "Esse voto não foi aceite.");
        }
    }

    private static boolean looksLikeBuildBattleVoteCommand(String rawCommand) {
        if (rawCommand == null || rawCommand.length() > 256) return false;
        String command = rawCommand.trim();
        if (command.startsWith("/")) command = command.substring(1).trim();
        String[] parts = command.split("\\s+");
        return parts.length >= 2
                && (parts[0].equalsIgnoreCase("buildbattle") || parts[0].equalsIgnoreCase("bb"))
                && parts[1].toLowerCase(Locale.ROOT).equals("vote");
    }

    private void disconnect(Player player) {
        UUID playerId = player.getUniqueId();
        if (coliseum != null) {
            try {
                Optional<ArenaMatch> match = coliseum.controller().currentMatch();
                if (match.isPresent() && match.orElseThrow().participants().contains(playerId)) {
                    attemptDisconnect("arena-active-disconnect", () -> coliseum.controller().disconnect(player));
                } else {
                    // A queued player is held by the wrapper/controller queue, not by a match.
                    attemptDisconnect("arena-queue-disconnect", () -> coliseum.leave(player));
                }
            } catch (RuntimeException failure) {
                reportFailure("arena-disconnect-lookup");
            }
        }
        if (sumo != null && inScope(policies.sumoParticipants(), player)) {
            attemptDisconnect("sumo-disconnect", () ->
                    sumo.controller().onDisconnect(playerId, eventId("sumo-disconnect", player)));
            // Sumo's wrapper owns a participant roster in addition to controller state.
            attemptDisconnect("sumo-wrapper-cleanup", () -> sumo.leave(player));
        }
        if (parkour != null && parkour.runId(playerId).isPresent()) {
            attemptDisconnect("parkour-disconnect", () -> parkour.controller().onDisconnect(playerId));
            // The wrapper owns the run identity used by later event routing.
            attemptDisconnect("parkour-wrapper-cleanup", () -> parkour.leave(player));
        }
        parkourCheckpointRuns.remove(playerId);
        parkourLastCheckpoints.remove(playerId);
        anvilLastCells.remove(playerId);
        if (archery != null && archery.runId(playerId).isPresent()) {
            attemptDisconnect("archery-disconnect", () -> archery.controller().onDisconnect(playerId));
            // The wrapper owns the run identity used by later event routing.
            attemptDisconnect("archery-wrapper-cleanup", () -> archery.leave(player));
        }
        if (buildBattle != null && inScope(policies.buildBattleParticipants(), player)) {
            attemptDisconnect("build-battle-disconnect", () -> buildBattle.controller().disconnect(playerId));
            // Disconnect mutates the controller directly; refresh the wrapper's match marker.
            attemptDisconnect("build-battle-wrapper-cleanup", buildBattle::currentMatchId);
        }
        if (hotPotato != null && inScope(policies.hotPotatoParticipants(), player)) {
            attemptDisconnect("hot-potato-disconnect", () -> hotPotato.controller().onDisconnect(playerId));
            // Disconnect mutates the controller directly; refresh the wrapper's match marker.
            attemptDisconnect("hot-potato-wrapper-cleanup", hotPotato::currentMatchId);
        }
        AnvilDodgeController anvil = anvilDodge == null ? null : anvilDodge.controller();
        if (anvil != null) {
            attemptDisconnect("anvil-disconnect", () ->
                    anvil.onDisconnect(playerId, eventId("anvil-disconnect", player)));
            attemptDisconnect("anvil-wrapper-cleanup", anvilDodge::currentMatchId);
        }
        ColorFloorController color = colorFloor == null ? null : colorFloor.controller();
        if (color != null) {
            attemptDisconnect("color-floor-disconnect", () ->
                    color.onDisconnect(playerId, eventId("color-disconnect", player)));
            attemptDisconnect("color-floor-wrapper-cleanup", colorFloor::currentMatchId);
        }
        ElytraRingsController elytra = elytraRings == null ? null : elytraRings.controller();
        if (elytra != null) {
            attemptDisconnect("elytra-disconnect", () ->
                    elytra.onDisconnect(playerId, eventId("elytra-disconnect", player)));
            attemptDisconnect("elytra-wrapper-cleanup", elytraRings::currentMatchId);
        }
        lastFeedback.remove(playerId);
    }

    /** Runs one cleanup step independently so one broken controller cannot skip later modules. */
    static void attemptDisconnect(String route, Runnable action, FailureReporter reporter) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            try { reporter.report(route); } catch (RuntimeException ignored) { }
        }
    }

    private void attemptDisconnect(String route, Runnable action) {
        attemptDisconnect(route, action, failureReporter);
    }

    private void reportFailure(String route) {
        try { failureReporter.report(route); } catch (RuntimeException ignored) { }
    }

    private boolean isColiseumParticipant(Player player) {
        return currentColiseumMatch().filter(match -> match.participants().contains(player.getUniqueId())
                && match.phase() != ArenaPhase.IDLE && match.phase() != ArenaPhase.WAITING
                && match.phase() != ArenaPhase.CLOSED).isPresent();
    }

    private Optional<ArenaMatch> currentColiseumMatch() {
        if (coliseum == null) return Optional.empty();
        try {
            Optional<ArenaMatch> match = coliseum.controller().currentMatch();
            return match == null ? Optional.empty() : match;
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private boolean sumoRunning() {
        try {
            return sumo.controller().status().phase()
                    == com.ciaac.minecraft.minigames.knockbacksumo.SumoPhase.RUNNING;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean buildBattleBuilding() {
        try {
            return buildBattle.controller().status().phase() == BuildBattlePhase.BUILDING;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean buildBattleActive() {
        try {
            BuildBattlePhase phase = buildBattle.controller().status().phase();
            return phase != BuildBattlePhase.IDLE && phase != BuildBattlePhase.CLOSED
                    && phase != BuildBattlePhase.RESETTING && phase != BuildBattlePhase.RECOVERING;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean buildBattleVoting() {
        try {
            return buildBattle.controller().status().phase() == BuildBattlePhase.VOTING;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean hotPotatoRunning() {
        try {
            HotPotatoPhase phase = hotPotato.controller().status().phase();
            return phase == HotPotatoPhase.RUNNING || phase == HotPotatoPhase.SUDDEN_DEATH;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean anvilRunning(AnvilDodgeController controller) {
        try {
            return controller.status().phase() == AnvilDodgePhase.RUNNING;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean contains(BoundaryPolicy policy, Player player, Location destination) {
        try {
            return policy.contains(player, destination);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean inScope(PlayerScope scope, Player player) {
        try {
            return scope.contains(player);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean isHazard(AnvilHazardResolver resolver, EntityDamageEvent event) {
        try {
            return resolver.isArenaHazard(event);
        } catch (RuntimeException failure) {
            return true;
        }
    }

    private static void cleanupProjectile(ArcheryPaperController controller, UUID projectileId) {
        try {
            controller.onProjectileCleanup(projectileId);
        } catch (RuntimeException ignored) {
            // The hit event is still cancelled by the caller; cleanup is best effort.
        }
    }

    private boolean isNewParkourCheckpoint(Player player, int checkpoint) {
        UUID runId = parkour.runId(player.getUniqueId()).orElse(null);
        if (runId == null) return false;
        UUID previousRun = parkourCheckpointRuns.get(player.getUniqueId());
        Integer previousCheckpoint = parkourLastCheckpoints.get(player.getUniqueId());
        return !runId.equals(previousRun) || previousCheckpoint == null || checkpoint > previousCheckpoint;
    }

    private void rememberParkourCheckpoint(Player player, int checkpoint) {
        UUID playerId = player.getUniqueId();
        parkourCheckpointRuns.put(playerId, parkour.runId(playerId).orElse(null));
        parkourLastCheckpoints.put(playerId, checkpoint);
    }

    private static Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private static Vector knockback(Player source, Player target, double damage) {
        Vector direction = target.getLocation().toVector().subtract(source.getLocation().toVector());
        if (direction.lengthSquared() < 1.0E-8) direction = source.getLocation().getDirection().clone();
        direction.setY(0.0D);
        if (direction.lengthSquared() < 1.0E-8) direction = new Vector(0.0D, 0.0D, 1.0D);
        direction.normalize().multiply(Math.min(3.0D, 0.8D + Math.max(0.0D, damage) * 0.2D));
        direction.setY(0.35D);
        return direction;
    }

    private static boolean isHotPotatoPassAttempt(Player player, EquipmentSlot hand) {
        if (hand == EquipmentSlot.OFF_HAND) {
            return player.getInventory().getItemInOffHand().getType() == Material.POTATO;
        }
        return player.getInventory().getItemInMainHand().getType() == Material.POTATO;
    }

    private UUID eventId(String kind, Player player) {
        return UUID.nameUUIDFromBytes((kind + ":" + player.getUniqueId() + ":" + (++eventSequence))
                .getBytes(StandardCharsets.UTF_8));
    }

    private void feedback(Player player, String message) {
        Instant now = clock.instant();
        Instant previous = lastFeedback.get(player.getUniqueId());
        if (previous != null && now.isBefore(previous.plus(FEEDBACK_COOLDOWN))) return;
        lastFeedback.put(player.getUniqueId(), now);
        try {
            player.sendMessage("§c" + message);
        } catch (RuntimeException ignored) { }
    }

    private static void protectDeath(PlayerDeathEvent event, Player player) {
        event.setCancelled(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setShouldDropExperience(false);
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        try {
            var maximumHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
            if (player.isDead() && maximumHealthAttribute != null
                    && Double.isFinite(maximumHealthAttribute.getValue())
                    && maximumHealthAttribute.getValue() > 0.0D) {
                player.setHealth(maximumHealthAttribute.getValue());
            }
        } catch (RuntimeException ignored) {
            // Session restoration remains the authoritative recovery boundary.
        }
    }
}
