package com.ciaac.minecraft.minigames.paper.colorfloor;

import com.ciaac.minecraft.minigames.colorfloor.FloorColor;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import pt.ciaac.minigames.paper.template.TemplateArtifact;
import pt.ciaac.minigames.paper.template.TemplateArtifactRepository;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * Native Color Floor template adapter. It only consumes an operator-reviewed
 * artifact; startup never captures the live floor and this class never writes
 * to a world.
 */
public final class PaperColorFloorTemplatePort implements ColorFloorTemplatePort {
    private final TemplateArtifactRepository repository;
    private final String artifactId;

    public PaperColorFloorTemplatePort(TemplateArtifactRepository repository, String artifactId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.artifactId = Objects.requireNonNull(artifactId, "artifactId");
    }

    @Override public boolean available() {
        return repository.inspect(artifactId)
                .filter(artifact -> artifact.coversVolume() && !artifact.colorIds().isEmpty()
                        && artifact.blockData().keySet().equals(artifact.colorIds().keySet())
                        && validBlockData(artifact)
                        && artifact.colorIds().values().stream().allMatch(value -> {
                            try {
                                FloorColor.valueOf(value);
                                return true;
                            } catch (IllegalArgumentException invalid) {
                                return false;
                            }
                        }))
                .isPresent();
    }

    @Override public Optional<Template> load(World world, CuboidRegion floor, String rulesetRevision) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(floor, "floor");
        Objects.requireNonNull(rulesetRevision, "rulesetRevision");
        Optional<TemplateArtifact> loaded = repository.load(artifactId, world, floor, rulesetRevision);
        if (loaded.isEmpty()) return Optional.empty();
        try {
            TemplateArtifact artifact = loaded.orElseThrow();
            if (!artifact.coversVolume() || !artifact.blockData().keySet().equals(artifact.colorIds().keySet())) {
                return Optional.empty();
            }
            Map<ColorFloorCell, BlockData> blocks = new LinkedHashMap<>();
            Map<ColorFloorCell, FloorColor> colors = new LinkedHashMap<>();
            for (Map.Entry<TemplateArtifact.BlockCoordinate, String> entry : artifact.blockData().entrySet()) {
                TemplateArtifact.BlockCoordinate coordinate = entry.getKey();
                ColorFloorCell cell = new ColorFloorCell(coordinate.x(), coordinate.y(), coordinate.z());
                blocks.put(cell, Bukkit.createBlockData(entry.getValue()));
                String color = artifact.colorIds().get(coordinate);
                if (color == null) return Optional.empty();
                colors.put(cell, FloorColor.valueOf(color));
            }
            return Optional.of(new Template(artifact.revision(), artifact.checksumSha256(), colors, blocks));
        } catch (RuntimeException invalidArtifact) {
            return Optional.empty();
        }
    }

    private static boolean validBlockData(TemplateArtifact artifact) {
        try {
            artifact.blockData().values().forEach(Bukkit::createBlockData);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
