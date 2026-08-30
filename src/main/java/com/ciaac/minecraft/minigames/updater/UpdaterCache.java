package com.ciaac.minecraft.minigames.updater;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Small, non-secret ETag/body cache with fail-closed filesystem handling. */
public final class UpdaterCache {
    private static final long MAXIMUM_RELEASE_JSON_BYTES = 2_000_000L;
    private static final int MAXIMUM_ETAG_VALUE_BYTES = 512;
    private static final int MAXIMUM_ETAG_CACHE_BYTES = 1_024;
    private static final long MAXIMUM_RESTART_ATTEMPT_BYTES = 4_096L;
    private static final String RELEASE_FILE = "latest-release.json";
    private static final String ETAG_FILE = "etag.txt";
    private static final String ETAG_CACHE_FORMAT = "ciaac-platform-updater-etag-v2";
    private static final String RESTART_ATTEMPT_FILE = "restart-attempt.properties";
    private static final String RESTART_ATTEMPT_FORMAT = "ciaac-platform-updater-restart-v1";
    private final Path directory;

    public UpdaterCache(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    public CacheSnapshot read(String selector) throws IOException {
        validateSelector(selector);
        rejectSymlinkAncestors(directory, "updater cache path");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(directory)) {
            throw new IOException("updater cache directory must not be a symbolic link");
        }
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return CacheSnapshot.empty();
        requireDirectory(directory, "updater cache directory");
        Path release = directory.resolve(RELEASE_FILE);
        Path etag = directory.resolve(ETAG_FILE);
        Optional<String> rawJson = readText(release, MAXIMUM_RELEASE_JSON_BYTES, "release cache");
        Optional<String> rawEtag = readText(etag, MAXIMUM_ETAG_CACHE_BYTES, "ETag cache");
        if (rawJson.isEmpty()) return new CacheSnapshot(Optional.empty(), Optional.empty());
        byte[] body = rawJson.orElseThrow().getBytes(StandardCharsets.UTF_8);
        Optional<String> validatedEtag = rawEtag.flatMap(value -> pairedEtag(value, selector, body));
        return new CacheSnapshot(rawJson, validatedEtag);
    }

    public void store(String selector, String rawJson, Optional<String> etag) throws IOException {
        validateSelector(selector);
        Objects.requireNonNull(rawJson, "rawJson");
        Objects.requireNonNull(etag, "etag");
        byte[] body = rawJson.getBytes(StandardCharsets.UTF_8);
        if (body.length == 0 || body.length > MAXIMUM_RELEASE_JSON_BYTES) {
            throw new IOException("release cache body is outside its bound");
        }
        String etagValue = etag.orElse("");
        if (etagValue.length() > MAXIMUM_ETAG_VALUE_BYTES) {
            throw new IOException("ETag is outside its safe bound");
        }
        validateEtag(etagValue);
        rejectSymlinkAncestors(directory, "updater cache path");
        prepareDirectory();
        String pairedEtag = ETAG_CACHE_FORMAT + "\n"
                + "selector=" + selector + "\n"
                + "sha256=" + sha256(body) + "\n"
                + "etag-base64=" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(etagValue.getBytes(StandardCharsets.US_ASCII)) + "\n";
        writeAtomically(directory.resolve(RELEASE_FILE), body);
        writeAtomically(directory.resolve(ETAG_FILE), pairedEtag.getBytes(StandardCharsets.US_ASCII));
    }

    public Path directory() {
        return directory;
    }

    /** Reads the one durable restart attempt, if one has been recorded. */
    public Optional<RestartAttempt> readRestartAttempt() throws IOException {
        rejectSymlinkAncestors(directory, "updater cache path");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(directory)) {
            throw new IOException("updater cache directory must not be a symbolic link");
        }
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        requireDirectory(directory, "updater cache directory");
        return readText(directory.resolve(RESTART_ATTEMPT_FILE), MAXIMUM_RESTART_ATTEMPT_BYTES,
                "restart attempt").map(UpdaterCache::parseRestartAttempt);
    }

    /** Atomically replaces the bounded restart marker with one attempt. */
    public void storeRestartAttempt(RestartAttempt attempt) throws IOException {
        Objects.requireNonNull(attempt, "attempt");
        rejectSymlinkAncestors(directory, "updater cache path");
        prepareDirectory();
        String content = RESTART_ATTEMPT_FORMAT + "\n"
                + "release-id=" + attempt.releaseId() + "\n"
                + "version=" + attempt.version() + "\n"
                + "sha256=" + attempt.sha256() + "\n"
                + "attempted-at=" + attempt.attemptedAt() + "\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_RESTART_ATTEMPT_BYTES) {
            throw new IOException("restart attempt is outside its safe bound");
        }
        writeAtomically(directory.resolve(RESTART_ATTEMPT_FILE), bytes);
    }

    /** Removes the marker only after the installed version has consumed it. */
    public void clearRestartAttempt() throws IOException {
        rejectSymlinkAncestors(directory, "updater cache path");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(directory)) {
            throw new IOException("updater cache directory must not be a symbolic link");
        }
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        requireDirectory(directory, "updater cache directory");
        Path marker = directory.resolve(RESTART_ATTEMPT_FILE);
        if (Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(marker)
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("restart attempt marker is not a regular file");
        }
        Files.deleteIfExists(marker);
    }

    private void prepareDirectory() throws IOException {
        rejectSymlinkAncestors(directory, "updater cache path");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(directory)) {
            throw new IOException("updater cache directory must not be a symbolic link");
        }
        Files.createDirectories(directory);
        requireDirectory(directory, "updater cache directory");
    }

    private static void rejectSymlinkAncestors(Path path, String label) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException(label + " must not contain symbolic-link ancestors");
            }
            current = current.getParent();
        }
    }

    private static Optional<String> readText(Path path, long maximum, String label) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (Files.isSymbolicLink(path)) throw new IOException(label + " must not be a symbolic link");
        requireRegular(path, label);
        long declared = Files.size(path);
        if (declared > maximum) throw new IOException(label + " is too large");
        byte[] bytes;
        try (java.io.InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
            bytes = readBounded(input, maximum);
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return Optional.of(decoded.toString());
        } catch (CharacterCodingException invalid) {
            throw new IOException(label + " is not strict UTF-8", invalid);
        }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new IOException("updater cache target must not be a symbolic link");
        }
        Path parent = target.getParent();
        if (parent == null) throw new IOException("updater cache target has no parent");
        String temporaryName = target.getFileName() + ".tmp-" + UUID.randomUUID();
        Path temporary = parent.resolve(temporaryName);
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("atomic updater cache replacement is unavailable", unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] readBounded(java.io.InputStream input, long maximum) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximum) throw new IOException("cache response exceeds its size bound");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void validateEtag(String value) throws IOException {
        if (value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new IOException("ETag is outside its ASCII header bound");
        }
    }

    /**
     * Uses the cached validator only when it names the exact cached body. A
     * legacy, interrupted, or mismatched pair deliberately causes a full GET.
     */
    private static Optional<String> pairedEtag(String content, String selector, byte[] body) {
        String[] lines = content.split("\\n", -1);
        if (lines.length != 5 || !lines[4].isEmpty()
                || !ETAG_CACHE_FORMAT.equals(lines[0])
                || !lines[1].equals("selector=" + selector)
                || !lines[2].startsWith("sha256=")
                || !lines[3].startsWith("etag-base64=")) {
            return Optional.empty();
        }
        String expectedDigest = lines[2].substring("sha256=".length());
        if (!expectedDigest.matches("[0-9a-f]{64}") || !expectedDigest.equals(sha256(body))) {
            return Optional.empty();
        }
        String encoded = lines[3].substring("etag-base64=".length());
        if (!encoded.matches("[A-Za-z0-9_-]{0,684}")) return Optional.empty();
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        if (decoded.length > MAXIMUM_ETAG_VALUE_BYTES) return Optional.empty();
        for (byte value : decoded) {
            int character = Byte.toUnsignedInt(value);
            if (character < 0x21 || character > 0x7e) return Optional.empty();
        }
        String value = new String(decoded, StandardCharsets.US_ASCII);
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static void validateSelector(String selector) {
        Objects.requireNonNull(selector, "selector");
        if (!selector.matches("[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("cache selector is invalid");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("JRE must provide SHA-256", impossible);
        }
    }

    private static void requireDirectory(Path path, String label) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException(label + " is not a directory");
    }

    private static void requireRegular(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException(label + " is not a regular file");
    }

    private static RestartAttempt parseRestartAttempt(String content) {
        String[] lines = content.split("\\n", -1);
        if (lines.length != 6 || !lines[lines.length - 1].isEmpty()
                || !RESTART_ATTEMPT_FORMAT.equals(lines[0])) {
            throw new IllegalArgumentException("restart attempt marker has an invalid format");
        }
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        for (int index = 1; index < lines.length - 1; index++) {
            String line = lines[index];
            int separator = line.indexOf('=');
            if (separator <= 0 || separator != line.lastIndexOf('=')
                    || line.substring(separator + 1).isBlank()
                    || line.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("restart attempt marker has an invalid line");
            }
            if (values.putIfAbsent(line.substring(0, separator), line.substring(separator + 1)) != null) {
                throw new IllegalArgumentException("restart attempt marker has duplicate fields");
            }
        }
        if (!values.keySet().equals(java.util.Set.of("release-id", "version", "sha256", "attempted-at"))) {
            throw new IllegalArgumentException("restart attempt marker has invalid fields");
        }
        try {
            long releaseId = Long.parseLong(values.get("release-id"));
            if (releaseId < 1) throw new NumberFormatException();
            return new RestartAttempt(releaseId,
                    SemanticVersion.parse(values.get("version")),
                    values.get("sha256"), Instant.parse(values.get("attempted-at")));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("restart attempt marker is invalid", invalid);
        }
    }

    public record CacheSnapshot(Optional<String> rawJson, Optional<String> etag) {
        public CacheSnapshot {
            Objects.requireNonNull(rawJson, "rawJson");
            Objects.requireNonNull(etag, "etag");
        }

        static CacheSnapshot empty() {
            return new CacheSnapshot(Optional.empty(), Optional.empty());
        }
    }

    public record RestartAttempt(long releaseId, SemanticVersion version,
                                 String sha256, Instant attemptedAt) {
        public RestartAttempt {
            if (releaseId < 1) throw new IllegalArgumentException("release id must be positive");
            Objects.requireNonNull(version, "version");
            sha256 = Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 is invalid");
            Objects.requireNonNull(attemptedAt, "attemptedAt");
        }
    }
}
