package com.ciaac.minecraft.minigames.persistence;

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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;

/** Durable evidence for external operations; PENDING never means a mutation is safe to repeat. */
public final class ExternalOperationJournal implements AutoCloseable {
    public enum State { NEW, PENDING, COMMITTED }
    private static final int MAX_PAYLOAD = 8 * 1024 * 1024;
    private final Connection connection;

    public ExternalOperationJournal(Path directory) {
        Objects.requireNonNull(directory, "directory");
        Connection opened = null;
        try {
            Path root = directory.toAbsolutePath().normalize();
            // A private child directory is required: do not chmod the plugin's shared data folder.
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("External journal directory is not a real directory");
                }
            } else {
                Files.createDirectory(root);
            }
            if (!root.toRealPath().equals(root)) throw new IOException("External journal path contains a symlink");
            restrict(root, "rwx------");
            Path file = root.resolve("operations.sqlite");
            for (String suffix : new String[] {"", "-wal", "-shm", "-journal"}) {
                Path candidate = root.resolve("operations.sqlite" + suffix);
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                        && (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))) {
                    throw new IOException("External journal file is not a regular file");
                }
            }
            Class.forName("org.sqlite.JDBC");
            opened = DriverManager.getConnection("jdbc:sqlite:" + file);
            restrict(file, "rw-------");
            initialize(opened);
            connection = opened;
        } catch (IOException | SQLException | ClassNotFoundException | RuntimeException | LinkageError failure) {
            if (opened != null) try { opened.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw new PersistenceFailure("Could not initialize the external operation journal", failure);
        }
    }

    private static void restrict(Path path, String permissions) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        }
    }

    private static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int version;
            try (ResultSet rows = statement.executeQuery("PRAGMA user_version")) { version = rows.getInt(1); }
            if (version != 0 && version != 1) throw new SQLException("Unsupported external journal schema");
            if (version == 0) {
                try (ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
                    if (rows.getInt(1) != 0) throw new SQLException("Unversioned external journal is not empty");
                }
                connection.setAutoCommit(false);
                try {
                    statement.executeUpdate("""
                            CREATE TABLE external_operation (
                                provider_id TEXT NOT NULL,
                                operation_id TEXT NOT NULL,
                                request_digest TEXT NOT NULL,
                                state TEXT NOT NULL CHECK (state IN ('PENDING', 'COMMITTED')),
                                result BLOB,
                                result_sha256 TEXT,
                                committed_at TEXT,
                                PRIMARY KEY (provider_id, operation_id),
                                CHECK ((state = 'PENDING' AND result IS NULL AND result_sha256 IS NULL AND committed_at IS NULL)
                                    OR (state = 'COMMITTED' AND result IS NOT NULL AND result_sha256 IS NOT NULL AND committed_at IS NOT NULL))
                            )
                            """);
                    statement.executeUpdate("PRAGMA user_version = 1");
                    connection.commit();
                } catch (SQLException failure) {
                    connection.rollback();
                    throw failure;
                } finally { connection.setAutoCommit(true); }
            }
            // Reject incomplete/corrupt stores before exposing a usable journal.
            try (ResultSet rows = statement.executeQuery(
                    "SELECT provider_id, operation_id, request_digest, state, result, result_sha256, committed_at FROM external_operation LIMIT 0")) {}
            try (ResultSet rows = statement.executeQuery("PRAGMA quick_check")) {
                if (!rows.next() || !"ok".equals(rows.getString(1))) throw new SQLException("External journal is corrupt");
            }
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    /** Inserts durable intent before an external mutation. Existing unfinished intent returns PENDING. */
    public synchronized State begin(String provider, int version, PlayerStateOperation operation, byte[] request) {
        String digest = digest(provider, version, operation, request);
        try (var insert = connection.prepareStatement("""
                INSERT INTO external_operation(provider_id, operation_id, request_digest, state)
                VALUES (?, ?, ?, 'PENDING') ON CONFLICT(provider_id, operation_id) DO NOTHING
                """)) {
            insert.setString(1, provider);
            insert.setString(2, operation.operationId().toString());
            insert.setString(3, digest);
            if (insert.executeUpdate() == 1) return State.NEW;
            return find(provider, operation.operationId(), digest).state();
        } catch (SQLException failure) { throw new PersistenceFailure("Could not begin external operation", failure); }
    }

    /** Commits a proven result; replay with a different request or result is rejected. */
    public synchronized void commit(
            String provider, int version, PlayerStateOperation operation, byte[] request, byte[] result) {
        String digest = digest(provider, version, operation, request);
        byte[] value = bounded(result);
        try {
            Entry previous = find(provider, operation.operationId(), digest);
            if (previous.state() == State.COMMITTED) {
                if (!Arrays.equals(previous.result(), value)) throw new IllegalStateException("External result conflicts with replay");
                return;
            }
            try (var update = connection.prepareStatement("""
                    UPDATE external_operation SET state = 'COMMITTED', result = ?, result_sha256 = ?, committed_at = ?
                    WHERE provider_id = ? AND operation_id = ? AND request_digest = ? AND state = 'PENDING'
                    """)) {
                update.setBytes(1, value);
                update.setString(2, checksum(value));
                update.setString(3, Instant.now().toString());
                update.setString(4, provider);
                update.setString(5, operation.operationId().toString());
                update.setString(6, digest);
                if (update.executeUpdate() != 1) throw new IllegalStateException("External operation changed during commit");
            }
        } catch (SQLException failure) { throw new PersistenceFailure("Could not commit external operation", failure); }
    }

    public synchronized Instant committedAt(String provider, int version, PlayerStateOperation operation, byte[] request) {
        try {
            Entry entry = find(provider, operation.operationId(), digest(provider, version, operation, request));
            if (entry.state() != State.COMMITTED) throw new IllegalStateException("External operation has no commit time");
            return entry.committedAt();
        } catch (SQLException failure) { throw new PersistenceFailure("Could not read external commit time", failure); }
    }

    public synchronized byte[] committedResult(
            String provider, int version, PlayerStateOperation operation, byte[] request) {
        try {
            Entry entry = find(provider, operation.operationId(), digest(provider, version, operation, request));
            if (entry.state() != State.COMMITTED) throw new IllegalStateException("External operation has no proven result");
            return entry.result().clone();
        } catch (SQLException failure) { throw new PersistenceFailure("Could not read external result", failure); }
    }

    private Entry find(String provider, UUID operation, String digest) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT request_digest, state, result, result_sha256, length(result), committed_at FROM external_operation
                WHERE provider_id = ? AND operation_id = ?
                """)) {
            query.setString(1, provider);
            query.setString(2, operation.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("External operation has no durable intent");
                if (!digest.equals(row.getString(1))) throw new IllegalStateException("External operation identity conflicts with replay");
                State state = State.valueOf(row.getString(2));
                if (row.getLong(5) > MAX_PAYLOAD) throw new IllegalStateException("External result exceeds bounded size");
                byte[] result = row.getBytes(3);
                String checksum = row.getString(4);
                String time = row.getString(6);
                if (state == State.NEW || (state == State.COMMITTED) != (result != null)
                        || (state == State.COMMITTED) != (time != null)) {
                    throw new IllegalStateException("External operation has invalid durable state");
                }
                if ((result == null && checksum != null) || (result != null && !checksum(result).equals(checksum))) {
                    throw new IllegalStateException("External result checksum does not match");
                }
                if (time != null && time.length() > 64) throw new IllegalStateException("Invalid external commit time");
                return new Entry(state, result == null ? null : bounded(result), time == null ? null : Instant.parse(time));
            }
        }
    }

    private record Entry(State state, byte[] result, Instant committedAt) {}

    private static String digest(String provider, int version, PlayerStateOperation operation, byte[] request) {
        if (provider == null || !provider.matches("[a-z0-9][a-z0-9_.-]{0,63}") || version < 1) {
            throw new IllegalArgumentException("External provider identity is invalid");
        }
        Objects.requireNonNull(operation, "operation");
        if (operation.capturedConnectionId() == null) throw new IllegalArgumentException("External operation has no captured epoch");
        byte[] payload = bounded(request);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(1);
                output.writeUTF(provider);
                output.writeInt(version);
                output.writeUTF(operation.kind().name());
                for (UUID id : new UUID[] {operation.operationId(), operation.captureOperationId(),
                        operation.snapshotId(), operation.sessionId(), operation.matchId(), operation.playerId(),
                        operation.capturedConnectionId()}) {
                    output.writeLong(id.getMostSignificantBits());
                    output.writeLong(id.getLeastSignificantBits());
                }
                output.writeUTF(operation.game().id());
                output.writeLong(operation.capturedAt().getEpochSecond());
                output.writeInt(operation.capturedAt().getNano());
                output.writeInt(payload.length);
                output.write(payload);
            }
            // The current recovery epoch is deliberately excluded. The gateway authenticates
            // each attempt; a reconnect must still refer to the same durable external operation.
            return checksum(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not identify external operation", impossible);
        }
    }

    private static String checksum(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private static byte[] bounded(byte[] value) {
        Objects.requireNonNull(value, "payload");
        if (value.length > MAX_PAYLOAD) throw new IllegalArgumentException("External journal payload exceeds limit");
        return value.clone();
    }

    @Override public synchronized void close() {
        try { connection.close(); }
        catch (SQLException failure) { throw new PersistenceFailure("Could not close external journal", failure); }
    }
}
