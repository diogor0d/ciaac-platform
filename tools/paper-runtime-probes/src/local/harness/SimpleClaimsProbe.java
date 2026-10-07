package local.harness;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.paper.isolation.ClaimsAndHomesAuthority;
import com.ciaac.minecraft.minigames.paper.isolation.EssentialsHomesAuthority;
import com.ciaac.minecraft.minigames.paper.isolation.ReadGuardedExternalStatePort;
import com.ciaac.minecraft.minigames.paper.isolation.SimpleClaimStateBinding;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.persistence.SqliteAuditRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Real loaded SimpleClaimSystem and Essentials home authorities; no session admission. */
final class SimpleClaimsProbe {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID EXTRA_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final int CLAIM_ID = 1;
    private static final int EXTRA_CLAIM_ID = 991;

    private SimpleClaimsProbe() {}

    static void verify(Server server, Plugin essentials, Player fixture, UUID playerId, Path fixtureRoot) throws Exception {
        require(OWNER.equals(playerId), "Synthetic account UUID differs from the persisted claim fixture");
        Plugin scs = server.getPluginManager().getPlugin("SimpleClaimSystem");
        require(scs != null, "Real SimpleClaimSystem plugin required");
        var claims = SimpleClaimStateBinding.create(server, scs);
        var homes = new EssentialsHomesAuthority(server, essentials);
        var authority = new ClaimsAndHomesAuthority(claims, homes);
        require(authority.available(), "Combined claims/homes authority is unavailable after startup");

        verifyLoadedClaims(server, scs, fixture);
        byte[] original = authority.read(playerId);
        authority.validate(playerId, original);
        expectRejected(() -> authority.validate(UUID.randomUUID(), original));
        require(Arrays.equals(original, authority.read(playerId)), "Combined source snapshot is not deterministic");

        Files.createDirectories(fixtureRoot);
        UUID operation = UUID.randomUUID(), epoch = UUID.randomUUID();
        var capture = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, operation, operation,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), playerId, epoch, epoch,
                GameKey.ARENA, Instant.now());
        var enter = phase(capture, PlayerStateOperation.Kind.ENTER, epoch);
        var purge = phase(capture, PlayerStateOperation.Kind.PURGE, UUID.randomUUID());
        var restore = phase(capture, PlayerStateOperation.Kind.RESTORE, purge.connectionId());
        byte[] payload;
        try (var db = new SqliteDatabase(fixtureRoot, Path.of("audit.sqlite"));
                var journal = new ExternalOperationJournal(fixtureRoot.resolve("journal"))) {
            var port = port(authority, journal, db);
            payload = port.capture(fixture, capture);
            port.enterTemporaryState(fixture, enter);
            require(Arrays.equals(original, authority.read(playerId)), "Entry changed claims or homes");
        }
        try (var db = new SqliteDatabase(fixtureRoot, Path.of("audit.sqlite"));
                var journal = new ExternalOperationJournal(fixtureRoot.resolve("journal"))) {
            var port = port(authority, journal, db);
            require(Arrays.equals(payload, port.capture(fixture, capture)), "Durable combined capture replay differs");
            port.purgeTemporaryState(fixture, purge);
            port.restore(fixture, restore, 1, payload);
            port.restore(fixture, restore, 1, payload);
            require(Arrays.equals(original, authority.read(playerId)), "Lifecycle changed claims or homes");

            mutatePermissionAndVerifyGuard(port, authority, fixture, restore, original);
        }
        verifyDatabaseIndexMismatch(server, scs, authority, playerId);
    }

    private static void verifyLoadedClaims(Server server, Plugin scs, Player fixture) throws Exception {
        ClassLoader loader = scs.getClass().getClassLoader();
        Class<?> apiType = Class.forName("fr.xyness.SCS.API.SimpleClaimSystemAPI", false, loader);
        Class<?> apiImplType = Class.forName("fr.xyness.SCS.API.SimpleClaimSystemAPI_Impl", true, loader);
        Object api = apiImplType.getConstructor(scs.getClass()).newInstance(scs);
        Set<?> all = (Set<?>)apiType.getMethod("getAllClaims").invoke(api);
        Set<?> owned = (Set<?>)apiType.getMethod("getPlayerClaims", Player.class).invoke(api, fixture);
        Set<?> memberships = (Set<?>)apiType.getMethod("getPlayerClaimsWhereMember", Player.class).invoke(api, fixture);
        require(all.size() >= 2, "Real SCS API did not load both owner-scoped fixture claims");

        Object own = findClaim(all, OWNER);
        Object other = findClaim(all, OTHER_OWNER);
        require(own != null && other != null, "SCS API omitted one of the duplicate numeric claim IDs");
        verifyClaim(own, OWNER, 10, 17L);
        verifyClaim(other, OTHER_OWNER, 11, 23L);
        require(collectionHasIdentity(owned, OWNER, CLAIM_ID), "SCS owner-scoped API omitted the owner claim");
        require(collectionHasIdentity(memberships, OTHER_OWNER, CLAIM_ID), "SCS member API omitted the second owner-scoped claim");
        require(!collectionHasIdentity(owned, OTHER_OWNER, CLAIM_ID), "SCS owner API conflated the other owner with this player");
    }

    private static Object findClaim(Set<?> claims, UUID owner) throws Exception {
        for (Object claim : claims) {
            if (owner.equals(claim.getClass().getMethod("getUUID").invoke(claim))
                    && ((Integer)claim.getClass().getMethod("getId").invoke(claim)) == CLAIM_ID) return claim;
        }
        return null;
    }

    private static void verifyClaim(Object claim, UUID owner, int chunkX, long price) throws Exception {
        Class<?> type = claim.getClass();
        require(owner.equals(type.getMethod("getUUID").invoke(claim)), "Claim owner UUID differs");
        require(((Integer)type.getMethod("getId").invoke(claim)) == CLAIM_ID, "Claim ID differs");
        require(((Long)type.getMethod("getPrice").invoke(claim)) == price, "Fixture sale price differs");
        Object location = type.getMethod("getLocation").invoke(claim);
        require(location != null, "Fixture claim location missing");
        var world = (org.bukkit.World)location.getClass().getMethod("getWorld").invoke(location);
        require(world != null && world.getName().equals("ciaac-synthetic-test"), "Fixture claim world differs");
        Set<?> chunks = (Set<?>)type.getMethod("getChunks").invoke(claim);
        require(chunks.size() == 1, "Fixture claim must contain one chunk");
        Object chunk = chunks.iterator().next();
        require(((Integer)chunk.getClass().getMethod("getX").invoke(chunk)) == chunkX
                && ((Integer)chunk.getClass().getMethod("getZ").invoke(chunk)) == 0,
                "Fixture claim chunk coordinates differ");
        @SuppressWarnings("unchecked") Map<String, Map<String, Boolean>> permissions =
                (Map<String, Map<String, Boolean>>)type.getMethod("getPermissions").invoke(claim);
        require(permissions.get("natural").size() == 6 && permissions.get("visitors").size() == 27
                && permissions.get("members").size() == 27, "Fixture permission map shape differs");
        for (var role : permissions.values()) {
            require(role.values().stream().allMatch(value -> Boolean.FALSE.equals(value)),
                    "Fixture contains a non-false role permission");
        }
    }

    private static boolean collectionHasIdentity(Set<?> claims, UUID owner, int id) throws Exception {
        for (Object claim : claims) {
            if (owner.equals(claim.getClass().getMethod("getUUID").invoke(claim))
                    && ((Integer)claim.getClass().getMethod("getId").invoke(claim)) == id) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void mutatePermissionAndVerifyGuard(ReadGuardedExternalStatePort port,
            ClaimsAndHomesAuthority authority, Player fixture, PlayerStateOperation restore, byte[] original) throws Exception {
        Plugin scs = fixture.getServer().getPluginManager().getPlugin("SimpleClaimSystem");
        Class<?> implementation = Class.forName("fr.xyness.SCS.API.SimpleClaimSystemAPI_Impl", true,
                scs.getClass().getClassLoader());
        Object api = implementation.getConstructor(scs.getClass()).newInstance(scs);
        Object own = findClaim((Set<?>)implementation.getMethod("getAllClaims").invoke(api), OWNER);
        require(own != null, "Owner claim disappeared before the drift check");
        Map<String, LinkedHashMap<String, Boolean>> permissions =
                (Map<String, LinkedHashMap<String, Boolean>>)own.getClass().getMethod("getPermissions").invoke(own);
        Map.Entry<String, Boolean> entry = permissions.get("natural").entrySet().iterator().next();
        require(Boolean.FALSE.equals(entry.getValue()), "Fixture permission was not initially false");
        permissions.get("natural").put(entry.getKey(), true);

        byte[] drifted = authority.read(fixture.getUniqueId());
        require(!Arrays.equals(original, drifted), "Permission drift did not change combined capture");
        expectRejected(() -> port.validateRestore(restore, 1, original));
        expectRejected(() -> port.restore(fixture, restore, 1, original));
        expectRejected(() -> port.capture(fixture, captureFor(restore)));
        require(Boolean.TRUE.equals(permissions.get("natural").get(entry.getKey())),
                "Read guard overwrote the externally changed SCS permission");
    }

    private static PlayerStateOperation captureFor(PlayerStateOperation phase) {
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, phase.captureOperationId(),
                phase.captureOperationId(), phase.snapshotId(), phase.sessionId(), phase.matchId(), phase.playerId(),
                phase.capturedConnectionId(), phase.capturedConnectionId(), phase.game(), phase.capturedAt());
    }

    private static void verifyDatabaseIndexMismatch(Server server, Plugin scs,
            ClaimsAndHomesAuthority authority, UUID playerId) throws Exception {
        Class<?> sourceType = Class.forName("fr.xyness.libs.hikari.HikariDataSource", false, scs.getClass().getClassLoader());
        DataSource source;
        try {
            source = (DataSource)java.lang.invoke.MethodHandles.publicLookup().findVirtual(scs.getClass(),
                    "getDataSource", java.lang.invoke.MethodType.methodType(sourceType)).invoke(scs);
        } catch (Throwable unavailable) { throw new IllegalStateException("Real SCS datasource unavailable", unavailable); }
        try (var connection = source.getConnection(); var insert = connection.prepareStatement(
                "INSERT INTO scs_claims_1 (id_claim, owner_uuid, owner_name, claim_name, claim_description, chunks, world_name, location, members, permissions, for_sale, sale_price, bans) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setInt(1, EXTRA_CLAIM_ID); insert.setString(2, EXTRA_OWNER.toString());
            insert.setString(3, "ProbeExtra"); insert.setString(4, "probe-extra"); insert.setString(5, "temporary mismatch row");
            insert.setString(6, "rO0ABXcIAAAAAAAAAAA="); insert.setString(7, "ciaac-synthetic-test");
            insert.setString(8, "0.5;64.0;0.5;0.0;0.0"); insert.setString(9, playerId.toString());
            insert.setString(10, "natural:000000;visitors:000000000000000000000000000;members:000000000000000000000000000");
            insert.setInt(11, 0); insert.setLong(12, 0); insert.setString(13, ""); insert.executeUpdate();
        }
        try {
            expectRejected(() -> authority.read(playerId));
        } finally {
            try (var connection = source.getConnection(); var delete = connection.prepareStatement(
                    "DELETE FROM scs_claims_1 WHERE id_claim = ? AND owner_uuid = ?")) {
                delete.setInt(1, EXTRA_CLAIM_ID); delete.setString(2, EXTRA_OWNER.toString());
                require(delete.executeUpdate() == 1, "Temporary mismatch fixture row was not removed exactly once");
            }
        }
        require(authority.available(), "Authority unavailable after temporary database mismatch cleanup");
        require(authority.read(playerId).length > 0, "Authority did not recover after mismatch row cleanup");
    }

    private static ReadGuardedExternalStatePort port(ClaimsAndHomesAuthority authority,
            ExternalOperationJournal journal, SqliteDatabase db) {
        return new ReadGuardedExternalStatePort("scs-homes-guard", Set.of(PlayerStateFacet.CLAIMS_AND_HOMES),
                authority, journal, new SqliteAuditRepository(db));
    }

    private static PlayerStateOperation phase(PlayerStateOperation capture, PlayerStateOperation.Kind kind, UUID epoch) {
        return new PlayerStateOperation(kind, UUID.randomUUID(), capture.captureOperationId(), capture.snapshotId(),
                capture.sessionId(), capture.matchId(), capture.playerId(), capture.capturedConnectionId(), epoch,
                capture.game(), capture.capturedAt());
    }

    private static void expectRejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Unsafe claims/homes operation was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
