package com.ciaac.minecraft.minigames.paper;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionViolation;
import com.ciaac.minecraft.minigames.runtime.SessionViolationHandler;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

/** Plugin-side immutable-region and participant-containment enforcement. */
public final class RegionProtectionListener implements Listener {
    private final ProtectedRegionRegistry regions;
    private final RegionAdmissionRegistry admissions;
    private final SessionRegistry sessions;
    private final SessionViolationHandler violations;
    private final Clock clock;

    public RegionProtectionListener(
            ProtectedRegionRegistry regions,
            RegionAdmissionRegistry admissions,
            SessionRegistry sessions,
            SessionViolationHandler violations,
            Clock clock) {
        this.regions = Objects.requireNonNull(regions, "regions");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.violations = Objects.requireNonNull(violations, "violations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        if (event.isCancelled()) return;
        Location from = event.getFrom();
        Location destination = event.getTo();
        if (from == null || destination == null || from.getWorld() == null || destination.getWorld() == null) {
            event.setCancelled(true);
            return;
        }
        if (sameBlock(from, destination)) {
            return;
        }
        ProtectedRegionTransportPolicy.Destination resolvedDestination = resolve(destination);
        Optional<ProtectedRegion> destinationRegion = resolvedDestination.region();
        Optional<PlayerSession> session = sessions.findByPlayer(event.getPlayer().getUniqueId());
        ProtectedRegionTransportPolicy.Decision transportDecision = transportDecision(
                event.getPlayer(), resolvedDestination);
        if (!transportDecision.allowed()) {
            event.setCancelled(true);
            reportViolation(event.getPlayer(), transportDecision);
            return;
        }
        if (session.isPresent() && session.orElseThrow().phase() == SessionPhase.ACTIVE) {
            PlayerSession active = session.orElseThrow();
            // Coliseum participants have two legal states: active fighters on
            // the admitted floor and eliminated players on the public benches.
            // The registered Coliseum boundary listener owns that distinction.
            if (active.game() == GameKey.ARENA) return;
            boolean staysInGameBoundary = destinationRegion.isPresent()
                    && destinationRegion.orElseThrow().game() == active.game()
                    && destinationRegion.orElseThrow().requiresAdmission()
                    && admissions.permits(
                            active.playerId(), active.sessionId(), destinationRegion.orElseThrow().id(), clock.instant());
            if (!staysInGameBoundary) {
                event.setCancelled(true);
                violations.onViolation(event.getPlayer(), active, SessionViolation.ESCAPE_ATTEMPT);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        enforceTransport(event, event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPortal(PlayerPortalEvent event) {
        enforceTransport(event, event.getPlayer(), event.getTo());
    }

    /** VehicleMoveEvent is not cancellable; restore a denied vehicle to its source. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVehicleMove(VehicleMoveEvent event) {
        List<Player> passengers = event.getVehicle().getPassengers().stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .toList();
        if (passengers.isEmpty()) return;

        ProtectedRegionTransportPolicy.Destination source = resolve(event.getFrom());
        ProtectedRegionTransportPolicy.Destination destination = resolve(event.getTo());
        List<Player> denied = passengers.stream()
                .filter(player -> !vehicleDecision(player, source, destination).allowed())
                .toList();
        if (denied.isEmpty()) return;

        denied.forEach(player -> reportViolation(player, vehicleDecision(player, source, destination)));
        if (source.known() && source.region().isPresent() && destination.region().isPresent()
                && source.region().orElseThrow().id().equals(destination.region().orElseThrow().id())) {
            event.getVehicle().eject();
            return;
        }
        if (event.getFrom() != null && event.getFrom().getWorld() != null) {
            try {
                if (event.getVehicle().teleport(
                        event.getFrom().clone(), PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // Fall through to the fail-closed passenger ejection below.
            }
        }
        event.getVehicle().eject();
        if (event.getFrom() != null && event.getFrom().getWorld() != null) {
            for (Player player : denied) {
                try {
                    player.teleport(event.getFrom().clone(), PlayerTeleportEvent.TeleportCause.PLUGIN);
                } catch (RuntimeException ignored) {
                    // The player remains ejected when the recovery teleport fails.
                }
            }
        }
    }

    private void enforceTransport(Cancellable event, Player player, Location destination) {
        if (event.isCancelled()) return;
        ProtectedRegionTransportPolicy.Decision decision = transportDecision(player, resolve(destination));
        if (!decision.allowed()) {
            event.setCancelled(true);
            reportViolation(player, decision);
        }
    }

    private ProtectedRegionTransportPolicy.Decision vehicleDecision(
            Player player,
            ProtectedRegionTransportPolicy.Destination source,
            ProtectedRegionTransportPolicy.Destination destination) {
        ProtectedRegionTransportPolicy.Decision destinationDecision = transportDecision(player, destination);
        if (!destinationDecision.allowed()) return destinationDecision;

        Optional<PlayerSession> session = sessions.findByPlayer(player.getUniqueId());
        if (session.isEmpty() || session.orElseThrow().phase() != SessionPhase.ACTIVE
                || session.orElseThrow().game() != GameKey.ARENA) {
            return destinationDecision;
        }
        Optional<ProtectedRegion> sourceRegion = source.region()
                .filter(ProtectedRegion::requiresAdmission)
                .filter(region -> region.game() == GameKey.ARENA)
                .filter(region -> admissions.permits(
                        player.getUniqueId(), session.orElseThrow().sessionId(), region.id(), clock.instant()));
        if (sourceRegion.isEmpty()) return destinationDecision;
        boolean remainsOnAdmittedFloor = destination.region()
                .filter(region -> region.id().equals(sourceRegion.orElseThrow().id()))
                .filter(region -> admissions.permits(
                        player.getUniqueId(), session.orElseThrow().sessionId(), region.id(), clock.instant()))
                .isPresent();
        return remainsOnAdmittedFloor
                ? ProtectedRegionTransportPolicy.Decision.ALLOW
                : ProtectedRegionTransportPolicy.Decision.DENY_ACTIVE_ESCAPE;
    }

    private void reportViolation(Player player, ProtectedRegionTransportPolicy.Decision decision) {
        sessions.findByPlayer(player.getUniqueId()).ifPresent(session -> {
            if (decision == ProtectedRegionTransportPolicy.Decision.DENY_NO_ADMISSION) {
                violations.onViolation(player, session, SessionViolation.UNAUTHORIZED_REGION_ENTRY);
            } else if (decision == ProtectedRegionTransportPolicy.Decision.DENY_ACTIVE_ESCAPE) {
                violations.onViolation(player, session, SessionViolation.ESCAPE_ATTEMPT);
            }
        });
    }

    private ProtectedRegionTransportPolicy.Decision transportDecision(
            Player player,
            ProtectedRegionTransportPolicy.Destination destination) {
        return ProtectedRegionTransportPolicy.evaluate(
                destination,
                player.getUniqueId(),
                sessions.findByPlayer(player.getUniqueId()),
                admissions,
                clock.instant());
    }

    private ProtectedRegionTransportPolicy.Destination resolve(Location location) {
        if (location == null || location.getWorld() == null) {
            return ProtectedRegionTransportPolicy.Destination.ambiguous();
        }
        try {
            return ProtectedRegionTransportPolicy.Destination.known(regions.at(location));
        } catch (RuntimeException ignored) {
            return ProtectedRegionTransportPolicy.Destination.ambiguous();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (immutable(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        if (immutable(event.getBlockPlaced())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (immutable(event.getBlock()) || immutable(event.getBlockClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (immutable(event.getBlock()) || immutable(event.getBlockClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFlow(BlockFromToEvent event) {
        if (immutable(event.getBlock()) || immutable(event.getToBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block ->
                immutable(block) || immutable(block.getRelative(event.getDirection())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block ->
                immutable(block) || immutable(block.getRelative(event.getDirection())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (regions.at(event.getLocation()).filter(ProtectedRegion::immutable).isPresent()) {
            event.setCancelled(true);
            return;
        }
        event.blockList().removeIf(this::immutable);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (immutable(event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        event.blockList().removeIf(this::immutable);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (immutable(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityBlockForm(EntityBlockFormEvent event) {
        if (immutable(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHangingBreak(HangingBreakEvent event) {
        if (regions.at(event.getEntity().getLocation()).filter(ProtectedRegion::immutable).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (regions.at(event.getEntity().getLocation()).filter(ProtectedRegion::immutable).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onGrow(BlockGrowEvent event) { if (immutable(event.getBlock())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSpread(BlockSpreadEvent event) {
        if (immutable(event.getBlock()) || immutable(event.getSource())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBurn(BlockBurnEvent event) { if (immutable(event.getBlock())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFade(BlockFadeEvent event) { if (immutable(event.getBlock())) event.setCancelled(true); }

    private boolean immutable(Block block) {
        return regions.at(block).filter(ProtectedRegion::immutable).isPresent();
    }

    private static boolean sameBlock(Location left, Location right) {
        return left.getWorld() == right.getWorld()
                && left.getBlockX() == right.getBlockX()
                && left.getBlockY() == right.getBlockY()
                && left.getBlockZ() == right.getBlockZ();
    }
}
