package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionViolation;
import com.ciaac.minecraft.minigames.runtime.SessionViolationHandler;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.time.Clock;
import java.util.Objects;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.plugin.Plugin;

/** Authenticated Arena launches only; reconciles real insertion/cancellation/removal before restoration. */
public final class ArenaProjectileLifecycleListener implements Listener, AutoCloseable {
    private final Plugin owner;
    private final ArenaWorldLedger ledger;
    private final ArenaProjectileOwnership ownership;
    private final ArenaWorldStatePort worldState;
    private final SessionRegistry sessions;
    private final AuthenticationRegistry authentication;
    private final ConnectionRegistry connections;
    private final ProtectedRegionRegistry regions;
    private final RegionAdmissionRegistry admissions;
    private final SessionViolationHandler violations;
    private final Clock clock;
    private boolean closed;

    public ArenaProjectileLifecycleListener(Plugin owner, ArenaWorldLedger ledger, ArenaProjectileOwnership ownership,
            ArenaWorldStatePort worldState,
            SessionRegistry sessions, AuthenticationRegistry authentication, ConnectionRegistry connections,
            ProtectedRegionRegistry regions, RegionAdmissionRegistry admissions, SessionViolationHandler violations, Clock clock) {
        this.owner = Objects.requireNonNull(owner, "owner"); this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.ownership = Objects.requireNonNull(ownership, "ownership"); this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.worldState = Objects.requireNonNull(worldState, "worldState");
        this.authentication = Objects.requireNonNull(authentication, "authentication"); this.connections = Objects.requireNonNull(connections, "connections");
        this.regions = Objects.requireNonNull(regions, "regions"); this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.violations = Objects.requireNonNull(violations, "violations"); this.clock = Objects.requireNonNull(clock, "clock");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void launch(ProjectileLaunchEvent event) {
        if (closed) return;
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        var found = sessions.findByPlayer(player.getUniqueId()).filter(session -> session.game() == GameKey.ARENA
                && session.phase().isolationActive());
        if (found.isEmpty() || event.isCancelled()) return;
        PlayerSession session = found.orElseThrow();
        boolean ownershipAttempted = false;
        try {
            var lease = ledger.requireLease(session.sessionId());
            var auth = authentication.current(player.getUniqueId(), clock.instant()).orElseThrow();
            var connection = connections.current(player.getUniqueId()).orElseThrow();
            var region = regions.at(player.getLocation()).orElseThrow();
            if (!worldState.available() || session.phase() != SessionPhase.ACTIVE || !player.isOnline() || !player.isValid()
                    || owner.getServer().getPlayer(player.getUniqueId()) != player
                    || !connections.isCurrent(player, auth.connectionId()) || !connection.id().equals(auth.connectionId())
                    || !lease.capture().capturedConnectionId().equals(auth.connectionId())
                    || !lease.capture().matchId().equals(session.matchId()) || !lease.capture().playerId().equals(player.getUniqueId())
                    || region.game() != GameKey.ARENA || region.role() != ProtectedRegionRole.PARTICIPANT_ONLY
                    || !admissions.permits(player.getUniqueId(), session.sessionId(), region.id(), clock.instant())
                    || !(event.getEntity() instanceof AbstractArrow arrow)
                    || (!(arrow instanceof Arrow) && !(arrow instanceof SpectralArrow)))
                throw new IllegalStateException("Arena projectile has no current authenticated floor admission");
            ownershipAttempted = true;
            ownership.beforeAdd(lease.capture(), arrow);
            // A cancelled launch never emits world-removal. Check the final native state next tick.
            schedule(() -> {
                try {
                    settleLaunch(arrow);
                } catch (RuntimeException failure) {
                    closeProvider();
                    fail(player, session);
                }
            });
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            if (ownershipAttempted) closeProvider();
            // Do not start restoration inside native entity insertion; pending launch must settle first.
            boolean reconcileIntention = ownershipAttempted;
            schedule(() -> {
                if (reconcileIntention) {
                    try {
                        if (ledger.findEntity(event.getEntity().getUniqueId()).isPresent()) settleLaunch(event.getEntity());
                    } catch (RuntimeException unsettled) { closeProvider(); }
                }
                fail(player, session);
            });
        }
    }

    private void settleLaunch(Entity entity) {
        if (entity.isValid() && owner.getServer().getEntity(entity.getUniqueId()) == entity) ownership.afterAdd(entity);
        else if (!entity.isInWorld() && ledger.findEntity(entity.getUniqueId()).orElseThrow().status() == ArenaWorldLedger.EntityStatus.PENDING)
            ownership.cancelledBeforeAdd(entity);
        else ownership.afterUntracking(entity);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void added(EntityAddToWorldEvent event) {
        if (closed) return;
        if (trackedType(event.getEntity())) reconcile(event.getEntity(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void removed(EntityRemoveFromWorldEvent event) {
        if (closed) return;
        // This event can precede UUID lookup removal; validate completion on the next main-thread tick.
        Entity entity = event.getEntity();
        if (!trackedType(entity)) return;
        try {
            if (ledger.findEntity(entity.getUniqueId()).isPresent() || ownership.hasLabel(entity))
                schedule(() -> reconcile(entity, false));
        } catch (RuntimeException failure) { failOwner(entity); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void hit(ProjectileHitEvent event) {
        if (closed) return;
        if (!trackedType(event.getEntity())) return;
        try {
            if (ledger.findEntity(event.getEntity().getUniqueId()).isEmpty()) {
                if (ownership.hasLabel(event.getEntity())) {
                    event.setCancelled(true);
                    failOwner(event.getEntity());
                }
                return;
            }
        } catch (RuntimeException failure) {
            event.setCancelled(true);
            failOwner(event.getEntity());
            return;
        }
        if (event.getHitBlock() != null) {
            event.setCancelled(true);
            removeHit(event.getEntity());
        } else {
            // Let normal PvP damage finish before removing the projectile.
            schedule(() -> {
                Entity entity = event.getEntity();
                if (entity.isValid() && owner.getServer().getEntity(entity.getUniqueId()) == entity) removeHit(entity);
                else reconcile(entity, false);
            });
        }
    }

    private void removeHit(Entity entity) {
        try { ownership.removeOwned(entity.getUniqueId()); }
        catch (RuntimeException failure) { failOwner(entity); }
    }

    private void reconcile(Entity entity, boolean added) {
        try {
            if (added) ownership.afterAdd(entity); else ownership.afterUntracking(entity);
        } catch (RuntimeException failure) { failOwner(entity); }
    }

    private void failOwner(Entity entity) {
        closeProvider();
        try {
            ledger.findEntity(entity.getUniqueId()).ifPresent(claim -> sessions.findById(claim.sessionId()).ifPresent(session -> {
                Player player = owner.getServer().getPlayer(session.playerId());
                if (player != null) schedule(() -> fail(player, session));
            }));
        } catch (RuntimeException unavailable) {
            // The failed ledger cannot establish a player identity. Provider availability stays closed.
        }
    }

    private void closeProvider() {
        worldState.lifecycleFailed();
        owner.getLogger().severe("A propriedade de um projétil da Arena requer revisão; o restauro permanece protegido.");
    }

    private boolean trackedType(Entity entity) {
        return entity instanceof AbstractArrow || ownership.hasLabel(entity);
    }

    private void schedule(Runnable action) {
        // During onDisable, explicit coordinator purge still proves its own removals synchronously.
        if (closed || !owner.isEnabled()) return;
        owner.getServer().getScheduler().runTask(owner, () -> {
            if (!closed) action.run();
        });
    }

    @Override public void close() {
        if (!owner.getServer().isPrimaryThread()) throw new IllegalStateException("Arena lifecycle close requires the primary thread");
        closed = true;
        HandlerList.unregisterAll(this);
        worldState.lifecycleFailed();
    }

    private void fail(Player player, PlayerSession session) {
        if (sessions.findById(session.sessionId()).orElse(null) == session && session.phase().isolationActive())
            violations.onViolation(player, session, SessionViolation.WORLD_ISOLATION_FAILURE);
    }
}
