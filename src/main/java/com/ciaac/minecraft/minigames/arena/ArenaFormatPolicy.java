package com.ciaac.minecraft.minigames.arena;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Configuration-backed format admission policy for the single arena. */
public record ArenaFormatPolicy(
        int maxTeamSize,
        boolean asymmetricAllowed,
        Set<ArenaFormat> allowedAsymmetricFormats) {

    public ArenaFormatPolicy {
        if (maxTeamSize < 1 || maxTeamSize > 3) {
            throw new IllegalArgumentException("maxTeamSize must be between 1 and 3");
        }
        Objects.requireNonNull(allowedAsymmetricFormats, "allowedAsymmetricFormats");
        var copy = new LinkedHashSet<ArenaFormat>();
        for (ArenaFormat format : allowedAsymmetricFormats) {
            Objects.requireNonNull(format, "allowed asymmetric format");
            if (!format.isAsymmetric()) {
                throw new IllegalArgumentException("The asymmetric allow-list cannot contain symmetric formats");
            }
            if (format.teamASize() > maxTeamSize || format.teamBSize() > maxTeamSize) {
                throw new IllegalArgumentException("Allowed format exceeds maxTeamSize");
            }
            copy.add(format);
        }
        if (!asymmetricAllowed && !copy.isEmpty()) {
            throw new IllegalArgumentException("Asymmetric formats require asymmetricAllowed=true");
        }
        allowedAsymmetricFormats = Set.copyOf(copy);
    }

    public static ArenaFormatPolicy defaultPolicy() {
        return new ArenaFormatPolicy(3, false, Set.of());
    }

    public boolean supports(ArenaFormat format) {
        Objects.requireNonNull(format, "format");
        if (format.teamASize() > maxTeamSize || format.teamBSize() > maxTeamSize) {
            return false;
        }
        return !format.isAsymmetric()
                || (asymmetricAllowed && allowedAsymmetricFormats.contains(format));
    }

    public void requireSupported(ArenaFormat format) {
        if (!supports(format)) {
            throw new IllegalArgumentException("Arena format is not enabled by the configured policy: " + format);
        }
    }
}
