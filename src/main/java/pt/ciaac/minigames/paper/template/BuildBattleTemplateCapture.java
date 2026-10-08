package pt.ciaac.minigames.paper.template;

import com.ciaac.minecraft.minigames.paper.buildbattle.BuildBattleBlockPolicy;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;

/** Read-only capture of an already-loaded, policy-approved Build Battle volume. */
public final class BuildBattleTemplateCapture {
    private static final long MAX_CELLS = 100_000;

    private BuildBattleTemplateCapture() { }

    public static TemplateArtifact capture(World world, CuboidRegion volume, String artifactId, String revision) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("A captura do template tem de ocorrer na thread principal do servidor.");
        }
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(volume, "volume");

        long width = (long) volume.maxX() - volume.minX() + 1L;
        long height = (long) volume.maxY() - volume.minY() + 1L;
        long depth = (long) volume.maxZ() - volume.minZ() + 1L;
        if (width <= 0 || height <= 0 || depth <= 0
                || width > MAX_CELLS || height > MAX_CELLS / width
                || depth > MAX_CELLS / (width * height)) {
            throw new IllegalArgumentException("O volume pode conter no máximo 100000 blocos.");
        }

        var worldId = Objects.requireNonNull(world.getUID(), "world UUID");
        String worldName = Objects.requireNonNull(world.getName(), "world name");
        if (!worldId.equals(volume.worldId())) {
            throw new IllegalArgumentException("O UUID do mundo não coincide com o volume configurado.");
        }
        if (volume.minY() < world.getMinHeight() || volume.maxY() >= world.getMaxHeight()) {
            throw new IllegalArgumentException("O volume está fora dos limites de altura do mundo carregado.");
        }

        Map<TemplateArtifact.BlockCoordinate, String> blocks = new LinkedHashMap<>();
        Set<Long> checkedChunks = new HashSet<>();
        for (int x = volume.minX(); ; x++) {
            for (int y = volume.minY(); ; y++) {
                for (int z = volume.minZ(); ; z++) {
                    int chunkX = x >> 4;
                    int chunkZ = z >> 4;
                    long chunkKey = ((long) chunkX << 32) ^ (chunkZ & 0xffff_ffffL);
                    if (checkedChunks.add(chunkKey) && !world.isChunkLoaded(chunkX, chunkZ)) {
                        throw new IllegalStateException("A captura foi recusada: há chunks do volume por carregar.");
                    }

                    Block block = Objects.requireNonNull(world.getBlockAt(x, y, z), "volume block");
                    if (block.getWorld() == null || !worldId.equals(block.getWorld().getUID())
                            || !worldName.equals(block.getWorld().getName())
                            || block.getX() != x || block.getY() != y || block.getZ() != z) {
                        throw new IllegalStateException("A leitura do bloco não corresponde ao mundo e às coordenadas configuradas.");
                    }
                    if (block.getState() instanceof TileState) {
                        throw new IllegalArgumentException("O volume não pode conter blocos com estado de inventário ou tile.");
                    }
                    var data = Objects.requireNonNull(block.getBlockData(), "block data");
                    if (!BuildBattleBlockPolicy.allows(data) || data.getMaterial() != block.getType()) {
                        throw new IllegalArgumentException("O volume contém um bloco fora da política de construção segura.");
                    }
                    var coordinate = new TemplateArtifact.BlockCoordinate(x, y, z);
                    blocks.put(coordinate, data.getAsString());
                    if (z == volume.maxZ()) break;
                }
                if (y == volume.maxY()) break;
            }
            if (x == volume.maxX()) break;
        }
        if (!worldId.equals(world.getUID()) || !worldName.equals(world.getName())) {
            throw new IllegalStateException("A identidade do mundo mudou durante a captura.");
        }

        TemplateArtifact draft = new TemplateArtifact(artifactId, revision, worldId, worldName,
                volume, "0".repeat(64), blocks, Map.of());
        return new TemplateArtifact(artifactId, revision, worldId, worldName,
                volume, draft.calculateChecksum(), blocks, Map.of());
    }
}
