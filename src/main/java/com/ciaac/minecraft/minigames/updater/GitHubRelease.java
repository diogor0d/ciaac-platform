package com.ciaac.minecraft.minigames.updater;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Strict subset of one public GitHub release response. */
public record GitHubRelease(
        long id,
        String tag,
        boolean draft,
        boolean prerelease,
        boolean immutable,
        List<Asset> assets) {

    public GitHubRelease {
        if (id < 1) throw new IllegalArgumentException("release id must be positive");
        tag = bounded(tag, "tag", 128);
        assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
        if (assets.size() > 256) throw new IllegalArgumentException("too many release assets");
        if (assets.stream().map(Asset::name).distinct().count() != assets.size()) {
            throw new IllegalArgumentException("release asset names must be unique");
        }
    }

    public Optional<Asset> asset(String exactName) {
        return assets.stream().filter(asset -> asset.name().equals(exactName)).findFirst();
    }

    public record Asset(long id, String name, long size, String state, Optional<String> digest) {
        public Asset {
            if (id < 1 || size < 0) throw new IllegalArgumentException("invalid release asset metadata");
            name = bounded(name, "asset name", 128);
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("unsafe release asset name");
            }
            state = bounded(state, "asset state", 32);
            digest = Objects.requireNonNull(digest, "digest").map(value -> {
                String normalized = bounded(value, "digest", 128);
                if (!normalized.matches("sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("unsupported release digest");
                }
                return normalized;
            });
        }
    }

    private static String bounded(String value, String name, int maximum) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
