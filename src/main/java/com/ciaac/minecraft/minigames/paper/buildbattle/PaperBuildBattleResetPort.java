package com.ciaac.minecraft.minigames.paper.buildbattle;

import pt.ciaac.minigames.paper.template.BlockMutationBatch;
import pt.ciaac.minigames.paper.template.TemplateArtifact;
import pt.ciaac.minigames.paper.template.TemplateArtifactRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Native reset adapter backed by an operator-reviewed artifact. It never
 * deletes worlds, forces chunks, or emits block drops; each tick applies only
 * a bounded no-physics batch on the Paper main thread.
 */
public final class PaperBuildBattleResetPort implements BuildBattleResetPort {
    private static final int MAX_PENDING_RESETS = 4;
    private final TemplateArtifactRepository repository;
    private final String artifactId;
    private final String revision;
    private final com.ciaac.minecraft.minigames.region.CuboidRegion volume;
    private final int batchSize;
    private final Map<UUID, PendingReset> pending = new LinkedHashMap<>();

    public PaperBuildBattleResetPort(
            TemplateArtifactRepository repository,
            String artifactId,
            String revision,
            com.ciaac.minecraft.minigames.region.CuboidRegion volume,
            int batchSize) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.artifactId = Objects.requireNonNull(artifactId, "artifactId");
        this.revision = Objects.requireNonNull(revision, "revision");
        this.volume = Objects.requireNonNull(volume, "volume");
        if (batchSize < 1 || batchSize > 100_000) throw new IllegalArgumentException("batchSize is invalid");
        this.batchSize = batchSize;
    }

    @Override public synchronized boolean available() {
        return repository.inspect(artifactId)
                .filter(artifact -> artifact.revision().equals(revision) && artifact.volume().equals(volume)
                        && artifact.coversVolume() && validBlockData(artifact))
                .isPresent();
    }

    /** Legacy synchronous calls are deliberately refused to preserve batching. */
    @Override public ResetResult reset(UUID matchId, World world) {
        return new ResetResult(false, "ASYNC_RESET_REQUIRED");
    }

    @Override public synchronized ResetStart beginReset(UUID matchId, World world) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(world, "world");
        if (!world.getUID().equals(volume.worldId())) {
            return ResetStart.completed(new ResetResult(false, "WORLD_UUID_MISMATCH"));
        }
        if (!Bukkit.isPrimaryThread()) {
            return ResetStart.completed(new ResetResult(false, "MAIN_THREAD_REQUIRED"));
        }
        PendingReset existing = pending.get(matchId);
        if (existing != null) return ResetStart.pending(existing.handle());
        if (pending.size() >= MAX_PENDING_RESETS) {
            return ResetStart.completed(new ResetResult(false, "RESET_BUSY"));
        }
        Optional<TemplateArtifact> loaded = repository.load(artifactId, world, volume, revision);
        if (loaded.isEmpty()) {
            String code = repository.lastDiagnostic().code();
            return ResetStart.completed(new ResetResult(false, safeCode(code, "TEMPLATE_UNAVAILABLE")));
        }
        try {
            TemplateArtifact artifact = loaded.orElseThrow();
            if (!artifact.coversVolume()) {
                return ResetStart.completed(new ResetResult(false, "TEMPLATE_INCOMPLETE"));
            }
            ArrayList<BlockMutationBatch.Mutation> mutations = new ArrayList<>(artifact.blockData().size());
            ArrayList<Map.Entry<TemplateArtifact.BlockCoordinate, String>> entries =
                    new ArrayList<>(artifact.blockData().entrySet());
            entries.sort(Map.Entry.comparingByKey(Comparator.comparingInt(TemplateArtifact.BlockCoordinate::x)
                    .thenComparingInt(TemplateArtifact.BlockCoordinate::y)
                    .thenComparingInt(TemplateArtifact.BlockCoordinate::z)));
            for (Map.Entry<TemplateArtifact.BlockCoordinate, String> entry : entries) {
                TemplateArtifact.BlockCoordinate coordinate = entry.getKey();
                if (!world.isChunkLoaded(coordinate.x() >> 4, coordinate.z() >> 4)) {
                    return ResetStart.completed(new ResetResult(false, "CHUNK_UNLOADED"));
                }
                mutations.add(new BlockMutationBatch.Mutation(
                        coordinate.x(), coordinate.y(), coordinate.z(),
                        null,
                        Bukkit.createBlockData(entry.getValue())));
            }
            ResetHandle handle = new ResetHandle(matchId, UUID.randomUUID());
            pending.put(handle.matchId(), new PendingReset(handle,
                    new BlockMutationBatch(world.getUID(), world.getName(), mutations)));
            return ResetStart.pending(handle);
        } catch (RuntimeException failure) {
            return ResetStart.completed(new ResetResult(false, "TEMPLATE_APPLY_INVALID"));
        }
    }

    @Override public synchronized ResetProgress pollReset(ResetHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (!Bukkit.isPrimaryThread()) return ResetProgress.failed("MAIN_THREAD_REQUIRED");
        PendingReset reset = pending.get(handle.matchId());
        if (reset == null || !reset.handle().operationId().equals(handle.operationId())) {
            return ResetProgress.failed("RESET_HANDLE_UNKNOWN");
        }
        World world = Bukkit.getWorld(reset.batch().worldId());
        if (world == null || !world.getName().equals(reset.batch().worldName())) {
            pending.remove(handle.matchId());
            return ResetProgress.failed("WORLD_UNLOADED");
        }
        BlockMutationBatch.Progress progress = reset.batch().apply(world, batchSize);
        if (!progress.complete()) return ResetProgress.running(progress.applied(), progress.remaining());
        pending.remove(handle.matchId());
        return progress.successful()
                ? ResetProgress.completed(progress.applied())
                : ResetProgress.failed(safeCode(progress.code(), "RESET_FAILED"));
    }

    private static String safeCode(String value, String fallback) {
        return value != null && value.matches("[A-Z0-9][A-Z0-9_.-]{0,63}") ? value : fallback;
    }

    private static boolean validBlockData(TemplateArtifact artifact) {
        try {
            artifact.blockData().values().forEach(Bukkit::createBlockData);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private record PendingReset(ResetHandle handle, BlockMutationBatch batch) {}

}
