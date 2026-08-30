package com.ciaac.minecraft.minigames.retention;

import java.time.Instant;
import java.util.Objects;

/** Twelve-month policy for detailed Passport events; aggregate seasonal awards remain pseudonymous. */
public final class RetentionPrivacyPolicy {
    private final int detailRetentionMonths;
    public RetentionPrivacyPolicy(int detailRetentionMonths) {
        if (detailRetentionMonths != 12) throw new IllegalArgumentException("Passport detail retention is fixed at 12 months");
        this.detailRetentionMonths = detailRetentionMonths;
    }
    public int detailRetentionMonths() { return detailRetentionMonths; }
    public Instant detailCutoff(Instant now) {
        return Objects.requireNonNull(now, "now").atZone(LisbonSeasonCalendar.LISBON)
                .minusMonths(detailRetentionMonths).toInstant();
    }
    public boolean shouldAnonymize(RetentionEvent event, Instant now) {
        return Objects.requireNonNull(event, "event").occurredAt().isBefore(detailCutoff(now));
    }
}
