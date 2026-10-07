package local.harness;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.persistence.SqliteAuditRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import com.ciaac.minecraft.minigames.paper.isolation.ExternalStateAuthority;
import com.ciaac.minecraft.minigames.paper.isolation.ReadGuardedExternalStatePort;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Exercises the production read-only LuckPerms binding against real local API objects. */
final class LuckPermsProbe {
    private static final UUID FIXTURE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PREFIX = "ciaac_probe_";

    private LuckPermsProbe() {}

    static void verify(Server server, Path fixtureRoot) throws Exception {
        Plugin plugin = server.getPluginManager().getPlugin("LuckPerms");
        if (plugin == null || !plugin.isEnabled() || !"5.5.65".equals(plugin.getDescription().getVersion())) {
            throw new IllegalStateException("Real LuckPerms 5.5.65 plugin required");
        }
        var selected = server.getServicesManager().getRegistration(LuckPerms.class);
        require(selected != null && selected.getPlugin() == plugin,
                "Selected LuckPerms API service does not belong to the pinned plugin");
        LuckPerms api = selected.getProvider();

        // Invoke only the package-private production binding; no substitute authority/service is used.
        Class<?> binding = Class.forName(
                "com.ciaac.minecraft.minigames.paper.isolation.LuckPermsAuthorityBinding", true,
                server.getPluginManager().getPlugin("CIAACPlatform").getClass().getClassLoader());
        Method create = binding.getDeclaredMethod("create", Server.class, Plugin.class);
        create.setAccessible(true);
        ExternalStateAuthority authority = (ExternalStateAuthority)create.invoke(null, server, plugin);
        require(authority.available(), "Production LuckPerms authority is unavailable");

        var users = api.getUserManager();
        require(!users.isLoaded(FIXTURE_ID) && users.getUser(FIXTURE_ID) == null,
                "Synthetic LuckPerms UUID is already loaded; refusing to reuse it");
        require(!users.getUniqueUsers().join().contains(FIXTURE_ID),
                "Synthetic LuckPerms UUID already exists in persistent storage; refusing to overwrite it");
        int loadedBefore = users.getLoadedUsers().size();
        expectRejected(() -> authority.read(FIXTURE_ID));
        require(!users.isLoaded(FIXTURE_ID) && users.getUser(FIXTURE_ID) == null
                        && users.getLoadedUsers().size() == loadedBefore,
                "A missing-user read loaded or created a LuckPerms user");

        Files.createDirectories(fixtureRoot);
        List<Group> createdGroups = new ArrayList<>();
        User user = null;
        boolean userCreated = false;
        Throwable failure = null;
        try {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String baseName = PREFIX + suffix + "_base";
            String middleName = PREFIX + suffix + "_middle";
            String leafName = PREFIX + suffix + "_leaf";
            var groupManager = api.getGroupManager();
            for (String name : List.of(baseName, middleName, leafName)) {
                require(groupManager.loadGroup(name).join().isEmpty(),
                        "Synthetic LuckPerms group name already exists; refusing to reuse it: " + name);
            }
            Group base = groupManager.createAndLoadGroup(baseName).join();
            createdGroups.add(base);
            Group middle = groupManager.createAndLoadGroup(middleName).join();
            createdGroups.add(middle);
            Group leaf = groupManager.createAndLoadGroup(leafName).join();
            createdGroups.add(leaf);

            add(base.getData(DataType.NORMAL), permission("ciaac.probe.base", false,
                    "server", "ciaac-local", Instant.now().plusSeconds(3600)));
            add(base.getData(DataType.TRANSIENT), permission("ciaac.probe.base.transient", false,
                    "world", "ciaac-synthetic-test", Instant.now().plusSeconds(1800)));
            add(middle.getData(DataType.NORMAL), inheritance(baseName, true,
                    "server", "ciaac-local", Instant.now().plusSeconds(3600)));
            add(middle.getData(DataType.TRANSIENT), permission("ciaac.probe.middle.transient", false,
                    "world", "ciaac-synthetic-test", Instant.now().plusSeconds(1800)));
            add(leaf.getData(DataType.NORMAL), permission("ciaac.probe.leaf", false,
                    "server", "ciaac-local", Instant.now().plusSeconds(3600)));
            add(leaf.getData(DataType.TRANSIENT), inheritance(middleName, true,
                    "world", "ciaac-synthetic-test", Instant.now().plusSeconds(1800)));
            for (Group group : createdGroups) groupManager.saveGroup(group).join();

            user = users.loadUser(FIXTURE_ID, "CiaacLpProbe").join();
            userCreated = true;
            require(FIXTURE_ID.equals(user.getUniqueId()), "LuckPerms loaded a fixture with the wrong UUID");
            // The real API requires ordinary membership before assigning its stored primary group.
            add(user.getData(DataType.NORMAL), InheritanceNode.builder(leafName).build());
            require(user.setPrimaryGroup(leafName).wasSuccessful(), "Could not assign synthetic primary group");
            add(user.getData(DataType.NORMAL), permission("ciaac.probe.user", false,
                    "server", "ciaac-local", Instant.now().plusSeconds(3600)));
            add(user.getData(DataType.TRANSIENT), permission("ciaac.probe.user.transient", false,
                    "world", "ciaac-synthetic-test", Instant.now().plusSeconds(1800)));
            add(user.getData(DataType.NORMAL), inheritance(leafName, true,
                    "server", "ciaac-local", Instant.now().plusSeconds(3600)));
            users.saveUser(user).join();

            Player fixture = fixturePlayer(server, FIXTURE_ID);
            byte[] original = authority.read(FIXTURE_ID);
            authority.validate(FIXTURE_ID, original);
            expectRejected(() -> authority.validate(UUID.randomUUID(), original));
            byte[] secondRead = authority.read(FIXTURE_ID);
            require(Arrays.equals(original, secondRead), "LuckPerms snapshot is not stable across identical reads");

            UUID connection = UUID.randomUUID();
            PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE,
                    UUID.randomUUID(), UUID.randomUUID(), FIXTURE_ID,
                    connection, connection);
            PlayerStateOperation enter = phase(capture, PlayerStateOperation.Kind.ENTER, connection);
            PlayerStateOperation purge = phase(capture, PlayerStateOperation.Kind.PURGE, UUID.randomUUID());
            PlayerStateOperation restore = phase(capture, PlayerStateOperation.Kind.RESTORE, purge.connectionId());
            byte[] payload;
            try (var database = new SqliteDatabase(fixtureRoot, Path.of("audit.sqlite"));
                    var journal = new ExternalOperationJournal(fixtureRoot.resolve("journal"))) {
                var port = port(authority, journal, database);
                payload = port.capture(fixture, capture);
                require(Arrays.equals(original, payload), "Guard payload differs from real authority snapshot");
                port.enterTemporaryState(fixture, enter);
                port.enterTemporaryState(fixture, enter);
                require(Arrays.equals(original, authority.read(FIXTURE_ID)), "ENTER changed LuckPerms nodes");
            }
            try (var database = new SqliteDatabase(fixtureRoot, Path.of("audit.sqlite"));
                    var journal = new ExternalOperationJournal(fixtureRoot.resolve("journal"))) {
                var port = port(authority, journal, database);
                require(Arrays.equals(payload, port.capture(fixture, capture)), "Reopened capture replay differs");
                port.purgeTemporaryState(fixture, purge);
                port.purgeTemporaryState(fixture, purge);
                require(Arrays.equals(original, authority.read(FIXTURE_ID)), "PURGE changed LuckPerms nodes");
            }
            try (var database = new SqliteDatabase(fixtureRoot, Path.of("audit.sqlite"));
                    var journal = new ExternalOperationJournal(fixtureRoot.resolve("journal"))) {
                var port = port(authority, journal, database);
                require(Arrays.equals(payload, port.capture(fixture, capture)), "Capture changed after journal reopen");
                port.purgeTemporaryState(fixture, purge);
                port.restore(fixture, restore, 1, payload);
                port.restore(fixture, restore, 1, payload);
                require(Arrays.equals(original, authority.read(FIXTURE_ID)), "RESTORE changed LuckPerms nodes");
            }
            try (var database = new SqliteDatabase(fixtureRoot, Path.of("audit.sqlite"));
                    var journal = new ExternalOperationJournal(fixtureRoot.resolve("journal"))) {
                var port = port(authority, journal, database);
                require(Arrays.equals(payload, port.capture(fixture, capture)), "Capture replay failed after restore restart");
                port.purgeTemporaryState(fixture, purge);
                port.restore(fixture, restore, 1, payload);

                Node userOriginal = find(user.getData(DataType.NORMAL).toCollection(), "ciaac.probe.user");
                Node userChanged = permission("ciaac.probe.user", true,
                        "server", "ciaac-local", userOriginal.getExpiry());
                Node groupOriginal = find(leaf.getData(DataType.NORMAL).toCollection(), "ciaac.probe.leaf");
                Node groupChanged = permission("ciaac.probe.leaf", true,
                        "server", "ciaac-local", groupOriginal.getExpiry());
                replace(user.getData(DataType.NORMAL), userOriginal, userChanged);
                replace(leaf.getData(DataType.NORMAL), groupOriginal, groupChanged);
                users.saveUser(user).join();
                api.getGroupManager().saveGroup(leaf).join();
                expectRejected(() -> port.validateRestore(restore, 1, payload));
                expectRejected(() -> port.restore(fixture, restore, 1, payload));
                require(contains(user.getData(DataType.NORMAL).toCollection(), userChanged)
                                && contains(leaf.getData(DataType.NORMAL).toCollection(), groupChanged),
                        "Drift refusal overwrote the changed LuckPerms nodes");
                long[] audits = database.read(connectionToDb -> {
                    try (var statement = connectionToDb.createStatement();
                            var rows = statement.executeQuery(
                                    "SELECT count(*), count(DISTINCT event_id), count(DISTINCT operation_id) FROM mg_audit_event")) {
                        if (!rows.next()) throw new IllegalStateException("LuckPerms audit count missing");
                        return new long[] {rows.getLong(1), rows.getLong(2), rows.getLong(3)};
                    }
                });
                require(Arrays.equals(audits, new long[] {4, 4, 4}),
                        "LuckPerms lifecycle replay duplicated or omitted unique audit records: " + Arrays.toString(audits));
            }
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            List<Throwable> cleanupFailures = new ArrayList<>();
            try {
                if (userCreated && user != null) {
                    // deletePlayerData only removes the name mapping; save a default-only user first.
                    user.getData(DataType.NORMAL).clear();
                    user.getData(DataType.TRANSIENT).clear();
                    Node defaultMembership = InheritanceNode.builder("default").build();
                    add(user.getData(DataType.NORMAL), defaultMembership);
                    require(user.setPrimaryGroup("default").wasSuccessful(),
                            "Could not reset synthetic LuckPerms primary group");
                    require(user.getData(DataType.NORMAL).toCollection().size() == 1
                                    && contains(user.getData(DataType.NORMAL).toCollection(), defaultMembership)
                                    && user.getData(DataType.TRANSIENT).toCollection().isEmpty()
                                    && "default".equals(user.getPrimaryGroup()),
                            "Synthetic LuckPerms user is not default-only before cleanup");
                    users.saveUser(user).join();
                    require(!users.getUniqueUsers().join().contains(FIXTURE_ID),
                            "Synthetic LuckPerms permission nodes remain after cleanup");
                    users.cleanupUser(user);
                    users.deletePlayerData(FIXTURE_ID).join();
                }
            } catch (Throwable cleanupFailure) {
                cleanupFailures.add(cleanupFailure);
            }
            for (int index = createdGroups.size() - 1; index >= 0; index--) {
                try {
                    api.getGroupManager().deleteGroup(createdGroups.get(index)).join();
                } catch (Throwable cleanupFailure) {
                    cleanupFailures.add(cleanupFailure);
                }
            }
            if (failure != null) {
                for (Throwable cleanupFailure : cleanupFailures) {
                    if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                }
            } else if (!cleanupFailures.isEmpty()) {
                Throwable cleanupFailure = cleanupFailures.getFirst();
                for (int index = 1; index < cleanupFailures.size(); index++) {
                    Throwable secondary = cleanupFailures.get(index);
                    if (secondary != cleanupFailure) cleanupFailure.addSuppressed(secondary);
                }
                if (cleanupFailure instanceof Exception exception) throw exception;
                if (cleanupFailure instanceof Error error) throw error;
                throw new IllegalStateException("Could not clean up synthetic LuckPerms fixture", cleanupFailure);
            }
        }
    }

    private static PermissionNode permission(String key, boolean value, String contextKey,
            String contextValue, Instant expiry) {
        return PermissionNode.builder(key).value(value).withContext(contextKey, contextValue).expiry(expiry).build();
    }

    private static InheritanceNode inheritance(String group, boolean value, String contextKey,
            String contextValue, Instant expiry) {
        return InheritanceNode.builder(group).value(value).withContext(contextKey, contextValue).expiry(expiry).build();
    }

    private static void add(net.luckperms.api.model.data.NodeMap map, Node node) {
        require(map.add(node) == DataMutateResult.SUCCESS, "Could not add synthetic LuckPerms node " + node.getKey());
    }

    private static void replace(net.luckperms.api.model.data.NodeMap map, Node oldNode, Node newNode) {
        require(map.remove(oldNode) == DataMutateResult.SUCCESS, "Could not replace synthetic LuckPerms node");
        add(map, newNode);
    }

    private static Node find(Iterable<Node> nodes, String key) {
        for (Node node : nodes) if (key.equals(node.getKey())) return node;
        throw new IllegalStateException("Synthetic LuckPerms node is missing: " + key);
    }

    private static boolean contains(Iterable<Node> nodes, Node expected) {
        for (Node node : nodes) if (node.equals(expected)) return true;
        return false;
    }

    private static PlayerStateOperation operation(PlayerStateOperation.Kind kind, UUID operationId,
            UUID snapshotId, UUID playerId, UUID capturedConnection, UUID connection) {
        return new PlayerStateOperation(kind, operationId, operationId, snapshotId, UUID.randomUUID(),
                UUID.randomUUID(), playerId, capturedConnection, connection, GameKey.ARENA, Instant.now());
    }

    private static PlayerStateOperation phase(PlayerStateOperation capture,
            PlayerStateOperation.Kind kind, UUID connection) {
        return new PlayerStateOperation(kind, UUID.randomUUID(), capture.captureOperationId(),
                capture.snapshotId(), capture.sessionId(), capture.matchId(), capture.playerId(),
                capture.capturedConnectionId(), connection, capture.game(), capture.capturedAt());
    }

    private static ReadGuardedExternalStatePort port(ExternalStateAuthority authority,
            ExternalOperationJournal journal, SqliteDatabase database) {
        return new ReadGuardedExternalStatePort("luckperms-5.5.65-permissions-guard",
                Set.of(PlayerStateFacet.PERMISSIONS), authority, journal, new SqliteAuditRepository(database));
    }

    private static Player fixturePlayer(Server server, UUID id) {
        return (Player)java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName", "getDisplayName" -> "CiaacLpProbe";
                    case "getServer" -> server;
                    case "getWorld" -> server.getWorlds().getFirst();
                    case "getLocation" -> server.getWorlds().getFirst().getSpawnLocation();
                    case "isOnline", "isOp", "hasMetadata" -> false;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> id.hashCode();
                    case "toString" -> "SyntheticLuckPermsFixture";
                    default -> throw new UnsupportedOperationException("Unexpected fixture Player method: " + method.getName());
                });
    }

    private static void expectRejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Unsafe LuckPerms operation was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
