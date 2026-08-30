package com.ciaac.minecraft.minigames.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** Single-connection SQLite boundary with fail-closed path validation. */
public final class SqliteDatabase implements AutoCloseable {
    @FunctionalInterface
    public interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private final Path databasePath;
    private final Connection connection;

    public SqliteDatabase(Path pluginDataDirectory, Path configuredRelativePath) {
        Path root = Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory")
                .toAbsolutePath()
                .normalize();
        Path configured = Objects.requireNonNull(configuredRelativePath, "configuredRelativePath");
        if (configured.isAbsolute()) {
            throw new IllegalArgumentException("SQLite path must be relative to the plugin data directory");
        }
        databasePath = root.resolve(configured).normalize();
        if (!databasePath.startsWith(root) || databasePath.equals(root)) {
            throw new IllegalArgumentException("SQLite path escapes the plugin data directory");
        }
        Connection opened = null;
        try {
            Files.createDirectories(root);
            Path parent = databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Class.forName("org.sqlite.JDBC");
            opened = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            configure(opened);
            MinigameSchema.initialize(opened);
        } catch (ClassNotFoundException | IOException | SQLException | LinkageError exception) {
            closeAfterInitializationFailure(opened, exception);
            throw new PersistenceFailure("Could not initialize the minigame SQLite store", exception);
        } catch (RuntimeException exception) {
            closeAfterInitializationFailure(opened, exception);
            throw exception;
        }
        connection = Objects.requireNonNull(opened, "opened SQLite connection");
    }

    private static void closeAfterInitializationFailure(Connection candidate, Throwable failure) {
        if (candidate == null) return;
        try {
            candidate.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    public synchronized <T> T read(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        try {
            return work.execute(connection);
        } catch (SQLException exception) {
            throw new PersistenceFailure("SQLite read failed", exception);
        }
    }

    public synchronized <T> T transaction(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        try {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new PersistenceFailure("SQLite transaction failed", exception);
        }
    }

    public Path databasePath() {
        return databasePath;
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException exception) {
            throw new PersistenceFailure("Could not close the minigame SQLite store", exception);
        }
    }
}
