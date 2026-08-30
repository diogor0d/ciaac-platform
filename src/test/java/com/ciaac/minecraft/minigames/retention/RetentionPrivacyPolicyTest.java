package com.ciaac.minecraft.minigames.retention;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetentionPrivacyPolicyTest {
    @Test void detailEventsBecomeEligibleForAnonymizationAfterTwelveCalendarMonths() {
        RetentionPrivacyPolicy policy = new RetentionPrivacyPolicy(12);
        Instant now = Instant.parse("2027-09-15T12:00:00Z");
        RetentionEvent old = event(Instant.parse("2026-09-14T12:00:00Z"));
        RetentionEvent retained = event(Instant.parse("2026-09-15T12:00:00Z"));
        assertTrue(policy.shouldAnonymize(old, now));
        assertFalse(policy.shouldAnonymize(retained, now));
    }
    private static RetentionEvent event(Instant occurredAt) {
        return new RetentionEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), occurredAt,
                LocalDate.of(2026, 9, 15), RetentionEvent.Kind.JOIN_QUALIFIED, "passport-2026-09-15", 1);
    }
}
