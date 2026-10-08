package pt.ciaac.minigames.paper.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildBattleTemplateCaptureTest {
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private Server previousServer;

    @TempDir Path temporaryDirectory;

    @BeforeEach void installPrimaryThreadServer() throws Exception {
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        previousServer = (Server) field.get(null);
        field.set(null, proxy(Server.class, (method, args) -> method.getName().equals("isPrimaryThread")
                ? true : defaultValue(method.getReturnType())));
    }

    @AfterEach void restoreServer() throws Exception {
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, previousServer);
    }

    @Test
    void capturesCompleteThreeDimensionalStaticVolumeAndExportsCreateOnly() throws Exception {
        CuboidRegion volume = region(-1, 64, 2, 1, 65, 3);
        World world = world(volume, Material.STONE, true, false, new AtomicInteger(), new AtomicInteger());

        TemplateArtifact artifact = BuildBattleTemplateCapture.capture(world, volume, "bb-test", "build-battle-1");

        assertEquals(12, artifact.blockData().size());
        assertTrue(artifact.coversVolume());
        assertTrue(artifact.colorIds().isEmpty());
        assertTrue(artifact.checksumMatches());
        assertTrue(artifact.blockData().values().stream().allMatch("minecraft:stone"::equals));

        Path root = temporaryDirectory.toRealPath().resolve("templates");
        Path saved = TemplateArtifactExporter.export(artifact, root);
        assertEquals(root.resolve("bb-test.template"), saved);
        assertEquals(artifact, new TemplateArtifactRepository(root).inspect("bb-test").orElseThrow());
        assertThrows(java.io.IOException.class, () -> TemplateArtifactExporter.export(artifact, root));
    }

    @Test
    void refusesFluidsCircuitryAndTileStateBlocks() {
        CuboidRegion volume = region(0, 64, 0, 0, 64, 0);
        for (Material material : new Material[] {Material.WATER, Material.REDSTONE_WIRE}) {
            assertThrows(IllegalArgumentException.class, () -> BuildBattleTemplateCapture.capture(
                    world(volume, material, true, false, new AtomicInteger(), new AtomicInteger()),
                    volume, "bb-test", "r1"));
        }
        assertThrows(IllegalArgumentException.class, () -> BuildBattleTemplateCapture.capture(
                world(volume, Material.CHEST, true, true, new AtomicInteger(), new AtomicInteger()),
                volume, "bb-test", "r1"));
    }

    @Test
    void refusesUnloadedChunkWithoutForcingLoadOrReadingBlocks() {
        CuboidRegion volume = region(0, 64, 0, 0, 64, 0);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger forcedLoads = new AtomicInteger();
        World unloaded = world(volume, Material.STONE, false, false, reads, forcedLoads);

        assertThrows(IllegalStateException.class,
                () -> BuildBattleTemplateCapture.capture(unloaded, volume, "bb-test", "r1"));
        assertEquals(0, reads.get());
        assertEquals(0, forcedLoads.get());
    }

    @Test
    void rejectsInvalidIdentityHeightAndOversizedVolume() {
        CuboidRegion volume = region(0, 64, 0, 0, 64, 0);
        World wrongIdentity = proxy(World.class, (method, args) -> switch (method.getName()) {
            case "getUID" -> UUID.randomUUID();
            case "getName" -> "synthetic";
            default -> defaultValue(method.getReturnType());
        });
        assertThrows(IllegalArgumentException.class,
                () -> BuildBattleTemplateCapture.capture(wrongIdentity, volume, "bb-test", "r1"));

        World shortWorld = proxy(World.class, (method, args) -> switch (method.getName()) {
            case "getUID" -> WORLD_ID;
            case "getName" -> "synthetic";
            case "getMinHeight" -> 0;
            case "getMaxHeight" -> 64;
            default -> defaultValue(method.getReturnType());
        });
        assertThrows(IllegalArgumentException.class,
                () -> BuildBattleTemplateCapture.capture(shortWorld, volume, "bb-test", "r1"));

        CuboidRegion oversized = region(0, 0, 0, 100, 100, 100);
        assertThrows(IllegalArgumentException.class, () -> BuildBattleTemplateCapture.capture(
                world(oversized, Material.STONE, true, false, new AtomicInteger(), new AtomicInteger()),
                oversized, "bb-test", "r1"));
    }

    private static CuboidRegion region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new CuboidRegion(WORLD_ID, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static World world(CuboidRegion bounds, Material material, boolean loaded, boolean tile,
            AtomicInteger reads, AtomicInteger forcedLoads) {
        return proxy(World.class, (method, args) -> switch (method.getName()) {
            case "getUID" -> WORLD_ID;
            case "getName" -> "synthetic-buildbattle";
            case "getMinHeight" -> 0;
            case "getMaxHeight" -> 320;
            case "isChunkLoaded" -> loaded;
            case "getBlockAt" -> {
                reads.incrementAndGet();
                int x = (int) args[0];
                int y = (int) args[1];
                int z = (int) args[2];
                yield block(x, y, z, material, tile);
            }
            case "loadChunk" -> { forcedLoads.incrementAndGet(); yield true; }
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Block block(int x, int y, int z, Material material, boolean tile) {
        BlockData data = proxy(BlockData.class, (method, args) -> switch (method.getName()) {
            case "getMaterial" -> material;
            case "getAsString" -> "minecraft:" + material.name().toLowerCase(java.util.Locale.ROOT);
            default -> defaultValue(method.getReturnType());
        });
        World identity = proxy(World.class, (method, args) -> switch (method.getName()) {
            case "getUID" -> WORLD_ID;
            case "getName" -> "synthetic-buildbattle";
            default -> defaultValue(method.getReturnType());
        });
        BlockState state = tile ? proxy(TileState.class, (method, args) -> defaultValue(method.getReturnType()))
                : proxy(BlockState.class, (method, args) -> defaultValue(method.getReturnType()));
        return proxy(Block.class, (method, args) -> switch (method.getName()) {
            case "getX" -> x;
            case "getY" -> y;
            case "getZ" -> z;
            case "getWorld" -> identity;
            case "getType" -> material;
            case "getBlockData" -> data;
            case "getState" -> state;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (instance, method, arguments) -> invocation.invoke(method, arguments)));
    }

    @FunctionalInterface private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }
}
