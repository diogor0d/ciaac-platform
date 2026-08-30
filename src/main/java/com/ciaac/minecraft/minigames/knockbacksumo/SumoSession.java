package com.ciaac.minecraft.minigames.knockbacksumo;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Paper-neutral session. The adapter must enforce the spawn-safezone boundary and snapshot isolation. */
public final class SumoSession {
    private final UUID sessionId;
    private final SumoConfig config;
    private final UUID first;
    private final UUID second;
    private final Set<UUID> operations = new HashSet<>();
    private SumoPhase phase = SumoPhase.WAITING;
    private int firstWins;
    private int secondWins;
    private int rounds;
    private Instant roundStarted;
    private SumoResult result;

    public SumoSession(UUID id, SumoConfig c, UUID a, UUID b) {
        sessionId = Objects.requireNonNull(id);
        config = Objects.requireNonNull(c);
        first = Objects.requireNonNull(a);
        second = Objects.requireNonNull(b);
        if (first.equals(second)) throw new IllegalArgumentException("distinct players required");
    }

    public synchronized SumoPhase phase() { return phase; }
    public UUID sessionId() { return sessionId; }
    public synchronized Optional<SumoResult> result() { return Optional.ofNullable(result); }

    public synchronized void start(Instant now, UUID event) {
        event(event);
        if (phase != SumoPhase.WAITING) throw new IllegalStateException("not waiting");
        roundStarted = Objects.requireNonNull(now);
        phase = SumoPhase.RUNNING;
    }

    /** Returns true only once the current round has reached its configured deadline. */
    public synchronized boolean roundTimedOut(Instant now) {
        Objects.requireNonNull(now);
        return phase == SumoPhase.RUNNING
                && roundStarted != null
                && !now.isBefore(roundStarted.plus(config.roundTimeout()));
    }

    /** Ends the current round as a draw; roster order makes the result deterministic. */
    public synchronized void timeout(Instant now, UUID event) {
        Objects.requireNonNull(now);
        event(event);
        if (!roundTimedOut(now)) throw new IllegalStateException("round timeout has not elapsed");
        rounds++;
        phase = SumoPhase.FINISHING;
        result = new SumoResult(UUID.randomUUID(), sessionId, config.rulesetRevision(), first, second, rounds, "ROUND_TIMEOUT");
    }

    public synchronized void ringOut(UUID player, Instant now, UUID event) {
        event(event);
        if (phase != SumoPhase.RUNNING) throw new IllegalStateException("not running");
        if (!player.equals(first) && !player.equals(second)) throw new IllegalArgumentException("unknown player");
        rounds++;
        if (player.equals(first)) secondWins++; else firstWins++;
        if (firstWins >= config.roundsToWin() || secondWins >= config.roundsToWin()) {
            phase = SumoPhase.FINISHING;
            result = new SumoResult(UUID.randomUUID(), sessionId, config.rulesetRevision(),
                    firstWins > secondWins ? first : second,
                    firstWins > secondWins ? second : first, rounds, "RING_OUT");
        } else {
            roundStarted = Objects.requireNonNull(now);
        }
    }

    public synchronized void disconnect(UUID player, UUID event) {
        event(event);
        if (phase != SumoPhase.RUNNING && phase != SumoPhase.WAITING) throw new IllegalStateException("closed");
        if (!player.equals(first) && !player.equals(second)) throw new IllegalArgumentException("unknown player");
        if (phase == SumoPhase.RUNNING) {
            phase = SumoPhase.FINISHING;
            result = new SumoResult(UUID.randomUUID(), sessionId, config.rulesetRevision(),
                    player.equals(first) ? second : first, player, Math.max(1, rounds), "DISCONNECT");
        } else {
            phase = SumoPhase.CANCELLED;
        }
    }

    public synchronized void cancel(UUID event) {
        event(event);
        if (phase == SumoPhase.CLOSED) throw new IllegalStateException("closed");
        phase = SumoPhase.CANCELLED;
    }

    public synchronized void close(UUID event) {
        event(event);
        if (phase != SumoPhase.FINISHING && phase != SumoPhase.CANCELLED) throw new IllegalStateException("not terminal");
        phase = SumoPhase.CLOSED;
    }

    private void event(UUID e) {
        Objects.requireNonNull(e);
        if (!operations.add(e)) throw new IllegalStateException("duplicate event");
    }
}
