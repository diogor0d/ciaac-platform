package com.ciaac.minecraft.minigames.region;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RegionAdmissionRegistry {
    private final Map<PlayerRegion, RegionAdmissionToken> tokens = new LinkedHashMap<>();

    public synchronized void issue(RegionAdmissionToken token) {
        Objects.requireNonNull(token, "token");
        PlayerRegion key = new PlayerRegion(token.playerId(), token.regionId());
        RegionAdmissionToken previous = tokens.get(key);
        if (previous != null && previous.issuedAt().isAfter(token.issuedAt())) {
            throw new IllegalStateException("Cannot replace a newer region admission token");
        }
        tokens.put(key, token);
    }

    public synchronized boolean permits(
            UUID playerId,
            UUID sessionId,
            String regionId,
            Instant now) {
        RegionAdmissionToken token = tokens.get(new PlayerRegion(
                Objects.requireNonNull(playerId, "playerId"),
                Objects.requireNonNull(regionId, "regionId")));
        return token != null
                && token.sessionId().equals(Objects.requireNonNull(sessionId, "sessionId"))
                && token.validAt(Objects.requireNonNull(now, "now"));
    }

    public synchronized Optional<RegionAdmissionToken> find(UUID playerId, String regionId) {
        return Optional.ofNullable(tokens.get(new PlayerRegion(playerId, regionId)));
    }

    public synchronized void revokeSession(UUID sessionId) {
        tokens.values().removeIf(token -> token.sessionId().equals(sessionId));
    }

    private record PlayerRegion(UUID playerId, String regionId) {}
}
