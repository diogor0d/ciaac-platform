package com.ciaac.minecraft.minigames.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Small in-memory guard against accidental double-clicks and command spam. */
public final class CommandRateLimiter {
    private record Key(UUID playerId, String action) {}
    private final Clock clock;
    private final Duration interval;
    private final Map<Key, Instant> lastAccepted = new HashMap<>();

    public CommandRateLimiter(Clock clock, Duration interval) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("interval must be between zero and ten seconds");
        }
    }

    public synchronized boolean allow(UUID playerId, String action) {
        Key key = new Key(Objects.requireNonNull(playerId, "playerId"), Objects.requireNonNull(action, "action"));
        Instant now = clock.instant();
        Instant previous = lastAccepted.get(key);
        if (previous != null && now.isBefore(previous.plus(interval))) return false;
        lastAccepted.put(key, now);
        if (lastAccepted.size() > 2_048) {
            Instant threshold = now.minus(Duration.ofMinutes(5));
            lastAccepted.values().removeIf(value -> value.isBefore(threshold));
        }
        return true;
    }
}
