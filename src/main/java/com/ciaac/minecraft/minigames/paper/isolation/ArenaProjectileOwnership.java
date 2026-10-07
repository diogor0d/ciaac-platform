package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Exact durable ownership of Arena arrows; never searches or deletes an area's other entities. */
public final class ArenaProjectileOwnership {
    private final Server server;
    private final ArenaWorldLedger ledger;
    private final NamespacedKey ownershipKey;

    public ArenaProjectileOwnership(Plugin owner, ArenaWorldLedger ledger) {
        this.server = Objects.requireNonNull(owner, "owner").getServer();
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.ownershipKey = new NamespacedKey(owner, "arena-projectile-owner-v1");
    }

    /** Requires an authenticated lifecycle caller, an armed lease, and an arrow not yet inserted. */
    public void beforeAdd(PlayerStateOperation capture, AbstractArrow arrow) {
        requireThread();
        Objects.requireNonNull(arrow, "arrow");
        ArenaWorldLedger.Lease lease = ledger.requireLease(capture);
        ArenaWorldManifest manifest = ArenaWorldManifest.decode(lease.manifest());
        if (lease.status() != ArenaWorldLedger.Status.ARMED || arrow.isInWorld()
                || !manifest.regions().getFirst().bounds().worldId().equals(arrow.getWorld().getUID()))
            throw new IllegalStateException("Arena projectile launch has no armed world lease");
        if (ledger.findEntity(arrow.getUniqueId()).isPresent())
            throw new IllegalStateException("Arena projectile UUID already has a durable owner");
        if (arrow.getPersistentDataContainer().has(ownershipKey))
            throw new IllegalStateException("Arena projectile already has an ownership label");
        ArenaWorldLedger.EntityType type = type(arrow);
        arrow.setPersistent(false);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setFireTicks(0);
        arrow.getPersistentDataContainer().set(ownershipKey, PersistentDataType.STRING, label(lease.capture()));
        // The intention is durable before native world insertion may occur.
        ledger.beginEntity(capture, arrow.getUniqueId(), arrow.getWorld().getUID(), type);
        validate(arrow, ledger.findEntity(arrow.getUniqueId()).orElseThrow());
    }

    /** Called only by the real post-insertion world event, after exact ownership validation. */
    public void afterAdd(Entity entity) {
        requireThread();
        Optional<ArenaWorldLedger.EntityClaim> found = ledger.findEntity(entity.getUniqueId());
        if (found.isEmpty()) {
            if (hasLabel(entity)) throw new IllegalStateException("Arena projectile label has no durable owner");
            return;
        }
        var claim = found.orElseThrow();
        validate(entity, claim);
        if (!entity.isInWorld() || !entity.isValid() || server.getEntity(entity.getUniqueId()) != entity)
            throw new IllegalStateException("Arena projectile insertion was not confirmed");
        ledger.confirmEntity(ledger.requireLease(claim.sessionId()).capture(), claim.entityId());
    }

    /** Cancelling launch produces no native removal event: prove it never entered the world first. */
    public void cancelledBeforeAdd(Entity entity) {
        requireThread();
        var claim = ledger.findEntity(entity.getUniqueId()).orElseThrow();
        validate(entity, claim);
        if (claim.status() != ArenaWorldLedger.EntityStatus.PENDING || entity.isInWorld()
                || server.getEntity(entity.getUniqueId()) != null)
            throw new IllegalStateException("Arena launch cancellation is not proven");
        ledger.markRemoved(ledger.requireLease(claim.sessionId()).capture(), claim.entityId());
    }

    /** Native removal completion, including nonpersistent chunk unload, must be observed before marking. */
    public void afterRemove(Entity entity) {
        requireThread();
        var found = ledger.findEntity(entity.getUniqueId());
        if (found.isEmpty()) {
            if (hasLabel(entity)) throw new IllegalStateException("Arena projectile label has no durable owner");
            return;
        }
        var claim = found.orElseThrow();
        validate(entity, claim);
        // On the pinned Paper build, isInWorld remains true after discard. It is not removal evidence.
        if (entity.isValid() || !entity.isDead() || server.getEntity(entity.getUniqueId()) != null)
            throw new IllegalStateException("Arena projectile removal is not yet proven");
        ledger.markRemoved(ledger.requireLease(claim.sessionId()).capture(), claim.entityId());
    }

    /**
     * Reconciles the exact entity observed by a native tracking-end callback after that callback settles.
     * Paper keeps nonpersistent arrows alive in inaccessible chunk slices instead of destroying them.
     */
    public void afterUntracking(Entity entity) {
        requireThread();
        var found = ledger.findEntity(entity.getUniqueId());
        if (found.isEmpty()) {
            afterRemove(entity);
            return;
        }
        var claim = found.orElseThrow();
        validate(entity, claim);
        if (!entity.isInWorld() || entity.isValid() || server.getEntity(entity.getUniqueId()) != null
                || (claim.status() == ArenaWorldLedger.EntityStatus.REMOVED && !entity.isDead()))
            throw new IllegalStateException("Arena projectile tracking end is not proven");
        // This is an observed, fully owned native object; null lookup alone never authorizes this deletion.
        if (!entity.isDead()) entity.remove();
        afterRemove(entity);
    }

    /** Read-only prevalidation of every row before removing even one entity. */
    public void validatePurge(PlayerStateOperation context) {
        requireThread();
        ledger.requireLease(context);
        for (var row : ledger.entities(context)) {
            Entity entity = server.getEntity(row.entityId());
            if (row.status() == ArenaWorldLedger.EntityStatus.REMOVED) {
                if (entity != null) throw new IllegalStateException("Removed Arena projectile unexpectedly exists");
            } else {
                // A null lookup alone cannot distinguish an unloaded persistent entity or unfinished launch.
                if (entity == null) throw new IllegalStateException("Arena projectile absence needs lifecycle reconciliation");
                validate(entity, ledger.findEntity(row.entityId()).orElseThrow());
                if (!entity.isInWorld() || !entity.isValid())
                    throw new IllegalStateException("Arena projectile is not a confirmed live entity");
            }
        }
    }

    /** Purges only ledger UUIDs after all ownership checks pass; failure leaves the lease blocking restore. */
    public void purge(PlayerStateOperation context) {
        validatePurge(context);
        ledger.beginPurge(context);
        for (var row : ledger.entities(context)) {
            if (row.status() == ArenaWorldLedger.EntityStatus.REMOVED) continue;
            Entity entity = server.getEntity(row.entityId());
            if (entity == null) throw new IllegalStateException("Arena projectile disappeared during purge");
            validate(entity, ledger.findEntity(row.entityId()).orElseThrow());
            entity.remove();
            if (entity.isValid() || !entity.isDead() || server.getEntity(row.entityId()) != null)
                throw new IllegalStateException("Arena projectile deletion was not confirmed");
            ledger.markRemoved(context, row.entityId());
        }
        ledger.completePurge(context);
    }

    /** Removes one proven owned projectile after a hit; unrelated UUIDs are rejected. */
    public void removeOwned(UUID entityId) {
        requireThread();
        var claim = ledger.findEntity(entityId).orElseThrow();
        Entity entity = server.getEntity(entityId);
        if (claim.status() == ArenaWorldLedger.EntityStatus.REMOVED && entity == null) return;
        if (entity == null) throw new IllegalStateException("Arena projectile absence needs lifecycle reconciliation");
        validate(entity, claim);
        entity.remove();
        if (entity.isValid() || !entity.isDead() || server.getEntity(entityId) != null)
            throw new IllegalStateException("Arena projectile deletion was not confirmed");
        ledger.markRemoved(ledger.requireLease(claim.sessionId()).capture(), entityId);
    }

    public boolean hasLabel(Entity entity) {
        return Objects.requireNonNull(entity, "entity").getPersistentDataContainer().has(ownershipKey);
    }

    private void validate(Entity entity, ArenaWorldLedger.EntityClaim claim) {
        var lease = ledger.requireLease(claim.sessionId());
        if (!(entity instanceof AbstractArrow arrow) || type(arrow) != claim.type()
                || !entity.getUniqueId().equals(claim.entityId())
                || !entity.getWorld().getUID().equals(claim.worldId())
                || entity.isPersistent() || entity.getVehicle() != null || !entity.getPassengers().isEmpty()
                || arrow.getPickupStatus() != AbstractArrow.PickupStatus.DISALLOWED || entity.getFireTicks() > 0
                || !label(lease.capture()).equals(entity.getPersistentDataContainer().get(ownershipKey, PersistentDataType.STRING)))
            throw new IllegalStateException("Arena projectile ownership or native isolation changed");
    }

    private static ArenaWorldLedger.EntityType type(AbstractArrow arrow) {
        if (arrow instanceof SpectralArrow) return ArenaWorldLedger.EntityType.SPECTRAL_ARROW;
        if (arrow instanceof Arrow) return ArenaWorldLedger.EntityType.ARROW;
        throw new IllegalStateException("Arena only owns normal and spectral arrows");
    }

    private static String label(PlayerStateOperation capture) {
        return "v1/" + capture.sessionId() + "/" + capture.matchId() + "/" + capture.playerId()
                + "/" + capture.captureOperationId() + "/" + capture.capturedConnectionId();
    }

    private void requireThread() {
        if (!server.isPrimaryThread()) throw new IllegalStateException("Arena entity processing requires the primary server thread");
    }
}
