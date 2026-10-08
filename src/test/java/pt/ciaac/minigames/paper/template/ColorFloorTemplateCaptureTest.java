package pt.ciaac.minigames.paper.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ColorFloorTemplateCaptureTest {
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private Server previousServer;

    @TempDir Path temporaryDirectory;

    @BeforeEach void installPrimaryThreadServer() throws Exception {
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        previousServer = (Server) field.get(null);
        field.set(null, proxy(Server.class, (method, args) -> method.getName().equals("isPrimaryThread")
                ? true : defaultValue(method, args)));
    }

    @AfterEach void restoreServer() throws Exception {
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, previousServer);
    }

    @Test
    void capturesExactIdentityCellsAndCanonicalChecksumThenRoundTripsThroughRepository() throws Exception {
        CuboidRegion floor = region(0, 64, 0, 1, 64, 1);
        World world = world("synthetic-floor", floor, Material.RED_CONCRETE, true);

        TemplateArtifact artifact = ColorFloorTemplateCapture.capture(world, floor, "color-floor-1");

        assertEquals(ColorFloorTemplateCapture.ARTIFACT_ID, artifact.artifactId());
        assertEquals("color-floor-1", artifact.revision());
        assertEquals(WORLD_ID, artifact.worldId());
        assertEquals("synthetic-floor", artifact.worldName());
        assertEquals(4, artifact.blockData().size());
        assertEquals(4, artifact.colorIds().size());
        assertEquals("RED", artifact.colorIds().values().iterator().next());
        assertTrue(artifact.checksumMatches());

        Path root = temporaryDirectory.toRealPath().resolve("templates");
        Path saved = ColorFloorTemplateCapture.export(artifact, root);
        TemplateArtifact parsed = new TemplateArtifactRepository(root).inspect(artifact.artifactId()).orElseThrow();
        assertEquals(root.resolve("immutable-floor-template.template"), saved);
        assertEquals(artifact, parsed);
        assertTrue(parsed.checksumMatches());
        assertThrows(java.io.IOException.class, () -> ColorFloorTemplateCapture.export(artifact, root));
    }

    @Test
    void rejectsUnsupportedMaterialsAndUnloadedChunksWithoutReadingBlocks() {
        CuboidRegion floor = region(0, 64, 0, 0, 64, 0);
        assertThrows(IllegalArgumentException.class,
                () -> ColorFloorTemplateCapture.capture(world("synthetic-floor", floor, Material.STONE, true), floor, "r1"));

        World unloaded = world("synthetic-floor", floor, Material.RED_WOOL, false);
        assertThrows(IllegalStateException.class,
                () -> ColorFloorTemplateCapture.capture(unloaded, floor, "r1"));
    }

    @Test
    void rejectsWrongWorldIdentityMultiHeightAndOversizedFloor() {
        CuboidRegion floor = region(0, 64, 0, 0, 64, 0);
        UUID otherWorldId = UUID.randomUUID();
        World wrongWorld = world("synthetic-floor", regionWithWorld(otherWorldId, 0, 64, 0, 0, 64, 0),
                Material.RED_WOOL, true);
        assertThrows(IllegalArgumentException.class,
                () -> ColorFloorTemplateCapture.capture(wrongWorld,
                        regionWithWorld(otherWorldId, 0, 64, 0, 0, 64, 0), "r1"));
        assertThrows(IllegalArgumentException.class,
                () -> ColorFloorTemplateCapture.capture(world("synthetic-floor", floor, Material.RED_WOOL, true),
                        region(0, 64, 0, 0, 65, 0), "r1"));
        assertThrows(IllegalArgumentException.class,
                () -> ColorFloorTemplateCapture.capture(world("synthetic-floor", floor, Material.RED_WOOL, true),
                        region(0, -1, 0, 0, -1, 0), "r1"));
        assertThrows(IllegalArgumentException.class,
                () -> ColorFloorTemplateCapture.capture(world("synthetic-floor", floor, Material.RED_WOOL, true),
                        region(0, 320, 0, 0, 320, 0), "r1"));
        assertThrows(IllegalArgumentException.class,
                () -> ColorFloorTemplateCapture.capture(world("synthetic-floor", floor, Material.RED_WOOL, true),
                        region(0, 64, 0, 64, 64, 63), "r1"));
    }

    @Test
    void rejectsSymlinkAncestorsAndSymlinkTargets() throws Exception {
        TemplateArtifact artifact = ColorFloorTemplateCapture.capture(
                world("synthetic-floor", region(0, 64, 0, 0, 64, 0), Material.CYAN_WOOL, true),
                region(0, 64, 0, 0, 64, 0), "r1");
        Path base = temporaryDirectory.toRealPath();
        Path realDirectory = Files.createDirectory(base.resolve("real"));
        Path ancestorLink = base.resolve("ancestor-link");
        Files.createSymbolicLink(ancestorLink, realDirectory);
        assertThrows(java.io.IOException.class,
                () -> ColorFloorTemplateCapture.export(artifact, ancestorLink.resolve("templates")));

        Path targetLinkDirectory = Files.createDirectory(base.resolve("target-link-dir"));
        Path outsideTarget = Files.createFile(base.resolve("outside.template"));
        Files.createSymbolicLink(targetLinkDirectory.resolve("immutable-floor-template.template"), outsideTarget);
        assertThrows(java.io.IOException.class,
                () -> ColorFloorTemplateCapture.export(artifact, targetLinkDirectory));
        assertTrue(Files.isSymbolicLink(targetLinkDirectory.resolve("immutable-floor-template.template")));
        assertTrue(Files.isRegularFile(outsideTarget));
    }

    private static CuboidRegion region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return regionWithWorld(WORLD_ID, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static CuboidRegion regionWithWorld(UUID id, int minX, int minY, int minZ,
                                                int maxX, int maxY, int maxZ) {
        return new CuboidRegion(id, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static World world(String name, CuboidRegion floor, Material material, boolean loaded) {
        return proxy(World.class, (method, args) -> switch (method.getName()) {
            case "getUID" -> WORLD_ID;
            case "getName" -> name;
            case "getMinHeight" -> 0;
            case "getMaxHeight" -> 320;
            case "isChunkLoaded" -> loaded;
            case "getBlockAt" -> block((int) args[0], (int) args[1], (int) args[2], material,
                    worldIdentity(name));
            default -> defaultValue(method, args);
        });
    }

    private static World worldIdentity(String name) {
        return proxy(World.class, (method, args) -> switch (method.getName()) {
            case "getUID" -> WORLD_ID;
            case "getName" -> name;
            default -> defaultValue(method, args);
        });
    }

    private static Block block(int x, int y, int z, Material material, World world) {
        BlockData data = proxy(BlockData.class, (method, args) -> method.getName().equals("getAsString")
                ? "minecraft:" + material.name().toLowerCase(java.util.Locale.ROOT)
                : defaultValue(method, args));
        return proxy(Block.class, (method, args) -> switch (method.getName()) {
            case "getX" -> x;
            case "getY" -> y;
            case "getZ" -> z;
            case "getWorld" -> world;
            case "getType" -> material;
            case "getBlockData" -> data;
            default -> defaultValue(method, args);
        });
    }

    private static Object defaultValue(java.lang.reflect.Method method, Object[] args) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == byte.class) return (byte) 0;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        if (method.getName().equals("equals")) return args != null && args.length == 1 && args[0] == null;
        if (method.getName().equals("hashCode")) return System.identityHashCode(args);
        if (method.getName().equals("toString")) return "proxy";
        return null;
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> invocation.invoke(method, args)));
    }

    @FunctionalInterface private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
