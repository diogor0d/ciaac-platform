package com.ciaac.minecraft.minigames.arena;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Pure domain state for one immutable-roster arena match. */
public final class ArenaMatch {
    private final UUID id;
    private final ArenaFormat format;
    private final ArenaKitMode kitMode;
    private final TeamRoster teamA;
    private final TeamRoster teamB;
    private final StakedEscrow stakedEscrow;
    private final Set<UUID> readyPlayers = new LinkedHashSet<>();
    private final Map<UUID, PlayerStateOperation> snapshotOperations = new LinkedHashMap<>();
    private final Map<UUID, PlayerStateOperation> restoreOperations = new LinkedHashMap<>();
    private final Map<UUID, ArenaAdmissionToken> admissionTokens = new LinkedHashMap<>();
    private final Map<UUID, String> forfeits = new LinkedHashMap<>();
    private final Map<UUID, String> eliminations = new LinkedHashMap<>();
    private ArenaPhase phase = ArenaPhase.IDLE;
    private UUID finalizationId;

    private ArenaMatch(UUID id, ArenaFormat format, ArenaKitMode kitMode, TeamRoster teamA, TeamRoster teamB,
                       StakedEscrow stakedEscrow) {
        this.id = Objects.requireNonNull(id, "id");
        this.format = Objects.requireNonNull(format, "format");
        this.kitMode = Objects.requireNonNull(kitMode, "kitMode");
        this.teamA = Objects.requireNonNull(teamA, "teamA");
        this.teamB = Objects.requireNonNull(teamB, "teamB");
        this.stakedEscrow = stakedEscrow;
        if (kitMode == ArenaKitMode.STAKED_SURVIVAL && stakedEscrow == null) {
            throw new IllegalArgumentException("Staked mode requires a consent-bound escrow");
        }
        if (kitMode != ArenaKitMode.STAKED_SURVIVAL && stakedEscrow != null) {
            throw new IllegalArgumentException("Only staked mode may carry an escrow");
        }
        if (teamA.players().size() != format.teamASize() || teamB.players().size() != format.teamBSize()) {
            throw new IllegalArgumentException("Rosters do not match the arena format");
        }
        if (teamA.players().stream().anyMatch(teamB.players()::contains)) {
            throw new IllegalArgumentException("A player cannot be on both teams");
        }
        if (stakedEscrow != null) {
            var rosterPlayers = new LinkedHashSet<UUID>();
            rosterPlayers.addAll(teamA.players());
            rosterPlayers.addAll(teamB.players());
            if (!stakedEscrow.matchId().equals(id) || !stakedEscrow.participants().equals(Set.copyOf(rosterPlayers))) {
                throw new IllegalArgumentException("Staked escrow must bind exactly to this match and roster");
            }
        }
    }

    public static ArenaMatch create(UUID id, ArenaFormat format, ArenaKitMode kitMode,
                                    TeamRoster teamA, TeamRoster teamB) {
        return new ArenaMatch(id, format, kitMode, teamA, teamB, null);
    }

    public static ArenaMatch create(UUID id, ArenaFormat format, ArenaKitMode kitMode,
                                    TeamRoster teamA, TeamRoster teamB, StakedEscrow stakedEscrow) {
        return new ArenaMatch(id, format, kitMode, teamA, teamB,
                Objects.requireNonNull(stakedEscrow, "stakedEscrow"));
    }

    public synchronized void transitionTo(ArenaPhase next) {
        Objects.requireNonNull(next, "next");
        if (next == ArenaPhase.CLOSED) {
            close();
            return;
        }
        if (!phase.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal arena transition: " + phase + " -> " + next);
        }
        if (next == ArenaPhase.ADMITTING) {
            requireAllReadyAndSnapshotted();
            if (stakedEscrow != null && !stakedEscrow.canAdmit()) {
                throw new IllegalStateException("Staked participants must mutually consent before admission");
            }
        }
        phase = next;
    }

    public synchronized boolean markReady(UUID playerId) {
        requireParticipant(playerId);
        if (phase != ArenaPhase.RESERVED_READY) {
            throw new IllegalStateException("Ready confirmation is only allowed during the ready check");
        }
        return readyPlayers.add(playerId);
    }

    public synchronized boolean recordSnapshot(PlayerStateOperation operation) {
        Objects.requireNonNull(operation, "operation");
        requireParticipant(operation.playerId());
        if (phase != ArenaPhase.RESERVED_READY && phase != ArenaPhase.ADMITTING) {
            throw new IllegalStateException("Snapshots are only recorded before or during admission");
        }
        return putEvidence(snapshotOperations, operation, "snapshot");
    }

    public synchronized boolean recordRestore(PlayerStateOperation operation) {
        Objects.requireNonNull(operation, "operation");
        requireParticipant(operation.playerId());
        if (phase != ArenaPhase.RESTORING && phase != ArenaPhase.RECOVERING) {
            throw new IllegalStateException("Restores are only recorded during restoration or recovery");
        }
        if (!snapshotOperations.containsKey(operation.playerId())) {
            throw new IllegalStateException("Cannot restore a player without snapshot evidence");
        }
        return putEvidence(restoreOperations, operation, "restore");
    }

    private boolean putEvidence(Map<UUID, PlayerStateOperation> evidence, PlayerStateOperation operation,
                                String kind) {
        PlayerStateOperation previous = evidence.putIfAbsent(operation.playerId(), operation);
        if (previous == null) return true;
        if (!previous.equals(operation)) {
            throw new IllegalStateException("Conflicting " + kind + " evidence for player " + operation.playerId());
        }
        return false;
    }

    /** Records a result exactly once; repeating the same identifier is a no-op. */
    public synchronized boolean finalizeOnce(UUID resultId) {
        Objects.requireNonNull(resultId, "resultId");
        if (finalizationId != null) {
            if (!finalizationId.equals(resultId)) {
                throw new IllegalStateException("Match already finalized with another identifier");
            }
            return false;
        }
        if (phase != ArenaPhase.FINISHING && phase != ArenaPhase.RECOVERING) {
            throw new IllegalStateException("A match can be finalized only while FINISHING or RECOVERING");
        }
        finalizationId = resultId;
        return true;
    }

    public synchronized ArenaAdmissionToken issueAdmissionToken(UUID playerId, UUID tokenId,
                                                                  Instant issuedAt, Instant expiresAt) {
        requireParticipant(playerId);
        if (phase != ArenaPhase.ADMITTING && phase != ArenaPhase.ACTIVE) {
            throw new IllegalStateException("Admission tokens are only issued for an admitted match");
        }
        if (!snapshotOperations.containsKey(playerId)) {
            throw new IllegalStateException("Admission requires snapshot evidence");
        }
        var token = new ArenaAdmissionToken(tokenId, id, playerId, issuedAt, expiresAt);
        admissionTokens.put(playerId, token);
        return token;
    }

    public synchronized boolean mayEnterFloor(ArenaAdmissionToken token, Instant now) {
        Objects.requireNonNull(token, "token");
        if (phase != ArenaPhase.ADMITTING && phase != ArenaPhase.ACTIVE) return false;
        if (!id.equals(token.matchId()) || !participants().contains(token.playerId())) return false;
        ArenaAdmissionToken current = admissionTokens.get(token.playerId());
        return token.equals(current) && token.isValidAt(now);
    }

    /** Controlled state cannot leave through external teleport during finishing/recovery. */
    public synchronized boolean allowsExternalEscape() {
        return phase != ArenaPhase.ADMITTING && phase != ArenaPhase.ACTIVE
                && phase != ArenaPhase.FINISHING && phase != ArenaPhase.RESTORING
                && phase != ArenaPhase.RECOVERING;
    }

    /**
     * Records one bounded forfeit. Team combat ends only after every member of
     * one side has been eliminated; a single 2v2/3v3 forfeit must not end the
     * match for the remaining team-mates.
     */
    public synchronized boolean forfeit(UUID playerId, String reason) {
        String normalized = normalizeOutcomeReason(reason, "Forfeit");
        boolean added = eliminate(playerId, normalized);
        String previous = forfeits.putIfAbsent(playerId, normalized);
        if (previous != null && !previous.equals(normalized)) {
            throw new IllegalStateException("Conflicting forfeit evidence");
        }
        return added && previous == null;
    }

    /** Records a controlled defeat without invoking Minecraft death drops. */
    public synchronized boolean eliminate(UUID playerId, String reason) {
        requireParticipant(playerId);
        String normalized = normalizeOutcomeReason(reason, "Elimination");
        if (phase != ArenaPhase.ACTIVE && phase != ArenaPhase.FINISHING) {
            throw new IllegalStateException("Eliminations are only recorded during combat");
        }
        String previous = eliminations.putIfAbsent(playerId, normalized);
        if (previous != null && !previous.equals(normalized)) {
            throw new IllegalStateException("Conflicting elimination evidence");
        }
        if (phase == ArenaPhase.ACTIVE && (teamADefeated() || teamBDefeated())) {
            transitionTo(ArenaPhase.FINISHING);
        }
        return previous == null;
    }

    /** Closes active combat as an explicit timer draw without inventing a winner. */
    public synchronized void finishTimedDraw() {
        if (phase != ArenaPhase.ACTIVE) {
            throw new IllegalStateException("A timer draw is legal only during active combat");
        }
        transitionTo(ArenaPhase.FINISHING);
    }

    /** Converts disconnects into recovery or an explicit combat forfeit. */
    public synchronized ArenaDisconnectDisposition handleDisconnect(UUID playerId) {
        requireParticipant(playerId);
        return switch (phase) {
            case RESERVED_READY, ADMITTING -> {
                transitionTo(ArenaPhase.RECOVERING);
                yield ArenaDisconnectDisposition.READY_CHECK_RECOVERY;
            }
            case ACTIVE -> {
                forfeit(playerId, "DISCONNECT");
                yield ArenaDisconnectDisposition.ACTIVE_FORFEIT;
            }
            case FINISHING, RESTORING, RECOVERING -> ArenaDisconnectDisposition.RESTORATION_RECOVERY;
            default -> ArenaDisconnectDisposition.IGNORED_TERMINAL;
        };
    }

    /** Completes restoration and makes this match terminal and releasable. */
    public synchronized void completeRestore() {
        if (phase != ArenaPhase.RESTORING) {
            throw new IllegalStateException("Restore completion is only allowed from RESTORING");
        }
        if (finalizationId == null) {
            throw new IllegalStateException("A match must have a committed finalization before closing");
        }
        if (!allRestored()) throw new IllegalStateException("Every snapshotted player needs restore evidence");
        if (stakedEscrow != null && !stakedEscrow.finalizationId().map(finalizationId::equals).orElse(false)) {
            throw new IllegalStateException("Staked escrow must finalize with the match result identifier");
        }
        requireEscrowTerminal();
        phase = ArenaPhase.CLOSED;
    }

    /** Closes a never-admitted reservation only when no snapshot is outstanding. */
    public synchronized void close() {
        if (phase == ArenaPhase.CLOSED) return;
        if (phase != ArenaPhase.WAITING && phase != ArenaPhase.RESERVED_READY) {
            throw new IllegalStateException("Only an unused reservation can close directly");
        }
        if (!snapshotOperations.isEmpty()) {
            throw new IllegalStateException("A match with snapshots cannot close before restoration");
        }
        if (stakedEscrow != null) {
            throw new IllegalStateException(
                    "A staked reservation requires an explicit result-bound recovery and refund");
        }
        requireEscrowTerminal();
        phase = ArenaPhase.CLOSED;
    }

    private void requireAllReadyAndSnapshotted() {
        if (!readyPlayers.containsAll(participants())) {
            throw new IllegalStateException("Every roster member must be ready");
        }
        if (!snapshotOperations.keySet().containsAll(participants())) {
            throw new IllegalStateException("Every roster member needs snapshot evidence");
        }
    }

    private void requireEscrowTerminal() {
        if (stakedEscrow != null && stakedEscrow.state() == StakedEscrow.SettlementState.OPEN) {
            throw new IllegalStateException("Staked escrow must be settled or refunded before close");
        }
    }

    private void requireParticipant(UUID playerId) {
        if (!participants().contains(Objects.requireNonNull(playerId, "playerId"))) {
            throw new IllegalArgumentException("Player is not in this match roster");
        }
    }

    public UUID id() { return id; }
    public ArenaFormat format() { return format; }
    public ArenaKitMode kitMode() { return kitMode; }
    public TeamRoster teamA() { return teamA; }
    public TeamRoster teamB() { return teamB; }
    public Optional<StakedEscrow> stakedEscrow() { return Optional.ofNullable(stakedEscrow); }

    public synchronized Set<UUID> participants() {
        var result = new LinkedHashSet<UUID>();
        result.addAll(teamA.players());
        result.addAll(teamB.players());
        return Set.copyOf(result);
    }

    public synchronized Set<UUID> readyPlayers() { return Set.copyOf(readyPlayers); }
    public synchronized Set<UUID> snapshottedPlayers() { return Set.copyOf(snapshotOperations.keySet()); }
    public synchronized Set<UUID> restoredPlayers() { return Set.copyOf(restoreOperations.keySet()); }
    public synchronized boolean allReady() { return readyPlayers.containsAll(participants()); }
    public synchronized boolean allSnapshotted() { return snapshotOperations.keySet().containsAll(participants()); }
    public synchronized boolean allRestored() { return restoreOperations.keySet().containsAll(snapshotOperations.keySet()); }
    public synchronized ArenaPhase phase() { return phase; }
    public synchronized Optional<UUID> finalizationId() { return Optional.ofNullable(finalizationId); }
    public synchronized Map<UUID, String> forfeits() { return Map.copyOf(forfeits); }
    public synchronized Map<UUID, String> eliminations() { return Map.copyOf(eliminations); }
    public synchronized Set<UUID> activePlayers() {
        var active = new LinkedHashSet<>(participants());
        active.removeAll(eliminations.keySet());
        return Set.copyOf(active);
    }
    public synchronized boolean teamADefeated() {
        return eliminations.keySet().containsAll(teamA.players());
    }
    public synchronized boolean teamBDefeated() {
        return eliminations.keySet().containsAll(teamB.players());
    }

    private static String normalizeOutcomeReason(String reason, String label) {
        Objects.requireNonNull(reason, "reason");
        String normalized = reason.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException(label + " reason must be machine-readable");
        }
        return normalized;
    }
}
