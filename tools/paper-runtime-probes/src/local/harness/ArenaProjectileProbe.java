package local.harness;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.paper.isolation.ArenaProjectileOwnership;
import com.ciaac.minecraft.minigames.paper.isolation.ArenaWorldManifest;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.plugin.Plugin;

/** Real synthetic arrows and native lifecycle only; no authenticated player or admission fixture. */
final class ArenaProjectileProbe implements Listener {
    private final ArenaWorldLedger ledger;
    private final ArenaProjectileOwnership ownership;
    private UUID cancelId;
    private int added, cancelled;

    private ArenaProjectileProbe(Plugin probe, ArenaWorldLedger ledger) {
        this.ledger = ledger;
        this.ownership = new ArenaProjectileOwnership(probe, ledger);
    }

    static void verify(Plugin probe, Path directory) throws Exception {
        var server = probe.getServer();
        var world = server.getWorlds().getFirst();
        var location = new Location(world, 3.5, 205, 3.5);
        UUID operationId = UUID.randomUUID(), player = UUID.randomUUID(), epoch = UUID.randomUUID();
        var capture = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, operationId, operationId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), player, epoch, epoch, GameKey.ARENA, Instant.now());
        var purge = new PlayerStateOperation(PlayerStateOperation.Kind.PURGE, UUID.randomUUID(), operationId,
                capture.snapshotId(), capture.sessionId(), capture.matchId(), player, epoch, epoch, GameKey.ARENA, capture.capturedAt());
        var manifest = new ArenaWorldManifest(server.getVersion(), List.of(
                new ProtectedRegion("probe-floor", GameKey.ARENA, new CuboidRegion(world.getUID(), 2, -64, 2, 5, 319, 5), ProtectedRegionRole.PARTICIPANT_ONLY, true),
                new ProtectedRegion("probe-seats", GameKey.ARENA, new CuboidRegion(world.getUID(), 20, -64, 2, 25, 319, 5), ProtectedRegionRole.SPECTATOR_PUBLIC, true)));
        List<Entity> fixtureEntities = new ArrayList<>();
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(capture, manifest.encode()); ledger.arm(capture);
            var fixture = new ArenaProjectileProbe(probe, ledger);
            server.getPluginManager().registerEvents(fixture, probe);
            try {
                Arrow foreign = world.spawn(location, Arrow.class);
                fixtureEntities.add(foreign);
                Arrow normal = world.spawn(location, Arrow.class, arrow -> {
                    fixtureEntities.add(arrow); fixture.ownership.beforeAdd(capture, arrow);
                });
                SpectralArrow spectral = world.spawn(location, SpectralArrow.class, arrow -> {
                    fixtureEntities.add(arrow); fixture.ownership.beforeAdd(capture, arrow);
                });
                require(fixture.added == 2, "Native Arena arrow additions were not confirmed");
                require(ledger.entities(capture).stream().allMatch(row -> row.status() == ArenaWorldLedger.EntityStatus.CONFIRMED),
                        "Real Arrow/SpectralArrow did not advance PENDING to CONFIRMED");
                require(!normal.isPersistent() && !spectral.isPersistent()
                        && normal.getPickupStatus() == AbstractArrow.PickupStatus.DISALLOWED,
                        "Native arrow isolation flags are incorrect");
                normal.setPersistent(true);
                boolean refused = false;
                try { fixture.ownership.purge(purge); } catch (IllegalStateException expected) { refused = true; }
                require(refused && normal.isValid() && spectral.isValid() && foreign.isValid()
                        && ledger.requireLease(capture).status() == ArenaWorldLedger.Status.ARMED,
                        "Ownership drift did not refuse purge before deleting other entities");
                normal.setPersistent(false);
                world.spawn(location, Arrow.class, arrow -> {
                    fixtureEntities.add(arrow); fixture.cancelId = arrow.getUniqueId(); fixture.ownership.beforeAdd(capture, arrow);
                });
                require(fixture.cancelled == 1 && ledger.findEntity(fixture.cancelId).orElseThrow().status() == ArenaWorldLedger.EntityStatus.REMOVED,
                        "Cancelled native launch was not reconciled");
                fixture.ownership.purge(purge);
                fixture.ownership.purge(purge);
                require(!normal.isValid() && !spectral.isValid() && foreign.isValid(),
                        "Purge failed exact owned-UUID removal or deleted the unrelated arrow");
                require(ledger.requireLease(capture).status() == ArenaWorldLedger.Status.PURGED
                        && ledger.entities(capture).stream().allMatch(row -> row.status() == ArenaWorldLedger.EntityStatus.REMOVED),
                        "Durable purge did not complete after actual native deletion");
            } finally {
                HandlerList.unregisterAll(fixture);
                for (Entity entity : fixtureEntities) if (entity.isValid()) entity.remove();
            }
        }
        try (var ledger = new ArenaWorldLedger(directory)) {
            require(ledger.requireLease(capture).status() == ArenaWorldLedger.Status.PURGED,
                    "Durable native purge did not survive ledger reopen");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void added(EntityAddToWorldEvent event) {
        if (ledger.findEntity(event.getEntity().getUniqueId()).isEmpty()) return;
        ownership.afterAdd(event.getEntity()); added++;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void cancel(ProjectileLaunchEvent event) {
        if (event.getEntity().getUniqueId().equals(cancelId)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void cancelled(ProjectileLaunchEvent event) {
        if (event.isCancelled() && event.getEntity().getUniqueId().equals(cancelId)) {
            ownership.cancelledBeforeAdd(event.getEntity()); cancelled++;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
