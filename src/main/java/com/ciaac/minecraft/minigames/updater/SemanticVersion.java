package com.ciaac.minecraft.minigames.updater;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Small SemVer 2.0 comparator used to prevent downgrade and ambiguous tags. */
public record SemanticVersion(int major, int minor, int patch, List<String> prerelease)
        implements Comparable<SemanticVersion> {

    public SemanticVersion {
        if (major < 0 || minor < 0 || patch < 0) throw new IllegalArgumentException("negative version");
        prerelease = List.copyOf(Objects.requireNonNull(prerelease, "prerelease"));
        for (String value : prerelease) {
            if (value.length() > 64 || !value.matches("[0-9A-Za-z-]+")) {
                throw new IllegalArgumentException("invalid prerelease");
            }
            if (value.length() > 1 && value.startsWith("0") && value.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("numeric prerelease identifiers cannot have leading zeroes");
            }
        }
    }

    public static SemanticVersion parse(String raw) {
        String value = Objects.requireNonNull(raw, "raw").trim();
        if (value.length() > 128) throw new IllegalArgumentException("version is too long");
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        int metadata = value.indexOf('+');
        if (metadata >= 0) {
            String build = value.substring(metadata + 1);
            if (build.isBlank() || !build.matches("[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*")) {
                throw new IllegalArgumentException("invalid build metadata");
            }
            value = value.substring(0, metadata);
        }
        String[] split = value.split("-", 2);
        String[] numeric = split[0].split("\\.", -1);
        if (numeric.length != 3) throw new IllegalArgumentException("version must use major.minor.patch");
        int major = component(numeric[0]);
        int minor = component(numeric[1]);
        int patch = component(numeric[2]);
        List<String> prerelease = new ArrayList<>();
        if (split.length == 2) {
            if (split[1].isBlank()) throw new IllegalArgumentException("empty prerelease");
            prerelease.addAll(List.of(split[1].split("\\.", -1)));
        }
        return new SemanticVersion(major, minor, patch, prerelease);
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int numeric = Integer.compare(major, other.major);
        if (numeric == 0) numeric = Integer.compare(minor, other.minor);
        if (numeric == 0) numeric = Integer.compare(patch, other.patch);
        if (numeric != 0) return numeric;
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return prerelease.isEmpty() == other.prerelease.isEmpty() ? 0 : prerelease.isEmpty() ? 1 : -1;
        }
        for (int index = 0; index < Math.min(prerelease.size(), other.prerelease.size()); index++) {
            String left = prerelease.get(index);
            String right = other.prerelease.get(index);
            boolean leftNumeric = left.chars().allMatch(Character::isDigit);
            boolean rightNumeric = right.chars().allMatch(Character::isDigit);
            int compared;
            if (leftNumeric && rightNumeric) compared = new java.math.BigInteger(left)
                    .compareTo(new java.math.BigInteger(right));
            else if (leftNumeric != rightNumeric) compared = leftNumeric ? -1 : 1;
            else compared = left.compareTo(right);
            if (compared != 0) return compared;
        }
        return Integer.compare(prerelease.size(), other.prerelease.size());
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch
                + (prerelease.isEmpty() ? "" : "-" + String.join(".", prerelease));
    }

    private static int component(String value) {
        if (!value.matches("0|[1-9][0-9]{0,8}")) throw new IllegalArgumentException("invalid version component");
        return Integer.parseInt(value);
    }
}
