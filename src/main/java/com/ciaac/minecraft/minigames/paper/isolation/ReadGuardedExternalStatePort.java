package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import org.bukkit.entity.Player;

/**
 * Preserves an authority the game never mutates. Drift is quarantined, never overwritten.
 * Durable checkpoints are read-only, so unfinished checkpoints may safely repeat their reads.
 * This is not a journal/recovery implementation for external write operations.
 */
public final class ReadGuardedExternalStatePort implements ExternalStateFacetPort {
    private static final byte[] EMPTY = new byte[0];
    private final String id;
    private final Set<PlayerStateFacet> facets;
    private final ExternalStateAuthority authority;
    private final ExternalOperationJournal journal;
    private final AuditRepository audit;

    public ReadGuardedExternalStatePort(String id, Set<PlayerStateFacet> facets,
            ExternalStateAuthority authority, ExternalOperationJournal journal, AuditRepository audit) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid guard ID");
        this.id = id;
        this.facets = Set.copyOf(facets);
        if (this.facets.isEmpty()) throw new IllegalArgumentException("Guard must own a facet");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override public int contractVersion() { return 2; }
    @Override public String id() { return id; }
    @Override public int snapshotVersion() { return 1; }
    @Override public Set<PlayerStateFacet> facets() { return facets; }
    @Override public boolean available() { return authority.available(); }

    @Override public byte[] capture(Player player, PlayerStateOperation context) {
        requirePlayer(player, context, PlayerStateOperation.Kind.CAPTURE);
        ExternalOperationJournal.State state = journal.begin(id, snapshotVersion(), context, EMPTY);
        if (state == ExternalOperationJournal.State.COMMITTED) {
            byte[] previous = journal.committedResult(id, snapshotVersion(), context, EMPTY);
            unchanged(context, previous);
            audit(context, EMPTY);
            return previous;
        }
        byte[] value = Objects.requireNonNull(authority.read(context.playerId()), "authority state").clone();
        authority.validate(context.playerId(), value.clone());
        journal.commit(id, snapshotVersion(), context, EMPTY, value);
        audit(context, EMPTY);
        return value;
    }

    @Override public void validateRestore(PlayerStateOperation context, int version, byte[] payload) {
        if (version != snapshotVersion()) throw new IllegalArgumentException("Unsupported guarded snapshot version");
        requireAvailable();
        byte[] captured = captured(context);
        if (!Arrays.equals(captured, payload)) throw new IllegalStateException("Guard payload conflicts with durable capture");
        unchanged(context, captured);
    }

    @Override public void enterTemporaryState(Player player, PlayerStateOperation context) {
        requirePlayer(player, context, PlayerStateOperation.Kind.ENTER);
        checkpoint(context, captured(context));
    }

    @Override public void purgeTemporaryState(Player player, PlayerStateOperation context) {
        requirePlayer(player, context, PlayerStateOperation.Kind.PURGE);
        checkpoint(context, captured(context));
    }

    @Override public void restore(Player player, PlayerStateOperation context, int version, byte[] payload) {
        requirePlayer(player, context, PlayerStateOperation.Kind.RESTORE);
        validateRestore(context, version, payload);
        checkpoint(context, payload.clone());
    }

    private void checkpoint(PlayerStateOperation context, byte[] captured) {
        unchanged(context, captured);
        journal.begin(id, snapshotVersion(), context, captured);
        // Never skip validation on a committed replay: new external drift remains unsafe.
        unchanged(context, captured);
        journal.commit(id, snapshotVersion(), context, captured, EMPTY);
        audit(context, captured);
    }

    private void audit(PlayerStateOperation context, byte[] request) {
        audit.append(new AuditEvent(OperationIds.derive(OperationIds.derive(context.operationId(), "EXTERNAL_GUARD"), id),
                context.operationId(), journal.committedAt(id, snapshotVersion(), context, request), "EXTERNAL_GUARD_" + context.kind(),
                "SESSION", context.sessionId().toString(), "UNCHANGED", id));
    }

    private byte[] captured(PlayerStateOperation context) {
        Objects.requireNonNull(context, "context");
        var capture = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, context.captureOperationId(),
                context.captureOperationId(), context.snapshotId(), context.sessionId(), context.matchId(),
                context.playerId(), context.capturedConnectionId(), context.capturedConnectionId(),
                context.game(), context.capturedAt());
        return journal.committedResult(id, snapshotVersion(), capture, EMPTY);
    }

    private void unchanged(PlayerStateOperation context, byte[] expected) {
        requireAvailable();
        authority.validate(context.playerId(), expected.clone());
        byte[] current = Objects.requireNonNull(authority.read(context.playerId()), "authority state");
        authority.validate(context.playerId(), current.clone());
        if (!Arrays.equals(expected, current)) throw new IllegalStateException("External state changed; manual reconciliation required");
    }

    private void requirePlayer(Player player, PlayerStateOperation context, PlayerStateOperation.Kind kind) {
        requireAvailable();
        if (context.kind() != kind || !player.getUniqueId().equals(context.playerId())) {
            throw new IllegalStateException("Guard operation has wrong player or phase");
        }
    }

    private void requireAvailable() {
        if (!available()) throw new IllegalStateException("Guard authority is unavailable");
    }
}
