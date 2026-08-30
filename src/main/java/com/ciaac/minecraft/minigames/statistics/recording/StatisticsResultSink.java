package com.ciaac.minecraft.minigames.statistics.recording;

import com.ciaac.minecraft.minigames.statistics.MatchResult;
import com.ciaac.minecraft.minigames.statistics.StatisticsRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Fail-closed adapter from terminal game results to the durable statistics
 * repository.
 *
 * <p>The repository remains the source of truth. A successful result is kept
 * in a small process-local cache only to avoid duplicate calls from repeated
 * ticks; a restart is safe because the repository's result-id contract makes
 * the retry idempotent.</p>
 */
public final class StatisticsResultSink {
    private final Optional<StatisticsRepository> repository;
    private final Consumer<MatchResult> committedCallback;
    private final Consumer<ResultRecording> failureCallback;
    private final Map<UUID, MatchResult> committed = new HashMap<>();

    public StatisticsResultSink(StatisticsRepository repository) {
        this(Optional.of(Objects.requireNonNull(repository, "repository")), ignored -> { }, ignored -> { });
    }

    public StatisticsResultSink(
            StatisticsRepository repository, Consumer<MatchResult> committedCallback) {
        this(Optional.of(Objects.requireNonNull(repository, "repository")), committedCallback, ignored -> { });
    }

    public StatisticsResultSink(
            StatisticsRepository repository,
            Consumer<MatchResult> committedCallback,
            Consumer<ResultRecording> failureCallback) {
        this(Optional.of(Objects.requireNonNull(repository, "repository")), committedCallback, failureCallback);
    }

    public StatisticsResultSink(Optional<StatisticsRepository> repository) {
        this(repository, ignored -> { }, ignored -> { });
    }

    public StatisticsResultSink(
            Optional<StatisticsRepository> repository, Consumer<MatchResult> committedCallback) {
        this(repository, committedCallback, ignored -> { });
    }

    public StatisticsResultSink(
            Optional<StatisticsRepository> repository,
            Consumer<MatchResult> committedCallback,
            Consumer<ResultRecording> failureCallback) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.committedCallback = Objects.requireNonNull(committedCallback, "committedCallback");
        this.failureCallback = Objects.requireNonNull(failureCallback, "failureCallback");
    }

    /** Creates a sink that refuses to claim a durable commit. */
    public static StatisticsResultSink unavailable() {
        return new StatisticsResultSink(Optional.empty());
    }

    public boolean available() {
        return repository.isPresent();
    }

    /**
     * Applies one immutable result. Result identities are derived from the
     * match and arbitrary/random identities are rejected before persistence.
     */
    public synchronized ResultRecording record(MatchResult result) {
        Objects.requireNonNull(result, "result");
        UUID expected = ResultIds.forMatch(result.matchId());
        if (!expected.equals(result.resultId())) {
            throw new IllegalArgumentException("Result identity must be derived from its match");
        }
        MatchResult previous = committed.get(result.resultId());
        if (previous != null) {
            if (!previous.equals(result)) {
                throw new IllegalStateException("Conflicting result replay " + result.resultId());
            }
            return new ResultRecording(ResultRecording.Status.IDEMPOTENT_REPLAY,
                    result.resultId(), "ALREADY_RECORDED");
        }
        StatisticsRepository target = repository.orElse(null);
        if (target == null) {
            return failed(ResultRecording.Status.UNAVAILABLE, result.resultId(), "STATISTICS_UNAVAILABLE");
        }
        try {
            boolean inserted = target.record(result);
            if (inserted) {
                committed.put(result.resultId(), result);
                try {
                    committedCallback.accept(result);
                } catch (RuntimeException ignored) {
                    // Statistics are authoritative; optional announcements may retry independently.
                }
            } else {
                // A repository replay is already durable; do not re-emit side effects
                // after a process restart.
                committed.put(result.resultId(), result);
            }
            return new ResultRecording(
                    inserted ? ResultRecording.Status.RECORDED : ResultRecording.Status.IDEMPOTENT_REPLAY,
                    result.resultId(), inserted ? "RECORDED" : "ALREADY_RECORDED");
        } catch (IllegalStateException failure) {
            return failed(ResultRecording.Status.FAILED, result.resultId(), "STATISTICS_CONFLICT");
        } catch (RuntimeException failure) {
            return failed(ResultRecording.Status.FAILED, result.resultId(), "STATISTICS_FAILURE");
        }
    }

    private ResultRecording failed(ResultRecording.Status status, UUID resultId, String code) {
        ResultRecording recording = new ResultRecording(status, resultId, code);
        try {
            failureCallback.accept(recording);
        } catch (RuntimeException ignored) {
            // Observability must never change the durable result outcome.
        }
        return recording;
    }
}
