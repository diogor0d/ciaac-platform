package com.ciaac.minecraft.minigames.buildbattle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure domain state for the one Build Battle instance.
 *
 * <p>All mutating methods are synchronized so a Paper adapter cannot race a
 * command, event, or persistence callback into a second transition. World,
 * inventory, authentication, and display integrations deliberately do not
 * belong here.</p>
 */
public final class BuildBattleMatch {
    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);
    private static final Comparator<BuildBattlePlot> PLOT_ORDER = Comparator.comparing(BuildBattlePlot::id);

    private final BuildBattleConfig config;
    private final List<BuildBattlePlot> plots;
    private BuildBattlePhase phase = BuildBattlePhase.IDLE;
    private BuildBattleTheme selectedTheme;
    private List<BuildBattleTheme> themeOptions = List.of();
    private final LinkedHashMap<UUID, BuildBattleTheme> themeVotes = new LinkedHashMap<>();
    private boolean themeLocked;
    private UUID matchId;
    private final LinkedHashSet<UUID> roster = new LinkedHashSet<>();
    private List<BuildBattlePlotAssignment> assignments = List.of();
    private final LinkedHashMap<BuildBattlePlot, UUID> ownerByPlot = new LinkedHashMap<>();
    private final LinkedHashMap<BallotKey, BuildBattleBallot> ballots = new LinkedHashMap<>();
    private BuildBattleResult finalizedResult;
    private BuildBattleResult lastResult;
    private final Set<BuildBattleOperationId> acceptedOperations = new HashSet<>();

    public BuildBattleMatch(BuildBattleConfig config, List<BuildBattlePlot> plots) {
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(plots, "plots");
        if (plots.size() < config.maximumPlayers()) {
            throw new IllegalArgumentException("at least maximumPlayers plots are required");
        }
        List<BuildBattlePlot> orderedPlots = new ArrayList<>(plots);
        orderedPlots.sort(PLOT_ORDER);
        if (new HashSet<>(orderedPlots).size() != orderedPlots.size()) {
            throw new IllegalArgumentException("plot ids must be unique");
        }
        this.plots = List.copyOf(orderedPlots);
        selectedTheme = config.theme();
        themeLocked = true;
    }

    public synchronized BuildBattlePhase phase() {
        return phase;
    }

    public synchronized UUID matchId() {
        return matchId;
    }

    public synchronized BuildBattleTheme theme() {
        return selectedTheme;
    }

    public synchronized List<BuildBattleTheme> themeOptions() {
        return themeOptions;
    }

    public synchronized boolean themeLocked() {
        return themeLocked;
    }

    public synchronized boolean hasThemeVote(UUID voter) {
        return themeVotes.containsKey(Objects.requireNonNull(voter, "voter"));
    }

    public synchronized int themeVoteCount(BuildBattleTheme theme) {
        Objects.requireNonNull(theme, "theme");
        return (int) themeVotes.values().stream()
                .filter(value -> value.id().equals(theme.id()))
                .count();
    }

    public synchronized Set<UUID> roster() {
        return Set.copyOf(roster);
    }

    public synchronized List<BuildBattlePlotAssignment> assignments() {
        return assignments;
    }

    public synchronized int ballotCount() {
        return ballots.size();
    }

    public synchronized BuildBattleResult finalizedResult() {
        return finalizedResult;
    }

    public synchronized BuildBattleResult lastResult() {
        return lastResult;
    }

    /** Opens the waiting room and creates the match identity. */
    public synchronized void openWaiting() {
        openWaiting(UUID.randomUUID());
    }

    /** Opens a waiting room with a supplied identity for durable recovery/tests. */
    public synchronized void openWaiting(UUID suppliedMatchId) {
        requirePhase(BuildBattlePhase.IDLE, "open waiting room");
        matchId = Objects.requireNonNull(suppliedMatchId, "suppliedMatchId");
        roster.clear();
        assignments = List.of();
        ownerByPlot.clear();
        ballots.clear();
        finalizedResult = null;
        selectedTheme = config.theme();
        themeOptions = List.of();
        themeVotes.clear();
        themeLocked = true;
        phase = BuildBattlePhase.WAITING;
    }

    /** Adds an authenticated UUID while the waiting room is open. */
    public synchronized boolean join(UUID player) {
        requirePhase(BuildBattlePhase.WAITING, "join");
        Objects.requireNonNull(player, "player");
        if (roster.contains(player) || roster.size() >= config.maximumPlayers()) {
            return false;
        }
        roster.add(player);
        return true;
    }

    public synchronized boolean join(UUID player, BuildBattleOperationId operation) {
        validateOperation(operation);
        boolean joined = join(player);
        if (joined) acceptOperation(operation);
        return joined;
    }

    /** Removes a player before the countdown freezes the roster. */
    public synchronized boolean leave(UUID player) {
        requirePhase(BuildBattlePhase.WAITING, "leave");
        return roster.remove(Objects.requireNonNull(player, "player"));
    }

    public synchronized boolean leave(UUID player, BuildBattleOperationId operation) {
        validateOperation(operation);
        boolean left = leave(player);
        if (left) acceptOperation(operation);
        return left;
    }

    /** Cancels an empty waiting room; a populated room must use recovery. */
    public synchronized void cancelWaiting() {
        requirePhase(BuildBattlePhase.WAITING, "cancel waiting room");
        if (!roster.isEmpty()) {
            throw new IllegalStateException("cannot cancel a populated waiting room");
        }
        clearCurrentState();
        phase = BuildBattlePhase.IDLE;
    }

    /** Freezes the roster. No join or leave operation is legal after this point. */
    public synchronized void beginThemeVoting(List<BuildBattleTheme> options) {
        requirePhase(BuildBattlePhase.WAITING, "begin theme voting");
        if (roster.size() < config.minimumPlayers()) {
            throw new IllegalStateException("minimum player count has not been reached");
        }
        Objects.requireNonNull(options, "options");
        if (options.size() != 3) {
            throw new IllegalArgumentException("Build Battle needs exactly three theme options");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (BuildBattleTheme option : options) {
            Objects.requireNonNull(option, "theme option");
            if (!ids.add(option.id())) {
                throw new IllegalArgumentException("theme option ids must be unique");
            }
        }
        themeOptions = List.copyOf(options);
        themeVotes.clear();
        selectedTheme = config.theme();
        themeLocked = false;
        phase = BuildBattlePhase.THEME_VOTING;
    }

    /** Locks the deterministic plurality winner; ties use option order. */
    public synchronized BuildBattleTheme lockTheme() {
        requirePhase(BuildBattlePhase.THEME_VOTING, "lock theme");
        BuildBattleTheme winner = themeOptions.get(0);
        int highest = -1;
        for (BuildBattleTheme option : themeOptions) {
            int votes = 0;
            for (BuildBattleTheme selected : themeVotes.values()) {
                if (selected.id().equals(option.id())) votes++;
            }
            if (votes > highest) {
                highest = votes;
                winner = option;
            }
        }
        selectedTheme = winner;
        themeLocked = true;
        return winner;
    }

    public synchronized void beginCountdown() {
        if (phase == BuildBattlePhase.WAITING) {
            if (roster.size() < config.minimumPlayers()) {
                throw new IllegalStateException("minimum player count has not been reached");
            }
        } else if (phase == BuildBattlePhase.THEME_VOTING) {
            if (!themeLocked) throw new IllegalStateException("theme has not been locked");
        } else {
            throw new IllegalStateException("begin countdown is not legal from " + phase);
        }
        phase = BuildBattlePhase.COUNTDOWN;
    }

    public synchronized void voteTheme(UUID voter, BuildBattleTheme theme, BuildBattleOperationId operation) {
        validateOperation(operation);
        requirePhase(BuildBattlePhase.THEME_VOTING, "vote theme");
        Objects.requireNonNull(voter, "voter");
        Objects.requireNonNull(theme, "theme");
        if (!roster.contains(voter)) throw new IllegalArgumentException("voter is not in the frozen roster");
        boolean known = themeOptions.stream().anyMatch(option -> option.id().equals(theme.id()));
        if (!known) throw new IllegalArgumentException("theme is not one of the offered options");
        themeVotes.put(voter, themeOptions.stream()
                .filter(option -> option.id().equals(theme.id()))
                .findFirst().orElseThrow());
        acceptOperation(operation);
    }

    /** Assigns plots in UUID order, making the assignment deterministic. */
    public synchronized void beginBuilding() {
        requirePhase(BuildBattlePhase.COUNTDOWN, "begin building");
        List<UUID> orderedPlayers = new ArrayList<>(roster);
        orderedPlayers.sort(UUID_ORDER);
        List<BuildBattlePlotAssignment> nextAssignments = new ArrayList<>(orderedPlayers.size());
        ownerByPlot.clear();
        for (int index = 0; index < orderedPlayers.size(); index++) {
            BuildBattlePlot plot = plots.get(index);
            UUID owner = orderedPlayers.get(index);
            nextAssignments.add(new BuildBattlePlotAssignment(plot, owner));
            ownerByPlot.put(plot, owner);
        }
        assignments = List.copyOf(nextAssignments);
        phase = BuildBattlePhase.BUILDING;
    }

    public synchronized void beginReview() {
        requirePhase(BuildBattlePhase.BUILDING, "begin review");
        phase = BuildBattlePhase.REVIEWING;
    }

    public synchronized void beginVoting() {
        if (phase != BuildBattlePhase.BUILDING && phase != BuildBattlePhase.REVIEWING) {
            throw new IllegalStateException("begin voting is not legal from " + phase);
        }
        phase = BuildBattlePhase.VOTING;
    }

    /** Records one immutable ballot per voter and plot. */
    public synchronized void castVote(UUID voter, BuildBattlePlot plot, int score) {
        requirePhase(BuildBattlePhase.VOTING, "cast vote");
        Objects.requireNonNull(voter, "voter");
        Objects.requireNonNull(plot, "plot");
        if (!roster.contains(voter)) {
            throw new IllegalArgumentException("voter is not in the frozen roster");
        }
        UUID owner = ownerByPlot.get(plot);
        if (owner == null) {
            throw new IllegalArgumentException("plot is not assigned in this match");
        }
        if (voter.equals(owner)) {
            throw new IllegalArgumentException("players cannot vote for their own plot");
        }
        if (score < config.minimumVote() || score > config.maximumVote()) {
            throw new IllegalArgumentException("vote score is outside configured bounds");
        }
        BallotKey key = new BallotKey(voter, plot);
        if (ballots.containsKey(key)) {
            throw new IllegalArgumentException("voter has already voted for this plot");
        }
        ballots.put(key, new BuildBattleBallot(voter, plot, score));
    }

    public synchronized void castVote(UUID voter, BuildBattlePlot plot, int score, BuildBattleOperationId operation) {
        validateOperation(operation);
        castVote(voter, plot, score);
        acceptOperation(operation);
    }

    /** Disconnects a participant and enters fail-closed recovery; no partial result is ranked. */
    public synchronized void disconnect(UUID player, BuildBattleOperationId operation) {
        validateOperation(operation); Objects.requireNonNull(player, "player");
        if (phase == BuildBattlePhase.WAITING) { roster.remove(player); acceptOperation(operation); return; }
        if (phase == BuildBattlePhase.IDLE || phase == BuildBattlePhase.CLOSED
                || phase == BuildBattlePhase.RESETTING || phase == BuildBattlePhase.RECOVERING) {
            throw new IllegalStateException("disconnect is not active from " + phase);
        }
        if (!roster.contains(player)) throw new IllegalArgumentException("player is not in the roster");
        beginRecovery();
        acceptOperation(operation);
    }

    public synchronized void beginResults() {
        requirePhase(BuildBattlePhase.VOTING, "begin results");
        requireVotingComplete();
        phase = BuildBattlePhase.RESULTS;
    }

    /**
     * Finalizes once. Repeated calls in RESULTS return the same immutable
     * result, which makes persistence retries idempotent.
     */
    public synchronized BuildBattleResult finalizeResult() {
        requirePhase(BuildBattlePhase.RESULTS, "finalize result");
        if (finalizedResult != null) {
            return finalizedResult;
        }
        Map<BuildBattlePlot, MutableScore> totals = new LinkedHashMap<>();
        for (BuildBattlePlotAssignment assignment : assignments) {
            totals.put(assignment.plot(), new MutableScore());
        }
        for (BuildBattleBallot ballot : ballots.values()) {
            MutableScore score = totals.get(ballot.plot());
            score.total += ballot.score();
            score.votes++;
        }
        BuildBattlePlot winner = assignments.stream()
                .map(BuildBattlePlotAssignment::plot)
                .min((left, right) -> comparePlots(totals.get(left), totals.get(right), left, right,
                        config.tiePolicy()))
                .orElseThrow(() -> new IllegalStateException("cannot finalize without plots"));

        LinkedHashMap<BuildBattlePlot, BuildBattleScore> scores = new LinkedHashMap<>();
        for (BuildBattlePlotAssignment assignment : assignments) {
            MutableScore score = totals.get(assignment.plot());
            scores.put(assignment.plot(), new BuildBattleScore(assignment.plot(), score.total, score.votes));
        }
        finalizedResult = new BuildBattleResult(matchId, selectedTheme, winner, assignments, scores,
                config.tiePolicy());
        return finalizedResult;
    }

    /** Results must be finalized before the disposable world can be reset. */
    public synchronized void beginResetting() {
        requirePhase(BuildBattlePhase.RESULTS, "begin reset");
        if (finalizedResult == null) {
            throw new IllegalStateException("result must be finalized before reset");
        }
        phase = BuildBattlePhase.RESETTING;
    }

    public synchronized void completeReset() {
        requirePhase(BuildBattlePhase.RESETTING, "complete reset");
        lastResult = finalizedResult;
        clearCurrentState();
        phase = BuildBattlePhase.IDLE;
    }

    /** Enters fail-closed recovery from any active phase. */
    public synchronized void beginRecovery() {
        if (phase == BuildBattlePhase.IDLE || phase == BuildBattlePhase.RECOVERING || phase == BuildBattlePhase.CLOSED) {
            throw new IllegalStateException("recovery is not legal from " + phase);
        }
        phase = BuildBattlePhase.RECOVERING;
    }

    public synchronized void completeRecovery() {
        requirePhase(BuildBattlePhase.RECOVERING, "complete recovery");
        if (finalizedResult != null) {
            lastResult = finalizedResult;
        }
        clearCurrentState();
        phase = BuildBattlePhase.IDLE;
    }

    /** Closing is terminal and is legal only after recovery/reset is complete. */
    public synchronized void close() {
        requirePhase(BuildBattlePhase.IDLE, "close instance");
        phase = BuildBattlePhase.CLOSED;
    }

    public synchronized UUID ownerOf(BuildBattlePlot plot) {
        return ownerByPlot.get(Objects.requireNonNull(plot, "plot"));
    }

    public synchronized BuildBattlePlot plotOf(UUID player) {
        UUID requested = Objects.requireNonNull(player, "player");
        return assignments.stream()
                .filter(assignment -> assignment.owner().equals(requested))
                .map(BuildBattlePlotAssignment::plot)
                .findFirst()
                .orElse(null);
    }

    private void clearCurrentState() {
        matchId = null;
        roster.clear();
        assignments = List.of();
        ownerByPlot.clear();
        ballots.clear();
        themeOptions = List.of();
        themeVotes.clear();
        selectedTheme = config.theme();
        themeLocked = false;
        finalizedResult = null;
        acceptedOperations.clear();
    }

    private void requirePhase(BuildBattlePhase expected, String operation) {
        if (phase != expected) {
            throw new IllegalStateException(operation + " is not legal from " + phase + "; expected " + expected);
        }
    }

    private void requireVotingComplete() {
        if (config.votingCompletionPolicy()
                != BuildBattleVotingCompletionPolicy.EVERY_ELIGIBLE_VOTER_RATES_EVERY_OTHER_PLOT) {
            throw new IllegalStateException("unsupported voting completion policy: "
                    + config.votingCompletionPolicy());
        }
        long expectedBallots = (long) roster.size() * (roster.size() - 1);
        if (ballots.size() != expectedBallots) {
            throw new IllegalStateException("every eligible voter must rate every other plot before results");
        }
        for (UUID voter : roster) {
            for (BuildBattlePlotAssignment assignment : assignments) {
                if (!voter.equals(assignment.owner())
                        && !ballots.containsKey(new BallotKey(voter, assignment.plot()))) {
                    throw new IllegalStateException("every eligible voter must rate every other plot before results");
                }
            }
        }
    }

    private void acceptOperation(BuildBattleOperationId operation) {
        validateOperation(operation);
        acceptedOperations.add(operation);
    }

    private void validateOperation(BuildBattleOperationId operation) {
        Objects.requireNonNull(operation, "operation");
        if (matchId == null || !matchId.equals(operation.matchId())) throw new IllegalArgumentException("operation is for another match");
        if (acceptedOperations.contains(operation)) throw new IllegalStateException("duplicate operation");
    }

    private static int comparePlots(MutableScore left, MutableScore right,
                                    BuildBattlePlot leftPlot, BuildBattlePlot rightPlot,
                                    BuildBattleTiePolicy tiePolicy) {
        return switch (tiePolicy) {
            case AVERAGE_THEN_TOTAL_THEN_PLOT_ID -> compareAverageTotalAndPlotId(
                    left, right, leftPlot, rightPlot);
        };
    }

    private static int compareAverageTotalAndPlotId(MutableScore left, MutableScore right,
                                                     BuildBattlePlot leftPlot, BuildBattlePlot rightPlot) {
        long leftCross = left.total * right.votes;
        long rightCross = right.total * left.votes;
        int average = Long.compare(rightCross, leftCross);
        if (average != 0) {
            return average;
        }
        int total = Long.compare(right.total, left.total);
        if (total != 0) {
            return total;
        }
        return leftPlot.id().compareTo(rightPlot.id());
    }

    private record BallotKey(UUID voter, BuildBattlePlot plot) {
    }

    private static final class MutableScore {
        private long total;
        private int votes;
    }
}
