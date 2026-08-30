package pt.ciaac.minigames.paper.template;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/** Main-thread, no-physics, bounded block mutation queue. */
public final class BlockMutationBatch {
    private final UUID worldId;
    private final String worldName;
    private final ArrayDeque<Mutation> pending;
    private int applied;
    private boolean failed;

    public BlockMutationBatch(UUID worldId, String worldName, Collection<Mutation> mutations) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.worldName = Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(mutations, "mutations");
        if (mutations.size() > TemplateArtifact.MAX_BLOCKS) throw new IllegalArgumentException("mutation batch is too large");
        this.pending = new ArrayDeque<>();
        Set<String> coordinates = new HashSet<>();
        for (Mutation mutation : mutations) {
            Mutation checked = Objects.requireNonNull(mutation, "mutation");
            if (!coordinates.add(checked.x() + ":" + checked.y() + ":" + checked.z())) {
                throw new IllegalArgumentException("mutation coordinates must be unique");
            }
            this.pending.add(checked);
        }
    }

    public synchronized Progress apply(World world, int maxPerTick) {
        Objects.requireNonNull(world, "world");
        if (!world.getUID().equals(worldId) || !world.getName().equals(worldName)) return fail("WORLD_IDENTITY_MISMATCH");
        if (!Bukkit.isPrimaryThread()) return fail("MAIN_THREAD_REQUIRED");
        if (maxPerTick < 1 || maxPerTick > 100_000) return fail("BATCH_SIZE_INVALID");
        if (failed) return new Progress(true, false, applied, pending.size(), "BATCH_FAILED");
        int processed = 0;
        while (!pending.isEmpty() && processed++ < maxPerTick) {
            Mutation mutation = pending.peek();
            if (!world.isChunkLoaded(mutation.x() >> 4, mutation.z() >> 4)) return fail("CHUNK_UNLOADED");
            Block block = world.getBlockAt(mutation.x(), mutation.y(), mutation.z());
            if (mutation.expected() != null && !block.getBlockData().equals(mutation.expected())) {
                return fail("BLOCK_STATE_CHANGED");
            }
            block.setBlockData(mutation.replacement().clone(), false);
            if (!block.getBlockData().equals(mutation.replacement())) return fail("BLOCK_APPLY_VERIFY_FAILED");
            pending.remove();
            applied++;
        }
        return new Progress(pending.isEmpty(), true, applied, pending.size(), pending.isEmpty() ? "COMPLETE" : "IN_PROGRESS");
    }

    public synchronized int applied() { return applied; }
    public synchronized int remaining() { return pending.size(); }
    public UUID worldId() { return worldId; }
    public String worldName() { return worldName; }

    private Progress fail(String code) {
        failed = true;
        return new Progress(true, false, applied, pending.size(), code);
    }

    public record Mutation(int x, int y, int z, BlockData expected, BlockData replacement) {
        public Mutation {
            expected = expected == null ? null : expected.clone();
            replacement = Objects.requireNonNull(replacement, "replacement").clone();
        }
        @Override public BlockData expected() { return expected == null ? null : expected.clone(); }
        @Override public BlockData replacement() { return replacement.clone(); }
    }

    public record Progress(boolean complete, boolean successful, int applied, int remaining, String code) {
        public Progress {
            if (applied < 0 || remaining < 0) throw new IllegalArgumentException("progress counts cannot be negative");
            Objects.requireNonNull(code, "code");
        }
    }
}
