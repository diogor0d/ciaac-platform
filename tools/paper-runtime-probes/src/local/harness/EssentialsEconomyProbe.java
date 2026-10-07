package local.harness;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.paper.isolation.EssentialsEconomyAuthority;
import com.ciaac.minecraft.minigames.paper.isolation.ReadGuardedExternalStatePort;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.persistence.SqliteAuditRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Real provider API fixture only; never authenticates a player or admits a session. */
final class EssentialsEconomyProbe {
    private static final UUID FIXTURE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private EssentialsEconomyProbe() {}

    static void verify(Server server, Path fixtureRoot) throws Exception {
        Plugin essentials = server.getPluginManager().getPlugin("Essentials");
        Plugin vault = server.getPluginManager().getPlugin("Vault");
        if (essentials == null || vault == null) throw new IllegalStateException("Real Essentials/Vault plugins required");
        var authority = new EssentialsEconomyAuthority(server, essentials, vault);
        require(authority.available(), "Essentials economy authority is unavailable after startup");
        ClassLoader loader = essentials.getClass().getClassLoader();
        UUID id = FIXTURE_ID;
        Player fixture = fixturePlayer(server, id);
        Object users = essentials.getClass().getMethod("getUsers").invoke(essentials);
        var cacheField = users.getClass().getDeclaredField("userCache");
        require(cacheField.trySetAccessible(), "Pinned loaded cache is inaccessible");
        Object nativeCache = cacheField.get(users);
        Class<?> cacheApi = Class.forName("com.google.common.cache.Cache", false, loader);
        @SuppressWarnings("unchecked") Map<UUID, Object> cache = (Map<UUID, Object>)cacheApi
                .getMethod("asMap").invoke(nativeCache);
        require(!cache.containsKey(id), "Synthetic UUID unexpectedly loaded");
        int before = cache.size();
        expectRejected(() -> authority.read(id));
        require(cache.size() == before && !cache.containsKey(id), "Read created or loaded an account");
        // Fixture preparation deliberately creates an account; the authority itself only reads it.
        Object user = users.getClass().getMethod("getUser", Player.class).invoke(users, fixture);
        require(user != null, "Essentials did not create the synthetic fixture");
        require(cache.get(id) == user, "Normal Essentials initialization did not populate its loaded cache");
        var money = user.getClass().getMethod("getMoney");
        var setMoney = user.getClass().getMethod("setMoney", BigDecimal.class);
        setMoney.invoke(user, new BigDecimal("123.45"));
        try {
            EssentialsHomesProbe.verify(server, essentials, user, id);
            SimpleClaimsProbe.verify(server, essentials, fixture, id,
                    fixtureRoot.resolve("claims-and-homes-" + UUID.randomUUID()));
            byte[] original = authority.read(id);
            authority.validate(id, original);
            expectRejected(() -> authority.validate(UUID.randomUUID(), original));
            Files.createDirectories(fixtureRoot);
            UUID operation = UUID.randomUUID(), epoch = UUID.randomUUID();
            var capture = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, operation, operation,
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), id, epoch, epoch, GameKey.ARENA, Instant.now());
            var enter = phase(capture, PlayerStateOperation.Kind.ENTER, epoch);
            var purge = phase(capture, PlayerStateOperation.Kind.PURGE, UUID.randomUUID());
            var restore = phase(capture, PlayerStateOperation.Kind.RESTORE, purge.connectionId());
            byte[] payload;
            try (var db = new SqliteDatabase(fixtureRoot, Path.of("audit.sqlite"));
                    var journal = new ExternalOperationJournal(fixtureRoot.resolve("journal"))) {
                var port = port(authority, journal, db);
                payload = port.capture(fixture, capture);
                port.enterTemporaryState(fixture, enter);
                require(Arrays.equals(original, authority.read(id)), "Entry changed economy state");
            }
            try (var db = new SqliteDatabase(fixtureRoot, Path.of("audit.sqlite"));
                    var journal = new ExternalOperationJournal(fixtureRoot.resolve("journal"))) {
                var port = port(authority, journal, db);
                require(Arrays.equals(payload, port.capture(fixture, capture)), "Durable capture replay differs");
                port.purgeTemporaryState(fixture, purge);
                port.restore(fixture, restore, 1, payload);
                port.restore(fixture, restore, 1, payload);
                require(new BigDecimal("123.45").compareTo((BigDecimal)money.invoke(user)) == 0, "Lifecycle changed balance");
                setMoney.invoke(user, new BigDecimal("456.78"));
                expectRejected(() -> port.validateRestore(restore, 1, payload));
                expectRejected(() -> port.restore(fixture, restore, 1, payload));
                expectRejected(() -> port.capture(fixture, capture));
                require(new BigDecimal("456.78").compareTo((BigDecimal)money.invoke(user)) == 0, "Recovery overwrote external balance change");
                long audits = db.read(connection -> {
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM mg_audit_event")) {
                        if (!rows.next()) throw new IllegalStateException("Audit count missing");
                        return rows.getLong(1);
                    }
                });
                require(audits == 4, "Checkpoint replay duplicated audit entries");
            }
        } finally {
            cache.remove(id, user);
        }
    }

    private static Player fixturePlayer(Server server, UUID id) {
        return (Player)Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName", "getDisplayName" -> "CiaacEcoProbe";
                    case "getServer" -> server;
                    case "getWorld" -> server.getWorlds().getFirst();
                    case "getLocation" -> server.getWorlds().getFirst().getSpawnLocation();
                    case "isOnline", "isOp", "hasMetadata" -> false;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> id.hashCode();
                    case "toString" -> "SyntheticEconomyFixture";
                    default -> throw new UnsupportedOperationException("Unexpected fixture Player method: " + method.getName());
                });
    }

    private static ReadGuardedExternalStatePort port(EssentialsEconomyAuthority authority,
            ExternalOperationJournal journal, SqliteDatabase db) {
        return new ReadGuardedExternalStatePort("essentials-2.22.0-economy-guard", Set.of(PlayerStateFacet.ECONOMY),
                authority, journal, new SqliteAuditRepository(db));
    }

    private static PlayerStateOperation phase(PlayerStateOperation capture, PlayerStateOperation.Kind kind, UUID epoch) {
        return new PlayerStateOperation(kind, UUID.randomUUID(), capture.captureOperationId(), capture.snapshotId(),
                capture.sessionId(), capture.matchId(), capture.playerId(), capture.capturedConnectionId(), epoch,
                capture.game(), capture.capturedAt());
    }

    private static void expectRejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Unsafe provider operation was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
