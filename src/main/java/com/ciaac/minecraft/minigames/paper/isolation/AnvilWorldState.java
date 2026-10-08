package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedAnvilDodgeConfiguration;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
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
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.Server;

/** Frozen immutable Anvil facility and exact shared-match marker cleanup before player restoration. */
public final class AnvilWorldState {
    private static final byte[] EMPTY = new byte[0];
    private final String providerId;
    private final Server server;
    private final ProtectedRegionRegistry regions;
    private final ArenaWorldLedger ledger;
    private final AnvilHazardOwnership hazards;
    private final ExternalOperationJournal journal;
    private final AuditRepository audit;
    private final Supplier<ResolvedAnvilDodgeConfiguration> configuration;

    public AnvilWorldState(String providerId, Server server, ProtectedRegionRegistry regions,
            ArenaWorldLedger ledger, AnvilHazardOwnership hazards, ExternalOperationJournal journal,
            AuditRepository audit, Supplier<ResolvedAnvilDodgeConfiguration> configuration) {
        this.providerId = Objects.requireNonNull(providerId);
        this.server = Objects.requireNonNull(server);
        this.regions = Objects.requireNonNull(regions);
        this.ledger = Objects.requireNonNull(ledger);
        this.hazards = Objects.requireNonNull(hazards);
        this.journal = Objects.requireNonNull(journal);
        this.audit = Objects.requireNonNull(audit);
        this.configuration = Objects.requireNonNull(configuration);
    }

    public byte[] capture(PlayerStateOperation context) {
        require(context, PlayerStateOperation.Kind.CAPTURE);
        byte[] payload = manifest();
        ledger.capture(context, payload);
        journal.begin(providerId, 1, context, EMPTY);
        journal.commit(providerId, 1, context, EMPTY, payload);
        audit(context, EMPTY);
        return payload;
    }

    public void validate(PlayerStateOperation context, int version, byte[] payload) {
        require(context, null);
        if (version != 1 || payload == null || payload.length == 0 || payload.length > 8192)
            throw new IllegalArgumentException("Unsupported Anvil world snapshot");
        var lease = ledger.requireLease(context);
        byte[] current = manifest();
        if (!Arrays.equals(payload, lease.manifest()) || !Arrays.equals(payload, current)
                || !Arrays.equals(payload, captured(context)))
            throw new IllegalStateException("Anvil facility or durable capture differs");
        for (var other : matchLeases(context)) {
            if (!Arrays.equals(current, other.manifest()))
                throw new IllegalStateException("Anvil match contains conflicting facility captures");
            hazards.validatePurge(other.capture());
            if (context.kind() == PlayerStateOperation.Kind.RESTORE
                    && other.status() != ArenaWorldLedger.Status.PURGED
                    && other.status() != ArenaWorldLedger.Status.RESTORED)
                throw new IllegalStateException("Every shared Anvil marker must be purged before player restoration");
        }
    }

    public void enter(PlayerStateOperation context) {
        require(context, PlayerStateOperation.Kind.ENTER);
        byte[] payload = captured(context);
        validate(context, 1, payload);
        journal.begin(providerId, 1, context, payload);
        ledger.arm(context);
        journal.commit(providerId, 1, context, payload, EMPTY);
        audit(context, payload);
    }

    public void purge(PlayerStateOperation context) {
        require(context, PlayerStateOperation.Kind.PURGE);
        byte[] payload = captured(context);
        // Read every match owner first: a later ambiguous owner must prevent every native deletion.
        validate(context, 1, payload);
        journal.begin(providerId, 1, context, payload);
        List<ArenaWorldLedger.Lease> owners = matchLeases(context);
        // Freeze all shared owners against further native additions before the first removal.
        for (var lease : owners) ledger.beginPurge(lease.capture());
        for (var lease : owners) hazards.purge(lease.capture());
        journal.commit(providerId, 1, context, payload, EMPTY);
        audit(context, payload);
    }

    public void restore(PlayerStateOperation context, int version, byte[] payload) {
        require(context, PlayerStateOperation.Kind.RESTORE);
        validate(context, version, payload);
        journal.begin(providerId, 1, context, payload);
        ledger.markRestored(context);
        journal.commit(providerId, 1, context, payload, EMPTY);
        audit(context, payload);
    }

    private List<ArenaWorldLedger.Lease> matchLeases(PlayerStateOperation context) {
        var leases = ledger.leasesForMatch(context.matchId(), GameKey.ANVIL_DODGE);
        if (leases.isEmpty()) throw new IllegalStateException("Anvil match has no durable owner");
        return leases;
    }

    private byte[] manifest() {
        var value = Objects.requireNonNull(configuration.get(), "Anvil configuration");
        var boundary = regions.find("anvil-dodge.boundary").orElseThrow();
        if (regions.all().stream().filter(r -> r.game() == GameKey.ANVIL_DODGE).count() != 1
                || boundary.game() != GameKey.ANVIL_DODGE
                || boundary.role() != ProtectedRegionRole.PARTICIPANT_ONLY || !boundary.immutable()
                || !boundary.bounds().equals(value.regions().get("boundary"))
                || server.getWorld(value.world().getUID()) != value.world())
            throw new IllegalStateException("Anvil protection or world identity is unavailable");
        var floor = Objects.requireNonNull(value.regions().get("floor"));
        if (floor.minY() < value.world().getMinHeight() || (long) floor.minY() + 8 >= value.world().getMaxHeight())
            throw new IllegalStateException("Anvil hazard height is unavailable");
        for (int x = floor.minX() >> 4; x <= (floor.maxX() >> 4); x++)
            for (int z = floor.minZ() >> 4; z <= (floor.maxZ() >> 4); z++)
                if (!value.world().isChunkLoaded(x, z))
                    throw new IllegalStateException("Anvil hazard chunk is not loaded");
        var domain = value.domain();
        long markers = 0;
        for (int wave = 0; wave < domain.waves(); wave++) {
            markers += Math.min(domain.floorCells(),
                    domain.hazardsPerWaveStart() + (long) wave * domain.hazardsPerWaveIncrement());
            if (markers > 4096) throw new IllegalStateException("Anvil match exceeds the 4096-marker ownership limit");
        }
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(0x43414148); // CAAH, distinct from every existing world snapshot
                out.writeInt(1);
                out.writeUTF(server.getVersion());
                out.writeUTF(value.world().getName());
                out.writeLong(value.world().getUID().getMostSignificantBits());
                out.writeLong(value.world().getUID().getLeastSignificantBits());
                out.writeUTF(boundary.id());
                out.writeUTF(boundary.role().name());
                for (var cuboid : List.of(boundary.bounds(), floor)) {
                    out.writeInt(cuboid.minX()); out.writeInt(cuboid.minY()); out.writeInt(cuboid.minZ());
                    out.writeInt(cuboid.maxX()); out.writeInt(cuboid.maxY()); out.writeInt(cuboid.maxZ());
                }
                for (String id : List.of("start", "exit")) {
                    var location = Objects.requireNonNull(value.locations().get(id));
                    out.writeDouble(location.getX()); out.writeDouble(location.getY()); out.writeDouble(location.getZ());
                    out.writeFloat(location.getYaw()); out.writeFloat(location.getPitch());
                }
                out.writeUTF(domain.rulesetRevision());
                out.writeInt(domain.minimumPlayers()); out.writeInt(domain.maximumPlayers());
                out.writeInt(domain.waves()); out.writeLong(domain.warning().toNanos());
                out.writeLong(domain.waveDuration().toNanos()); out.writeLong(domain.seed());
                out.writeInt(domain.floorCells()); out.writeInt(domain.hazardsPerWaveStart());
                out.writeInt(domain.hazardsPerWaveIncrement());
                out.writeInt(value.floorWidth()); out.writeInt(value.floorDepth());
                out.writeUTF(value.hazardInput().identifier());
            }
            byte[] payload = bytes.toByteArray();
            if (payload.length > 8192) throw new IllegalStateException("Anvil facility snapshot exceeds its bound");
            return payload;
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    /** The prefix is fixed by the capture encoder; ownership must use its frozen world identity. */
    static java.util.UUID capturedWorld(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 8192)
            throw new IllegalArgumentException("Invalid Anvil facility snapshot");
        try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload))) {
            if (input.readInt() != 0x43414148 || input.readInt() != 1)
                throw new IllegalArgumentException("Unsupported Anvil facility snapshot");
            if (input.readUTF().isBlank() || input.readUTF().isBlank())
                throw new IllegalArgumentException("Invalid Anvil world identity");
            return new java.util.UUID(input.readLong(), input.readLong());
        } catch (IOException malformed) { throw new IllegalArgumentException("Truncated Anvil snapshot", malformed); }
    }

    private byte[] captured(PlayerStateOperation context) {
        return journal.committedResult(providerId, 1, ledger.requireLease(context).capture(), EMPTY);
    }

    private void audit(PlayerStateOperation context, byte[] request) {
        audit.append(new AuditEvent(OperationIds.derive(context.operationId(), "ANVIL_WORLD"),
                context.operationId(), journal.committedAt(providerId, 1, context, request),
                "ANVIL_WORLD_" + context.kind(), "SESSION", context.sessionId().toString(), "VERIFIED", providerId));
    }

    private void require(PlayerStateOperation context, PlayerStateOperation.Kind kind) {
        if (!server.isPrimaryThread()) throw new IllegalStateException("Anvil world operations require the primary thread");
        if (context == null || context.game() != GameKey.ANVIL_DODGE || context.capturedConnectionId() == null
                || (kind != null && context.kind() != kind))
            throw new IllegalStateException("Anvil world operation has an incompatible capture identity");
    }
}
