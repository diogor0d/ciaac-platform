package com.ciaac.minecraft.minigames.updater;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The signed, deliberately small contract accompanying one updater JAR.
 *
 * <p>The signature covers the exact UTF-8 bytes downloaded from GitHub.  This
 * parser therefore does not use {@code Properties}: escapes, duplicate keys,
 * platform line endings, and silent character-set conversions would otherwise
 * make the signed meaning ambiguous.</p>
 */
public record ReleaseManifest(
        long releaseId,
        SemanticVersion version,
        String artifact,
        long size,
        String sha256,
        String pluginName,
        String pluginMain) {

    public static final String FORMAT = "ciaac-platform-update-v1";
    public static final int MAXIMUM_BYTES = 128 * 1024;

    public ReleaseManifest {
        if (releaseId < 1) throw new IllegalArgumentException("release id must be positive");
        version = Objects.requireNonNull(version, "version");
        artifact = boundedToken(artifact, "artifact", 128);
        if (!artifact.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\\.jar")) {
            throw new IllegalArgumentException("artifact is not a safe JAR name");
        }
        if (size < UpdaterConfiguration.MINIMUM_JAR_BYTES
                || size > UpdaterConfiguration.MAXIMUM_JAR_BYTES) {
            throw new IllegalArgumentException("manifest size is outside the safe JAR range");
        }
        sha256 = boundedToken(sha256, "sha256", 64);
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 is invalid");
        pluginName = boundedToken(pluginName, "pluginName", 128);
        pluginMain = boundedToken(pluginMain, "pluginMain", 256);
        if (!pluginName.matches("[A-Za-z0-9][A-Za-z0-9 ._-]{0,127}")) {
            throw new IllegalArgumentException("pluginName is invalid");
        }
        if (!pluginMain.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            throw new IllegalArgumentException("pluginMain is invalid");
        }
    }

    /** Parses without modifying the bytes that are later verified by Ed25519. */
    public static ReleaseManifest parse(byte[] bytes, String expectedArtifact, long maximumJarBytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("manifest is empty or too large");
        }
        if (maximumJarBytes < UpdaterConfiguration.MINIMUM_JAR_BYTES
                || maximumJarBytes > UpdaterConfiguration.MAXIMUM_JAR_BYTES) {
            throw new IllegalArgumentException("invalid configured JAR bound");
        }
        String text = decodeUtf8(bytes);
        if (text.startsWith("\uFEFF") || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("manifest has an ambiguous byte order or line ending");
        }
        if (!text.endsWith("\n")) throw new IllegalArgumentException("manifest must end with LF");
        String[] lines = text.split("\\n", -1);
        if (lines.length > 32 || lines[lines.length - 1].length() != 0) {
            throw new IllegalArgumentException("manifest has too many lines");
        }
        Map<String, String> values = new HashMap<>();
        for (int index = 0; index < lines.length - 1; index++) {
            String line = lines[index];
            int separator = line.indexOf('=');
            if (separator <= 0 || separator != line.lastIndexOf('=')) {
                throw new IllegalArgumentException("manifest line is not key=value");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!key.matches("[a-z][a-z0-9-]{0,31}") || value.isBlank()
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("manifest key or value is invalid");
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("manifest contains a duplicate key");
            }
        }
        if (!values.keySet().equals(java.util.Set.of(
                "format", "release-id", "version", "artifact", "size", "sha256",
                "plugin-name", "plugin-main"))) {
            throw new IllegalArgumentException("manifest has missing or unknown keys");
        }
        if (!FORMAT.equals(values.get("format"))) throw new IllegalArgumentException("manifest format is unsupported");
        long releaseId = positiveLong(values.get("release-id"), "release-id");
        SemanticVersion version = SemanticVersion.parse(values.get("version"));
        String artifact = values.get("artifact");
        if (expectedArtifact != null && !expectedArtifact.equals(artifact)) {
            throw new IllegalArgumentException("manifest artifact does not match configuration");
        }
        long size = positiveLong(values.get("size"), "size");
        if (size > maximumJarBytes) throw new IllegalArgumentException("manifest exceeds configured JAR bound");
        return new ReleaseManifest(releaseId, version, artifact, size, values.get("sha256"),
                values.get("plugin-name"), values.get("plugin-main"));
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("manifest is not strict UTF-8", invalid);
        }
    }

    private static long positiveLong(String value, String name) {
        if (!value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException(name + " is invalid");
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " is invalid", invalid);
        }
    }

    private static String boundedToken(String value, String name, int maximum) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
