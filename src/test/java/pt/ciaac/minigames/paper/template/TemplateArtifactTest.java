package pt.ciaac.minigames.paper.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemplateArtifactTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final CuboidRegion VOLUME = new CuboidRegion(WORLD, 0, 64, 0, 1, 64, 0);

    @Test
    void checksumIsCanonicalAndSelfVerifying() {
        Map<TemplateArtifact.BlockCoordinate, String> blocks = new LinkedHashMap<>();
        blocks.put(new TemplateArtifact.BlockCoordinate(1, 64, 0), "minecraft:stone");
        blocks.put(new TemplateArtifact.BlockCoordinate(0, 64, 0), "minecraft:dirt");
        TemplateArtifact provisional = new TemplateArtifact(
                "floor", "r1", WORLD, "arena", VOLUME, "0".repeat(64), blocks, Map.of());
        TemplateArtifact verified = new TemplateArtifact(
                "floor", "r1", WORLD, "arena", VOLUME, provisional.calculateChecksum(), blocks, Map.of());

        assertTrue(verified.checksumMatches());
        assertEquals(provisional.calculateChecksum(), verified.checksumSha256());
    }

    @Test
    void nonMatchingColorCoordinatesAreRejected() {
        TemplateArtifact.BlockCoordinate coordinate = new TemplateArtifact.BlockCoordinate(0, 64, 0);
        TemplateArtifact.BlockCoordinate other = new TemplateArtifact.BlockCoordinate(1, 64, 0);
        assertThrows(IllegalArgumentException.class, () -> new TemplateArtifact(
                "floor", "r1", WORLD, "arena", VOLUME, "0".repeat(64),
                Map.of(coordinate, "minecraft:stone"), Map.of(other, "RED")));
    }
}
