package local.harness;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.paper.isolation.ArenaProjectileLifecycleListener;
import com.ciaac.minecraft.minigames.paper.isolation.ArenaProjectileOwnership;
import com.ciaac.minecraft.minigames.paper.isolation.ArenaWorldManifest;
import com.ciaac.minecraft.minigames.paper.isolation.ArenaWorldStatePort;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.persistence.SqliteAuditRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/** Native production resource callbacks only. No player, authentication or admission fixture. */
final class ArenaProjectileLifecycleProbe implements Listener {
    private static final int CHUNK_X = 16, CHUNK_Z = 16;
    private final Plugin probe;
    private final World world;
    private final Consumer<Throwable> complete;
    private final List<Entity> entities = new ArrayList<>();
    private ArenaWorldLedger ledger;
    private ExternalOperationJournal journal;
    private SqliteDatabase database;
    private ArenaProjectileOwnership ownership;
    private ArenaWorldStatePort port;
    private ArenaProjectileLifecycleListener lifecycle;
    private PlayerStateOperation capture;
    private Arrow foreign, removed, unload;
    private SpectralArrow hit;
    private boolean chunkTicket, finished;
    private int blockHits, remainingUnloadTicks = 200;

    private ArenaProjectileLifecycleProbe(Plugin probe, Consumer<Throwable> complete) {
        this.probe = probe;
        this.world = probe.getServer().getWorlds().getFirst();
        this.complete = complete;
    }

    static void verify(Plugin probe, Path root, Consumer<Throwable> complete) {
        var fixture = new ArenaProjectileLifecycleProbe(probe, complete);
        fixture.step(() -> fixture.prepare(root));
    }

    private void prepare(Path root) throws Exception {
        Files.createDirectory(root);
        ledger = new ArenaWorldLedger(root.resolve("ledger"));
        journal = new ExternalOperationJournal(root.resolve("journal"));
        database = new SqliteDatabase(root, Path.of("audit.sqlite"));
        ownership = new ArenaProjectileOwnership(probe, ledger);
        var regions = new ProtectedRegionRegistry();
        regions.register(new ProtectedRegion("lifecycle-floor", GameKey.ARENA,
                new CuboidRegion(world.getUID(), 2, world.getMinHeight(), 2, 270, world.getMaxHeight() - 1, 270),
                ProtectedRegionRole.PARTICIPANT_ONLY, true));
        regions.register(new ProtectedRegion("lifecycle-seats", GameKey.ARENA,
                new CuboidRegion(world.getUID(), 280, world.getMinHeight(), 2, 285, world.getMaxHeight() - 1, 5),
                ProtectedRegionRole.SPECTATOR_PUBLIC, true));
        port = new ArenaWorldStatePort(probe.getServer(), regions, ledger, ownership, journal, new SqliteAuditRepository(database));
        lifecycle = new ArenaProjectileLifecycleListener(probe, ledger, ownership, port,
                new SessionRegistry(), new AuthenticationRegistry(), new ConnectionRegistry(), regions,
                new RegionAdmissionRegistry(), (player, session, violation) -> {
                    throw new IllegalStateException("Resource fixture must not authenticate or recover a player");
                }, Clock.systemUTC());
        probe.getServer().getPluginManager().registerEvents(lifecycle, probe);
        probe.getServer().getPluginManager().registerEvents(this, probe);
        // Only this fixture's resource callbacks are enabled; the production runtime remains unwired.
        port.lifecycleReady();
        UUID operation = UUID.randomUUID(), epoch = UUID.randomUUID();
        capture = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, operation, operation,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), epoch, epoch, GameKey.ARENA, Instant.now());
        ledger.capture(capture, new ArenaWorldManifest(probe.getServer().getVersion(), regions.all()).encode());
        ledger.arm(capture);
        world.getBlockAt(3, 202, 3).setType(Material.STONE, false);
        foreign = world.spawn(new Location(world, 4.5, 205, 4.5), Arrow.class, arrow -> arrow.setGravity(false));
        entities.add(foreign);
        removed = spawn(new Location(world, 3.5, 205, 3.5), Arrow.class);
        hit = spawn(new Location(world, 3.5, 205, 3.5), SpectralArrow.class);
        require(ledger.findEntity(removed.getUniqueId()).orElseThrow().status() == ArenaWorldLedger.EntityStatus.CONFIRMED
                && ledger.findEntity(hit.getUniqueId()).orElseThrow().status() == ArenaWorldLedger.EntityStatus.CONFIRMED,
                "Production insertion callback did not confirm both native arrow types");
        removed.remove();
        hit.setVelocity(new Vector(0, -1, 0));
        later(10, this::checkRemovalAndHit);
    }

    private <T extends AbstractArrow> T spawn(Location location, Class<T> type) {
        return world.spawn(location, type, arrow -> {
            entities.add(arrow);
            arrow.setGravity(false);
            ownership.beforeAdd(capture, arrow);
        });
    }

    private void checkRemovalAndHit() {
        require(ledger.findEntity(removed.getUniqueId()).orElseThrow().status() == ArenaWorldLedger.EntityStatus.REMOVED,
                "Deferred production removal callback did not prove native discard");
        require(blockHits == 1 && ledger.findEntity(hit.getUniqueId()).orElseThrow().status() == ArenaWorldLedger.EntityStatus.REMOVED,
                "Production block-hit callback did not cancel and remove the owned spectral arrow");
        require(foreign.isValid() && port.available(), "Lifecycle affected an unrelated arrow or failed availability");
        require(!world.isChunkLoaded(CHUNK_X, CHUNK_Z) && !world.isChunkForceLoaded(CHUNK_X, CHUNK_Z)
                && world.getPluginChunkTickets(CHUNK_X, CHUNK_Z).isEmpty(),
                "Unload fixture chunk is already in use; refusing to change its load policy");
        require(world.addPluginChunkTicket(CHUNK_X, CHUNK_Z, probe), "Could not load the private unload fixture chunk");
        chunkTicket = true;
        later(20, this::startUnload);
    }

    private void startUnload() {
        unload = spawn(new Location(world, CHUNK_X * 16 + 3.5, 205, CHUNK_Z * 16 + 3.5), Arrow.class);
        require(ledger.findEntity(unload.getUniqueId()).orElseThrow().status() == ArenaWorldLedger.EntityStatus.CONFIRMED,
                "Unload fixture arrow was not natively inserted");
        require(world.removePluginChunkTicket(CHUNK_X, CHUNK_Z, probe), "Could not release private unload ticket");
        chunkTicket = false;
        world.unloadChunkRequest(CHUNK_X, CHUNK_Z);
        later(1, this::checkUnload);
    }

    private void checkUnload() {
        if (world.isChunkLoaded(CHUNK_X, CHUNK_Z)) {
            require(--remainingUnloadTicks > 0, "Private chunk did not unload within the native test deadline");
            later(1, this::checkUnload);
            return;
        }
        // The world event precedes UUID-index removal and the listener reconciles on the following tick.
        later(2, () -> {
            probe.getLogger().info("Estado nativo após unload: row="
                    + ledger.findEntity(unload.getUniqueId()).orElseThrow().status()
                    + " valid=" + unload.isValid() + " dead=" + unload.isDead() + " inWorld=" + unload.isInWorld()
                    + " indexed=" + (probe.getServer().getEntity(unload.getUniqueId()) != null)
                    + " persistent=" + unload.isPersistent() + " available=" + port.available());
            require(ledger.findEntity(unload.getUniqueId()).orElseThrow().status() == ArenaWorldLedger.EntityStatus.REMOVED
                    && !unload.isValid() && unload.isDead() && probe.getServer().getEntity(unload.getUniqueId()) == null,
                    "Production unload callback did not prove nonpersistent arrow removal");
            require(port.available() && foreign.isValid(), "Unload failed world availability or touched unrelated entities");
            var purge = new PlayerStateOperation(PlayerStateOperation.Kind.PURGE, UUID.randomUUID(), capture.captureOperationId(),
                    capture.snapshotId(), capture.sessionId(), capture.matchId(), capture.playerId(), capture.capturedConnectionId(),
                    capture.connectionId(), capture.game(), capture.capturedAt());
            ownership.purge(purge);
            require(ledger.requireLease(capture).status() == ArenaWorldLedger.Status.PURGED,
                    "Reconciled native lifecycle still blocked durable purge");
            finish(null);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void blockHit(ProjectileHitEvent event) {
        if (hit != null && event.getEntity() == hit && event.getHitBlock() != null) {
            require(event.isCancelled(), "Owned block hit was not cancelled by the production listener");
            blockHits++;
        }
    }

    private interface Step { void run() throws Exception; }

    private void later(long ticks, Step action) {
        probe.getServer().getScheduler().runTaskLater(probe, () -> step(action), ticks);
    }

    private void step(Step action) {
        if (finished) return;
        try { action.run(); } catch (Throwable failure) { finish(failure); }
    }

    private void finish(Throwable failure) {
        if (finished) return;
        finished = true;
        if (lifecycle != null) lifecycle.close();
        HandlerList.unregisterAll(this);
        try {
            for (Entity entity : entities) if (entity.isValid()) entity.remove();
            if (chunkTicket) world.removePluginChunkTicket(CHUNK_X, CHUNK_Z, probe);
        } catch (Throwable cleanup) { failure = combine(failure, cleanup); }
        for (AutoCloseable resource : new AutoCloseable[] {journal, ledger, database}) {
            if (resource != null) try { resource.close(); }
            catch (Throwable cleanup) { failure = combine(failure, cleanup); }
        }
        complete.accept(failure);
    }

    private static Throwable combine(Throwable failure, Throwable other) {
        if (failure == null) return other;
        failure.addSuppressed(other);
        return failure;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
