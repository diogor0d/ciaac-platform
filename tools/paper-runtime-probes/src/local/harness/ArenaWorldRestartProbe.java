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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Two-process resource proof only. Never authenticates or admits a player/session. */
final class ArenaWorldRestartProbe {
    static void run(Plugin probe, String phase) {
        String run = System.getProperty("ciaac.local-test-restart-id", "");
        if (!run.matches("[a-z0-9-]{1,64}") || !java.util.Set.of("seed", "verify").contains(phase)) {
            throw new IllegalArgumentException("Explicit local restart phase and run ID required");
        }
        require(probe.getServer().getOnlinePlayers().isEmpty(), "Restart fixture requires zero players");
        require(ArenaWorldStatePort.nativeBuildMatches(), "Exact reviewed native build required");
        World world = probe.getServer().getWorlds().getFirst();
        Path root = probe.getDataFolder().toPath().resolve("restart-" + run);
        try {
            if (phase.equals("seed")) {
                Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                require(world.addPluginChunkTicket(3, 0, probe), "Could not own the seed chunk ticket");
                probe.getServer().getScheduler().runTaskLater(probe, () -> {
                    try { seed(probe, world, root); }
                    catch (Exception failure) { failed(probe, root, failure); Bukkit.shutdown(); }
                }, 20L);
            } else {
                require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root), "Missing private restart fixture");
                require(world.addPluginChunkTicket(3, 0, probe), "Could not own the verify chunk ticket");
                probe.getServer().getScheduler().runTaskLater(probe, () -> {
                    try { verify(probe, world, root); }
                    catch (Exception failure) { failed(probe, root, failure); }
                    finally { world.removePluginChunkTicket(3, 0, probe); Bukkit.shutdown(); }
                }, 10L);
            }
        } catch (Exception failure) {
            failed(probe, root, failure);
            Bukkit.shutdown();
        }
    }

    private static void seed(Plugin probe, World world, Path root) throws Exception {
        var capture = capture();
        Properties state = new Properties();
        try (var ledger = new ArenaWorldLedger(root.resolve("ledger"));
                var journal = new ExternalOperationJournal(root.resolve("journal"));
                var database = new SqliteDatabase(root, Path.of("audit.sqlite"))) {
            var ownership = new ArenaProjectileOwnership(probe, ledger);
            var port = port(probe, world, ledger, ownership, journal, database);
            port.lifecycleReady(); // Resource fixture only, not production/player admission.
            port.capture(player(), capture);
            port.enterTemporaryState(player(), phase(PlayerStateOperation.Kind.ENTER));
            Arrow owned = world.spawn(new Location(world, 51.5, 205, 3.5), Arrow.class,
                    arrow -> { arrow.setGravity(false); ownership.beforeAdd(capture, arrow); });
            ownership.afterAdd(owned);
            Arrow control = world.spawn(new Location(world, 52.5, 205, 3.5), Arrow.class,
                    arrow -> { arrow.setGravity(false); arrow.setPersistent(true); });
            require(owned.isValid() && !owned.isPersistent() && control.isValid() && control.isPersistent(), "Native seed flags differ");
            require(ledger.findEntity(owned.getUniqueId()).orElseThrow().status() == ArenaWorldLedger.EntityStatus.CONFIRMED,
                    "Native seed ownership was not durably confirmed");
            state.setProperty("world", world.getUID().toString());
            state.setProperty("owned", owned.getUniqueId().toString());
            state.setProperty("control", control.getUniqueId().toString());
            state.setProperty("result", "SEED_CREATED");
            write(root.resolve("state.properties"), state);
            world.save();
        }
        // Give native region/entity I/O time to finish; verify will require the control really survived.
        probe.getServer().getScheduler().runTaskLater(probe, () -> {
            try {
                require(probe.getServer().getOnlinePlayers().isEmpty(), "A player joined during seed preparation");
                state.setProperty("result", "SEED_READY");
                write(root.resolve("state.properties"), state);
                probe.getLogger().info("Arena restart resource seed is ready; await external local crash driver.");
            } catch (Exception failure) { failed(probe, root, failure); Bukkit.shutdown(); }
        }, 50L);
    }

    private static void verify(Plugin probe, World world, Path root) throws Exception {
        require(probe.getServer().getOnlinePlayers().isEmpty(), "A player joined during restart verification");
        Properties state = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve("state.properties"))) { state.load(reader); }
        require(state.getProperty("result").equals("SEED_READY") && state.getProperty("world").equals(world.getUID().toString()),
                "Restart seed or world identity differs");
        UUID ownedId = UUID.fromString(state.getProperty("owned"));
        UUID controlId = UUID.fromString(state.getProperty("control"));
        var control = probe.getServer().getEntity(controlId);
        require(control instanceof Arrow && control.isValid() && control.isPersistent()
                && control.getWorld().getUID().equals(world.getUID()), "Persisted unrelated control did not survive; absence proof invalid");
        require(probe.getServer().getEntity(ownedId) == null, "Nonpersistent owned arrow unexpectedly survived");
        try (var ledger = new ArenaWorldLedger(root.resolve("ledger"));
                var journal = new ExternalOperationJournal(root.resolve("journal"));
                var database = new SqliteDatabase(root, Path.of("audit.sqlite"))) {
            var ownership = new ArenaProjectileOwnership(probe, ledger);
            var port = port(probe, world, ledger, ownership, journal, database);
            port.lifecycleReady();
            var capture = capture();
            byte[] payload = ledger.requireLease(capture).manifest();
            require(ledger.findEntity(ownedId).orElseThrow().status() == ArenaWorldLedger.EntityStatus.CONFIRMED,
                    "Restart changed durable ownership before reconciliation");
            rejected(() -> port.purgeTemporaryState(player(), phase(PlayerStateOperation.Kind.PURGE)));
            rejected(() -> port.restore(player(), phase(PlayerStateOperation.Kind.RESTORE), 1, payload));
            require(ledger.requireLease(capture).status() == ArenaWorldLedger.Status.ARMED
                    && ledger.findEntity(ownedId).orElseThrow().status() == ArenaWorldLedger.EntityStatus.CONFIRMED,
                    "Unproven absence mutated ownership or lease");
            long count = database.read(connection -> {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM mg_audit_event")) {
                    require(rows.next(), "Audit count missing"); return rows.getLong(1);
                }
            });
            require(count == 2 && probe.getServer().getEntity(controlId) == control && control.isValid(),
                    "Rejected recovery changed audit or unrelated native control");
            state.setProperty("result", "PASS");
            state.setProperty("absentOwnedArrowRejected", "true");
            state.setProperty("durableIntentPreserved", "true");
            state.setProperty("unrelatedPersistedArrowPreserved", "true");
            state.setProperty("authenticatedPlayerRecovery", "UNVERIFIED");
            write(root.resolve("evidence.properties"), state);
            // This exact control was created by this fixture; production purge left it intact.
            control.remove();
            probe.getLogger().info("Arena restart resource guard: PASS; authenticated session recovery remains unverified.");
        }
    }

    private static ArenaWorldStatePort port(Plugin probe, World world, ArenaWorldLedger ledger,
            ArenaProjectileOwnership ownership, ExternalOperationJournal journal, SqliteDatabase database) {
        var regions = new ProtectedRegionRegistry();
        regions.register(new ProtectedRegion("restart-floor", GameKey.ARENA,
                new CuboidRegion(world.getUID(), 50, world.getMinHeight(), 2, 55, world.getMaxHeight() - 1, 5),
                ProtectedRegionRole.PARTICIPANT_ONLY, true));
        regions.register(new ProtectedRegion("restart-seats", GameKey.ARENA,
                new CuboidRegion(world.getUID(), 60, world.getMinHeight(), 2, 65, world.getMaxHeight() - 1, 5),
                ProtectedRegionRole.SPECTATOR_PUBLIC, true));
        return new ArenaWorldStatePort(probe.getServer(), regions, ledger, ownership, journal, new SqliteAuditRepository(database));
    }

    private static PlayerStateOperation capture() {
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, id(1), id(1), id(2), id(3), id(4),
                id(5), id(6), id(6), GameKey.ARENA, Instant.parse("2026-10-04T00:00:00.000000007Z"));
    }
    private static PlayerStateOperation phase(PlayerStateOperation.Kind kind) {
        var c = capture();
        return new PlayerStateOperation(kind, id(10 + kind.ordinal()), c.captureOperationId(), c.snapshotId(),
                c.sessionId(), c.matchId(), c.playerId(), c.capturedConnectionId(),
                kind == PlayerStateOperation.Kind.ENTER ? c.connectionId() : id(7), c.game(), c.capturedAt());
    }
    private static UUID id(int number) { return new UUID(0x701L, number); }
    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (instance, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return id(5);
                    throw new IllegalStateException("Unexpected resource fixture player call");
                });
    }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) {
            require(expected.getMessage().equals("Arena projectile absence needs lifecycle reconciliation"), "Unexpected rejection reason");
            return;
        }
        throw new IllegalStateException("Unproven crash absence was accepted");
    }
    private static void write(Path file, Properties values) throws Exception {
        if (Files.isSymbolicLink(file)) throw new IllegalStateException("Refusing symlink evidence");
        var text = new StringWriter(); values.store(text, "Private synthetic resource evidence");
        Path temporary = Files.createTempFile(file.getParent(), ".evidence-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var bytes = ByteBuffer.wrap(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void failed(Plugin probe, Path root, Exception failure) {
        probe.getLogger().log(java.util.logging.Level.SEVERE, "Arena restart resource fixture failed", failure);
        try {
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                var result = new Properties(); result.setProperty("result", "FAIL");
                result.setProperty("failureType", failure.getClass().getSimpleName()); write(root.resolve("evidence.properties"), result);
            }
        } catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
