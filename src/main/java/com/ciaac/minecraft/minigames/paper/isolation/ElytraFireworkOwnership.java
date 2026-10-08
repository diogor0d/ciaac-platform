package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.NativeProcessIdentity;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Fireworks;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Exact durable ownership of native Elytra boost fireworks. */
public final class ElytraFireworkOwnership {
    private final Server server;
    private final ArenaWorldLedger ledger;
    private final NamespacedKey ownershipKey;
    private final NativeProcessIdentity processIdentity;
    private final Function<Firework, Fireworks> effectiveFireworks;

    public ElytraFireworkOwnership(Plugin owner, ArenaWorldLedger ledger) {
        this(owner, ledger, firework -> firework.getItem().getData(DataComponentTypes.FIREWORKS));
    }

    ElytraFireworkOwnership(Plugin owner, ArenaWorldLedger ledger,
            Function<Firework, Fireworks> effectiveFireworks) {
        this.server = Objects.requireNonNull(owner, "owner").getServer();
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.ownershipKey = new NamespacedKey(owner, "elytra-firework-owner-v1");
        this.processIdentity = NativeProcessIdentity.current();
        this.effectiveFireworks = Objects.requireNonNull(effectiveFireworks, "effectiveFireworks");
    }

    /** Persists the ownership intent before Paper inserts this native boost firework. */
    public void beforeAdd(PlayerStateOperation capture, Firework firework) {
        requireThread();
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(firework, "firework");
        ArenaWorldLedger.Lease lease = ledger.requireLease(capture);
        UUID worldId = firework.getWorld().getUID();
        Player attached = firework.getAttachedTo() instanceof Player player ? player : null;
        Player currentPlayer = server.getPlayer(lease.capture().playerId());
        if (capture.kind() != PlayerStateOperation.Kind.CAPTURE
                || lease.capture().game() != GameKey.ELYTRA_RINGS
                || lease.status() != ArenaWorldLedger.Status.ARMED
                || firework.isInWorld() || server.getWorld(worldId) != firework.getWorld()
                || !worldId.equals(ElytraWorldState.capturedWorld(lease.manifest()))) {
            throw new IllegalStateException("Elytra firework has no armed lease in its loaded native world");
        }
        if (currentPlayer == null || !currentPlayer.isOnline()
                || !lease.capture().playerId().equals(firework.getSpawningEntity())
                || attached != currentPlayer) {
            throw new IllegalStateException("Elytra firework is not attached to its live spawning player");
        }
        if (ledger.findEntity(firework.getUniqueId()).isPresent())
            throw new IllegalStateException("Elytra firework UUID already has a durable owner");
        if (hasLabel(firework)) throw new IllegalStateException("Elytra firework already has an ownership label");
        if (!validFirework(firework)) {
            FireworkMeta meta = firework.getFireworkMeta();
            Fireworks effective = effectiveFireworks.apply(firework);
            server.getLogger().warning("CIAACPlatform: native Elytra rocket rejected; metaPower="
                    + (meta == null ? "missing" : meta.getPower())
                    + "; effectiveFlightDuration=" + (effective == null ? "missing" : effective.flightDuration())
                    + "; effectiveEffects=" + (effective == null ? "missing" : effective.effects().size())
                    + "; vehicle=" + (firework.getVehicle() != null)
                    + "; passengers=" + firework.getPassengers().size()
                    + "; fireTicks=" + firework.getFireTicks());
            throw new IllegalStateException("Elytra firework native isolation is invalid");
        }

        firework.setPersistent(false);
        if (firework.isPersistent()) throw new IllegalStateException("Elytra firework nonpersistence was not applied");
        firework.getPersistentDataContainer().set(ownershipKey, PersistentDataType.STRING, label(lease.capture()));
        ledger.beginEntity(capture, firework.getUniqueId(), worldId,
                ArenaWorldLedger.EntityType.ELYTRA_FIREWORK, processIdentity);
        validate(firework, ledger.findEntity(firework.getUniqueId()).orElseThrow());
    }

    /** Confirms only the exact native object inserted under its durable UUID. */
    public void afterAdd(Entity entity) {
        requireThread();
        Optional<ArenaWorldLedger.EntityClaim> found = ledger.findEntity(entity.getUniqueId());
        if (found.isEmpty()) {
            if (hasLabel(entity)) throw new IllegalStateException("Elytra firework label has no durable owner");
            return;
        }
        ArenaWorldLedger.EntityClaim claim = found.orElseThrow();
        validate(entity, claim);
        if (!entity.isInWorld() || !entity.isValid() || server.getEntity(entity.getUniqueId()) != entity)
            throw new IllegalStateException("Elytra firework insertion was not confirmed");
        ledger.confirmEntity(ledger.requireLease(claim.sessionId()).capture(), claim.entityId());
    }

    /** Settles a cancelled spawn only when native insertion is affirmatively absent. */
    public void cancelledBeforeAdd(Entity entity) {
        requireThread();
        ArenaWorldLedger.EntityClaim claim = ledger.findEntity(entity.getUniqueId()).orElseThrow();
        validate(entity, claim);
        if (claim.status() != ArenaWorldLedger.EntityStatus.PENDING || entity.isInWorld()
                || server.getEntity(entity.getUniqueId()) != null)
            throw new IllegalStateException("Elytra firework cancellation is not proven");
        ledger.markRemoved(ledger.requireLease(claim.sessionId()).capture(), claim.entityId());
    }

    /** Records removal only after native death and exact server UUID lookup both confirm it. */
    public void afterRemove(Entity entity) {
        requireThread();
        Optional<ArenaWorldLedger.EntityClaim> found = ledger.findEntity(entity.getUniqueId());
        if (found.isEmpty()) {
            if (hasLabel(entity)) throw new IllegalStateException("Elytra firework label has no durable owner");
            return;
        }
        ArenaWorldLedger.EntityClaim claim = found.orElseThrow();
        validate(entity, claim);
        if (entity.isValid() || !entity.isDead() || server.getEntity(entity.getUniqueId()) != null)
            throw new IllegalStateException("Elytra firework removal is not yet proven");
        ledger.markRemoved(ledger.requireLease(claim.sessionId()).capture(), claim.entityId());
    }

    /** Resolves the exact native object observed by a tracking-end callback. */
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
                || (claim.status() == ArenaWorldLedger.EntityStatus.REMOVED && !entity.isDead()))
            throw new IllegalStateException("Elytra firework tracking end is not proven");
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
                if (entity != null) throw new IllegalStateException("Removed Elytra firework unexpectedly exists");
            } else if (entity == null) {
                if (!coldProcessAbsence(row))
                    throw new IllegalStateException("Elytra firework absence needs lifecycle reconciliation");
            } else {
                validate(entity, ledger.findEntity(row.entityId()).orElseThrow());
                if (!entity.isInWorld() || !entity.isValid())
                    throw new IllegalStateException("Elytra firework is not a confirmed live entity");
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
                    throw new IllegalStateException("Elytra firework disappeared during purge");
                ledger.markRemoved(context, row.entityId());
                continue;
            }
            validate(entity, ledger.findEntity(row.entityId()).orElseThrow());
            entity.remove();
            if (entity.isValid() || !entity.isDead() || server.getEntity(row.entityId()) != null)
                throw new IllegalStateException("Elytra firework deletion was not confirmed");
            ledger.markRemoved(context, row.entityId());
        }
        ledger.completePurge(context);
    }

    /** Removes one exact owned native firework; unrelated UUIDs have no authority. */
    public void removeOwned(UUID entityId) {
        requireThread();
        ArenaWorldLedger.EntityClaim claim = ledger.findEntity(Objects.requireNonNull(entityId, "entityId"))
                .orElseThrow();
        Entity entity = server.getEntity(entityId);
        if (claim.status() == ArenaWorldLedger.EntityStatus.REMOVED && entity == null) return;
        if (entity == null) throw new IllegalStateException("Elytra firework absence needs lifecycle reconciliation");
        validate(entity, claim);
        entity.remove();
        if (entity.isValid() || !entity.isDead() || server.getEntity(entityId) != null)
            throw new IllegalStateException("Elytra firework deletion was not confirmed");
        ledger.markRemoved(ledger.requireLease(claim.sessionId()).capture(), claim.entityId());
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
        if (!(entity instanceof Firework firework) || claim.type() != ArenaWorldLedger.EntityType.ELYTRA_FIREWORK
                || !entity.getUniqueId().equals(claim.entityId())
                || !entity.getWorld().getUID().equals(claim.worldId()) || entity.isPersistent()
                || entity.getVehicle() != null || !entity.getPassengers().isEmpty() || entity.getFireTicks() > 0
                || !lease.capture().playerId().equals(firework.getSpawningEntity())
                || (firework.getAttachedTo() != null
                        && !lease.capture().playerId().equals(firework.getAttachedTo().getUniqueId()))
                || !validFirework(firework)
                || !label(lease.capture()).equals(entity.getPersistentDataContainer()
                        .get(ownershipKey, PersistentDataType.STRING))) {
            throw new IllegalStateException("Elytra firework ownership or native isolation changed");
        }
    }

    private boolean validFirework(Firework firework) {
        Fireworks effective = effectiveFireworks.apply(firework);
        return effective != null && effective.flightDuration() == 1 && effective.effects().isEmpty()
                && firework.getVehicle() == null && firework.getPassengers().isEmpty()
                && firework.getFireTicks() <= 0;
    }

    private static String label(PlayerStateOperation capture) {
        return "v1/" + capture.sessionId() + "/" + capture.matchId() + "/" + capture.playerId()
                + "/" + capture.captureOperationId() + "/" + capture.capturedConnectionId();
    }

    private void requireThread() {
        if (!server.isPrimaryThread())
            throw new IllegalStateException("Elytra firework processing requires the primary server thread");
    }
}
