package com.ciaac.minecraft.minigames.updater;

import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;

/** Fail-closed startup updater settings. No credential is stored here. */
public record UpdaterConfiguration(
        boolean enabled,
        String owner,
        String repository,
        boolean allowPrereleases,
        String jarAssetName,
        String manifestAssetName,
        String signatureAssetName,
        Optional<byte[]> trustedEd25519PublicKey,
        Duration connectTimeout,
        Duration requestTimeout,
        long maximumJarBytes,
        boolean restartEmptyServerAfterStaging) {

    public static final long MINIMUM_JAR_BYTES = 16 * 1024L;
    public static final long MAXIMUM_JAR_BYTES = 128 * 1024 * 1024L;

    public UpdaterConfiguration {
        owner = identifier(owner, "owner");
        repository = identifier(repository, "repository");
        jarAssetName = assetName(jarAssetName, "jarAssetName", ".jar");
        manifestAssetName = assetName(manifestAssetName, "manifestAssetName", ".properties");
        signatureAssetName = assetName(signatureAssetName, "signatureAssetName", ".sig");
        trustedEd25519PublicKey = Objects.requireNonNull(
                trustedEd25519PublicKey, "trustedEd25519PublicKey").map(value -> {
                    validateEd25519PublicKey(value);
                    return value.clone();
                });
        connectTimeout = boundedTimeout(connectTimeout, "connectTimeout");
        requestTimeout = boundedTimeout(requestTimeout, "requestTimeout");
        if (maximumJarBytes < MINIMUM_JAR_BYTES || maximumJarBytes > MAXIMUM_JAR_BYTES) {
            throw new IllegalArgumentException("maximumJarBytes is outside the safe range");
        }
        if (enabled && trustedEd25519PublicKey.isEmpty()) {
            throw new IllegalArgumentException("An enabled updater requires a pinned Ed25519 public key");
        }
    }

    @Override
    public Optional<byte[]> trustedEd25519PublicKey() {
        return trustedEd25519PublicKey.map(byte[]::clone);
    }

    /** Compatibility accessor for callers using the pre-restart setting name. */
    public boolean stopEmptyServerAfterStaging() {
        return restartEmptyServerAfterStaging;
    }

    public static UpdaterConfiguration disabled() {
        return new UpdaterConfiguration(false, "disabled-owner", "disabled-repository", false,
                "ciaac-platform.jar", "ciaac-platform-update.properties",
                "ciaac-platform-update.properties.sig", Optional.empty(),
                Duration.ofSeconds(5), Duration.ofSeconds(20), 32 * 1024 * 1024L, false);
    }

    public static Optional<byte[]> decodePublicKey(String encoded) {
        if (encoded == null || encoded.isBlank()
                || encoded.toUpperCase(Locale.ROOT).contains("__SET_ME__")) {
            return Optional.empty();
        }
        try {
            byte[] value = Base64.getDecoder().decode(encoded.trim());
            if (value.length < 32 || value.length > 128) return Optional.empty();
            return Optional.of(value);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.equalsIgnoreCase("set_owner") || value.equalsIgnoreCase("set_repository")
                || value.toUpperCase(Locale.ROOT).contains("__SET_ME__")
                || !value.matches("[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,98}[A-Za-z0-9])?")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String assetName(String value, String name, String suffix) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") || !value.endsWith(suffix)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static Duration boundedTimeout(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(Duration.ofSeconds(1)) < 0 || value.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException(name + " must be between one second and two minutes");
        }
        return value;
    }

    private static void validateEd25519PublicKey(byte[] value) {
        Objects.requireNonNull(value, "public key");
        if (value.length < 32 || value.length > 128) {
            throw new IllegalArgumentException("trusted Ed25519 public key has an invalid length");
        }
        byte[] encoded = value.length == 32 ? rawEd25519X509(value) : value;
        try {
            KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException invalid) {
            throw new IllegalArgumentException("trusted Ed25519 public key is invalid", invalid);
        }
    }

    private static byte[] rawEd25519X509(byte[] raw) {
        byte[] prefix = new byte[] {
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
                0x03, 0x21, 0x00
        };
        byte[] encoded = new byte[prefix.length + raw.length];
        System.arraycopy(prefix, 0, encoded, 0, prefix.length);
        System.arraycopy(raw, 0, encoded, prefix.length, raw.length);
        return encoded;
    }
}
