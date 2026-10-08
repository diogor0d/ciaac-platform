package com.ciaac.minecraft.minigames.persistence;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable, game-specific lease for the single configured Color Floor facility. */
public final class ColorFloorWorldLedger implements AutoCloseable {
    public enum Status { CAPTURED, ARMED, PURGING, PURGED, RESTORED }
    private static final int SCHEMA = 1;
    private static final int MAX_MANIFEST = 16 * 1024 * 1024;
    private static final String CREATE_TABLE = """
            CREATE TABLE color_floor_lease (
                session_id TEXT NOT NULL PRIMARY KEY,
                player_id TEXT NOT NULL,
                match_id TEXT NOT NULL,
                capture_operation_id TEXT NOT NULL UNIQUE,
                capture_identity BLOB NOT NULL,
                manifest BLOB NOT NULL,
                manifest_sha256 TEXT NOT NULL,
                status TEXT NOT NULL CHECK (status IN ('CAPTURED','ARMED','PURGING','PURGED','RESTORED'))
            )
            """;
    private final Connection connection;

    public record Lease(PlayerStateOperation capture, byte[] manifest, Status status) {
        public Lease {
            Objects.requireNonNull(capture, "capture");
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(status, "status");
            manifest = manifest.clone();
        }
        @Override public byte[] manifest() { return manifest.clone(); }
    }

    public ColorFloorWorldLedger(Path directory) {
        Objects.requireNonNull(directory, "directory");
        Connection opened = null;
        try {
            Path root = directory.toAbsolutePath().normalize();
            rejectSymlinkAncestors(root);
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Color Floor ledger directory is not a real directory");
            } else Files.createDirectory(root);
            if (!root.toRealPath().equals(root)) throw new IOException("Color Floor ledger path contains a symlink");
            restrict(root, "rwx------");
            Path file = root.resolve("color-floor-world.sqlite");
            for (String suffix : new String[] {"", "-wal", "-shm", "-journal"}) {
                Path candidate = root.resolve("color-floor-world.sqlite" + suffix);
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                        && (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("Color Floor ledger file or sidecar is not a regular file");
            }
            Class.forName("org.sqlite.JDBC");
            opened = DriverManager.getConnection("jdbc:sqlite:" + file);
            restrict(file, "rw-------");
            initialize(opened);
            connection = opened;
        } catch (IOException | SQLException | ClassNotFoundException | RuntimeException | LinkageError failure) {
            if (opened != null) try { opened.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw new PersistenceFailure("Could not initialize the Color Floor world ledger", failure);
        }
    }

    /** Captures the single facility manifest; every lease for one match must share its exact bytes. */
    public synchronized Lease capture(PlayerStateOperation context, byte[] manifest) {
        requireKind(context, PlayerStateOperation.Kind.CAPTURE);
        PlayerStateOperation capture = normalize(context);
        byte[] payload = boundedManifest(manifest);
        byte[] frozenIdentity = identity(capture);
        return transaction(() -> {
            Optional<Lease> existing = readBySession(capture.sessionId());
            if (existing.isPresent()) {
                Lease lease = existing.get();
                if (!Arrays.equals(frozenIdentity, identity(lease.capture())) || !Arrays.equals(payload, lease.manifest()))
                    throw new IllegalStateException("Color Floor capture conflicts with existing session lease");
                return lease;
            }
            List<Lease> leases = readUnfinished();
            for (Lease lease : leases) {
                if (!lease.capture().matchId().equals(capture.matchId()))
                    throw new IllegalStateException("Another Color Floor match still owns the facility lease");
                if (!Arrays.equals(lease.manifest(), payload))
                    throw new IllegalStateException("Color Floor match manifest conflicts with its frozen facility");
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO color_floor_lease(session_id, player_id, match_id, capture_operation_id,
                        capture_identity, manifest, manifest_sha256, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'CAPTURED')
                    """)) {
                insert.setString(1, capture.sessionId().toString());
                insert.setString(2, capture.playerId().toString());
                insert.setString(3, capture.matchId().toString());
                insert.setString(4, capture.captureOperationId().toString());
                insert.setBytes(5, frozenIdentity);
                insert.setBytes(6, payload);
                insert.setString(7, checksum(payload));
                insert.executeUpdate();
            }
            return readLease(capture);
        });
    }

    public synchronized Lease requireLease(PlayerStateOperation context) {
        PlayerStateOperation capture = normalize(context);
        try { return readLease(capture); }
        catch (SQLException failure) { throw failure("Could not read Color Floor lease", failure); }
    }

    public synchronized Lease arm(PlayerStateOperation context) {
        requireKind(context, PlayerStateOperation.Kind.ENTER);
        PlayerStateOperation capture = normalize(context);
        return transition(capture, Status.CAPTURED, Status.ARMED, Status.ARMED);
    }

    public synchronized Lease beginPurge(PlayerStateOperation context) {
        requireKind(context, PlayerStateOperation.Kind.PURGE);
        PlayerStateOperation capture = normalize(context);
        return transaction(() -> {
            Lease lease = readLease(capture);
            if (lease.status() == Status.CAPTURED || lease.status() == Status.ARMED) {
                updateStatus(capture, lease.status(), Status.PURGING);
                return readLease(capture);
            }
            if (lease.status() == Status.PURGING || lease.status() == Status.PURGED || lease.status() == Status.RESTORED) return lease;
            throw new IllegalStateException("Unknown Color Floor lease phase");
        });
    }

    /** Records facility cleanup for this player lease; callers serialize shared world cleanup. */
    public synchronized Lease markPurged(PlayerStateOperation context) {
        requireKind(context, PlayerStateOperation.Kind.PURGE);
        PlayerStateOperation capture = normalize(context);
        return transaction(() -> {
            Lease lease = readLease(capture);
            if (lease.status() == Status.PURGED || lease.status() == Status.RESTORED) return lease;
            if (lease.status() != Status.CAPTURED && lease.status() != Status.ARMED && lease.status() != Status.PURGING)
                throw new IllegalStateException("Color Floor lease cannot be purged from " + lease.status());
            updateStatus(capture, lease.status(), Status.PURGED);
            return readLease(capture);
        });
    }

    public synchronized Lease markRestored(PlayerStateOperation context) {
        requireKind(context, PlayerStateOperation.Kind.RESTORE);
        PlayerStateOperation capture = normalize(context);
        return transaction(() -> {
            Lease lease = readLease(capture);
            if (lease.status() == Status.RESTORED) return lease;
            if (lease.status() != Status.PURGED) throw new IllegalStateException("Color Floor lease cannot be restored from " + lease.status());
            updateStatus(capture, Status.PURGED, Status.RESTORED);
            return readLease(capture);
        });
    }

    private Lease transition(PlayerStateOperation capture, Status expected, Status next, Status replay) {
        return transaction(() -> {
            Lease lease = readLease(capture);
            if (lease.status() == replay) return lease;
            if (lease.status() != expected) throw new IllegalStateException("Color Floor lease cannot transition from " + lease.status());
            updateStatus(capture, expected, next);
            return readLease(capture);
        });
    }

    private Lease readLease(PlayerStateOperation capture) throws SQLException {
        Lease lease = readBySession(capture.sessionId()).orElseThrow(() -> new IllegalStateException("Color Floor capture lease does not exist"));
        if (!Arrays.equals(identity(capture), identity(lease.capture())))
            throw new IllegalStateException("Color Floor lease identity differs from operation context");
        return lease;
    }

    private Optional<Lease> readBySession(UUID sessionId) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT player_id, match_id, capture_operation_id, capture_identity,
                    length(capture_identity), length(manifest), manifest_sha256, manifest, status
                FROM color_floor_lease WHERE session_id = ?
                """)) {
            query.setString(1, sessionId.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                UUID playerId = parseUuid(row.getString(1));
                UUID matchId = parseUuid(row.getString(2));
                UUID operationId = parseUuid(row.getString(3));
                long identityLength = row.getLong(5); boolean nullIdentityLength = row.wasNull();
                long manifestLength = row.getLong(6); boolean nullManifestLength = row.wasNull();
                if (nullIdentityLength || nullManifestLength || identityLength <= 0 || identityLength > 256
                        || manifestLength <= 0 || manifestLength > MAX_MANIFEST)
                    throw new IllegalStateException("Color Floor lease payload lengths are corrupt");
                byte[] storedIdentity = row.getBytes(4);
                if (storedIdentity == null || storedIdentity.length != identityLength)
                    throw new IllegalStateException("Color Floor capture identity length is corrupt");
                PlayerStateOperation capture = decodeIdentity(storedIdentity);
                if (!capture.sessionId().equals(sessionId) || !capture.playerId().equals(playerId)
                        || !capture.matchId().equals(matchId) || !capture.captureOperationId().equals(operationId))
                    throw new IllegalStateException("Color Floor capture identity differs from lease columns");
                byte[] manifest = row.getBytes(8);
                if (manifest == null || manifest.length != manifestLength || !checksum(manifest).equals(row.getString(7)))
                    throw new IllegalStateException("Color Floor manifest is corrupt");
                Status status;
                try { status = Status.valueOf(row.getString(9)); }
                catch (RuntimeException malformed) { throw new IllegalStateException("Color Floor lease phase is corrupt", malformed); }
                if (row.next()) throw new IllegalStateException("Duplicate Color Floor session lease");
                return Optional.of(new Lease(capture, manifest, status));
            }
        }
    }

    private List<Lease> readUnfinished() throws SQLException {
        List<Lease> leases = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT session_id FROM color_floor_lease WHERE status <> 'RESTORED' ORDER BY session_id");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) leases.add(readBySession(parseUuid(rows.getString(1)))
                    .orElseThrow(() -> new IllegalStateException("Color Floor lease disappeared")));
        }
        return leases;
    }

    private void updateStatus(PlayerStateOperation capture, Status expected, Status next) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE color_floor_lease SET status = ? WHERE session_id = ? AND player_id = ? AND status = ?")) {
            update.setString(1, next.name()); update.setString(2, capture.sessionId().toString());
            update.setString(3, capture.playerId().toString()); update.setString(4, expected.name());
            if (update.executeUpdate() != 1) throw new IllegalStateException("Color Floor lease changed during transition");
        }
    }

    private interface SqlWork<T> { T run() throws SQLException; }
    private <T> T transaction(SqlWork<T> work) {
        try {
            connection.setAutoCommit(false);
            try { T result = work.run(); connection.commit(); return result; }
            catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException failure) {
            if (failure.getErrorCode() == 19) throw new IllegalStateException("Color Floor lease identity or phase conflicts", failure);
            throw failure("Color Floor ledger transaction failed", failure);
        }
    }

    private static PlayerStateOperation normalize(PlayerStateOperation context) {
        Objects.requireNonNull(context, "context");
        if (context.game() != GameKey.COLOR_FLOOR || context.capturedConnectionId() == null)
            throw new IllegalArgumentException("Color Floor lease requires its captured game and connection epoch");
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, context.captureOperationId(),
                context.captureOperationId(), context.snapshotId(), context.sessionId(), context.matchId(),
                context.playerId(), context.capturedConnectionId(), context.capturedConnectionId(),
                context.game(), context.capturedAt());
    }

    private static void requireKind(PlayerStateOperation context, PlayerStateOperation.Kind expected) {
        Objects.requireNonNull(context, "context");
        if (context.kind() != expected) throw new IllegalArgumentException("Color Floor operation requires " + expected);
    }

    private static byte[] identity(PlayerStateOperation capture) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(SCHEMA); out.writeUTF(PlayerStateOperation.Kind.CAPTURE.name());
                for (UUID id : new UUID[] {capture.operationId(), capture.captureOperationId(), capture.snapshotId(),
                        capture.sessionId(), capture.matchId(), capture.playerId(), capture.capturedConnectionId()}) {
                    out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
                }
                out.writeUTF(GameKey.COLOR_FLOOR.id()); out.writeLong(capture.capturedAt().getEpochSecond());
                out.writeInt(capture.capturedAt().getNano());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException("Could not encode Color Floor capture identity", impossible); }
    }

    private static PlayerStateOperation decodeIdentity(byte[] encoded) {
        if (encoded == null || encoded.length == 0 || encoded.length > 256) throw new IllegalStateException("Color Floor capture identity is corrupt");
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(encoded))) {
            if (in.readInt() != SCHEMA || !PlayerStateOperation.Kind.CAPTURE.name().equals(in.readUTF()))
                throw new IllegalStateException("Color Floor capture identity header is corrupt");
            UUID operationId = readUuid(in), captureOperationId = readUuid(in), snapshotId = readUuid(in);
            UUID sessionId = readUuid(in), matchId = readUuid(in), playerId = readUuid(in), epoch = readUuid(in);
            if (!GameKey.COLOR_FLOOR.id().equals(in.readUTF())) throw new IllegalStateException("Color Floor capture game is corrupt");
            Instant at = Instant.ofEpochSecond(in.readLong(), in.readInt());
            if (in.available() != 0 || !operationId.equals(captureOperationId)) throw new IllegalStateException("Color Floor capture identity is invalid");
            PlayerStateOperation capture = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE,
                    operationId, captureOperationId, snapshotId, sessionId, matchId, playerId, epoch, epoch,
                    GameKey.COLOR_FLOOR, at);
            if (!Arrays.equals(encoded, identity(capture))) throw new IllegalStateException("Color Floor capture identity is not canonical");
            return capture;
        } catch (IOException | RuntimeException malformed) {
            if (malformed instanceof IllegalStateException invalid) throw invalid;
            throw new IllegalStateException("Color Floor capture identity is corrupt", malformed);
        }
    }

    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static byte[] boundedManifest(byte[] manifest) {
        Objects.requireNonNull(manifest, "manifest");
        if (manifest.length == 0 || manifest.length > MAX_MANIFEST) throw new IllegalArgumentException("Color Floor manifest size is invalid");
        return manifest.clone();
    }
    private static String checksum(byte[] payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static UUID parseUuid(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Noncanonical Color Floor UUID");
        return id;
    }

    private static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            int version;
            try (ResultSet row = statement.executeQuery("PRAGMA user_version")) {
                if (!row.next()) throw new SQLException("Color Floor ledger schema version is missing");
                version = row.getInt(1);
                if (row.next()) throw new SQLException("Color Floor ledger schema version is ambiguous");
            }
            if (version != 0 && version != SCHEMA) throw new SQLException("Unsupported Color Floor ledger schema");
            if (version == 0) {
                try (ResultSet row = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
                    if (row.getInt(1) != 0) throw new SQLException("Unversioned Color Floor ledger is not empty");
                }
                connection.setAutoCommit(false);
                try { statement.executeUpdate(CREATE_TABLE); statement.executeUpdate("PRAGMA user_version = 1"); connection.commit(); }
                catch (SQLException failure) { connection.rollback(); throw failure; }
                finally { connection.setAutoCommit(true); }
            }
            try (ResultSet rows = statement.executeQuery("SELECT session_id, player_id, match_id, capture_operation_id, capture_identity, manifest, manifest_sha256, status FROM color_floor_lease LIMIT 0")) {}
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
                if (!rows.next() || rows.getInt(1) != 1) throw new SQLException("Color Floor ledger has unexpected schema objects");
            }
            requireSchemaSql(statement, CREATE_TABLE);
            try (ResultSet rows = statement.executeQuery("PRAGMA quick_check")) {
                if (!rows.next() || !"ok".equals(rows.getString(1))) throw new SQLException("Color Floor ledger is corrupt");
            }
            statement.execute("PRAGMA journal_mode = WAL"); statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    private static void rejectSymlinkAncestors(Path root) throws IOException {
        Path current = root.getRoot();
        for (Path component : root) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Color Floor ledger path contains a symlink");
        }
    }
    private static void requireSchemaSql(Statement statement, String expected) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='color_floor_lease'")) {
            if (!rows.next() || !canonicalSql(expected).equals(canonicalSql(rows.getString(1))) || rows.next())
                throw new SQLException("Color Floor ledger schema differs");
        }
    }
    private static String canonicalSql(String sql) { return sql == null ? "" : sql.replaceAll("\\s+", "").toUpperCase(Locale.ROOT); }
    private static void restrict(Path path, String permissions) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }
    private static PersistenceFailure failure(String message, Throwable cause) { return new PersistenceFailure(message, cause); }

    @Override public synchronized void close() {
        try { connection.close(); }
        catch (SQLException failure) { throw failure("Could not close Color Floor world ledger", failure); }
    }
}
