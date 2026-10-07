package com.ciaac.minecraft.minigames.hotpotato;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Pure domain state machine for one Hot Potato instance.
 *
 * <p>The carrier is logical state only. Bukkit/Paper adapters are responsible
 * for representing it visually and for translating game events into these
 * methods.</p>
 */
public final class HotPotatoGame {
    private static final Map<HotPotatoPhase, Set<HotPotatoPhase>> LEGAL_TRANSITIONS = legalTransitions();

    private final UUID matchId;
    private final int minimumPlayers;
    private final int maximumPlayers;
    private final Duration fuseDuration;
    private final Duration suddenDeathFuseDuration;
    private final Duration fuseReductionPerElimination;
    private final Duration passCooldown;
    private final RandomGenerator random;
    private final double passRange;
    private final Duration matchTimeout;
    private final String rulesetRevision;
    private final LinkedHashSet<UUID> roster = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> livePlayers = new LinkedHashSet<>();
    private final Map<DirectedPair, Instant> recentPasses = new HashMap<>();
    private final Set<OperationId> acceptedOperations = new HashSet<>();

    private HotPotatoPhase phase = HotPotatoPhase.DISABLED;
    private UUID carrier;
    private Instant fuseDeadline;
    private MatchResult result;
    private long lastOperationSequence;
    private int passes;
    private int eliminations;
    private int forfeits;
    private Instant carrierSince;
    private Duration longestCarrierTime = Duration.ZERO;
    private final Map<UUID, Integer> passesByPlayer = new HashMap<>();
    private final Map<UUID, Integer> eliminationsByPlayer = new HashMap<>();
    private final Map<UUID, Integer> forfeitsByPlayer = new HashMap<>();
    private final Map<UUID, Duration> carrierTimeByPlayer = new HashMap<>();
    private final Map<UUID, Duration> survivalTimeByPlayer = new HashMap<>();
    private HotPotatoOutcome outcome;

    public HotPotatoGame(
            UUID matchId,
            int minimumPlayers,
            int maximumPlayers,
            Duration fuseDuration,
                Duration suddenDeathFuseDuration,
                Duration passCooldown,
                RandomGenerator random) {
        this(matchId, minimumPlayers, maximumPlayers, fuseDuration, suddenDeathFuseDuration,
                passCooldown, random, 4.0, Duration.ofMinutes(15), "legacy", Duration.ZERO);
    }

    public HotPotatoGame(UUID matchId, HotPotatoConfig config, RandomGenerator random) {
        this(matchId, config.minimumPlayers(), config.maximumPlayers(), config.fuse(),
                config.suddenDeathFuse(), config.passCooldown(), random, config.passRange(),
                config.matchTimeout(), config.rulesetRevision(), config.fuseReductionPerElimination());
    }

    private HotPotatoGame(UUID matchId, int minimumPlayers, int maximumPlayers,
                          Duration fuseDuration, Duration suddenDeathFuseDuration,
                          Duration passCooldown, RandomGenerator random, double passRange,
                          Duration matchTimeout, String rulesetRevision,
                          Duration fuseReductionPerElimination) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        if (minimumPlayers < 2) {
            throw new IllegalArgumentException("minimumPlayers must be at least two");
        }
        if (maximumPlayers < minimumPlayers) {
            throw new IllegalArgumentException("maximumPlayers must be >= minimumPlayers");
        }
        this.minimumPlayers = minimumPlayers;
        this.maximumPlayers = maximumPlayers;
        this.fuseDuration = positive(fuseDuration, "fuseDuration");
        this.suddenDeathFuseDuration = positive(suddenDeathFuseDuration, "suddenDeathFuseDuration");
        this.fuseReductionPerElimination = nonNegative(
                fuseReductionPerElimination, "fuseReductionPerElimination");
        this.passCooldown = nonNegative(passCooldown, "passCooldown");
        this.random = Objects.requireNonNull(random, "random");
        if (!Double.isFinite(passRange) || passRange <= 0) throw new IllegalArgumentException("passRange must be positive");
        this.passRange = passRange;
        this.matchTimeout = positive(matchTimeout, "matchTimeout");
        this.rulesetRevision = Objects.requireNonNull(rulesetRevision, "rulesetRevision");
    }

    public synchronized UUID matchId() {
        return matchId;
    }

    public synchronized HotPotatoPhase phase() {
        return phase;
    }

    public synchronized Set<UUID> roster() {
        return Set.copyOf(roster);
    }

    public synchronized Set<UUID> livePlayers() {
        return Set.copyOf(livePlayers);
    }

    public synchronized Optional<UUID> carrier() {
        return Optional.ofNullable(carrier);
    }

    public synchronized Optional<Instant> fuseDeadline() {
        return Optional.ofNullable(fuseDeadline);
    }

    public synchronized Optional<MatchResult> result() {
        return Optional.ofNullable(result);
    }

    public synchronized Optional<HotPotatoOutcome> outcome() { return Optional.ofNullable(outcome); }

    public synchronized HotPotatoMetrics metrics() {
        return new HotPotatoMetrics(passes, eliminations, forfeits, longestCarrierTime, rulesetRevision,
                passesByPlayer, eliminationsByPlayer, forfeitsByPlayer,
                carrierTimeByPlayer, survivalTimeByPlayer);
    }

    public synchronized void enable(OperationId operation) {
        mutate(operation, HotPotatoPhase.IDLE, "enable");
    }

    public synchronized void disable(OperationId operation) {
        mutate(operation, HotPotatoPhase.DISABLED, "disable");
    }

    /** Closes an unused instance without entering a match or discarding a snapshot. */
    public synchronized void close(OperationId operation) {
        if (phase != HotPotatoPhase.DISABLED && phase != HotPotatoPhase.IDLE) {
            throw new IllegalStateException("close is only allowed before queue admission");
        }
        transition(operation, HotPotatoPhase.CLOSED);
        roster.clear();
    }

    public synchronized void openQueue(OperationId operation) {
        mutate(operation, HotPotatoPhase.WAITING, "openQueue");
    }

    public synchronized void join(UUID player, OperationId operation) {
        Objects.requireNonNull(player, "player");
        requirePhase(HotPotatoPhase.WAITING, "join");
        requireOperation(operation);
        if (roster.contains(player)) {
            throw new IllegalStateException("player is already in the roster");
        }
        if (roster.size() >= maximumPlayers) {
            throw new IllegalStateException("roster is full");
        }
        acceptOperation(operation);
        roster.add(player);
    }

    public synchronized void leave(UUID player, OperationId operation) {
        Objects.requireNonNull(player, "player");
        if (phase != HotPotatoPhase.WAITING && phase != HotPotatoPhase.COUNTDOWN) {
            throw new IllegalStateException("leave is only allowed before entry is locked");
        }
        requireOperation(operation);
        if (!roster.contains(player)) {
            throw new IllegalStateException("player is not in the roster");
        }
        acceptOperation(operation);
        roster.remove(player);
        if (phase == HotPotatoPhase.COUNTDOWN) {
            // A countdown snapshots its roster. Returning to WAITING invalidates
            // that countdown so the adapter can start a fresh one after the
            // remaining queue again reaches the minimum player count.
            transitionTo(HotPotatoPhase.WAITING);
        }
    }

    public synchronized void beginCountdown(OperationId operation) {
        requirePhase(HotPotatoPhase.WAITING, "beginCountdown");
        if (roster.size() < minimumPlayers) {
            throw new IllegalStateException("minimum player count has not been reached");
        }
        transition(operation, HotPotatoPhase.COUNTDOWN);
    }

    public synchronized void lockEntry(OperationId operation) {
        requirePhase(HotPotatoPhase.COUNTDOWN, "lockEntry");
        if (roster.size() < minimumPlayers) {
            throw new IllegalStateException("minimum player count has not been reached");
        }
        transition(operation, HotPotatoPhase.ENTRY_LOCKED);
        livePlayers.clear();
        livePlayers.addAll(roster);
    }

    public synchronized void start(Instant now, OperationId operation) {
        Objects.requireNonNull(now, "now");
        if (phase == HotPotatoPhase.WAITING) {
            if (roster.size() < minimumPlayers) {
                throw new IllegalStateException("minimum player count has not been reached");
            }
            requireOperation(operation);
            acceptOperation(operation);
            // Convenience entry point for adapters that have no separate
            // countdown callback. The same guarded lifecycle still occurs.
            transitionTo(HotPotatoPhase.COUNTDOWN);
            transitionTo(HotPotatoPhase.ENTRY_LOCKED);
            livePlayers.clear();
            livePlayers.addAll(roster);
        } else {
            requirePhase(HotPotatoPhase.ENTRY_LOCKED, "start");
            requireOperation(operation);
            acceptOperation(operation);
        }
        if (livePlayers.size() < minimumPlayers) {
            throw new IllegalStateException("not enough live players to start");
        }
        transitionTo(HotPotatoPhase.RUNNING);
        carrier = choosePlayer(livePlayers);
        startedAt = now;
        carrierSince = now;
        fuseDeadline = now.plus(fuseDuration);
    }

    public synchronized void enterSuddenDeath(Instant now, OperationId operation) {
        Objects.requireNonNull(now, "now");
        requirePhase(HotPotatoPhase.RUNNING, "enterSuddenDeath");
        if (livePlayers.size() < 2) {
            throw new IllegalStateException("sudden death requires at least two live players");
        }
        requireOperation(operation);
        acceptOperation(operation);
        transitionTo(HotPotatoPhase.SUDDEN_DEATH);
        fuseDeadline = now.plus(suddenDeathFuseDuration);
    }

    /**
     * Attempts a pass from the current carrier to a live target. The caller
     * must provide the source carrier so delayed hit events cannot pass from a
     * player who no longer holds the potato.
     */
    public synchronized void pass(UUID source, UUID target, Instant now, OperationId operation) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, "now");
        if (phase != HotPotatoPhase.RUNNING && phase != HotPotatoPhase.SUDDEN_DEATH) {
            throw new IllegalStateException("pass is only allowed during a match");
        }
        requireOperation(operation);
        if (!source.equals(carrier)) {
            throw new IllegalStateException("source is not the current carrier");
        }
        if (source.equals(target)) {
            throw new IllegalArgumentException("carrier cannot pass to itself");
        }
        if (!livePlayers.contains(source) || !livePlayers.contains(target)) {
            throw new IllegalStateException("both players must be live");
        }
        DirectedPair forward = new DirectedPair(source, target);
        DirectedPair reverse = new DirectedPair(target, source);
        if (isWithinCooldown(recentPasses.get(forward), now)
                || isWithinCooldown(recentPasses.get(reverse), now)) {
            throw new IllegalStateException("pass is within the immediate-return cooldown");
        }
        acceptOperation(operation);
        recentPasses.put(forward, now);
        recordCarrierTime(now);
        passes++;
        passesByPlayer.merge(source, 1, Integer::sum);
        carrierSince = now;
        carrier = target;
    }

    /** Validated adapter form enforcing range and line-of-sight before mutation. */
    public synchronized void pass(PassIntent intent, Instant now, OperationId operation) {
        Objects.requireNonNull(intent, "intent");
        if (intent.distance() > passRange) throw new IllegalStateException("pass is outside configured range");
        pass(intent.source(), intent.target(), now, operation);
    }

    /** Eliminates a player for a controlled disconnect/death/forfeit path. */
    public synchronized void eliminate(UUID player, Instant now, OperationId operation) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(now, "now");
        if (phase != HotPotatoPhase.RUNNING && phase != HotPotatoPhase.SUDDEN_DEATH) {
            throw new IllegalStateException("elimination is only allowed during a match");
        }
        requireOperation(operation);
        if (!livePlayers.contains(player)) {
            throw new IllegalStateException("player is not live");
        }
        if (livePlayers.size() == 1) {
            throw new IllegalStateException("final player cannot be eliminated without an explicit no-contest");
        }
        acceptOperation(operation);
        eliminations++;
        eliminationsByPlayer.merge(player, 1, Integer::sum);
        if (startedAt != null) survivalTimeByPlayer.put(player, Duration.between(startedAt, now));
        recordCarrierTime(now);
        livePlayers.remove(player);
        if (player.equals(carrier)) {
            carrier = livePlayers.isEmpty() ? null : choosePlayer(livePlayers);
            carrierSince = carrier == null ? null : now;
        }
        if (livePlayers.size() == 1) {
            finish(now);
        } else {
            fuseDeadline = now.plus(nextFuseDuration());
        }
    }

    /** Expires the fuse and eliminates the current carrier when the deadline is reached. */
    public synchronized void expireFuse(Instant now, OperationId operation) {
        Objects.requireNonNull(now, "now");
        if (phase != HotPotatoPhase.RUNNING && phase != HotPotatoPhase.SUDDEN_DEATH) {
            throw new IllegalStateException("fuse is not active");
        }
        if (fuseDeadline == null || now.isBefore(fuseDeadline)) {
            throw new IllegalStateException("fuse has not expired");
        }
        UUID expiredCarrier = carrier;
        if (expiredCarrier == null) {
            throw new IllegalStateException("active match has no carrier");
        }
        eliminate(expiredCarrier, now, operation);
    }

    /** Disconnect/AFK/escape path with an explicit forfeit metric. */
    public synchronized void forfeit(UUID player, Instant now, OperationId operation) {
        eliminate(player, now, operation);
        forfeits++;
        forfeitsByPlayer.merge(player, 1, Integer::sum);
    }

    /** Ends a match without a winner; adapters must restore all snapshots. */
    public synchronized void cancel(Instant now, String reason, OperationId operation) {
        Objects.requireNonNull(now, "now"); Objects.requireNonNull(reason, "reason");
        if (phase == HotPotatoPhase.CLOSED || phase == HotPotatoPhase.DISABLED || phase == HotPotatoPhase.IDLE)
            throw new IllegalStateException("cancel is not active");
        requireOperation(operation); acceptOperation(operation);
        outcome = new HotPotatoOutcome(matchId, "CANCELLED", roster, now, boundedReason(reason));
        transitionTo(HotPotatoPhase.FINISHING); livePlayers.clear(); carrier = null; fuseDeadline = null;
    }

    public synchronized void noContest(Instant now, String reason, OperationId operation) {
        cancel(now, reason, operation);
        outcome = new HotPotatoOutcome(matchId, "NO_CONTEST", roster, now, boundedReason(reason));
    }

    public synchronized boolean timedOut(Instant now) {
        return startedAt != null && !now.isBefore(startedAt.plus(matchTimeout));
    }

    /** Marks an interrupted active lifecycle for fail-closed recovery. */
    public synchronized void recover(OperationId operation) {
        requireOperation(operation);
        boolean earlyRecovery = phase == HotPotatoPhase.WAITING || phase == HotPotatoPhase.COUNTDOWN;
        if (phase != HotPotatoPhase.ENTRY_LOCKED
                && phase != HotPotatoPhase.WAITING
                && phase != HotPotatoPhase.COUNTDOWN
                && phase != HotPotatoPhase.RUNNING
                && phase != HotPotatoPhase.SUDDEN_DEATH
                && phase != HotPotatoPhase.FINISHING
                && phase != HotPotatoPhase.RESTORING) {
            throw new IllegalStateException("phase cannot enter recovery");
        }
        acceptOperation(operation);
        transitionTo(HotPotatoPhase.RECOVERING);
        if (result == null) {
            // An interrupted match has no winner. The adapter will restore
            // snapshots; the domain must not leave stale live-player state
            // blocking completion of that recovery.
            livePlayers.clear();
            carrier = null;
            fuseDeadline = null;
            if (earlyRecovery) {
                // Queue/countdown players have not entered the arena, so
                // there is no match roster to restore or score.
                roster.clear();
            }
        }
    }

    public synchronized void beginRestore(OperationId operation) {
        if (phase != HotPotatoPhase.RECOVERING && phase != HotPotatoPhase.FINISHING) {
            throw new IllegalStateException("beginRestore is not legal from " + phase);
        }
        transition(operation, HotPotatoPhase.RESTORING);
    }

    public synchronized void completeRestore(OperationId operation) {
        requirePhase(HotPotatoPhase.RESTORING, "completeRestore");
        requireOperation(operation);
        if (result == null && !livePlayers.isEmpty()) {
            throw new IllegalStateException("cannot complete restore before a result or abort is recorded");
        }
        acceptOperation(operation);
        transitionTo(HotPotatoPhase.CLOSED);
        carrier = null;
        fuseDeadline = null;
        livePlayers.clear();
    }

    private void finish(Instant now) {
        transitionTo(HotPotatoPhase.FINISHING);
        recordCarrierTime(now);
        UUID winner = livePlayers.iterator().next();
        if (startedAt != null) survivalTimeByPlayer.put(winner, Duration.between(startedAt, now));
        result = new MatchResult(matchId, winner, roster, now);
        carrier = winner;
        fuseDeadline = null;
    }

    private Instant startedAt;
    private void recordCarrierTime(Instant now) {
        if (carrierSince != null && carrier != null) {
            Duration elapsed = Duration.between(carrierSince, now);
            if (elapsed.compareTo(longestCarrierTime) > 0) longestCarrierTime = elapsed;
            carrierTimeByPlayer.merge(carrier, elapsed, Duration::plus);
            // Close this accounting segment even when another player was
            // eliminated. Otherwise a later pass/explosion would count the
            // already-recorded interval for the unchanged carrier again.
            carrierSince = now;
        }
    }
    private static String boundedReason(String reason) {
        String value = reason.trim();
        if (value.isEmpty() || value.length() > 64 || !value.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("reason must be bounded");
        return value;
    }

    private void transition(OperationId operation, HotPotatoPhase next) {
        requireOperation(operation);
        acceptOperation(operation);
        transitionTo(next);
    }

    private void mutate(OperationId operation, HotPotatoPhase next, String action) {
        // Validate idempotency before phase legality so replaying an already
        // accepted operation is always reported as a duplicate, even if the
        // phase has since advanced.
        requireOperation(operation);
        if (!LEGAL_TRANSITIONS.getOrDefault(phase, Set.of()).contains(next)) {
            throw new IllegalStateException(action + " is not legal from " + phase);
        }
        acceptOperation(operation);
        transitionTo(next);
    }

    private void requirePhase(HotPotatoPhase expected, String action) {
        if (phase != expected) {
            throw new IllegalStateException(action + " is not legal from " + phase);
        }
    }

    private void transitionTo(HotPotatoPhase next) {
        if (!LEGAL_TRANSITIONS.getOrDefault(phase, Set.of()).contains(next)) {
            throw new IllegalStateException("transition " + phase + " -> " + next + " is not legal");
        }
        phase = next;
    }

    private void requireOperation(OperationId operation) {
        Objects.requireNonNull(operation, "operation");
        if (!matchId.equals(operation.matchId())) {
            throw new StaleOperationException(operation);
        }
        if (acceptedOperations.contains(operation)) {
            throw new DuplicateOperationException(operation);
        }
        if (operation.sequence() <= lastOperationSequence) {
            throw new StaleOperationException(operation);
        }
    }

    private void acceptOperation(OperationId operation) {
        acceptedOperations.add(operation);
        lastOperationSequence = operation.sequence();
    }

    private UUID choosePlayer(Set<UUID> players) {
        return new ArrayList<>(players).get(random.nextInt(players.size()));
    }

    private boolean isWithinCooldown(Instant previous, Instant now) {
        return previous != null && now.isBefore(previous.plus(passCooldown));
    }

    private Duration nextFuseDuration() {
        if (phase == HotPotatoPhase.SUDDEN_DEATH) return suddenDeathFuseDuration;
        Duration reduction = fuseReductionPerElimination.multipliedBy(eliminations);
        Duration candidate = reduction.compareTo(fuseDuration) >= 0
                ? Duration.ZERO : fuseDuration.minus(reduction);
        return candidate.compareTo(suddenDeathFuseDuration) < 0
                ? suddenDeathFuseDuration : candidate;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static Map<HotPotatoPhase, Set<HotPotatoPhase>> legalTransitions() {
        EnumMap<HotPotatoPhase, Set<HotPotatoPhase>> transitions = new EnumMap<>(HotPotatoPhase.class);
        transitions.put(HotPotatoPhase.DISABLED, Set.of(HotPotatoPhase.IDLE, HotPotatoPhase.CLOSED));
        transitions.put(HotPotatoPhase.IDLE, Set.of(HotPotatoPhase.WAITING, HotPotatoPhase.DISABLED, HotPotatoPhase.CLOSED));
        transitions.put(HotPotatoPhase.WAITING, Set.of(HotPotatoPhase.COUNTDOWN, HotPotatoPhase.RECOVERING, HotPotatoPhase.CLOSED));
        transitions.put(HotPotatoPhase.COUNTDOWN, Set.of(HotPotatoPhase.ENTRY_LOCKED, HotPotatoPhase.WAITING, HotPotatoPhase.RECOVERING, HotPotatoPhase.CLOSED));
        transitions.put(HotPotatoPhase.ENTRY_LOCKED, Set.of(HotPotatoPhase.RUNNING, HotPotatoPhase.RECOVERING, HotPotatoPhase.CLOSED));
        transitions.put(HotPotatoPhase.RUNNING, Set.of(HotPotatoPhase.SUDDEN_DEATH, HotPotatoPhase.FINISHING, HotPotatoPhase.RECOVERING));
        transitions.put(HotPotatoPhase.SUDDEN_DEATH, Set.of(HotPotatoPhase.FINISHING, HotPotatoPhase.RECOVERING));
        transitions.put(HotPotatoPhase.FINISHING, Set.of(HotPotatoPhase.RESTORING, HotPotatoPhase.RECOVERING));
        transitions.put(HotPotatoPhase.RESTORING, Set.of(HotPotatoPhase.CLOSED, HotPotatoPhase.RECOVERING));
        transitions.put(HotPotatoPhase.RECOVERING, Set.of(HotPotatoPhase.RESTORING, HotPotatoPhase.CLOSED));
        transitions.put(HotPotatoPhase.CLOSED, Set.of());
        return Map.copyOf(transitions);
    }

    private record DirectedPair(UUID source, UUID target) {
    }

    public static class StaleOperationException extends IllegalStateException {
        public StaleOperationException(OperationId operation) {
            super("stale operation: " + operation);
        }
    }

    public static class DuplicateOperationException extends IllegalStateException {
        public DuplicateOperationException(OperationId operation) {
            super("duplicate operation: " + operation);
        }
    }
}
