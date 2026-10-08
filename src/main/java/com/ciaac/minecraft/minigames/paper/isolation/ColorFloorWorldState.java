package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.isolation.WorldRecoveryPendingException;
import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.ColorFloorWorldLedger;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.Server;
import org.bukkit.World;
import pt.ciaac.minigames.paper.template.BlockMutationBatch;
import pt.ciaac.minigames.paper.template.TemplateArtifact;

/** Exact Color Floor ownership: only reviewed floor cells may become AIR and be restored. */
public final class ColorFloorWorldState {
    public static final int MAX_FLOOR_CELLS = 4096;
    private static final int BLOCKS_PER_BATCH = 256;
    private static final byte[] EMPTY = new byte[0];
    private final String providerId;
    private final Server server;
    private final ProtectedRegionRegistry regions;
    private final ColorFloorWorldLedger ledger;
    private final ExternalOperationJournal journal;
    private final AuditRepository audit;
    private final Supplier<ColorFloorWorldManifest> currentManifest;

    public ColorFloorWorldState(String providerId, Server server, ProtectedRegionRegistry regions,
            ColorFloorWorldLedger ledger, ExternalOperationJournal journal, AuditRepository audit,
            Supplier<ColorFloorWorldManifest> currentManifest) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.server = Objects.requireNonNull(server, "server");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.currentManifest = Objects.requireNonNull(currentManifest, "currentManifest");
    }

    public byte[] capture(PlayerStateOperation context) {
        require(context, PlayerStateOperation.Kind.CAPTURE);
        ColorFloorWorldManifest manifest = currentManifest.get();
        inspectCells(manifest, false);
        byte[] payload = manifest.encode();
        ledger.capture(context, payload);
        journal.begin(providerId, 1, context, EMPTY);
        journal.commit(providerId, 1, context, EMPTY, payload);
        audit(context, EMPTY);
        return payload;
    }

    public void validate(PlayerStateOperation context, int version, byte[] payload) {
        require(context, null);
        if (version != 1) throw new IllegalArgumentException("Unsupported Color Floor world snapshot version");
        ColorFloorWorldManifest manifest = ColorFloorWorldManifest.decode(payload);
        var lease = ledger.requireLease(context);
        if (!Arrays.equals(payload, lease.manifest())
                || !Arrays.equals(payload, captured(context))
                || !Arrays.equals(payload, currentManifest.get().encode()))
            throw new IllegalStateException("Color Floor snapshot differs from its frozen template or protection policy");
        if (context.kind() == PlayerStateOperation.Kind.RESTORE
                && lease.status() != ColorFloorWorldLedger.Status.PURGED
                && lease.status() != ColorFloorWorldLedger.Status.RESTORED)
            throw new IllegalStateException("Color Floor cleanup must finish before player restoration");
        inspectCells(manifest, context.kind() != PlayerStateOperation.Kind.RESTORE
                && lease.status() != ColorFloorWorldLedger.Status.CAPTURED);
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

    /** Frozen lease precedes each write. A third-party block or unloaded chunk is never overwritten. */
    public void purge(PlayerStateOperation context) {
        require(context, PlayerStateOperation.Kind.PURGE);
        byte[] payload = captured(context);
        validate(context, 1, payload);
        ColorFloorWorldManifest manifest = ColorFloorWorldManifest.decode(payload);
        World world = requireWorld(manifest);
        ArrayList<BlockMutationBatch.Mutation> changes = inspectCells(manifest, true);
        ledger.beginPurge(context);
        if (!changes.isEmpty()) {
            var batch = new BlockMutationBatch(world.getUID(), world.getName(), changes);
            var progress = batch.apply(world, BLOCKS_PER_BATCH);
            if (!progress.successful()) throw new IllegalStateException("Color Floor cleanup failed its block comparison");
            if (!progress.complete()) throw new WorldRecoveryPendingException();
        }
        // Prove the full owned floor, including already-restored cells, before releasing player state.
        inspectCells(manifest, false);
        journal.begin(providerId, 1, context, payload);
        ledger.markPurged(context);
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

    private ArrayList<BlockMutationBatch.Mutation> inspectCells(ColorFloorWorldManifest manifest, boolean allowAir) {
        World world = requireWorld(manifest);
        var cells = manifest.artifact().blockData();
        if (cells.size() > MAX_FLOOR_CELLS)
            throw new IllegalStateException("Color Floor exceeds the bounded 4096-cell facility limit");
        ArrayList<BlockMutationBatch.Mutation> changes = new ArrayList<>();
        var ordered = cells.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparingInt(TemplateArtifact.BlockCoordinate::x)
                        .thenComparingInt(TemplateArtifact.BlockCoordinate::y)
                        .thenComparingInt(TemplateArtifact.BlockCoordinate::z))).toList();
        for (var entry : ordered) {
            var cell = entry.getKey();
            if (!world.isChunkLoaded(cell.x() >> 4, cell.z() >> 4))
                throw new IllegalStateException("Color Floor owned chunk is not loaded");
            var block = world.getBlockAt(cell.x(), cell.y(), cell.z());
            var current = block.getBlockData();
            if (current.getAsString().equals(entry.getValue())) continue;
            if (!allowAir || !current.getAsString().equals("minecraft:air"))
                throw new IllegalStateException("Color Floor cell is neither its template nor proven temporary AIR");
            changes.add(new BlockMutationBatch.Mutation(cell.x(), cell.y(), cell.z(), current,
                    server.createBlockData(entry.getValue())));
        }
        return changes;
    }

    private World requireWorld(ColorFloorWorldManifest manifest) {
        World world = server.getWorld(manifest.worldId());
        if (world == null || !world.getName().equals(manifest.artifact().worldName())
                || manifest.boundary().bounds().minY() < world.getMinHeight()
                || manifest.boundary().bounds().maxY() >= world.getMaxHeight()
                || !regions.find(manifest.boundary().id()).filter(manifest.boundary()::equals).isPresent())
            throw new IllegalStateException("Color Floor world or protected geometry is not the captured facility");
        return world;
    }

    private byte[] captured(PlayerStateOperation context) {
        var capture = ledger.requireLease(context).capture();
        return journal.committedResult(providerId, 1, capture, EMPTY);
    }

    private void audit(PlayerStateOperation context, byte[] request) {
        audit.append(new AuditEvent(OperationIds.derive(context.operationId(), "COLOR_FLOOR_WORLD"),
                context.operationId(), journal.committedAt(providerId, 1, context, request),
                "COLOR_FLOOR_WORLD_" + context.kind(), "SESSION", context.sessionId().toString(), "VERIFIED", providerId));
    }

    private void require(PlayerStateOperation context, PlayerStateOperation.Kind kind) {
        if (!server.isPrimaryThread() || context == null || context.game() != GameKey.COLOR_FLOOR
                || context.capturedConnectionId() == null || (kind != null && context.kind() != kind))
            throw new IllegalStateException("Color Floor world operation requires its native captured identity and phase");
    }
}
