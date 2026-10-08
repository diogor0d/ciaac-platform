package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.*;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleConfig;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleTheme;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleThemePool;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleTiePolicy;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleVotingCompletionPolicy;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.paper.buildbattle.BuildBattleResetPort;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedBuildBattleConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.TemplateResolution;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import pt.ciaac.minigames.paper.template.TemplateArtifact;
import pt.ciaac.minigames.paper.template.TemplateArtifactExporter;
import pt.ciaac.minigames.paper.template.TemplateArtifactRepository;

class BuildBattleWorldStateTest {
    @TempDir Path temporary;
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000141");
    private Server previousServer;
    private final AtomicInteger blockDataCreations = new AtomicInteger();

    @BeforeEach void installBukkitServer() throws Exception {
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        previousServer = (Server) field.get(null);
        field.set(null, proxy(Server.class, (method, args) -> switch (method.getName()) {
            case "isPrimaryThread" -> true;
            case "createBlockData" -> { blockDataCreations.incrementAndGet(); yield blockData((String) args[0]); }
            default -> defaultValue(method.getReturnType());
        }));
    }

    @AfterEach void restoreBukkitServer() throws Exception {
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, previousServer);
    }

    @Test void resetsOnlyTheUnionOfPlotsAndPurgesEveryMatchOwnerBeforeRestoration() throws Exception {
        Fixture fixture = new Fixture(temporary.resolve("templates"));
        try (var ledger = new ArenaWorldLedger(temporary.resolve("ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var state = fixture.state(ledger, journal);
            PlayerStateOperation first = fixture.capture(1, 1);
            PlayerStateOperation second = fixture.capture(2, 2);
            byte[] firstManifest = state.capture(first);
            byte[] secondManifest = state.capture(second);
            assertArrayEquals(firstManifest, secondManifest);
            state.enter(fixture.phase(first, PlayerStateOperation.Kind.ENTER, 11));
            state.enter(fixture.phase(second, PlayerStateOperation.Kind.ENTER, 12));
            fixture.dirtyOwnedPlots("minecraft:dirt");
            int writesBefore = fixture.writes.get();

            state.purge(fixture.phase(first, PlayerStateOperation.Kind.PURGE, 13));

            assertEquals(ArenaWorldLedger.Status.PURGED, ledger.requireLease(first).status());
            assertEquals(ArenaWorldLedger.Status.PURGED, ledger.requireLease(second).status());
            assertEquals(fixture.ownedCells(), fixture.writes.get() - writesBefore);
            assertEquals("minecraft:chest", fixture.gapValue());
            assertEquals(0, fixture.gapReads.get(), "gap cells must never be read or mutated");
            state.restore(fixture.phase(first, PlayerStateOperation.Kind.RESTORE, 14), 1, firstManifest);
            state.restore(fixture.phase(second, PlayerStateOperation.Kind.RESTORE, 15), 1, secondManifest);
            assertEquals(ArenaWorldLedger.Status.RESTORED, ledger.requireLease(first).status());
            assertEquals(ArenaWorldLedger.Status.RESTORED, ledger.requireLease(second).status());
        }
    }

    @Test void unsupportedOwnedBlockAndTemplateDriftFailBeforeWrites() throws Exception {
        Fixture fixture = new Fixture(temporary.resolve("templates"));
        try (var ledger = new ArenaWorldLedger(temporary.resolve("ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var state = fixture.state(ledger, journal);
            var captured = fixture.capture(3, 3);
            byte[] payload = state.capture(captured);
            state.enter(fixture.phase(captured, PlayerStateOperation.Kind.ENTER, 31));
            fixture.setOwned("minecraft:chest");
            assertThrows(IllegalStateException.class,
                    () -> state.purge(fixture.phase(captured, PlayerStateOperation.Kind.PURGE, 32)));
            assertEquals(0, fixture.writes.get());
            assertEquals(ArenaWorldLedger.Status.ARMED, ledger.requireLease(captured).status());

            fixture.setOwned("minecraft:dirt");
            fixture.writeArtifact("bb-template-drift", "build-battle-r1", "minecraft:glass");
            fixture.config.set(fixture.configuration("bb-template-drift", "build-battle-r1"));
            assertThrows(IllegalStateException.class, () -> state.validate(
                    fixture.phase(captured, PlayerStateOperation.Kind.PURGE, 33), 1, payload));
            assertEquals(0, fixture.writes.get());
        }
    }

    @Test void captureRejectsAnotherUnfinishedMatchAndResetHandleIsColdReconstructible() throws Exception {
        Fixture fixture = new Fixture(temporary.resolve("templates"));
        try (var ledger = new ArenaWorldLedger(temporary.resolve("ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var state = fixture.state(ledger, journal);
            PlayerStateOperation captured = fixture.capture(4, 4);
            state.capture(captured);
            assertThrows(IllegalStateException.class,
                    () -> state.capture(fixture.capture(5, 5, UUID.randomUUID())));
            state.enter(fixture.phase(captured, PlayerStateOperation.Kind.ENTER, 41));
            fixture.dirtyOwnedPlots("minecraft:dirt");
            var reset = state.resetPort();
            var started = reset.beginReset(captured.matchId(), fixture.world);
            var handle = started.pending().orElseThrow();
            assertEquals(0, fixture.writes.get(), "beginReset only prepares durable intent and a handle");
            BuildBattleWorldState coldRecovery = fixture.state(ledger, journal);
            var progress = coldRecovery.resetPort().pollReset(handle);
            assertTrue(progress.complete());
            assertTrue(progress.successful());
            assertEquals(ArenaWorldLedger.Status.PURGED, ledger.requireLease(captured).status());
        }
    }

    @Test void retainedResetBatchesReuseReviewedArtifactAndAvoidRepeatedOwnedCellScans() throws Exception {
        Fixture fixture = new Fixture(temporary.resolve("templates"), 16);
        try (var ledger = new ArenaWorldLedger(temporary.resolve("ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var state = fixture.state(ledger, journal);
            PlayerStateOperation captured = fixture.capture(6, 6);
            state.capture(captured);
            state.enter(fixture.phase(captured, PlayerStateOperation.Kind.ENTER, 61));
            fixture.dirtyOwnedPlots("minecraft:dirt");
            var started = state.resetPort().beginReset(captured.matchId(), fixture.world);
            var handle = started.pending().orElseThrow();
            int artifactParsesBeforePoll = blockDataCreations.get();
            int cellReadsBeforePoll = fixture.blockReads.get();

            var first = state.resetPort().pollReset(handle);
            assertFalse(first.complete(), "512 cells require two 256-cell batches");
            var second = state.resetPort().pollReset(handle);
            assertTrue(second.complete());
            assertTrue(second.successful());

            assertEquals(artifactParsesBeforePoll, blockDataCreations.get(),
                    "retained ticks must reuse this reset's reviewed immutable artifact");
            assertEquals(3 * fixture.ownedCells(), fixture.blockReads.get() - cellReadsBeforePoll,
                    "reset reads each changed cell for CAS and apply verification, then each baseline cell once");
            assertEquals(ArenaWorldLedger.Status.PURGED, ledger.requireLease(captured).status());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> handler.invoke(method, args));
    }

    private static Material material(String data) {
        String block = data.substring(data.indexOf(':') + 1).split("[\\[]", 2)[0].toUpperCase(java.util.Locale.ROOT);
        return Material.valueOf(block);
    }

    private static BlockData blockData(String text) {
        String canonical = text.contains(":") ? text : "minecraft:" + text;
        Material type = material(canonical);
        return proxy(BlockData.class, (method, args) -> switch (method.getName()) {
            case "getAsString" -> canonical;
            case "getMaterial" -> type;
            case "clone" -> blockData(canonical);
            case "equals" -> args[0] instanceof BlockData other && canonical.equals(other.getAsString());
            case "hashCode" -> canonical.hashCode();
            case "toString" -> canonical;
            default -> defaultValue(method.getReturnType());
        });
    }

    private final class Fixture {
        final UUID matchId = UUID.randomUUID();
        final int plotSide;
        final List<CuboidRegion> plotBounds;
        final CuboidRegion lobby = new CuboidRegion(WORLD_ID, 50, 64, 0, 52, 64, 2);
        final Map<String, BlockData> live = new HashMap<>();
        final AtomicInteger writes = new AtomicInteger(), gapReads = new AtomicInteger(), blockReads = new AtomicInteger();
        final AtomicReference<ResolvedBuildBattleConfiguration> config = new AtomicReference<>();
        final ProtectedRegionRegistry protectedRegions = new ProtectedRegionRegistry();
        final World world;
        final Server server;
        final Path templateRoot;

        Fixture(Path templateRoot) throws Exception {
            this(templateRoot, 4);
        }

        Fixture(Path templateRoot, int plotSide) throws Exception {
            this.templateRoot = templateRoot;
            this.plotSide = plotSide;
            this.plotBounds = List.of(
                    new CuboidRegion(WORLD_ID, 0, 64, 0, plotSide - 1, 64, plotSide - 1),
                    new CuboidRegion(WORLD_ID, plotSide + 1, 64, 0, plotSide * 2, 64, plotSide - 1));
            this.world = proxy(World.class, (method, args) -> switch (method.getName()) {
                case "getUID" -> WORLD_ID;
                case "getName" -> "fixture-buildbattle";
                case "getMinHeight" -> 0;
                case "getMaxHeight" -> 320;
                case "isChunkLoaded" -> true;
                case "getBlockAt" -> block((int) args[0], (int) args[1], (int) args[2]);
                default -> defaultValue(method.getReturnType());
            });
            this.server = proxy(Server.class, (method, args) -> switch (method.getName()) {
                case "isPrimaryThread" -> true;
                case "getVersion" -> "Paper fixture 1.21.5";
                case "getWorld" -> args[0] instanceof UUID id ? id.equals(WORLD_ID) ? world : null
                        : "fixture-buildbattle".equals(args[0]) ? world : null;
                default -> defaultValue(method.getReturnType());
            });
            writeArtifact("bb-template", "build-battle-r1", "minecraft:stone");
            config.set(configuration("bb-template", "build-battle-r1"));
        }

        ResolvedBuildBattleConfiguration configuration(String artifactId, String revision) {
            var theme = new BuildBattleTheme("space", "Space");
            var plots = new LinkedHashMap<String, ResolvedBuildBattleConfiguration.PlotDefinition>();
            plots.put("plot-a", new ResolvedBuildBattleConfiguration.PlotDefinition("plot-a", "plot-a",
                    new Location(world, plotSide / 2d, 64, plotSide / 2d), TemplateResolution.unresolved("plot-a", "reset via shared template")));
            plots.put("plot-b", new ResolvedBuildBattleConfiguration.PlotDefinition("plot-b", "plot-b",
                    new Location(world, plotSide + 1 + plotSide / 2d, 64, plotSide / 2d), TemplateResolution.unresolved("plot-b", "reset via shared template")));
            return new ResolvedBuildBattleConfiguration(world,
                    Map.of("lobby", new Location(world, 51.5, 64, 1.5)),
                    Map.of("lobby", lobby, "plot-a", plotBounds.get(0), "plot-b", plotBounds.get(1)),
                    "build-battle-r1", com.ciaac.minecraft.minigames.isolation.IsolationPolicy.strictNoProgress(),
                    new BuildBattleConfig(2, 2, 1, 5, theme,
                            BuildBattleVotingCompletionPolicy.EVERY_ELIGIBLE_VOTER_RATES_EVERY_OTHER_PLOT,
                            BuildBattleTiePolicy.AVERAGE_THEN_TOTAL_THEN_PLOT_ID),
                    new BuildBattleThemePool(List.of(theme, new BuildBattleTheme("ocean", "Ocean"))),
                    artifactId, plots, Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofMinutes(5),
                    Duration.ofSeconds(15), plotSide, 1, 10, TemplateResolution.readyForAdapter(artifactId), Duration.ofMinutes(1));
        }

        void writeArtifact(String artifactId, String revision, String data) throws Exception {
            CuboidRegion bbox = new CuboidRegion(WORLD_ID, 0, 64, 0, plotSide * 2, 64, plotSide - 1);
            Map<TemplateArtifact.BlockCoordinate, String> blocks = new LinkedHashMap<>();
            for (int x = bbox.minX(); x <= bbox.maxX(); x++)
                for (int y = bbox.minY(); y <= bbox.maxY(); y++)
                    for (int z = bbox.minZ(); z <= bbox.maxZ(); z++)
                        blocks.put(new TemplateArtifact.BlockCoordinate(x, y, z), data);
            var draft = new TemplateArtifact(artifactId, revision, WORLD_ID, "fixture-buildbattle", bbox,
                    "0".repeat(64), blocks, Map.of());
            var artifact = new TemplateArtifact(artifactId, revision, WORLD_ID, "fixture-buildbattle", bbox,
                    draft.calculateChecksum(), blocks, Map.of());
            TemplateArtifactExporter.export(artifact, templateRoot);
        }

        BuildBattleWorldState state(ArenaWorldLedger ledger, ExternalOperationJournal journal) {
            if (protectedRegions.all().isEmpty()) {
                protectedRegions.register(new ProtectedRegion("build-battle.lobby", GameKey.BUILD_BATTLE,
                    lobby, ProtectedRegionRole.PARTICIPANT_ONLY, true));
                protectedRegions.register(new ProtectedRegion("build-battle.plot-a", GameKey.BUILD_BATTLE,
                    plotBounds.get(0), ProtectedRegionRole.PARTICIPANT_ONLY, false));
                protectedRegions.register(new ProtectedRegion("build-battle.plot-b", GameKey.BUILD_BATTLE,
                    plotBounds.get(1), ProtectedRegionRole.PARTICIPANT_ONLY, false));
            }
            return new BuildBattleWorldState("build-battle-world", server, protectedRegions, ledger, journal,
                    event -> true, config::get, new TemplateArtifactRepository(templateRoot));
        }

        PlayerStateOperation capture(int id, int player) {
            return capture(id, player, matchId);
        }

        PlayerStateOperation capture(int id, int player, UUID match) {
            UUID operation = new UUID(0, id);
            return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, operation, operation,
                    new UUID(1, id), new UUID(2, id), match, new UUID(3, player), new UUID(4, player),
                    new UUID(4, player), GameKey.BUILD_BATTLE, Instant.parse("2026-10-07T12:00:00Z"));
        }

        PlayerStateOperation phase(PlayerStateOperation captured, PlayerStateOperation.Kind kind, int operation) {
            UUID id = new UUID(9, operation);
            return new PlayerStateOperation(kind, id, captured.captureOperationId(), captured.snapshotId(),
                    captured.sessionId(), captured.matchId(), captured.playerId(), captured.capturedConnectionId(),
                    captured.capturedConnectionId(), captured.game(), captured.capturedAt());
        }

        int ownedCells() { return plotBounds.stream().mapToInt(region -> (int) ((long) (region.maxX() - region.minX() + 1)
                * (region.maxY() - region.minY() + 1) * (region.maxZ() - region.minZ() + 1))).sum(); }

        void dirtyOwnedPlots(String raw) { setOwned(raw); }

        void setOwned(String raw) {
            for (CuboidRegion region : plotBounds)
                for (int x = region.minX(); x <= region.maxX(); x++)
                    for (int y = region.minY(); y <= region.maxY(); y++)
                        for (int z = region.minZ(); z <= region.maxZ(); z++) live.put(key(x, y, z), blockData(raw));
        }

        String gapValue() { return live.getOrDefault(key(plotSide, 64, 1), blockData("minecraft:chest")).getAsString(); }

        Block block(int x, int y, int z) {
            boolean owned = plotBounds.stream().anyMatch(region -> region.contains(x, y, z));
            if (!owned) {
                gapReads.incrementAndGet();
                throw new AssertionError("provider accessed a non-owned gap block");
            }
            live.putIfAbsent(key(x, y, z), blockData("minecraft:stone"));
            return proxy(Block.class, (method, args) -> switch (method.getName()) {
                case "getX" -> x;
                case "getY" -> y;
                case "getZ" -> z;
                case "getWorld" -> world;
                case "getBlockData" -> { blockReads.incrementAndGet(); yield live.get(key(x, y, z)); }
                case "getType" -> material(live.get(key(x, y, z)).getAsString());
                case "getState" -> {
                    if (material(live.get(key(x, y, z)).getAsString()) == Material.CHEST)
                        yield proxy(TileState.class, (stateMethod, stateArgs) -> defaultValue(stateMethod.getReturnType()));
                    yield proxy(BlockState.class, (stateMethod, stateArgs) -> defaultValue(stateMethod.getReturnType()));
                }
                case "setBlockData" -> {
                    assertFalse((boolean) args[1], "plot reset must not run block physics");
                    live.put(key(x, y, z), (BlockData) args[0]);
                    writes.incrementAndGet();
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        String key(int x, int y, int z) { return x + ":" + y + ":" + z; }
    }
}
