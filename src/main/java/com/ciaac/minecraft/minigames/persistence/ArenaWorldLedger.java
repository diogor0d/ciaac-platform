package com.ciaac.minecraft.minigames.persistence;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable ownership evidence for arena entities. This class never mutates a world or entity. */
public final class ArenaWorldLedger implements AutoCloseable {
    public enum Status { CAPTURED, ARMED, PURGING, PURGED, RESTORED }
    public enum EntityType { ARROW, SPECTRAL_ARROW, ANVIL_MARKER, ELYTRA_FIREWORK }
    public enum EntityStatus { PENDING, CONFIRMED, REMOVED }
    private static final int SCHEMA = 3;
    private static final int IDENTITY_SCHEMA = 1;
    private static final int MAX_MANIFEST = 128 * 1024;
    private static final int MAX_ENTITIES = 4096;
    private static final Comparator<Entity> ENTITY_ORDER = Comparator.comparing(Entity::entityId);
    private static final String CREATE_LEASE_TABLE = """
            CREATE TABLE arena_lease (
                session_id TEXT NOT NULL,
                player_id TEXT NOT NULL,
                capture_operation_id TEXT NOT NULL UNIQUE,
                capture_identity BLOB NOT NULL,
                manifest BLOB NOT NULL,
                manifest_sha256 TEXT NOT NULL,
                status TEXT NOT NULL CHECK (status IN ('CAPTURED','ARMED','PURGING','PURGED','RESTORED')),
                PRIMARY KEY (session_id, player_id),
                UNIQUE (session_id)
            )
            """;
    private static final String CREATE_ENTITY_TABLE_V1 = """
            CREATE TABLE arena_entity (
                entity_id TEXT NOT NULL PRIMARY KEY,
                session_id TEXT NOT NULL,
                player_id TEXT NOT NULL,
                world_id TEXT NOT NULL,
                entity_type TEXT NOT NULL CHECK (entity_type IN ('ARROW','SPECTRAL_ARROW')),
                status TEXT NOT NULL CHECK (status IN ('PENDING','CONFIRMED','REMOVED')),
                FOREIGN KEY (session_id, player_id) REFERENCES arena_lease(session_id, player_id)
            )
            """;
    private static final String CREATE_ENTITY_TABLE = """
            CREATE TABLE arena_entity (
                entity_id TEXT NOT NULL PRIMARY KEY,
                session_id TEXT NOT NULL,
                player_id TEXT NOT NULL,
                world_id TEXT NOT NULL,
                entity_type TEXT NOT NULL CHECK (entity_type IN ('ARROW','SPECTRAL_ARROW','ANVIL_MARKER','ELYTRA_FIREWORK')),
                status TEXT NOT NULL CHECK (status IN ('PENDING','CONFIRMED','REMOVED')),
                FOREIGN KEY (session_id, player_id) REFERENCES arena_lease(session_id, player_id)
            )
            """;
    private static final String CREATE_ENTITY_PROCESS_TABLE = """
            CREATE TABLE arena_entity_process (
                entity_id TEXT NOT NULL PRIMARY KEY REFERENCES arena_entity(entity_id),
                process_pid INTEGER NOT NULL CHECK (typeof(process_pid) = 'integer' AND process_pid > 0),
                process_started_at INTEGER NOT NULL CHECK (typeof(process_started_at) = 'integer' AND process_started_at > 0)
            )
            """;
    private static final String CREATE_ENTITY_LEASE_INDEX =
            "CREATE INDEX arena_entity_lease ON arena_entity(session_id, player_id, entity_id)";
    private static final String CREATE_ACTIVE_PLAYER_INDEX =
            "CREATE UNIQUE INDEX arena_active_player ON arena_lease(player_id) WHERE status IN ('ARMED','PURGING','PURGED')";
    private final Connection connection;

    /** The capture identity and manifest are immutable snapshots; manifest access returns a copy. */
    public record Lease(PlayerStateOperation capture, byte[] manifest, Status status) {
        public Lease {
            Objects.requireNonNull(capture, "capture");
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(status, "status");
            manifest = manifest.clone();
        }
        @Override public byte[] manifest() { return manifest.clone(); }
    }

    public record Entity(UUID entityId, UUID worldId, EntityType type, EntityStatus status) {
        public Entity {
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(status, "status");
        }
    }

    /** Validated event lookup result containing the durable lease key and exact owned entity identity. */
    public record EntityClaim(UUID sessionId, UUID entityId, UUID worldId, EntityType type, EntityStatus status) {
        public EntityClaim {
            Objects.requireNonNull(sessionId, "sessionId"); Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(worldId, "worldId"); Objects.requireNonNull(type, "type");
            Objects.requireNonNull(status, "status");
        }
    }

    public ArenaWorldLedger(Path directory) {
        Objects.requireNonNull(directory, "directory");
        Connection opened = null;
        try {
            Path root = directory.toAbsolutePath().normalize();
            rejectSymlinkAncestors(root);
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Arena ledger directory is not a real directory");
            } else Files.createDirectory(root);
            if (!root.toRealPath().equals(root)) throw new IOException("Arena ledger path contains a symlink");
            restrict(root, "rwx------");
            Path file = root.resolve("arena-world.sqlite");
            for (String suffix : new String[] {"", "-wal", "-shm", "-journal"}) {
                Path candidate = root.resolve("arena-world.sqlite" + suffix);
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                        && (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("Arena ledger file or sidecar is not a regular file");
            }
            Class.forName("org.sqlite.JDBC");
            opened = DriverManager.getConnection("jdbc:sqlite:" + file);
            restrict(file, "rw-------");
            initialize(opened);
            connection = opened;
        } catch (IOException | SQLException | ClassNotFoundException | RuntimeException | LinkageError failure) {
            if (opened != null) try { opened.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw new PersistenceFailure("Could not initialize the arena world ledger", failure);
        }
    }

    /** Stores a bounded manifest once. Replays must match its complete frozen capture identity and bytes. */
    public synchronized Lease capture(PlayerStateOperation context, byte[] manifest) {
        PlayerStateOperation capture = normalize(context);
        byte[] payload = boundedManifest(manifest);
        byte[] identity = identity(capture);
        return transaction(() -> {
            Optional<Lease> existingLease = readLeaseBySession(capture.sessionId());
            if (existingLease.isPresent()) {
                Lease existing = existingLease.get();
                if (!Arrays.equals(identity, identity(existing.capture())) || !Arrays.equals(existing.manifest(), payload))
                    throw new IllegalStateException("Arena capture conflicts with existing session lease");
                return existing;
            }
            if (context.kind() != PlayerStateOperation.Kind.CAPTURE)
                throw new IllegalStateException("Only the capture phase may create an arena lease");
            if (hasActiveLeaseForPlayer(capture.playerId()))
                throw new IllegalStateException("Player already has an active arena lease");
            try (var insert = connection.prepareStatement("""
                    INSERT INTO arena_lease(session_id, player_id, capture_operation_id, capture_identity,
                        manifest, manifest_sha256, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'CAPTURED') ON CONFLICT(session_id, player_id) DO NOTHING
                    """)) {
                insert.setString(1, capture.sessionId().toString());
                insert.setString(2, capture.playerId().toString());
                insert.setString(3, capture.captureOperationId().toString());
                insert.setBytes(4, identity);
                insert.setBytes(5, payload);
                insert.setString(6, checksum(payload));
                insert.executeUpdate();
            }
            Lease lease = readLease(capture);
            if (!Arrays.equals(lease.manifest(), payload)) throw new IllegalStateException("Arena capture conflicts with existing manifest");
            return lease;
        });
    }

    public synchronized Lease requireLease(PlayerStateOperation context) {
        PlayerStateOperation capture = normalize(context);
        try { return readLease(capture); }
        catch (SQLException failure) { throw failure("Could not read arena lease", failure); }
    }

    /** Resolves a durable lease by session identity, validating its capture and manifest integrity. */
    public synchronized Lease requireLease(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        try { return readLeaseBySession(sessionId).orElseThrow(() -> new IllegalStateException("Arena capture lease does not exist")); }
        catch (SQLException failure) { throw failure("Could not read arena lease", failure); }
    }

    /** Returns every fully validated lease for one reviewed game and match, ordered by session UUID. */
    public synchronized List<Lease> leasesForMatch(UUID matchId, GameKey game) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(game, "game");
        if (!leaseGame(game)) throw new IllegalArgumentException("Match lease lookup requires a reviewed world game");
        return leasesMatching(lease -> lease.capture().matchId().equals(matchId) && lease.capture().game() == game);
    }

    /** Unfinished captures also retain facility ownership across a cold restart. */
    public synchronized List<Lease> unfinishedLeases(GameKey game) {
        Objects.requireNonNull(game, "game");
        if (!leaseGame(game)) throw new IllegalArgumentException("Facility lookup requires a reviewed world game");
        return leasesMatching(lease -> lease.capture().game() == game && lease.status() != Status.RESTORED);
    }

    private List<Lease> leasesMatching(java.util.function.Predicate<Lease> selection) {
        try {
            List<Lease> result = new ArrayList<>();
            try (var query = connection.createStatement();
                 ResultSet rows = query.executeQuery("SELECT session_id FROM arena_lease ORDER BY session_id")) {
                while (rows.next()) {
                    UUID sessionId = parseUuid(rows.getString(1));
                    Lease lease = readLeaseBySession(sessionId)
                            .orElseThrow(() -> new IllegalStateException("Arena capture lease disappeared"));
                    if (selection.test(lease)) {
                        if (result.size() >= MAX_ENTITIES) throw new IllegalStateException("Arena match lease limit exceeded");
                        result.add(lease);
                    }
                }
            }
            return List.copyOf(result);
        } catch (SQLException failure) { throw failure("Could not read arena match leases", failure); }
    }

    /** Resolves an entity by its globally unique UUID and validates that its exact lease still exists. */
    public synchronized Optional<EntityClaim> findEntity(UUID entityId) {
        Objects.requireNonNull(entityId, "entityId");
        try {
            EntityClaim row = readEntityClaim(entityId);
            if (row == null) return Optional.empty();
            Lease lease = requireLease(row.sessionId());
            if (!lease.capture().sessionId().equals(row.sessionId())) throw new IllegalStateException("Arena entity lease identity differs");
            return Optional.of(row);
        } catch (SQLException failure) { throw failure("Could not find arena entity claim", failure); }
    }

    public synchronized Lease arm(PlayerStateOperation context) {
        PlayerStateOperation capture = normalize(context);
        return transaction(() -> {
            Lease lease = readLease(capture);
            if (lease.status() == Status.ARMED) return lease;
            if (lease.status() != Status.CAPTURED) throw new IllegalStateException("Arena lease cannot be armed from " + lease.status());
            updateStatus(capture, Status.CAPTURED, Status.ARMED);
            return readLease(capture);
        });
    }

    public synchronized Lease beginPurge(PlayerStateOperation context) {
        PlayerStateOperation capture = normalize(context);
        return transaction(() -> {
            Lease lease = readLease(capture);
            if (lease.status() == Status.CAPTURED || lease.status() == Status.ARMED) {
                updateStatus(capture, lease.status(), Status.PURGING);
                return readLease(capture);
            }
            if (lease.status() == Status.PURGING || lease.status() == Status.PURGED || lease.status() == Status.RESTORED) return lease;
            throw new IllegalStateException("Unknown arena lease phase");
        });
    }

    /** Records completion only after callers have independently removed every owned entity. */
    public synchronized Lease completePurge(PlayerStateOperation context) {
        PlayerStateOperation capture = normalize(context);
        return transaction(() -> {
            Lease lease = readLease(capture);
            if (lease.status() == Status.RESTORED || lease.status() == Status.PURGED) return lease;
            if (lease.status() != Status.PURGING) throw new IllegalStateException("Arena lease is not purging");
            if (countNotRemoved(capture) != 0) throw new IllegalStateException("Arena entities remain unremoved");
            updateStatus(capture, Status.PURGING, Status.PURGED);
            return readLease(capture);
        });
    }

    public synchronized Lease markRestored(PlayerStateOperation context) {
        PlayerStateOperation capture = normalize(context);
        return transaction(() -> {
            Lease lease = readLease(capture);
            if (lease.status() == Status.RESTORED) return lease;
            if (lease.status() != Status.PURGED) throw new IllegalStateException("Arena lease cannot be restored from " + lease.status());
            updateStatus(capture, Status.PURGED, Status.RESTORED);
            return readLease(capture);
        });
    }

    /** Call only for a real, nonpersistent, tagged entity immediately before adding it to its world. */
    public synchronized Entity beginEntity(PlayerStateOperation context, UUID entityId, UUID worldId, EntityType type) {
        return beginEntity(context, entityId, worldId, type, null);
    }

    /** Records process proof atomically with the durable entity intent before native insertion. */
    public synchronized Entity beginEntity(PlayerStateOperation context, UUID entityId, UUID worldId,
            EntityType type, NativeProcessIdentity processIdentity) {
        PlayerStateOperation capture = normalize(context);
        Objects.requireNonNull(entityId, "entityId"); Objects.requireNonNull(worldId, "worldId"); Objects.requireNonNull(type, "type");
        requireCompatibleType(capture.game(), type);
        return transaction(() -> {
            Lease lease = readLease(capture);
            if (lease.status() != Status.ARMED) throw new IllegalStateException("Arena lease is not armed");
            EntityClaim previous = readEntityClaim(entityId);
            if (previous != null) {
                requireEntityOwner(capture, entityId);
                if (!previous.worldId().equals(worldId) || previous.type() != type)
                    throw new IllegalStateException("Arena entity identity conflicts with replay");
                if (!readProcessIdentity(entityId).equals(Optional.ofNullable(processIdentity)))
                    throw new IllegalStateException("Arena entity process proof conflicts with replay");
                return new Entity(previous.entityId(), previous.worldId(), previous.type(), previous.status());
            }
            if (countEntities(capture) >= MAX_ENTITIES) throw new IllegalStateException("Arena entity limit reached");
            try (var insert = connection.prepareStatement("""
                    INSERT INTO arena_entity(session_id, player_id, entity_id, world_id, entity_type, status)
                    VALUES (?, ?, ?, ?, ?, 'PENDING')
                    """)) {
                insert.setString(1, capture.sessionId().toString()); insert.setString(2, capture.playerId().toString());
                insert.setString(3, entityId.toString()); insert.setString(4, worldId.toString()); insert.setString(5, type.name());
                insert.executeUpdate();
            }
            if (processIdentity != null) {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO arena_entity_process(entity_id, process_pid, process_started_at)
                        VALUES (?, ?, ?)
                        """)) {
                    insert.setString(1, entityId.toString());
                    insert.setLong(2, processIdentity.pid());
                    insert.setLong(3, processIdentity.startedAtEpochMillis());
                    insert.executeUpdate();
                }
            }
            return new Entity(entityId, worldId, type, EntityStatus.PENDING);
        });
    }

    /** Returns durable nonpersistent-entity proof; missing entities are invalid, legacy rows have no proof. */
    public synchronized Optional<NativeProcessIdentity> nonPersistentProcess(UUID entityId) {
        Objects.requireNonNull(entityId, "entityId");
        try {
            if (readEntityClaim(entityId) == null) throw new IllegalStateException("Arena entity claim does not exist");
            return readProcessIdentity(entityId);
        } catch (SQLException failure) { throw failure("Could not read arena entity process proof", failure); }
    }

    private Optional<NativeProcessIdentity> readProcessIdentity(UUID entityId) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT process_pid, process_started_at FROM arena_entity_process WHERE entity_id = ?
                """)) {
            query.setString(1, entityId.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                long pid = row.getLong(1); boolean nullPid = row.wasNull();
                long startedAt = row.getLong(2); boolean nullStartedAt = row.wasNull();
                if (nullPid || nullStartedAt || row.next()) throw new IllegalStateException("Arena entity process proof is corrupt");
                try { return Optional.of(new NativeProcessIdentity(pid, startedAt)); }
                catch (IllegalArgumentException malformed) { throw new IllegalStateException("Arena entity process proof is corrupt", malformed); }
            }
        }
    }

    public synchronized Entity confirmEntity(PlayerStateOperation context, UUID entityId) {
        return updateEntity(context, entityId, EntityStatus.PENDING, EntityStatus.CONFIRMED, false);
    }

    public synchronized Entity markRemoved(PlayerStateOperation context, UUID entityId) {
        return updateEntity(context, entityId, null, EntityStatus.REMOVED, true);
    }

    public synchronized List<Entity> entities(PlayerStateOperation context) {
        PlayerStateOperation capture = normalize(context);
        try {
            readLease(capture);
            if (countEntities(capture) > MAX_ENTITIES) throw new IllegalStateException("Arena entity limit exceeded in ledger");
            List<Entity> result = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT entity_id, world_id, entity_type, status FROM arena_entity
                    WHERE session_id = ? AND player_id = ? ORDER BY entity_id
                    """)) {
                bindLease(query, capture);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        if (result.size() >= MAX_ENTITIES) throw new IllegalStateException("Arena entity limit exceeded in ledger");
                        EntityType type = EntityType.valueOf(rows.getString(3));
                        requireCompatibleType(capture.game(), type);
                        result.add(new Entity(parseUuid(rows.getString(1)), parseUuid(rows.getString(2)),
                                type, EntityStatus.valueOf(rows.getString(4))));
                    }
                }
            }
            result.sort(ENTITY_ORDER);
            return List.copyOf(result);
        } catch (SQLException | IllegalArgumentException malformed) {
            throw failure("Could not read arena entities", malformed);
        }
    }

    private Entity updateEntity(PlayerStateOperation context, UUID entityId, EntityStatus expected,
            EntityStatus next, boolean allowAnyExisting) {
        PlayerStateOperation capture = normalize(context); Objects.requireNonNull(entityId, "entityId");
        return transaction(() -> {
            readLease(capture); requireEntityOwner(capture, entityId);
            EntityClaim claim = readEntityClaim(entityId);
            if (claim == null) throw new IllegalStateException("Arena entity has no durable intent");
            Entity entity = new Entity(claim.entityId(), claim.worldId(), claim.type(), claim.status());
            if (entity.status() == next) return entity;
            if (!allowAnyExisting && entity.status() != expected)
                throw new IllegalStateException("Arena entity cannot transition from " + entity.status());
            try (var update = connection.prepareStatement("UPDATE arena_entity SET status = ? WHERE entity_id = ? AND status = ?")) {
                update.setString(1, next.name()); update.setString(2, entityId.toString()); update.setString(3, entity.status().name());
                if (update.executeUpdate() != 1) throw new IllegalStateException("Arena entity changed during transition");
            }
            return new Entity(entity.entityId(), entity.worldId(), entity.type(), next);
        });
    }

    private Lease readLease(PlayerStateOperation capture) throws SQLException {
        Lease lease = readLeaseBySession(capture.sessionId())
                .orElseThrow(() -> new IllegalStateException("Arena capture lease does not exist"));
        if (!capture.playerId().equals(lease.capture().playerId())
                || !Arrays.equals(identity(capture), identity(lease.capture())))
            throw new IllegalStateException("Arena capture identity conflicts with context");
        return lease;
    }

    private Optional<Lease> readLeaseBySession(UUID sessionId) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT player_id, capture_operation_id, length(capture_identity), length(manifest),
                    manifest_sha256, status, capture_identity, manifest FROM arena_lease WHERE session_id = ?
                """)) {
            query.setString(1, sessionId.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                UUID playerId = parseUuid(row.getString(1));
                UUID captureOperationId = parseUuid(row.getString(2));
                long identityLength = row.getLong(3); boolean nullIdentityLength = row.wasNull();
                long manifestLength = row.getLong(4); boolean nullManifestLength = row.wasNull();
                if (nullIdentityLength || nullManifestLength || identityLength <= 0 || identityLength > 256
                        || manifestLength <= 0 || manifestLength > MAX_MANIFEST)
                    throw new IllegalStateException("Arena lease payload lengths are corrupt");
                byte[] storedIdentity = row.getBytes(7), manifest = row.getBytes(8);
                PlayerStateOperation capture = decodeIdentity(storedIdentity);
                if (!sessionId.equals(capture.sessionId()) || !playerId.equals(capture.playerId())
                        || !captureOperationId.equals(capture.captureOperationId()))
                    throw new IllegalStateException("Arena capture identity differs from lease columns");
                if (manifest == null || manifest.length != manifestLength
                        || !checksum(manifest).equals(row.getString(5))) throw new IllegalStateException("Arena manifest is corrupt");
                Status status;
                try { status = Status.valueOf(row.getString(6)); }
                catch (RuntimeException malformed) { throw new IllegalStateException("Arena lease phase is corrupt", malformed); }
                return Optional.of(new Lease(capture, manifest, status));
            }
        }
    }

    private boolean hasActiveLeaseForPlayer(UUID playerId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM arena_lease WHERE player_id = ? AND status IN ('ARMED','PURGING','PURGED') LIMIT 1")) {
            query.setString(1, playerId.toString());
            try (ResultSet row = query.executeQuery()) { return row.next(); }
        }
    }

    private EntityClaim readEntityClaim(UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT session_id, player_id, entity_id, world_id, entity_type, status FROM arena_entity WHERE entity_id = ?")) {
            query.setString(1, id.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                try {
                    UUID sessionId = parseUuid(row.getString(1));
                    UUID playerId = parseUuid(row.getString(2));
                    Lease lease = readLeaseBySession(sessionId).orElseThrow(() -> new IllegalStateException("Arena entity lease is missing"));
                    if (!lease.capture().playerId().equals(playerId)) throw new IllegalStateException("Arena entity player differs from lease");
                    EntityType type = EntityType.valueOf(row.getString(5));
                    requireCompatibleType(lease.capture().game(), type);
                    return new EntityClaim(sessionId, parseUuid(row.getString(3)), parseUuid(row.getString(4)),
                            type, EntityStatus.valueOf(row.getString(6)));
                }
                catch (RuntimeException malformed) { throw new IllegalStateException("Arena entity row is corrupt", malformed); }
            }
        }
    }

    private void requireEntityOwner(PlayerStateOperation capture, UUID entityId) throws SQLException {
        EntityClaim row = readEntityClaim(entityId);
        if (row == null || !capture.sessionId().equals(row.sessionId()))
            throw new IllegalStateException("Arena entity belongs to another capture");
    }

    private long countEntities(PlayerStateOperation capture) throws SQLException {
        try (var query = connection.prepareStatement("SELECT count(*) FROM arena_entity WHERE session_id = ? AND player_id = ?")) {
            bindLease(query, capture);
            try (ResultSet row = query.executeQuery()) { return row.getLong(1); }
        }
    }

    private long countNotRemoved(PlayerStateOperation capture) throws SQLException {
        try (var query = connection.prepareStatement("SELECT count(*) FROM arena_entity WHERE session_id = ? AND player_id = ? AND status <> 'REMOVED'")) {
            bindLease(query, capture);
            try (ResultSet row = query.executeQuery()) { return row.getLong(1); }
        }
    }

    private void updateStatus(PlayerStateOperation capture, Status expected, Status next) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE arena_lease SET status = ? WHERE session_id = ? AND player_id = ? AND status = ?")) {
            update.setString(1, next.name()); update.setString(2, capture.sessionId().toString());
            update.setString(3, capture.playerId().toString()); update.setString(4, expected.name());
            if (update.executeUpdate() != 1) throw new IllegalStateException("Arena lease changed during transition");
        }
    }

    private static void bindLease(java.sql.PreparedStatement query, PlayerStateOperation capture) throws SQLException {
        query.setString(1, capture.sessionId().toString()); query.setString(2, capture.playerId().toString());
    }

    private interface SqlWork<T> { T run() throws SQLException; }
    private <T> T transaction(SqlWork<T> work) {
        try {
            connection.setAutoCommit(false);
            try { T result = work.run(); connection.commit(); return result; }
            catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException failure) {
            if (failure.getErrorCode() == 19)
                throw new IllegalStateException("Arena ledger identity or phase constraint conflicts", failure);
            throw failure("Arena ledger transaction failed", failure);
        }
    }

    private static PlayerStateOperation normalize(PlayerStateOperation context) {
        Objects.requireNonNull(context, "context");
        if (!leaseGame(context.game()) || context.capturedConnectionId() == null)
            throw new IllegalArgumentException("World lease requires a reviewed game capture epoch");
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, context.captureOperationId(),
                context.captureOperationId(), context.snapshotId(), context.sessionId(), context.matchId(),
                context.playerId(), context.capturedConnectionId(), context.capturedConnectionId(),
                context.game(), context.capturedAt());
    }

    private static byte[] identity(PlayerStateOperation capture) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(IDENTITY_SCHEMA); out.writeUTF(capture.kind().name());
                for (UUID id : new UUID[] {capture.operationId(), capture.captureOperationId(), capture.snapshotId(),
                        capture.sessionId(), capture.matchId(), capture.playerId(), capture.capturedConnectionId()}) {
                    out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
                }
                out.writeUTF(capture.game().id()); out.writeLong(capture.capturedAt().getEpochSecond()); out.writeInt(capture.capturedAt().getNano());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException("Could not encode arena capture identity", impossible); }
    }

    private static PlayerStateOperation decodeIdentity(byte[] encoded) {
        if (encoded == null || encoded.length == 0 || encoded.length > 256)
            throw new IllegalStateException("Arena capture identity is corrupt");
        try (var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(encoded))) {
            if (in.readInt() != IDENTITY_SCHEMA || !PlayerStateOperation.Kind.CAPTURE.name().equals(in.readUTF()))
                throw new IllegalStateException("Arena capture identity header is corrupt");
            UUID operationId = readUuid(in), captureOperationId = readUuid(in), snapshotId = readUuid(in);
            UUID sessionId = readUuid(in), matchId = readUuid(in), playerId = readUuid(in), capturedEpoch = readUuid(in);
            GameKey game = GameKey.fromId(in.readUTF())
                    .orElseThrow(() -> new IllegalStateException("Arena capture game is corrupt"));
            Instant capturedAt = Instant.ofEpochSecond(in.readLong(), in.readInt());
            if (in.available() != 0 || !operationId.equals(captureOperationId) || !leaseGame(game))
                throw new IllegalStateException("Arena capture identity is invalid");
            PlayerStateOperation capture = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE,
                    operationId, captureOperationId, snapshotId, sessionId, matchId, playerId, capturedEpoch,
                    capturedEpoch, game, capturedAt);
            if (!Arrays.equals(encoded, identity(capture))) throw new IllegalStateException("Arena capture identity is not canonical");
            return capture;
        } catch (IOException | RuntimeException malformed) {
            if (malformed instanceof IllegalStateException invalid) throw invalid;
            throw new IllegalStateException("Arena capture identity is corrupt", malformed);
        }
    }

    private static UUID readUuid(java.io.DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static byte[] boundedManifest(byte[] manifest) {
        Objects.requireNonNull(manifest, "manifest");
        if (manifest.length == 0 || manifest.length > MAX_MANIFEST) throw new IllegalArgumentException("Arena manifest size is invalid");
        return manifest.clone();
    }

    private static boolean leaseGame(GameKey game) {
        return game == GameKey.ARENA || game == GameKey.ARCHERY_RANGE
                || game == GameKey.ANVIL_DODGE || game == GameKey.ELYTRA_RINGS || game == GameKey.BUILD_BATTLE;
    }

    private static void requireCompatibleType(GameKey game, EntityType type) {
        boolean compatible = switch (game) {
            case ARENA, ARCHERY_RANGE -> type == EntityType.ARROW || type == EntityType.SPECTRAL_ARROW;
            case ANVIL_DODGE -> type == EntityType.ANVIL_MARKER;
            case ELYTRA_RINGS -> type == EntityType.ELYTRA_FIREWORK;
            default -> false;
        };
        if (!compatible) throw new IllegalStateException("Arena entity type is incompatible with capture game");
    }

    private static String checksum(byte[] payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static UUID parseUuid(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Noncanonical arena UUID");
        return id;
    }

    private static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            int version;
            try (ResultSet row = statement.executeQuery("PRAGMA user_version")) { version = row.getInt(1); }
            if (version != 0 && version != 1 && version != 2 && version != SCHEMA) {
                throw new SQLException("Unsupported arena ledger schema");
            }
            if (version == 0) {
                try (ResultSet row = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
                    if (row.getInt(1) != 0) throw new SQLException("Unversioned arena ledger is not empty");
                }
                connection.setAutoCommit(false);
                try {
                    statement.executeUpdate(CREATE_LEASE_TABLE);
                    statement.executeUpdate(CREATE_ENTITY_TABLE);
                    statement.executeUpdate(CREATE_ENTITY_LEASE_INDEX);
                    statement.executeUpdate(CREATE_ACTIVE_PLAYER_INDEX);
                    statement.executeUpdate(CREATE_ENTITY_PROCESS_TABLE);
                    statement.executeUpdate("PRAGMA user_version = 3"); connection.commit();
                } catch (SQLException failure) { connection.rollback(); throw failure; }
                finally { connection.setAutoCommit(true); }
                version = SCHEMA;
            }
            if (version == 1 || version == 2) {
                // Validate all old evidence before the first schema mutation. Do not repair or rewrite it.
                validateSchema(statement, version);
                validateDataIntegrity(statement);
                validateEntityCompatibility(connection);
                connection.setAutoCommit(false);
                try {
                    migrateEntitySchemaToV3(statement, version == 2);
                    statement.executeUpdate("PRAGMA user_version = 3");
                    connection.commit();
                } catch (SQLException failure) { connection.rollback(); throw failure; }
                finally { connection.setAutoCommit(true); }
            }
            validateSchema(statement, SCHEMA);
            validateDataIntegrity(statement);
            validateEntityCompatibility(connection);
            statement.execute("PRAGMA journal_mode = WAL"); statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    private static void validateSchema(Statement statement, int version) throws SQLException {
        boolean includeProcessTable = version >= 2;
        try (ResultSet rows = statement.executeQuery("SELECT session_id, player_id, capture_operation_id, capture_identity, manifest, manifest_sha256, status FROM arena_lease LIMIT 0")) {}
        try (ResultSet rows = statement.executeQuery("SELECT entity_id, session_id, player_id, world_id, entity_type, status FROM arena_entity LIMIT 0")) {}
        if (includeProcessTable) {
            try (ResultSet rows = statement.executeQuery("SELECT entity_id, process_pid, process_started_at FROM arena_entity_process LIMIT 0")) {}
        }
        try (ResultSet rows = statement.executeQuery("SELECT player_id FROM arena_lease WHERE status IN ('ARMED','PURGING','PURGED') GROUP BY player_id HAVING count(*) > 1")) {
            if (rows.next()) throw new SQLException("Arena player has multiple active leases");
        }
        try (ResultSet rows = statement.executeQuery("SELECT session_id FROM arena_lease GROUP BY session_id HAVING count(*) > 1")) {
            if (rows.next()) throw new SQLException("Arena session has multiple leases");
        }
        requireSchemaSql(statement, "table", "arena_lease", CREATE_LEASE_TABLE);
        requireSchemaSql(statement, "table", "arena_entity",
                version >= 3 ? CREATE_ENTITY_TABLE : CREATE_ENTITY_TABLE_V1);
        requireSchemaSql(statement, "index", "arena_entity_lease", CREATE_ENTITY_LEASE_INDEX);
        requireSchemaSql(statement, "index", "arena_active_player", CREATE_ACTIVE_PLAYER_INDEX);
        if (includeProcessTable) requireSchemaSql(statement, "table", "arena_entity_process", CREATE_ENTITY_PROCESS_TABLE);
        requireExactSchemaObjects(statement, includeProcessTable);
    }

    /** Rebuilds the constrained entity table transactionally while preserving every old row verbatim. */
    private static void migrateEntitySchemaToV3(Statement statement, boolean includeProcessTable) throws SQLException {
        statement.executeUpdate("""
                CREATE TEMP TABLE arena_entity_migration AS
                SELECT entity_id, session_id, player_id, world_id, entity_type, status FROM main.arena_entity WHERE 0
                """);
        statement.executeUpdate("""
                INSERT INTO temp.arena_entity_migration(entity_id, session_id, player_id, world_id, entity_type, status)
                SELECT entity_id, session_id, player_id, world_id, entity_type, status FROM main.arena_entity
                """);
        if (includeProcessTable) {
            statement.executeUpdate("""
                    CREATE TEMP TABLE arena_entity_process_migration AS
                    SELECT entity_id, process_pid, process_started_at FROM main.arena_entity_process WHERE 0
                    """);
            statement.executeUpdate("""
                    INSERT INTO temp.arena_entity_process_migration(entity_id, process_pid, process_started_at)
                    SELECT entity_id, process_pid, process_started_at FROM main.arena_entity_process
                    """);
            statement.executeUpdate("DROP TABLE main.arena_entity_process");
        }
        statement.executeUpdate("DROP TABLE main.arena_entity");
        statement.executeUpdate(CREATE_ENTITY_TABLE);
        statement.executeUpdate("""
                INSERT INTO main.arena_entity(entity_id, session_id, player_id, world_id, entity_type, status)
                SELECT entity_id, session_id, player_id, world_id, entity_type, status FROM temp.arena_entity_migration
                """);
        statement.executeUpdate(CREATE_ENTITY_LEASE_INDEX);
        statement.executeUpdate(CREATE_ENTITY_PROCESS_TABLE);
        if (includeProcessTable) {
            statement.executeUpdate("""
                    INSERT INTO main.arena_entity_process(entity_id, process_pid, process_started_at)
                    SELECT entity_id, process_pid, process_started_at FROM temp.arena_entity_process_migration
                    """);
            statement.executeUpdate("DROP TABLE temp.arena_entity_process_migration");
        }
        statement.executeUpdate("DROP TABLE temp.arena_entity_migration");
    }

    private static void validateEntityCompatibility(Connection connection) throws SQLException {
        try (var query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT session_id, entity_type FROM arena_entity ORDER BY entity_id")) {
            while (rows.next()) {
                UUID sessionId = parseUuid(rows.getString(1));
                EntityType type;
                try { type = EntityType.valueOf(rows.getString(2)); }
                catch (RuntimeException malformed) { throw new SQLException("Arena entity type is corrupt", malformed); }
                Lease lease = readLeaseForValidation(connection, sessionId);
                try { requireCompatibleType(lease.capture().game(), type); }
                catch (RuntimeException incompatible) {
                    throw new SQLException("Arena entity type is incompatible with capture game", incompatible);
                }
            }
        }
    }

    private static Lease readLeaseForValidation(Connection connection, UUID sessionId) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT player_id, capture_operation_id, length(capture_identity), length(manifest),
                    manifest_sha256, status, capture_identity, manifest FROM arena_lease WHERE session_id = ?
                """)) {
            query.setString(1, sessionId.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("Arena entity lease is missing");
                UUID playerId = parseUuid(row.getString(1));
                UUID captureOperationId = parseUuid(row.getString(2));
                byte[] encoded = row.getBytes(7);
                byte[] manifest = row.getBytes(8);
                PlayerStateOperation capture;
                try { capture = decodeIdentity(encoded); }
                catch (RuntimeException malformed) { throw new SQLException("Arena capture identity is corrupt", malformed); }
                long identityLength = row.getLong(3);
                boolean nullIdentityLength = row.wasNull();
                long manifestLength = row.getLong(4);
                boolean nullManifestLength = row.wasNull();
                if (!sessionId.equals(capture.sessionId()) || !playerId.equals(capture.playerId())
                        || !captureOperationId.equals(capture.captureOperationId())
                        || nullIdentityLength || nullManifestLength || identityLength <= 0 || identityLength > 256
                        || manifest == null || manifestLength != manifest.length || manifestLength <= 0
                        || manifestLength > MAX_MANIFEST || !checksum(manifest).equals(row.getString(5))) {
                    throw new SQLException("Arena entity lease is corrupt");
                }
                Status status;
                try { status = Status.valueOf(row.getString(6)); }
                catch (RuntimeException malformed) { throw new SQLException("Arena lease status is corrupt", malformed); }
                return new Lease(capture, manifest, status);
            }
        }
    }

    private static void validateDataIntegrity(Statement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery("PRAGMA quick_check")) {
            if (!rows.next() || !"ok".equals(rows.getString(1))) throw new SQLException("Arena ledger is corrupt");
        }
        try (ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (rows.next()) throw new SQLException("Arena ledger contains a foreign-key violation");
        }
    }

    private static void requireExactSchemaObjects(Statement statement, boolean includeProcessTable) throws SQLException {
        java.util.Set<String> expected = includeProcessTable
                ? java.util.Set.of("table:arena_lease", "table:arena_entity", "table:arena_entity_process",
                        "index:arena_entity_lease", "index:arena_active_player")
                : java.util.Set.of("table:arena_lease", "table:arena_entity",
                        "index:arena_entity_lease", "index:arena_active_player");
        java.util.Set<String> actual = new java.util.HashSet<>();
        try (ResultSet rows = statement.executeQuery("SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")) {
            while (rows.next()) actual.add(rows.getString(1) + ":" + rows.getString(2));
        }
        if (!actual.equals(expected)) throw new SQLException("Arena ledger schema object set differs");
    }

    private static void rejectSymlinkAncestors(Path root) throws IOException {
        Path current = root.getRoot();
        for (Path component : root) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Arena ledger path contains a symlink");
        }
    }
    private static void requireSchemaSql(Statement statement, String type, String name, String expected)
            throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT sql FROM sqlite_master WHERE type='"
                + type + "' AND name='" + name + "'")) {
            if (!rows.next() || !canonicalSql(expected).equals(canonicalSql(rows.getString(1))) || rows.next())
                throw new SQLException("Arena ledger schema object differs: " + name);
        }
    }
    private static String canonicalSql(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
    private static void restrict(Path path, String permissions) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }
    private static PersistenceFailure failure(String message, Throwable cause) { return new PersistenceFailure(message, cause); }

    @Override public synchronized void close() {
        try { connection.close(); }
        catch (SQLException failure) { throw failure("Could not close arena world ledger", failure); }
    }
}
