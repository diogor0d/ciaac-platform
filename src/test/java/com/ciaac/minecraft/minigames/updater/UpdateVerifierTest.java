package com.ciaac.minecraft.minigames.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UpdateVerifierTest {
    @Test
    void verifiesDetachedSignatureDigestAndPluginIdentity(@TempDir Path temporaryDirectory) throws Exception {
        Path sourceJar = writeJar(temporaryDirectory.resolve("source.jar"));
        byte[] jarBytes = Files.readAllBytes(sourceJar);
        String sha256 = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(jarBytes));
        String manifestText = manifest(42, "0.2.0", jarBytes.length, sha256);
        byte[] manifestBytes = manifestText.getBytes(StandardCharsets.UTF_8);

        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(manifestBytes);
        byte[] signatureBytes = Base64.getEncoder().encode(signer.sign());
        UpdaterConfiguration configuration = new UpdaterConfiguration(true, "ciaac", "releases", false,
                "ciaac-platform.jar", "ciaac-platform-update.properties",
                "ciaac-platform-update.properties.sig", Optional.of(keyPair.getPublic().getEncoded()),
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1),
                32 * 1024 * 1024L, false);
        GitHubRelease release = new GitHubRelease(42, "v0.2.0", false, false, true, List.of(
                new GitHubRelease.Asset(1, configuration.jarAssetName(), jarBytes.length, "uploaded",
                        Optional.of("sha256:" + sha256)),
                new GitHubRelease.Asset(2, configuration.manifestAssetName(), manifestBytes.length, "uploaded",
                        Optional.empty()),
                new GitHubRelease.Asset(3, configuration.signatureAssetName(), signatureBytes.length, "uploaded",
                        Optional.empty())));

        ReleaseManifest manifest = UpdateVerifier.verifyManifest(release, configuration,
                manifestBytes, signatureBytes);
        Path verified = temporaryDirectory.resolve("verified.jar");
        UpdateVerifier.VerificationResult result;
        try (InputStream input = Files.newInputStream(sourceJar, StandardOpenOption.READ)) {
            result = UpdateVerifier.verifyJar(release.asset(configuration.jarAssetName()).orElseThrow(),
                    configuration, manifest, input, verified);
        }

        assertEquals(manifest.version(), result.pluginVersion());
        assertEquals(jarBytes.length, result.bytes());
        assertEquals(jarBytes.length, Files.size(verified));
    }

    @Test
    void rejectsAChangedManifestEvenWhenTheReleaseShapeIsValid() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UpdaterConfiguration configuration = new UpdaterConfiguration(true, "ciaac", "releases", false,
                "ciaac-platform.jar", "ciaac-platform-update.properties",
                "ciaac-platform-update.properties.sig", Optional.of(keyPair.getPublic().getEncoded()),
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1),
                32 * 1024 * 1024L, false);
        byte[] original = manifest(42, "0.2.0", 16_384, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(original);
        byte[] signature = Base64.getEncoder().encode(signer.sign());
        byte[] changed = new String(original, StandardCharsets.UTF_8)
                .replace("version=0.2.0", "version=0.2.1").getBytes(StandardCharsets.UTF_8);
        GitHubRelease release = new GitHubRelease(42, "v0.2.1", false, false, true, List.of());

        assertThrows(java.io.IOException.class,
                () -> UpdateVerifier.verifyManifest(release, configuration, changed, signature));
    }

    @Test
    void rejectsNonCanonicalSignatureWhitespace() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UpdaterConfiguration configuration = new UpdaterConfiguration(true, "ciaac", "releases", false,
                "ciaac-platform.jar", "ciaac-platform-update.properties",
                "ciaac-platform-update.properties.sig", Optional.of(keyPair.getPublic().getEncoded()),
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1),
                32 * 1024 * 1024L, false);
        byte[] manifest = manifest(42, "0.2.0", 16_384,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(manifest);
        byte[] signature = Base64.getEncoder().encode(signer.sign());
        byte[] padded = (" " + new String(signature, StandardCharsets.US_ASCII) + " ")
                .getBytes(StandardCharsets.US_ASCII);
        GitHubRelease release = new GitHubRelease(42, "v0.2.0", false, false, true, List.of());

        assertThrows(java.io.IOException.class,
                () -> UpdateVerifier.verifyManifest(release, configuration, manifest, padded));
    }

    private static Path writeJar(Path path) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("name: CIAACPlatform\n"
                    + "version: '0.2.0'\n"
                    + "main: com.ciaac.minecraft.platform.CiaacPlatformPlugin\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("payload.bin"));
            byte[] payload = new byte[32_000];
            new java.util.Random(42L).nextBytes(payload);
            output.write(payload);
            output.closeEntry();
        }
        return path;
    }

    private static String manifest(long releaseId, String version, long size, String sha256) {
        return "format=ciaac-platform-update-v1\n"
                + "release-id=" + releaseId + "\n"
                + "version=" + version + "\n"
                + "artifact=ciaac-platform.jar\n"
                + "size=" + size + "\n"
                + "sha256=" + sha256 + "\n"
                + "plugin-name=CIAACPlatform\n"
                + "plugin-main=com.ciaac.minecraft.platform.CiaacPlatformPlugin\n";
    }
}
