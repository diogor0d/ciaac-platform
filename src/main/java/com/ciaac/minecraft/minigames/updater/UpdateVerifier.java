package com.ciaac.minecraft.minigames.updater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Verifies every signed and self-identifying part of one release before staging it. */
public final class UpdateVerifier {
    private static final int MAXIMUM_PLUGIN_DESCRIPTOR_BYTES = 64 * 1024;
    private static final byte[] RAW_ED25519_X509_PREFIX = new byte[] {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
            0x03, 0x21, 0x00
    };

    private UpdateVerifier() { }

    public static ReleaseAssets selectAssets(GitHubRelease release, UpdaterConfiguration configuration)
            throws IOException {
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(configuration, "configuration");
        if (release.draft() || (release.prerelease() && !configuration.allowPrereleases())) {
            throw failure("release is draft or is not allowed by the configured channel");
        }
        if (!release.immutable()) throw failure("release is not immutable");
        GitHubRelease.Asset jar = exactUploaded(release, configuration.jarAssetName());
        GitHubRelease.Asset manifest = exactUploaded(release, configuration.manifestAssetName());
        GitHubRelease.Asset signature = exactUploaded(release, configuration.signatureAssetName());
        if (jar.size() < UpdaterConfiguration.MINIMUM_JAR_BYTES
                || jar.size() > configuration.maximumJarBytes()) {
            throw failure("JAR asset metadata is outside the configured bound");
        }
        if (manifest.size() < 1 || manifest.size() > ReleaseManifest.MAXIMUM_BYTES
                || signature.size() < 1 || signature.size() > 128 * 1024L) {
            throw failure("release metadata asset is outside the configured bound");
        }
        return new ReleaseAssets(jar, manifest, signature);
    }

    public static ReleaseManifest verifyManifest(
            GitHubRelease release,
            UpdaterConfiguration configuration,
            byte[] manifestBytes,
            byte[] signatureBytes) throws IOException {
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(configuration, "configuration");
        ReleaseManifest manifest;
        try {
            manifest = ReleaseManifest.parse(manifestBytes, configuration.jarAssetName(),
                    configuration.maximumJarBytes());
        } catch (RuntimeException invalid) {
            throw failure("manifest is invalid", invalid);
        }
        final SemanticVersion releaseVersion;
        try {
            releaseVersion = SemanticVersion.parse(release.tag());
        } catch (RuntimeException invalid) {
            throw failure("release tag is not a semantic version", invalid);
        }
        if (!release.tag().equals("v" + releaseVersion)
                || release.prerelease() != !releaseVersion.prerelease().isEmpty()) {
            throw failure("release tag and GitHub channel metadata are inconsistent");
        }
        if (manifest.releaseId() != release.id() || !manifest.version().equals(releaseVersion)) {
            throw failure("manifest does not identify the selected release");
        }
        verifySignature(configuration, manifestBytes, signatureBytes);
        return manifest;
    }

    /** Streams the untrusted JAR to a new temporary path while checking its digest and descriptor. */
    public static VerificationResult verifyJar(
            GitHubRelease.Asset jarAsset,
            UpdaterConfiguration configuration,
            ReleaseManifest manifest,
            InputStream jarInput,
            Path temporaryJar) throws IOException {
        Objects.requireNonNull(jarAsset, "jarAsset");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(jarInput, "jarInput");
        Objects.requireNonNull(temporaryJar, "temporaryJar");
        if (temporaryJar.getParent() == null) throw failure("temporary JAR has no parent");

        MessageDigest digest = sha256();
        long count = 0;
        try (jarInput;
             FileChannel output = FileChannel.open(temporaryJar, StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = jarInput.read(buffer)) >= 0) {
                count += read;
                if (count > configuration.maximumJarBytes()) {
                    throw failure("downloaded JAR exceeds the configured bound");
                }
                digest.update(buffer, 0, read);
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                while (bytes.hasRemaining()) output.write(bytes);
            }
            output.force(true);
        } catch (IOException | RuntimeException failure) {
            throw failure("downloaded JAR could not be staged for verification", failure);
        }
        if (count != manifest.size() || count != jarAsset.size()) {
            throw failure("JAR size does not match the signed release metadata");
        }
        String sha256 = java.util.HexFormat.of().formatHex(digest.digest());
        if (!sha256.equals(manifest.sha256())) throw failure("JAR SHA-256 does not match the manifest");
        if (jarAsset.digest().isPresent() && !jarAsset.digest().get().equals("sha256:" + sha256)) {
            throw failure("JAR GitHub digest does not match the downloaded bytes");
        }

        PluginDescriptor descriptor = inspectPluginDescriptor(temporaryJar);
        if (!manifest.pluginName().equals(descriptor.name())
                || !manifest.pluginMain().equals(descriptor.main())) {
            throw failure("JAR plugin.yml identity does not match the signed manifest");
        }
        SemanticVersion pluginVersion;
        try {
            pluginVersion = SemanticVersion.parse(descriptor.version());
        } catch (RuntimeException invalid) {
            throw failure("JAR plugin.yml version is invalid", invalid);
        }
        if (!manifest.version().equals(pluginVersion)) {
            throw failure("JAR plugin.yml version does not match the signed manifest");
        }
        return new VerificationResult(manifest, count, sha256, pluginVersion);
    }

    private static GitHubRelease.Asset exactUploaded(GitHubRelease release, String name) throws IOException {
        GitHubRelease.Asset asset = release.asset(name)
                .orElseThrow(() -> failure("required release asset is missing"));
        if (!"uploaded".equalsIgnoreCase(asset.state())) {
            throw failure("required release asset is not uploaded");
        }
        return asset;
    }

    private static void verifySignature(
            UpdaterConfiguration configuration, byte[] manifestBytes, byte[] signatureBytes) throws IOException {
        Objects.requireNonNull(manifestBytes, "manifestBytes");
        Objects.requireNonNull(signatureBytes, "signatureBytes");
        if (manifestBytes.length == 0 || manifestBytes.length > ReleaseManifest.MAXIMUM_BYTES
                || signatureBytes.length == 0 || signatureBytes.length > 128 * 1024) {
            throw failure("signature input is outside the safe bound");
        }
        byte[] signature = decodeSignature(signatureBytes);
        byte[] configuredKey = configuration.trustedEd25519PublicKey()
                .orElseThrow(() -> failure("the updater has no pinned public key"));
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(x509KeyBytes(configuredKey)));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(manifestBytes);
            if (!verifier.verify(signature)) throw failure("manifest signature is not valid");
        } catch (GeneralSecurityException invalid) {
            throw failure("pinned Ed25519 public key or signature is invalid", invalid);
        }
    }

    private static byte[] decodeSignature(byte[] bytes) throws IOException {
        String text;
        try {
            text = strictUtf8(bytes);
        } catch (IllegalArgumentException invalid) {
            throw failure("signature is not strict UTF-8", invalid);
        }
        if (text.length() > 1024 || text.isBlank()) throw failure("signature is too large or empty");
        try {
            byte[] decoded = Base64.getDecoder().decode(text);
            if (decoded.length != 64) throw failure("Ed25519 signature has an invalid length");
            if (!Base64.getEncoder().encodeToString(decoded).equals(text)) {
                throw failure("signature is not canonical Base64");
            }
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw failure("signature is not canonical Base64", invalid);
        }
    }

    private static byte[] x509KeyBytes(byte[] configuredKey) throws IOException {
        if (configuredKey.length == 32) {
            byte[] encoded = new byte[RAW_ED25519_X509_PREFIX.length + configuredKey.length];
            System.arraycopy(RAW_ED25519_X509_PREFIX, 0, encoded, 0, RAW_ED25519_X509_PREFIX.length);
            System.arraycopy(configuredKey, 0, encoded, RAW_ED25519_X509_PREFIX.length, configuredKey.length);
            return encoded;
        }
        if (configuredKey.length < RAW_ED25519_X509_PREFIX.length + 32 || configuredKey.length > 128) {
            throw failure("pinned Ed25519 key has an invalid length");
        }
        return configuredKey.clone();
    }

    private static PluginDescriptor inspectPluginDescriptor(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            int descriptorCount = 0;
            JarEntry descriptor = null;
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().equals("plugin.yml")) {
                    descriptorCount++;
                    descriptor = entry;
                }
            }
            if (descriptorCount != 1 || descriptor == null) {
                throw failure("JAR must contain exactly one root plugin.yml");
            }
            long declared = descriptor.getSize();
            if (declared > MAXIMUM_PLUGIN_DESCRIPTOR_BYTES) throw failure("plugin.yml is too large");
            byte[] bytes;
            try (InputStream input = jar.getInputStream(descriptor)) {
                bytes = readBounded(input, MAXIMUM_PLUGIN_DESCRIPTOR_BYTES);
            }
            return parsePluginDescriptor(bytes);
        } catch (java.util.zip.ZipException invalid) {
            throw failure("JAR archive is invalid", invalid);
        }
    }

    private static PluginDescriptor parsePluginDescriptor(byte[] bytes) throws IOException {
        String text;
        try {
            text = strictUtf8(bytes);
        } catch (IllegalArgumentException invalid) {
            throw failure("plugin.yml is not strict UTF-8", invalid);
        }
        Map<String, String> values = new HashMap<>();
        String[] lines = text.split("\\n", -1);
        if (lines.length > 1024) throw failure("plugin.yml has too many lines");
        for (String rawLine : lines) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            if (Character.isWhitespace(line.charAt(0))) continue;
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            String key = line.substring(0, separator);
            if (!(key.equals("name") || key.equals("main") || key.equals("version"))) continue;
            String value = scalar(line.substring(separator + 1).strip());
            if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)
                    || values.putIfAbsent(key, value) != null) {
                throw failure("plugin.yml has an invalid or duplicate identity field");
            }
        }
        if (!values.keySet().equals(java.util.Set.of("name", "main", "version"))) {
            throw failure("plugin.yml is missing name, main, or version");
        }
        return new PluginDescriptor(values.get("name"), values.get("main"), values.get("version"));
    }

    private static String scalar(String value) throws IOException {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                value = value.substring(1, value.length() - 1);
            } else if (first == '\'' || first == '"') {
                throw failure("plugin.yml has an unterminated scalar");
            }
        }
        return value;
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("invalid UTF-8", invalid);
        }
    }

    private static byte[] readBounded(InputStream input, long maximum) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximum) throw failure("descriptor exceeds its size bound");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException unavailable) {
            throw failure("SHA-256 is unavailable", unavailable);
        }
    }

    private static IOException failure(String message) {
        return new IOException(message);
    }

    private static IOException failure(String message, Throwable cause) {
        return new IOException(message, cause);
    }

    public record ReleaseAssets(
            GitHubRelease.Asset jar,
            GitHubRelease.Asset manifest,
            GitHubRelease.Asset signature) {
        public ReleaseAssets {
            Objects.requireNonNull(jar, "jar");
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(signature, "signature");
        }
    }

    public record VerificationResult(
            ReleaseManifest manifest,
            long bytes,
            String sha256,
            SemanticVersion pluginVersion) {
        public VerificationResult {
            Objects.requireNonNull(manifest, "manifest");
            if (bytes < 1) throw new IllegalArgumentException("bytes must be positive");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(pluginVersion, "pluginVersion");
        }
    }

    private record PluginDescriptor(String name, String main, String version) { }
}
