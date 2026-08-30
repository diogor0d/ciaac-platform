package com.ciaac.minecraft.minigames.paper;

import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Central admission decision for non-walking transport into protected regions. */
public final class ProtectedRegionTransportPolicy {
    private ProtectedRegionTransportPolicy() {}

    public enum Decision {
        ALLOW,
        DENY_AMBIGUOUS_DESTINATION,
        DENY_NO_ADMISSION,
        DENY_ACTIVE_ESCAPE;

        public boolean allowed() {
            return this == ALLOW;
        }
    }

    /** A destination is either resolved by the region registry or explicitly ambiguous. */
    public record Destination(boolean known, Optional<ProtectedRegion> region) {
        public Destination {
            Objects.requireNonNull(region, "region");
            if (!known && region.isPresent()) {
                throw new IllegalArgumentException("An ambiguous destination cannot contain a region");
            }
        }

        public static Destination known(Optional<ProtectedRegion> region) {
            return new Destination(true, region);
        }

        public static Destination ambiguous() {
            return new Destination(false, Optional.empty());
        }
    }

    public static Decision evaluate(
            Destination destination,
            UUID playerId,
            Optional<PlayerSession> session,
            RegionAdmissionRegistry admissions,
            Instant now) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(admissions, "admissions");
        Objects.requireNonNull(now, "now");

        if (!destination.known()) {
            return Decision.DENY_AMBIGUOUS_DESTINATION;
        }
        Optional<ProtectedRegion> region = destination.region();
        if (region.isPresent() && region.orElseThrow().requiresAdmission()) {
            if (session.isEmpty()) {
                return Decision.DENY_NO_ADMISSION;
            }
            PlayerSession value = session.orElseThrow();
            if (value.game() != region.orElseThrow().game()
                    || !admissions.permits(playerId, value.sessionId(), region.orElseThrow().id(), now)) {
                return Decision.DENY_NO_ADMISSION;
            }
            return Decision.ALLOW;
        }

        // Recovery and restore exits are permitted after the coordinator leaves
        // ACTIVE. Coliseum fighter/spectator transitions need match-roster context
        // and remain owned by the Coliseum boundary router.
        if (session.filter(value -> value.phase() == SessionPhase.ACTIVE)
                .filter(value -> value.game() != GameKey.ARENA)
                .isPresent()) {
            return Decision.DENY_ACTIVE_ESCAPE;
        }
        return Decision.ALLOW;
    }
}
