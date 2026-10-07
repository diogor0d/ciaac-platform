package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.bukkit.Server;

/** Read-only preservation of the immutable Sumo/Hot Potato protection policy.
 * These games create no world entities or blocks; this is not a world reset adapter. */
final class ImmutableMinigameWorldState {
    private static final byte[] EMPTY = new byte[0];
    private final String adapterId;
    private final Server server;
    private final ProtectedRegionRegistry regions;
    private final ExternalOperationJournal journal;
    private final AuditRepository audit;

    ImmutableMinigameWorldState(String adapterId, Server server, ProtectedRegionRegistry regions,
            ExternalOperationJournal journal, AuditRepository audit) {
        this.adapterId = adapterId;
        this.server = server;
        this.regions = regions;
        this.journal = journal;
        this.audit = audit;
    }

    byte[] capture(PlayerStateOperation context) {
        byte[] current = manifest(context.game());
        var state = journal.begin(adapterId, 1, context, EMPTY);
        if (state == ExternalOperationJournal.State.COMMITTED) {
            byte[] previous = journal.committedResult(adapterId, 1, context, EMPTY);
            if (!Arrays.equals(previous, current)) throw new IllegalStateException("Immutable minigame policy changed");
            audit(context, EMPTY);
            return previous;
        }
        journal.commit(adapterId, 1, context, EMPTY, current);
        audit(context, EMPTY);
        return current;
    }

    void validate(PlayerStateOperation context, int version, byte[] payload) {
        if (version != 1 || payload == null || payload.length == 0 || payload.length > 2048)
            throw new IllegalArgumentException("Unsupported immutable minigame snapshot");
        if (!Arrays.equals(payload, captured(context)) || !Arrays.equals(payload, manifest(context.game())))
            throw new IllegalStateException("Immutable minigame policy differs from durable capture");
    }

    void checkpoint(PlayerStateOperation context) {
        byte[] payload = captured(context);
        validate(context, 1, payload);
        journal.begin(adapterId, 1, context, payload);
        // No world writes: an unfinished checkpoint can only repeat these reads.
        validate(context, 1, payload);
        journal.commit(adapterId, 1, context, payload, EMPTY);
        audit(context, payload);
    }

    private byte[] captured(PlayerStateOperation context) {
        var capture = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, context.captureOperationId(),
                context.captureOperationId(), context.snapshotId(), context.sessionId(), context.matchId(),
                context.playerId(), context.capturedConnectionId(), context.capturedConnectionId(),
                context.game(), context.capturedAt());
        return journal.committedResult(adapterId, 1, capture, EMPTY);
    }

    private byte[] manifest(GameKey game) {
        String id;
        ProtectedRegionRole role;
        if (game == GameKey.KNOCKBACK_SUMO) {
            id = "knockback-sumo.boundary";
            role = ProtectedRegionRole.PARTICIPANT_ONLY;
        } else if (game == GameKey.HOT_POTATO) {
            id = "hot-potato.arena";
            role = ProtectedRegionRole.GAME_WORLD_BOUNDARY;
        } else throw new IllegalArgumentException("Unsupported immutable minigame");
        var owned = regions.all().stream().filter(region -> region.game() == game).toList();
        if (owned.size() != 1) throw new IllegalStateException("Immutable minigame needs one protection boundary");
        var region = owned.getFirst();
        if (!region.id().equals(id) || region.role() != role || !region.immutable() || !region.requiresAdmission())
            throw new IllegalStateException("Immutable minigame boundary is incompatible");
        var bounds = region.bounds();
        var world = server.getWorld(bounds.worldId());
        if (world == null || bounds.minY() < world.getMinHeight() || bounds.maxY() >= world.getMaxHeight())
            throw new IllegalStateException("Immutable minigame world or height is unavailable");
        String version = server.getVersion();
        if (version == null || version.length() > 256) throw new IllegalStateException("Invalid server identity");
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(0x4341494D); // CAIM, distinct from the Arena manifest
                output.writeInt(1);
                output.writeUTF(version);
                output.writeUTF(game.id());
                output.writeUTF(id);
                output.writeUTF(role.name());
                output.writeLong(bounds.worldId().getMostSignificantBits());
                output.writeLong(bounds.worldId().getLeastSignificantBits());
                output.writeInt(bounds.minX()); output.writeInt(bounds.minY()); output.writeInt(bounds.minZ());
                output.writeInt(bounds.maxX()); output.writeInt(bounds.maxY()); output.writeInt(bounds.maxZ());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private void audit(PlayerStateOperation context, byte[] request) {
        audit.append(new AuditEvent(OperationIds.derive(context.operationId(), "IMMUTABLE_GAME_WORLD"),
                context.operationId(), journal.committedAt(adapterId, 1, context, request),
                "IMMUTABLE_GAME_WORLD_" + context.kind(), "SESSION", context.sessionId().toString(),
                "UNCHANGED", adapterId));
    }
}
