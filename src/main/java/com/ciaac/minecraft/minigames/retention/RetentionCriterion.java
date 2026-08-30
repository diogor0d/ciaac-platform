package com.ciaac.minecraft.minigames.retention;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Stable criterion SPI for future durable, authenticated Passport programmes. */
public interface RetentionCriterion {
    String id();

    /** Returns a bounded deterministic credit proposal; the shared ledger remains the idempotency authority. */
    Optional<CreditProposal> evaluate(CriterionContext context);

    record CriterionContext(UUID playerId, UUID connectionId, Instant occurredAt,
                            LocalDate localDate, String sourceCode) {}

    record CreditProposal(UUID deterministicSourceId, RetentionEvent.Kind kind,
                          String seasonId, int points, String reasonCode) {
        public CreditProposal {
            if (points < 0 || points > 25 || reasonCode == null
                    || !reasonCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw new IllegalArgumentException("A proposta de crédito é inválida.");
            }
        }
    }
}
