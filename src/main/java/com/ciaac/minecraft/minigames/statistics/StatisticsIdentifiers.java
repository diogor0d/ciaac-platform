package com.ciaac.minecraft.minigames.statistics;

import java.util.Locale;
import java.util.Objects;

/** Package-private validation shared by immutable statistics contracts. */
final class StatisticsIdentifiers {
    private static final String IDENTIFIER_PATTERN = "[a-z0-9][a-z0-9_.-]{0,31}";

    private StatisticsIdentifiers() {
    }

    static String identifier(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException(field + " must be a bounded machine-readable value");
        }
        return normalized;
    }

    static String metricKey(String value) {
        return identifier(value, "metric");
    }
}
