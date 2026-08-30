package com.ciaac.minecraft.minigames.retention;

import java.util.Locale;
import java.util.Objects;

/** Allowlisted reward metadata. Provider ids are not commands and are never interpreted as code. */
public record RewardDescriptor(String id, String titlePtPt, RewardMetric metric, int threshold,
                               DeliveryKind deliveryKind, String providerId) {
    public RewardDescriptor {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("reward id is invalid");
        if (titlePtPt == null || titlePtPt.isBlank() || titlePtPt.length() > 80) throw new IllegalArgumentException("reward title is invalid");
        metric = Objects.requireNonNull(metric, "metric");
        deliveryKind = Objects.requireNonNull(deliveryKind, "deliveryKind");
        if (threshold < 1 || threshold > 1_000_000) throw new IllegalArgumentException("reward threshold is invalid");
        providerId = providerId == null ? "internal" : providerId.toLowerCase(Locale.ROOT);
        if (!providerId.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("provider id is invalid");
        if (deliveryKind == DeliveryKind.INTERNAL && !providerId.equals("internal")) throw new IllegalArgumentException("internal reward provider is invalid");
        if (deliveryKind != DeliveryKind.INTERNAL && providerId.equals("internal")) throw new IllegalArgumentException("external reward needs a provider");
    }

    public enum RewardMetric { CURRENT_JOIN_STREAK, LONGEST_JOIN_STREAK, ACTIVE_DAYS, WEEKLY_OBJECTIVES, PASSPORT_POINTS, SEASON_MILESTONES }
    public enum DeliveryKind { INTERNAL, EXTERNAL_MANUAL_CLAIM }
}
