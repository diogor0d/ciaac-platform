package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.NativeProcessIdentity;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Exact durable ownership of Anvil Dodge marker stands. */
public final class AnvilHazardOwnership {
    private final Server server;
    private final ArenaWorldLedger ledger;
    private final NamespacedKey ownershipKey;
    private final NativeProcessIdentity processIdentity;

    public AnvilHazardOwnership(Plugin owner, ArenaWorldLedger ledger) {
        this.server = Objects.requireNonNull(owner, "owner").getServer();
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.ownershipKey = new NamespacedKey(owner, "anvil-hazard-owner-v1");
        this.processIdentity = NativeProcessIdentity.current();
    }

    /** Resolves the durable capture identity from the session; callers cannot invent ownership context. */
    public void beforeAdd(UUID sessionId, ArmorStand stand) {
        requireThread();
        ArenaWorldLedger.Lease lease = ledger.requireLease(Objects.requireNonNull(sessionId, "sessionId"));
        beforeAdd(lease.capture(), stand);
    }

    /** Persists ownership intent before Paper inserts an already-configured marker. */
    public void beforeAdd(PlayerStateOperation capture, ArmorStand stand) {
        requireThread();
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(stand, "stand");
        ArenaWorldLedger.Lease lease = ledger.requireLease(capture);
        UUID worldId = stand.getWorld().getUID();
        if (capture.kind() != PlayerStateOperation.Kind.CAPTURE || capture.game() != GameKey.ANVIL_DODGE
                || lease.status() != ArenaWorldLedger.Status.ARMED
                || stand.isInWorld() || server.getWorld(worldId) != stand.getWorld()
                || !worldId.equals(AnvilWorldState.capturedWorld(lease.manifest()))) {
            throw new IllegalStateException("Anvil marker has no armed lease in its loaded native world");
        }
        if (ledger.findEntity(stand.getUniqueId()).isPresent())
            throw new IllegalStateException("Anvil marker UUID already has a durable owner");
        if (hasLabel(stand)) throw new IllegalStateException("Anvil marker already has an ownership label");
        if (!validMarker(stand)) throw new IllegalStateException("Anvil marker native isolation is invalid");

        stand.setPersistent(false);
        if (stand.isPersistent()) throw new IllegalStateException("Anvil marker nonpersistence was not applied");
        stand.getPersistentDataContainer().set(ownershipKey, PersistentDataType.STRING, label(lease.capture()));
        ledger.beginEntity(capture, stand.getUniqueId(), worldId,
                ArenaWorldLedger.EntityType.ANVIL_MARKER, processIdentity);
        validate(stand, ledger.findEntity(stand.getUniqueId()).orElseThrow());
    }

    /** Confirms only the exact native object inserted under its durable UUID. */
    public void afterAdd(Entity entity) {
        requireThread();
        Optional<ArenaWorldLedger.EntityClaim> found = ledger.findEntity(entity.getUniqueId());
        if (found.isEmpty()) {
            if (hasLabel(entity)) throw new IllegalStateException("Anvil marker label has no durable owner");
            return;
        }
        ArenaWorldLedger.EntityClaim claim = found.orElseThrow();
        validate(entity, claim);
        if (!entity.isInWorld() || !entity.isValid() || server.getEntity(entity.getUniqueId()) != entity)
            throw new IllegalStateException("Anvil marker insertion was not confirmed");
        ledger.confirmEntity(ledger.requireLease(claim.sessionId()).capture(), claim.entityId());
    }

    /** A cancelled spawn is complete only when no native insertion occurred. */
    public void cancelledBeforeAdd(Entity entity) {
        requireThread();
        ArenaWorldLedger.EntityClaim claim = ledger.findEntity(entity.getUniqueId()).orElseThrow();
        validate(entity, claim);
        if (claim.status() != ArenaWorldLedger.EntityStatus.PENDING || entity.isInWorld()
                || server.getEntity(entity.getUniqueId()) != null) {
            throw new IllegalStateException("Anvil marker cancellation is not proven");
        }
        ledger.markRemoved(ledger.requireLease(claim.sessionId()).capture(), claim.entityId());
    }

    /** Records removal only after native death and exact server-UUID lookup both confirm it. */
    public void afterRemove(Entity entity) {
        requireThread();
        Optional<ArenaWorldLedger.EntityClaim> found = ledger.findEntity(entity.getUniqueId());
        if (found.isEmpty()) {
            if (hasLabel(entity)) throw new IllegalStateException("Anvil marker label has no durable owner");
            return;
        }
        ArenaWorldLedger.EntityClaim claim = found.orElseThrow();
        validate(entity, claim);
        // Paper may retain the historical isInWorld flag after discard; it is not removal proof.
        if (entity.isValid() || !entity.isDead() || server.getEntity(entity.getUniqueId()) != null)
            throw new IllegalStateException("Anvil marker removal is not yet proven");
        ledger.markRemoved(ledger.requireLease(claim.sessionId()).capture(), claim.entityId());
    }

    /** Removes only the exact fully-owned native object observed by a tracking-end callback. */
    public void afterUntracking(Entity entity) {
        requireThread();
        Optional<ArenaWorldLedger.EntityClaim> found = ledger.findEntity(entity.getUniqueId());
        if (found.isEmpty()) {
            afterRemove(entity);
            return;
        }
        ArenaWorldLedger.EntityClaim claim = found.orElseThrow();
        validate(entity, claim);
        if (!entity.isInWorld() || entity.isValid() || server.getEntity(entity.getUniqueId()) != null
                || (claim.status() == ArenaWorldLedger.EntityStatus.REMOVED && !entity.isDead())) {
            throw new IllegalStateException("Anvil marker tracking end is not proven");
        }
        if (!entity.isDead()) entity.remove();
        afterRemove(entity);
    }

    /** Validates every durable row before purge removes or marks any entity. */
    public void validatePurge(PlayerStateOperation context) {
        requireThread();
        ledger.requireLease(context);
        for (ArenaWorldLedger.Entity row : ledger.entities(context)) {
            Entity entity = server.getEntity(row.entityId());
            if (row.status() == ArenaWorldLedger.EntityStatus.REMOVED) {
                if (entity != null) throw new IllegalStateException("Removed Anvil marker unexpectedly exists");
            } else if (entity == null) {
                // Null lookup cannot prove absence while this native process or an unloaded world remains.
                if (!coldProcessAbsence(row))
                    throw new IllegalStateException("Anvil marker absence needs lifecycle reconciliation");
            } else {
                validate(entity, ledger.findEntity(row.entityId()).orElseThrow());
                if (!entity.isInWorld() || !entity.isValid())
                    throw new IllegalStateException("Anvil marker is not a confirmed live entity");
            }
        }
    }

    /** Purges exact ledger UUIDs only after read-only prevalidation succeeds. */
    public void purge(PlayerStateOperation context) {
        validatePurge(context);
        ledger.beginPurge(context);
        for (ArenaWorldLedger.Entity row : ledger.entities(context)) {
            if (row.status() == ArenaWorldLedger.EntityStatus.REMOVED) continue;
            Entity entity = server.getEntity(row.entityId());
            if (entity == null) {
                if (!coldProcessAbsence(row))
                    throw new IllegalStateException("Anvil marker disappeared during purge");
                ledger.markRemoved(context, row.entityId());
                continue;
            }
            validate(entity, ledger.findEntity(row.entityId()).orElseThrow());
            entity.remove();
            if (entity.isValid() || !entity.isDead() || server.getEntity(row.entityId()) != null)
                throw new IllegalStateException("Anvil marker deletion was not confirmed");
            ledger.markRemoved(context, row.entityId());
        }
        ledger.completePurge(context);
    }

    /** Removes one ledger-owned marker; unrelated UUIDs have no authority. */
    public void removeOwned(UUID entityId) {
        requireThread();
        ArenaWorldLedger.EntityClaim claim = ledger.findEntity(Objects.requireNonNull(entityId, "entityId"))
                .orElseThrow();
        Entity entity = server.getEntity(entityId);
        if (claim.status() == ArenaWorldLedger.EntityStatus.REMOVED && entity == null) return;
        if (entity == null) throw new IllegalStateException("Anvil marker absence needs lifecycle reconciliation");
        validate(entity, claim);
        entity.remove();
        if (entity.isValid() || !entity.isDead() || server.getEntity(entityId) != null)
            throw new IllegalStateException("Anvil marker deletion was not confirmed");
        ledger.markRemoved(ledger.requireLease(claim.sessionId()).capture(), entityId);
    }

    public boolean hasLabel(Entity entity) {
        requireThread();
        return Objects.requireNonNull(entity, "entity").getPersistentDataContainer().has(ownershipKey);
    }

    private boolean coldProcessAbsence(ArenaWorldLedger.Entity row) {
        World loadedWorld = server.getWorld(row.worldId());
        return loadedWorld != null && row.worldId().equals(loadedWorld.getUID())
                && ledger.nonPersistentProcess(row.entityId())
                        .filter(created -> !created.equals(processIdentity)).isPresent();
    }

    private void validate(Entity entity, ArenaWorldLedger.EntityClaim claim) {
        ArenaWorldLedger.Lease lease = ledger.requireLease(claim.sessionId());
        if (!(entity instanceof ArmorStand stand) || claim.type() != ArenaWorldLedger.EntityType.ANVIL_MARKER
                || !entity.getUniqueId().equals(claim.entityId())
                || !entity.getWorld().getUID().equals(claim.worldId()) || entity.isPersistent()
                || entity.getVehicle() != null || !entity.getPassengers().isEmpty() || entity.getFireTicks() > 0
                || !validMarker(stand)
                || !label(lease.capture()).equals(entity.getPersistentDataContainer()
                        .get(ownershipKey, PersistentDataType.STRING))) {
            throw new IllegalStateException("Anvil marker ownership or native isolation changed");
        }
    }

    private static boolean validMarker(ArmorStand stand) {
        EntityEquipment equipment = stand.getEquipment();
        return !stand.isVisible() && stand.isMarker() && !stand.hasGravity() && stand.isInvulnerable()
                && equipment != null && isType(equipment.getHelmet(), Material.ANVIL)
                && empty(equipment.getChestplate()) && empty(equipment.getLeggings())
                && empty(equipment.getBoots()) && empty(equipment.getItemInMainHand())
                && empty(equipment.getItemInOffHand()) && stand.getVehicle() == null
                && stand.getPassengers().isEmpty() && stand.getFireTicks() <= 0;
    }

    private static boolean isType(ItemStack stack, Material expected) {
        return stack != null && stack.getType() == expected;
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getType() == Material.CAVE_AIR
                || stack.getType() == Material.VOID_AIR;
    }

    private static String label(PlayerStateOperation capture) {
        return "v1/" + capture.sessionId() + "/" + capture.matchId() + "/" + capture.playerId()
                + "/" + capture.captureOperationId() + "/" + capture.capturedConnectionId();
    }

    private void requireThread() {
        if (!server.isPrimaryThread())
            throw new IllegalStateException("Anvil entity processing requires the primary server thread");
    }
}
