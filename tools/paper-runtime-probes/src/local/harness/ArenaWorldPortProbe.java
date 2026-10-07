package local.harness;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.paper.isolation.ArenaProjectileOwnership;
import com.ciaac.minecraft.minigames.paper.isolation.ArenaWorldStatePort;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.persistence.SqliteAuditRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Real Paper metadata/world and durable provider phases; synthetic identity, no admission/authentication. */
final class ArenaWorldPortProbe {
    static void verify(Plugin probe, Path root) throws Exception {
        Files.createDirectory(root);
        var server = probe.getServer();
        var world = server.getWorlds().getFirst();
        var owner = probe;
        var regions = new ProtectedRegionRegistry();
        regions.register(new ProtectedRegion("port-floor", GameKey.ARENA,
                new CuboidRegion(world.getUID(), 2, world.getMinHeight(), 2, 5, world.getMaxHeight() - 1, 5),
                ProtectedRegionRole.PARTICIPANT_ONLY, true));
        regions.register(new ProtectedRegion("port-seats", GameKey.ARENA,
                new CuboidRegion(world.getUID(), 20, world.getMinHeight(), 2, 25, world.getMaxHeight() - 1, 5),
                ProtectedRegionRole.SPECTATOR_PUBLIC, true));
        UUID operation = UUID.randomUUID(), epoch = UUID.randomUUID(), playerId = UUID.randomUUID();
        var capture = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, operation, operation,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), playerId, epoch, epoch, GameKey.ARENA, Instant.now());
        var enter = phase(capture, PlayerStateOperation.Kind.ENTER, epoch);
        var purge = phase(capture, PlayerStateOperation.Kind.PURGE, UUID.randomUUID());
        var restore = phase(capture, PlayerStateOperation.Kind.RESTORE, purge.connectionId());
        Player fixture = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (instance, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return playerId;
                    throw new IllegalStateException("Unexpected synthetic player call: " + method.getName());
                });
        Arrow arrow = null;
        byte[] payload;
        try {
            try (var ledger = new ArenaWorldLedger(root.resolve("ledger"));
                    var journal = new ExternalOperationJournal(root.resolve("journal"));
                    var database = new SqliteDatabase(root, Path.of("audit.sqlite"))) {
                var ownership = new ArenaProjectileOwnership(owner, ledger);
                var port = new ArenaWorldStatePort(server, regions, ledger, ownership, journal, new SqliteAuditRepository(database));
                require(!port.available(), "World provider advertised availability before readiness");
                // Direct fixture checkpoint only: production listener registration is separately required.
                port.lifecycleReady();
                require(port.available(), "Exact native Paper build was not recognized");
                payload = port.capture(fixture, capture);
                port.enterTemporaryState(fixture, enter);
                port.enterTemporaryState(fixture, enter);
                arrow = world.spawn(new Location(world, 3.5, 205, 3.5), Arrow.class,
                        entity -> ownership.beforeAdd(capture, entity));
                ownership.afterAdd(arrow);
                expectRejected(() -> port.restore(fixture, restore, 1, payload));
                require(arrow.isValid() && ledger.requireLease(capture).status() == ArenaWorldLedger.Status.ARMED,
                        "Premature restore mutated an armed lease");
            }
            try (var ledger = new ArenaWorldLedger(root.resolve("ledger"));
                    var journal = new ExternalOperationJournal(root.resolve("journal"));
                    var database = new SqliteDatabase(root, Path.of("audit.sqlite"))) {
                var port = new ArenaWorldStatePort(server, regions, ledger, new ArenaProjectileOwnership(owner, ledger),
                        journal, new SqliteAuditRepository(database));
                port.lifecycleReady();
                require(Arrays.equals(payload, port.capture(fixture, capture)), "World capture replay changed the manifest");
                port.purgeTemporaryState(fixture, purge);
                port.purgeTemporaryState(fixture, purge);
                require(!arrow.isValid() && arrow.isDead() && server.getEntity(arrow.getUniqueId()) == null,
                        "World provider purge did not remove the exact real arrow");
                port.restore(fixture, restore, 1, payload);
                port.restore(fixture, restore, 1, payload);
                require(ledger.requireLease(capture).status() == ArenaWorldLedger.Status.RESTORED,
                        "World lease did not reach restored state");
                long auditCount = database.read(connection -> {
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM mg_audit_event")) {
                        if (!rows.next()) throw new IllegalStateException("World audit count missing");
                        return rows.getLong(1);
                    }
                });
                require(auditCount == 4, "World checkpoint replays duplicated audit records");
                port.lifecycleFailed();
                require(!port.available(), "Ambiguous lifecycle did not close world availability");
                expectRejected(port::lifecycleReady);
                expectRejected(() -> port.restore(fixture, restore, 1, payload));
            }
        } finally {
            if (arrow != null && arrow.isValid()) arrow.remove();
        }
    }

    private static PlayerStateOperation phase(PlayerStateOperation capture, PlayerStateOperation.Kind kind, UUID epoch) {
        return new PlayerStateOperation(kind, UUID.randomUUID(), capture.captureOperationId(), capture.snapshotId(),
                capture.sessionId(), capture.matchId(), capture.playerId(), capture.capturedConnectionId(), epoch,
                capture.game(), capture.capturedAt());
    }

    private static void expectRejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new IllegalStateException("Expected world provider rejection");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
