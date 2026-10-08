package pt.ciaac.minigames.paper.template;

import com.ciaac.minecraft.minigames.colorfloor.FloorColor;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Read-only capture and create-only export for the reviewed Color Floor template. */
public final class ColorFloorTemplateCapture {
    public static final String ARTIFACT_ID = "immutable-floor-template";
    private static final int MAX_CELLS = 4_096;

    private ColorFloorTemplateCapture() { }

    /** Captures an already-loaded, single-layer Color Floor using read-only world access. */
    public static TemplateArtifact capture(World world, CuboidRegion floor, String revision) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("A captura do piso tem de ocorrer na thread principal do servidor.");
        }
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(floor, "floor");
        if (floor.minY() != floor.maxY()) {
            throw new IllegalArgumentException("O piso tem de ocupar uma única altura.");
        }
        long width = (long) floor.maxX() - floor.minX() + 1;
        long depth = (long) floor.maxZ() - floor.minZ() + 1;
        if (width <= 0 || depth <= 0 || width * depth > MAX_CELLS) {
            throw new IllegalArgumentException("O piso pode conter no máximo 4096 células.");
        }

        var worldId = Objects.requireNonNull(world.getUID(), "world UUID");
        String worldName = Objects.requireNonNull(world.getName(), "world name");
        if (!worldId.equals(floor.worldId())) {
            throw new IllegalArgumentException("O UUID do mundo não coincide com o volume do piso.");
        }
        if (floor.minY() < world.getMinHeight() || floor.minY() >= world.getMaxHeight()) {
            throw new IllegalArgumentException("A altura do piso está fora dos limites do mundo carregado.");
        }

        Map<TemplateArtifact.BlockCoordinate, String> blocks = new LinkedHashMap<>();
        Map<TemplateArtifact.BlockCoordinate, String> colors = new LinkedHashMap<>();
        Set<Long> checkedChunks = new HashSet<>();
        for (int x = floor.minX(); ; x++) {
            for (int z = floor.minZ(); ; z++) {
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                long chunkKey = ((long) chunkX << 32) ^ (chunkZ & 0xffff_ffffL);
                if (checkedChunks.add(chunkKey) && !world.isChunkLoaded(chunkX, chunkZ)) {
                    throw new IllegalStateException("A captura foi recusada: há chunks do piso por carregar.");
                }

                Block block = Objects.requireNonNull(world.getBlockAt(x, floor.minY(), z), "floor block");
                if (block.getWorld() == null || !worldId.equals(block.getWorld().getUID())
                        || !worldName.equals(block.getWorld().getName())
                        || block.getX() != x || block.getY() != floor.minY() || block.getZ() != z) {
                    throw new IllegalStateException("A leitura do bloco não corresponde ao mundo e às coordenadas do piso.");
                }
                FloorColor color = colorFor(block.getType());
                if (color == null) {
                    throw new IllegalArgumentException("Todas as células têm de ser lã ou betão colorido suportado.");
                }
                String blockData = block.getBlockData().getAsString();
                if (!isCanonicalMaterial(blockData, color)) {
                    throw new IllegalArgumentException("Os dados do bloco não são um material de piso canónico suportado.");
                }
                var coordinate = new TemplateArtifact.BlockCoordinate(x, floor.minY(), z);
                blocks.put(coordinate, blockData);
                colors.put(coordinate, color.name());
                if (z == floor.maxZ()) break;
            }
            if (x == floor.maxX()) break;
        }
        if (!worldId.equals(world.getUID()) || !worldName.equals(world.getName())) {
            throw new IllegalStateException("A identidade do mundo mudou durante a captura.");
        }

        TemplateArtifact draft = new TemplateArtifact(ARTIFACT_ID, revision, worldId, worldName,
                floor, "0".repeat(64), blocks, colors);
        return new TemplateArtifact(ARTIFACT_ID, revision, worldId, worldName,
                floor, draft.calculateChecksum(), blocks, colors);
    }

    /** Writes an artifact once and validates its exact repository readback. */
    public static Path export(TemplateArtifact artifact, Path templatesDirectory) throws IOException {
        Objects.requireNonNull(artifact, "artifact");
        validateColorFloorArtifact(artifact);
        return TemplateArtifactExporter.export(artifact, templatesDirectory);
    }

    private static void validateColorFloorArtifact(TemplateArtifact artifact) {
        if (!ARTIFACT_ID.equals(artifact.artifactId()) || !artifact.checksumMatches()
                || !artifact.coversVolume() || artifact.volume().minY() != artifact.volume().maxY()
                || artifact.blockData().size() > MAX_CELLS
                || !artifact.blockData().keySet().equals(artifact.colorIds().keySet())) {
            throw new IllegalArgumentException("O artefacto não é um template imutável de piso válido.");
        }
        long width = (long) artifact.volume().maxX() - artifact.volume().minX() + 1;
        long depth = (long) artifact.volume().maxZ() - artifact.volume().minZ() + 1;
        if (width * depth > MAX_CELLS) {
            throw new IllegalArgumentException("O template excede o limite de 4096 células.");
        }
        for (var entry : artifact.blockData().entrySet()) {
            FloorColor color;
            try {
                color = FloorColor.valueOf(artifact.colorIds().get(entry.getKey()));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("O template contém uma cor de piso inválida.", invalid);
            }
            if (!isCanonicalMaterial(entry.getValue(), color)) {
                throw new IllegalArgumentException("O template contém um material não suportado para o piso.");
            }
        }
    }

    private static FloorColor colorFor(Material material) {
        String name = material.name();
        int separator = name.indexOf('_');
        if (separator <= 0 || !(name.endsWith("_CONCRETE") || name.endsWith("_WOOL"))) return null;
        try {
            return FloorColor.valueOf(name.substring(0, separator));
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    private static boolean isCanonicalMaterial(String data, FloorColor color) {
        String expected = "minecraft:" + color.name().toLowerCase(java.util.Locale.ROOT) + "_";
        return data.equals(expected + "concrete") || data.equals(expected + "wool");
    }

}
