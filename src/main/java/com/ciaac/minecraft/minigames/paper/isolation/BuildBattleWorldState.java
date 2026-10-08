package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.buildbattle.BuildBattleConfig;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.isolation.WorldRecoveryPendingException;
import com.ciaac.minecraft.minigames.paper.buildbattle.BuildBattleBlockPolicy;
import com.ciaac.minecraft.minigames.paper.buildbattle.BuildBattleResetPort;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedBuildBattleConfiguration;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import pt.ciaac.minigames.paper.template.BlockMutationBatch;
import pt.ciaac.minigames.paper.template.TemplateArtifact;
import pt.ciaac.minigames.paper.template.TemplateArtifactRepository;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;

/** Durable isolation and exact plot-only reset for a dedicated Build Battle facility. */
public final class BuildBattleWorldState {
    private static final int MAGIC = 0x43414242; // CABB
    private static final int VERSION = 1;
    private static final int MAX_MANIFEST = 128 * 1024;
    private static final int MAX_OWNED_CELLS = 65_536;
    private static final int MAX_ARTIFACT_VOLUME = 100_000;
    private static final int RESET_BATCH_SIZE = 256;
    private static final byte[] EMPTY = new byte[0];
    private static final String BLOCK_POLICY_ID = "stateless-construction-block-policy-v1";
    private final String providerId;
    private final Server server;
    private final ProtectedRegionRegistry regions;
    private final ArenaWorldLedger ledger;
    private final ExternalOperationJournal journal;
    private final AuditRepository audit;
    private final Supplier<ResolvedBuildBattleConfiguration> configuration;
    private final TemplateArtifactRepository templates;
    private final Map<UUID, ActiveReset> activeResets = new HashMap<>();
    private final BuildBattleResetPort resetPort = new NativeResetPort();

    private record OwnedCell(TemplateArtifact.BlockCoordinate coordinate, BlockData baseline) { }
    private record Plot(String id, String regionId, ProtectedRegion protectedRegion, Location spawn) { }
    private record Geometry(ResolvedBuildBattleConfiguration config, World world, CuboidRegion boundingVolume,
            List<ProtectedRegion> protectedRegions, List<Plot> plots) { }
    private record Chunk(int x, int z) { }
    private record Facility(ResolvedBuildBattleConfiguration config, World world, CuboidRegion boundingVolume,
            TemplateArtifact artifact, List<ProtectedRegion> protectedRegions, List<Plot> plots,
            List<OwnedCell> cells, List<Chunk> chunks, byte[] manifest) { }
    private record ActiveReset(BuildBattleResetPort.ResetHandle handle, List<ArenaWorldLedger.Lease> owners,
            Facility facility, BlockMutationBatch batch) { }

    public BuildBattleWorldState(String providerId, Server server, ProtectedRegionRegistry regions,
            ArenaWorldLedger ledger, ExternalOperationJournal journal, AuditRepository audit,
            Supplier<ResolvedBuildBattleConfiguration> configuration, TemplateArtifactRepository templates) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.server = Objects.requireNonNull(server, "server");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.templates = Objects.requireNonNull(templates, "templates");
    }

    public byte[] capture(PlayerStateOperation context) {
        require(context, PlayerStateOperation.Kind.CAPTURE);
        Facility facility = facility();
        rejectDifferentUnfinishedMatch(context.matchId());
        byte[] payload = facility.manifest();
        ExternalOperationJournal.State state = journal.begin(providerId, VERSION, context, EMPTY);
        if (state == ExternalOperationJournal.State.COMMITTED) {
            if (!Arrays.equals(payload, journal.committedResult(providerId, VERSION, context, EMPTY))
                    || !Arrays.equals(payload, ledger.requireLease(context).manifest()))
                throw new IllegalStateException("Build Battle capture conflicts with durable state");
            validateCells(facility, false);
            audit(context, EMPTY);
            return payload;
        }
        validateCells(facility, true);
        ledger.capture(context, payload);
        journal.commit(providerId, VERSION, context, EMPTY, payload);
        audit(context, EMPTY);
        return payload;
    }

    public void validate(PlayerStateOperation context, int version, byte[] payload) {
        require(context, null);
        if (version != VERSION || payload == null || payload.length == 0 || payload.length > MAX_MANIFEST)
            throw new IllegalArgumentException("Unsupported Build Battle world snapshot");
        Facility facility = facility();
        var lease = ledger.requireLease(context);
        if (!Arrays.equals(payload, lease.manifest()) || !Arrays.equals(payload, facility.manifest())
                || !Arrays.equals(payload, captured(context)))
            throw new IllegalStateException("Build Battle facility, template, or durable capture differs");
        rejectDifferentUnfinishedMatch(context.matchId());
        List<ArenaWorldLedger.Lease> owners = matchLeases(context.matchId());
        for (var owner : owners) {
            if (!Arrays.equals(owner.manifest(), payload))
                throw new IllegalStateException("Build Battle match contains conflicting facility captures");
        }
        validateCells(facility, context.kind() == PlayerStateOperation.Kind.CAPTURE
                || context.kind() == PlayerStateOperation.Kind.RESTORE);
        if (context.kind() == PlayerStateOperation.Kind.RESTORE) {
            for (var owner : owners) {
                if (owner.status() != ArenaWorldLedger.Status.PURGED
                        && owner.status() != ArenaWorldLedger.Status.RESTORED)
                    throw new IllegalStateException("Every Build Battle plot must be reset before player restoration");
            }
        }
        if (context.kind() == PlayerStateOperation.Kind.ENTER
                && lease.status() != ArenaWorldLedger.Status.CAPTURED
                && lease.status() != ArenaWorldLedger.Status.ARMED)
            throw new IllegalStateException("Build Battle lease cannot be entered from " + lease.status());
        if (context.kind() == PlayerStateOperation.Kind.CAPTURE
                && lease.status() != ArenaWorldLedger.Status.CAPTURED)
            throw new IllegalStateException("Build Battle capture lease is not in its captured phase");
    }

    public void enter(PlayerStateOperation context) {
        require(context, PlayerStateOperation.Kind.ENTER);
        byte[] payload = captured(context);
        validate(context, VERSION, payload);
        ExternalOperationJournal.State state = journal.begin(providerId, VERSION, context, payload);
        if (state == ExternalOperationJournal.State.COMMITTED) {
            requireArmedOrAdvanced(context);
            if (journal.committedResult(providerId, VERSION, context, payload).length != 0)
                throw new IllegalStateException("Build Battle enter replay has an invalid result");
            audit(context, payload);
            return;
        }
        ArenaWorldLedger.Status status = ledger.requireLease(context).status();
        if (status == ArenaWorldLedger.Status.CAPTURED) ledger.arm(context);
        else requireArmedOrAdvanced(context);
        journal.commit(providerId, VERSION, context, payload, EMPTY);
        audit(context, payload);
    }

    public void purge(PlayerStateOperation context) {
        require(context, PlayerStateOperation.Kind.PURGE);
        byte[] payload = captured(context);
        validate(context, VERSION, payload);
        ExternalOperationJournal.State state = journal.begin(providerId, VERSION, context, payload);
        if (state == ExternalOperationJournal.State.COMMITTED) {
            requirePurged(context.matchId());
            validateCells(facility(), true);
            if (journal.committedResult(providerId, VERSION, context, payload).length != 0)
                throw new IllegalStateException("Build Battle purge replay has an invalid result");
            audit(context, payload);
            return;
        }
        var start = resetPort.beginReset(context.matchId(), facility().world());
        if (start.completed().isPresent()) {
            var result = start.completed().orElseThrow();
            if (!result.successful()) throw new IllegalStateException("Build Battle reset failed: " + result.code());
        } else {
            var progress = resetPort.pollReset(start.pending().orElseThrow());
            if (!progress.complete()) throw new WorldRecoveryPendingException();
            if (!progress.successful()) throw new IllegalStateException("Build Battle reset failed: " + progress.code());
        }
        requirePurged(context.matchId());
        journal.commit(providerId, VERSION, context, payload, EMPTY);
        audit(context, payload);
    }

    public void restore(PlayerStateOperation context, int version, byte[] payload) {
        require(context, PlayerStateOperation.Kind.RESTORE);
        validate(context, version, payload);
        ExternalOperationJournal.State state = journal.begin(providerId, VERSION, context, payload);
        if (state == ExternalOperationJournal.State.COMMITTED) {
            if (ledger.requireLease(context).status() != ArenaWorldLedger.Status.RESTORED
                    || journal.committedResult(providerId, VERSION, context, payload).length != 0)
                throw new IllegalStateException("Build Battle restore replay conflicts with durable state");
            audit(context, payload);
            return;
        }
        ledger.markRestored(context);
        journal.commit(providerId, VERSION, context, payload, EMPTY);
        audit(context, payload);
    }

    /** Shared recovery port for normal session exit and cold player-state recovery. */
    public BuildBattleResetPort resetPort() { return resetPort; }

    private final class NativeResetPort implements BuildBattleResetPort {
        @Override public boolean available() {
            try {
                Facility facility = facility();
                validateCells(facility, false);
                return true;
            } catch (RuntimeException unavailable) { return false; }
        }

        @Override public ResetResult reset(UUID matchId, World world) {
            return new ResetResult(false, "ASYNC_RESET_REQUIRED");
        }

        @Override public synchronized ResetStart beginReset(UUID matchId, World world) {
            Objects.requireNonNull(matchId, "matchId");
            Objects.requireNonNull(world, "world");
            if (!server.isPrimaryThread()) return ResetStart.completed(new ResetResult(false, "MAIN_THREAD_REQUIRED"));
            Facility facility;
            List<ArenaWorldLedger.Lease> owners;
            try {
                facility = facility();
                if (world != facility.world()) return ResetStart.completed(new ResetResult(false, "WORLD_IDENTITY_MISMATCH"));
                rejectDifferentUnfinishedMatch(matchId);
                owners = matchLeases(matchId);
                requireMatchingOwners(owners, facility.manifest());
                validateCells(facility, false);
                ActiveReset existing = activeResets.get(matchId);
                if (existing != null) return ResetStart.pending(existing.handle());
                for (var owner : owners) ledger.beginPurge(owner.capture());
                BlockMutationBatch batch = batch(facility);
                BuildBattleResetPort.ResetHandle handle = resetHandle(matchId);
                activeResets.put(matchId, new ActiveReset(handle, owners, facility, batch));
                return ResetStart.pending(handle);
            } catch (RuntimeException failure) {
                return ResetStart.completed(new ResetResult(false, safeCode(failure, "RESET_PREVALIDATION_FAILED")));
            }
        }

        @Override public synchronized ResetProgress pollReset(ResetHandle handle) {
            Objects.requireNonNull(handle, "handle");
            if (!server.isPrimaryThread()) return ResetProgress.failed("MAIN_THREAD_REQUIRED");
            ResetHandle expected = resetHandle(handle.matchId());
            if (!expected.operationId().equals(handle.operationId())) return ResetProgress.failed("RESET_HANDLE_UNKNOWN");
            ActiveReset active = activeResets.get(handle.matchId());
            if (active == null) {
                try {
                    active = reconstruct(handle);
                    activeResets.put(handle.matchId(), active);
                }
                catch (RuntimeException failure) { return ResetProgress.failed(safeCode(failure, "RESET_RECOVERY_FAILED")); }
            }
            try {
                byte[] currentManifest = currentManifest(active.facility());
                requireMatchingOwners(active.owners(), currentManifest);
                requireSameOwners(active.owners(), matchLeases(handle.matchId()));
                if (!Arrays.equals(active.facility().manifest(), currentManifest))
                    throw new IllegalStateException("Build Battle facility changed during reset");
            } catch (RuntimeException failure) {
                activeResets.remove(handle.matchId());
                return ResetProgress.failed(safeCode(failure, "RESET_POLICY_CHANGED"));
            }
            World world = server.getWorld(active.facility().world().getUID());
            if (world != active.facility().world() || !world.getName().equals(active.facility().world().getName())) {
                activeResets.remove(handle.matchId());
                return ResetProgress.failed("WORLD_IDENTITY_MISMATCH");
            }
            try {
                requireLoadedChunks(active.facility());
                BlockMutationBatch.Progress progress = active.batch().apply(world, RESET_BATCH_SIZE);
                if (!progress.complete()) return ResetProgress.running(progress.applied(), progress.remaining());
                if (!progress.successful()) {
                    activeResets.remove(handle.matchId());
                    return ResetProgress.failed(progress.code());
                }
                validateCells(active.facility(), true);
                for (var owner : active.owners()) ledger.completePurge(owner.capture());
                activeResets.remove(handle.matchId());
                return ResetProgress.completed(progress.applied());
            } catch (RuntimeException failure) {
                activeResets.remove(handle.matchId());
                return ResetProgress.failed(safeCode(failure, "RESET_FAILED"));
            }
        }
    }

    private ActiveReset reconstruct(BuildBattleResetPort.ResetHandle handle) {
        Facility facility = facility();
        World world = facility.world();
        List<ArenaWorldLedger.Lease> owners = matchLeases(handle.matchId());
        requireMatchingOwners(owners, facility.manifest());
        for (var owner : owners) {
            if (owner.status() != ArenaWorldLedger.Status.PURGING
                    && owner.status() != ArenaWorldLedger.Status.PURGED
                    && owner.status() != ArenaWorldLedger.Status.RESTORED)
                throw new IllegalStateException("Build Battle reset has no durable purge intent");
        }
        validateCells(facility, false);
        return new ActiveReset(handle, owners, facility, batch(facility));
    }

    private Facility facility() {
        Geometry geometry = geometry();
        ResolvedBuildBattleConfiguration value = geometry.config();
        World world = geometry.world();
        CuboidRegion bounds = geometry.boundingVolume();
        List<ProtectedRegion> owned = geometry.protectedRegions();
        List<Plot> plots = geometry.plots();
        long bboxVolume = volume(bounds);
        long ownedCount = plots.stream().mapToLong(plot -> volume(plot.protectedRegion().bounds())).sum();
        if (value.resetTemplate().unresolved()
                || !value.resetTemplate().identifier().equals(value.worldTemplateMarker()))
            throw new IllegalStateException("Build Battle reset template marker is unresolved or inconsistent");
        TemplateArtifact artifact = templates.load(value.worldTemplateMarker(), world, bounds, value.rulesetRevision())
                .orElseThrow(() -> new IllegalStateException("Build Battle template is unavailable: " + templates.lastDiagnostic().code()));
        validateArtifact(artifact, value, world, bounds, bboxVolume);
        List<OwnedCell> cells = cells(artifact, plots, ownedCount, world);
        byte[] manifest = manifest(value, world, bounds, artifact, owned, plots);
        return new Facility(value, world, bounds, artifact, owned, plots, List.copyOf(cells), chunks(plots), manifest);
    }

    /** Revalidates mutable configuration and region state while retaining only this reset's reviewed artifact. */
    private byte[] currentManifest(Facility reviewed) {
        Geometry geometry = geometry();
        if (geometry.world() != reviewed.world() || !geometry.boundingVolume().equals(reviewed.boundingVolume())
                || !geometry.protectedRegions().equals(reviewed.protectedRegions())
                || !samePlots(geometry.plots(), reviewed.plots()))
            throw new IllegalStateException("Build Battle geometry changed during reset");
        return manifest(geometry.config(), geometry.world(), geometry.boundingVolume(), reviewed.artifact(),
                geometry.protectedRegions(), geometry.plots());
    }

    private static boolean samePlots(List<Plot> left, List<Plot> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            Plot a = left.get(i), b = right.get(i);
            if (!a.id().equals(b.id()) || !a.regionId().equals(b.regionId())
                    || !a.protectedRegion().equals(b.protectedRegion())
                    || !a.spawn().equals(b.spawn())) return false;
        }
        return true;
    }

    private Geometry geometry() {
        ResolvedBuildBattleConfiguration value = Objects.requireNonNull(configuration.get(), "Build Battle configuration");
        World world = value.world();
        UUID worldId = world.getUID();
        if (!server.isPrimaryThread() || server.getWorld(worldId) != world
                || server.getWorld(world.getName()) != world)
            throw new IllegalStateException("Build Battle world is not the exact loaded native world");
        var lobbyBounds = Objects.requireNonNull(value.regions().get("lobby"), "Build Battle lobby region");
        var lobby = regions.find("build-battle.lobby").orElseThrow();
        if (!worldId.equals(lobbyBounds.worldId()) || !lobby.game().equals(GameKey.BUILD_BATTLE)
                || lobby.role() != ProtectedRegionRole.PARTICIPANT_ONLY || !lobby.immutable()
                || !lobby.bounds().equals(lobbyBounds))
            throw new IllegalStateException("Build Battle lobby protection differs from configuration");
        List<Plot> plots = value.plots().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    var definition = entry.getValue();
                    if (!entry.getKey().equals(definition.id())) throw new IllegalStateException("Build Battle plot key differs from its id");
                    CuboidRegion bounds = Objects.requireNonNull(value.regions().get(definition.regionId()),
                            "Build Battle plot bounds " + definition.regionId());
                    var protectedRegion = regions.find(regionId(definition.regionId())).orElseThrow();
                    if (!bounds.worldId().equals(worldId) || !protectedRegion.bounds().equals(bounds)
                            || !protectedRegion.game().equals(GameKey.BUILD_BATTLE)
                            || protectedRegion.role() != ProtectedRegionRole.PARTICIPANT_ONLY
                            || protectedRegion.immutable() || definition.spawn().getWorld() != world
                            || !bounds.contains(definition.spawn()))
                        throw new IllegalStateException("Build Battle plot protection or spawn is invalid");
                    return new Plot(definition.id(), definition.regionId(), protectedRegion, definition.spawn());
                }).toList();
        if (plots.size() < value.domain().maximumPlayers() || plots.isEmpty())
            throw new IllegalStateException("Build Battle has insufficient configured plots");
        Set<String> expectedRegionKeys = new HashSet<>();
        expectedRegionKeys.add("lobby");
        plots.forEach(plot -> expectedRegionKeys.add(plot.regionId()));
        if (!value.regions().keySet().equals(expectedRegionKeys))
            throw new IllegalStateException("Build Battle configured regions contain missing or unsupported geometry");
        List<ProtectedRegion> owned = regions.all().stream().filter(region -> region.game() == GameKey.BUILD_BATTLE)
                .sorted(Comparator.comparing(ProtectedRegion::id)).toList();
        if (owned.size() != plots.size() + 1 || !owned.contains(lobby)
                || !owned.containsAll(plots.stream().map(Plot::protectedRegion).toList()))
            throw new IllegalStateException("Build Battle protected region set is incomplete or contains extras");
        for (int i = 0; i < plots.size(); i++) {
            for (int j = i + 1; j < plots.size(); j++) {
                if (plots.get(i).protectedRegion().bounds().intersects(plots.get(j).protectedRegion().bounds()))
                    throw new IllegalStateException("Build Battle mutable plots overlap");
            }
            if (lobby.bounds().intersects(plots.get(i).protectedRegion().bounds()))
                throw new IllegalStateException("Build Battle lobby overlaps a mutable plot");
        }
        for (Location location : value.locations().values()) {
            if (location.getWorld() != world || location.getY() < world.getMinHeight()
                    || location.getY() >= world.getMaxHeight())
                throw new IllegalStateException("Build Battle location has an incompatible world or height");
        }
        Location lobbySpawn = value.locations().get("lobby");
        if (lobbySpawn == null || !lobbyBounds.contains(lobbySpawn))
            throw new IllegalStateException("Build Battle lobby spawn is outside its protected region");
        var bounds = bounding(plots.stream().map(plot -> plot.protectedRegion().bounds()).toList());
        long bboxVolume = volume(bounds);
        if (bboxVolume > MAX_ARTIFACT_VOLUME) throw new IllegalStateException("Build Battle template bounding volume exceeds 100000 cells");
        long ownedCount = 0;
        for (Plot plot : plots) {
            ownedCount += volume(plot.protectedRegion().bounds());
            if (ownedCount > MAX_OWNED_CELLS) throw new IllegalStateException("Build Battle owned plots exceed 65536 cells");
        }
        if (bounds.minY() < world.getMinHeight() || bounds.maxY() >= world.getMaxHeight()
                || lobby.bounds().minY() < world.getMinHeight() || lobby.bounds().maxY() >= world.getMaxHeight())
            throw new IllegalStateException("Build Battle protected geometry is outside world height");
        return new Geometry(value, world, bounds, owned, plots);
    }

    private void validateArtifact(TemplateArtifact artifact, ResolvedBuildBattleConfiguration value,
            World world, CuboidRegion bounds, long bboxVolume) {
        if (value.resetTemplate().unresolved()
                || !value.resetTemplate().identifier().equals(value.worldTemplateMarker()))
            throw new IllegalStateException("Build Battle reset template marker is unresolved or inconsistent");
        if (!artifact.coversVolume() || !artifact.checksumMatches() || !artifact.colorIds().isEmpty()
                || artifact.blockData().size() != bboxVolume
                || !artifact.worldId().equals(world.getUID()) || !artifact.worldName().equals(world.getName())
                || !artifact.revision().equals(value.rulesetRevision()) || !artifact.volume().equals(bounds))
            throw new IllegalStateException("Build Battle template coverage or immutable identity is invalid");
        validateArtifactPolicy(artifact);
    }

    private List<OwnedCell> cells(TemplateArtifact artifact, List<Plot> plots, long ownedCount, World world) {
        List<OwnedCell> cells = new ArrayList<>((int) ownedCount);
        Set<TemplateArtifact.BlockCoordinate> seen = new HashSet<>();
        for (Plot plot : plots) {
            CuboidRegion region = plot.protectedRegion().bounds();
            for (int x = region.minX(); ; x++) {
                for (int y = region.minY(); ; y++) {
                    for (int z = region.minZ(); ; z++) {
                        var coordinate = new TemplateArtifact.BlockCoordinate(x, y, z);
                        if (!seen.add(coordinate)) throw new IllegalStateException("Build Battle plot cell is multiply owned");
                        String raw = artifact.blockData().get(coordinate);
                        if (raw == null) throw new IllegalStateException("Build Battle plot template is incomplete");
                        BlockData baseline = Bukkit.createBlockData(raw);
                        if (!BuildBattleBlockPolicy.allows(baseline))
                            throw new IllegalStateException("Build Battle baseline contains a disallowed block");
                        if (z == region.maxZ()) break;
                    }
                    if (y == region.maxY()) break;
                }
                if (x == region.maxX()) break;
            }
        }
        for (var coordinate : seen) {
            int chunkX = coordinate.x() >> 4, chunkZ = coordinate.z() >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) throw new IllegalStateException("Build Battle plot chunk is not loaded");
            String raw = artifact.blockData().get(coordinate);
            cells.add(new OwnedCell(coordinate, Bukkit.createBlockData(raw)));
        }
        cells.sort(Comparator.comparingInt((OwnedCell cell) -> cell.coordinate().x())
                .thenComparingInt(cell -> cell.coordinate().y()).thenComparingInt(cell -> cell.coordinate().z()));
        return cells;
    }

    private void validateArtifactPolicy(TemplateArtifact artifact) {
        for (String raw : artifact.blockData().values()) {
            BlockData data = Bukkit.createBlockData(raw);
            if (!BuildBattleBlockPolicy.allows(data) || !data.getAsString().equals(raw))
                throw new IllegalStateException("Build Battle artifact contains a disallowed or noncanonical block state");
        }
    }

    private void validateCells(Facility facility, boolean requireBaseline) {
        World world = facility.world();
        if (server.getWorld(world.getUID()) != world || server.getWorld(world.getName()) != world)
            throw new IllegalStateException("Build Battle world identity changed");
        for (OwnedCell cell : facility.cells()) {
            var coordinate = cell.coordinate();
            if (!world.isChunkLoaded(coordinate.x() >> 4, coordinate.z() >> 4))
                throw new IllegalStateException("Build Battle plot chunk is not loaded");
            Block block = world.getBlockAt(coordinate.x(), coordinate.y(), coordinate.z());
            if (block.getWorld() != world || block.getX() != coordinate.x() || block.getY() != coordinate.y()
                    || block.getZ() != coordinate.z() || block.getState() instanceof TileState)
                throw new IllegalStateException("Build Battle plot block identity or state is unsafe");
            BlockData current = block.getBlockData();
            if (current == null || block.getType() != current.getMaterial() || !BuildBattleBlockPolicy.allows(current))
                throw new IllegalStateException("Build Battle plot contains a disallowed construction block");
            if (requireBaseline && !current.equals(cell.baseline()))
                throw new IllegalStateException("Build Battle plot differs from its immutable template baseline");
        }
    }

    private static List<Chunk> chunks(List<Plot> plots) {
        Set<Chunk> chunks = new HashSet<>();
        for (Plot plot : plots) {
            CuboidRegion bounds = plot.protectedRegion().bounds();
            for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++)
                for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) chunks.add(new Chunk(x, z));
        }
        return chunks.stream().sorted(Comparator.comparingInt(Chunk::x).thenComparingInt(Chunk::z)).toList();
    }

    private static void requireLoadedChunks(Facility facility) {
        for (Chunk chunk : facility.chunks()) {
            if (!facility.world().isChunkLoaded(chunk.x(), chunk.z()))
                throw new IllegalStateException("Build Battle plot chunk is not loaded");
        }
    }

    private BlockMutationBatch batch(Facility facility) {
        List<BlockMutationBatch.Mutation> mutations = new ArrayList<>();
        for (OwnedCell cell : facility.cells()) {
            var coordinate = cell.coordinate();
            BlockData current = facility.world().getBlockAt(coordinate.x(), coordinate.y(), coordinate.z()).getBlockData();
            if (!current.equals(cell.baseline()))
                mutations.add(new BlockMutationBatch.Mutation(coordinate.x(), coordinate.y(), coordinate.z(),
                        current, cell.baseline()));
        }
        return new BlockMutationBatch(facility.world().getUID(), facility.world().getName(), mutations);
    }

    private byte[] manifest(ResolvedBuildBattleConfiguration value, World world, CuboidRegion boundingVolume,
            TemplateArtifact artifact, List<ProtectedRegion> protectedRegions, List<Plot> plots) {
        String paperVersion = Objects.requireNonNull(server.getVersion(), "Paper version");
        if (paperVersion.isBlank() || paperVersion.length() > 512) throw new IllegalStateException("Invalid Paper version");
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(paperVersion);
                out.writeUTF(world.getName()); writeUuid(out, world.getUID());
                out.writeUTF(value.rulesetRevision()); out.writeUTF(value.worldTemplateMarker());
                out.writeUTF(artifact.artifactId()); out.writeUTF(artifact.revision());
                out.writeUTF(artifact.checksumSha256()); writeRegion(out, boundingVolume);
                out.writeUTF(BLOCK_POLICY_ID);
                out.writeInt(value.regions().size());
                for (var entry : value.regions().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    writeUtf(out, entry.getKey()); writeRegion(out, entry.getValue());
                }
                out.writeInt(protectedRegions.size());
                for (ProtectedRegion region : protectedRegions) {
                    out.writeUTF(region.id()); out.writeUTF(region.role().name()); out.writeBoolean(region.immutable());
                    writeRegion(out, region.bounds());
                }
                out.writeInt(plots.size());
                for (Plot plot : plots) {
                    out.writeUTF(plot.id()); out.writeUTF(plot.regionId());
                    writeLocation(out, plot.spawn());
                    var definition = value.plots().get(plot.id());
                    out.writeUTF(definition.resetInput().state().name());
                    out.writeUTF(definition.resetInput().identifier());
                    out.writeUTF(definition.resetInput().reason());
                }
                out.writeInt(value.locations().size());
                for (var entry : value.locations().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    writeUtf(out, entry.getKey()); writeLocation(out, entry.getValue());
                }
                BuildBattleConfig domain = value.domain();
                out.writeInt(domain.minimumPlayers()); out.writeInt(domain.maximumPlayers());
                out.writeInt(domain.minimumVote()); out.writeInt(domain.maximumVote());
                out.writeUTF(domain.theme().id()); out.writeUTF(domain.theme().displayName());
                out.writeUTF(domain.votingCompletionPolicy().name()); out.writeUTF(domain.tiePolicy().name());
                out.writeInt(value.themes().themes().size());
                for (var theme : value.themes().themes()) { out.writeUTF(theme.id()); out.writeUTF(theme.displayName()); }
                out.writeLong(value.queueDuration().toNanos()); out.writeLong(value.themeVoteDuration().toNanos());
                out.writeLong(value.buildDuration().toNanos()); out.writeLong(value.voteDuration().toNanos());
                out.writeInt(value.plotSize()); out.writeInt(value.plotSpacing()); out.writeInt(value.secondsPerPlot());
                out.writeLong(value.resetTimeout().toNanos());
                out.writeUTF(value.resetTemplate().state().name()); out.writeUTF(value.resetTemplate().identifier());
                out.writeUTF(value.resetTemplate().reason());
                out.writeInt(value.isolation().protectedFacets().size());
                value.isolation().protectedFacets().stream().map(facet -> facet.name()).sorted()
                        .forEach(facet -> writeUtf(out, facet));
            }
            byte[] payload = bytes.toByteArray();
            if (payload.length > MAX_MANIFEST) throw new IllegalStateException("Build Battle manifest exceeds 128 KiB");
            return payload;
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private List<ArenaWorldLedger.Lease> matchLeases(UUID matchId) {
        List<ArenaWorldLedger.Lease> owners = ledger.leasesForMatch(matchId, GameKey.BUILD_BATTLE);
        if (owners.isEmpty()) throw new IllegalStateException("Build Battle match has no durable owner");
        return owners;
    }

    private void requireMatchingOwners(List<ArenaWorldLedger.Lease> owners, byte[] manifest) {
        if (owners.isEmpty()) throw new IllegalStateException("Build Battle reset has no durable owners");
        for (var owner : owners) if (!Arrays.equals(owner.manifest(), manifest))
            throw new IllegalStateException("Build Battle match contains conflicting facility captures");
    }

    private void requireSameOwners(List<ArenaWorldLedger.Lease> expected, List<ArenaWorldLedger.Lease> current) {
        if (expected.size() != current.size())
            throw new IllegalStateException("Build Battle match ownership changed during reset");
        Map<UUID, ArenaWorldLedger.Lease> byCapture = new HashMap<>();
        for (var owner : expected) byCapture.put(owner.capture().operationId(), owner);
        for (var owner : current) {
            var prior = byCapture.remove(owner.capture().operationId());
            if (prior == null || !prior.capture().equals(owner.capture())
                    || !Arrays.equals(prior.manifest(), owner.manifest()))
                throw new IllegalStateException("Build Battle match ownership changed during reset");
        }
        if (!byCapture.isEmpty()) throw new IllegalStateException("Build Battle match ownership changed during reset");
    }

    private void rejectDifferentUnfinishedMatch(UUID matchId) {
        for (var lease : ledger.unfinishedLeases(GameKey.BUILD_BATTLE)) {
            if (!lease.capture().matchId().equals(matchId))
                throw new IllegalStateException("Another Build Battle match still owns an unfinished world lease");
        }
    }

    private void requirePurged(UUID matchId) {
        for (var owner : matchLeases(matchId)) {
            if (owner.status() != ArenaWorldLedger.Status.PURGED
                    && owner.status() != ArenaWorldLedger.Status.RESTORED)
                throw new IllegalStateException("Build Battle shared plot reset has not completed for every owner");
        }
    }

    private void requireArmedOrAdvanced(PlayerStateOperation context) {
        var status = ledger.requireLease(context).status();
        if (status != ArenaWorldLedger.Status.ARMED && status != ArenaWorldLedger.Status.PURGING
                && status != ArenaWorldLedger.Status.PURGED && status != ArenaWorldLedger.Status.RESTORED)
            throw new IllegalStateException("Build Battle enter replay conflicts with lease state");
    }

    private byte[] captured(PlayerStateOperation context) {
        return journal.committedResult(providerId, VERSION, ledger.requireLease(context).capture(), EMPTY);
    }

    private void audit(PlayerStateOperation context, byte[] request) {
        audit.append(new AuditEvent(OperationIds.derive(context.operationId(), "BUILD_BATTLE_WORLD"),
                context.operationId(), journal.committedAt(providerId, VERSION, context, request),
                "BUILD_BATTLE_WORLD_" + context.kind(), "SESSION", context.sessionId().toString(), "VERIFIED", providerId));
    }

    private void require(PlayerStateOperation context, PlayerStateOperation.Kind kind) {
        if (!server.isPrimaryThread()) throw new IllegalStateException("Build Battle world operations require the primary thread");
        if (context == null || context.game() != GameKey.BUILD_BATTLE || context.capturedConnectionId() == null
                || (kind != null && context.kind() != kind))
            throw new IllegalStateException("Build Battle world operation has an incompatible capture identity");
    }

    private static BuildBattleResetPort.ResetHandle resetHandle(UUID matchId) {
        UUID operation = UUID.nameUUIDFromBytes(("BUILD_BATTLE_PLOT_RESET:" + matchId).getBytes(StandardCharsets.UTF_8));
        return new BuildBattleResetPort.ResetHandle(matchId, operation);
    }

    private static String regionId(String sourceId) {
        String id = GameKey.BUILD_BATTLE.id() + "." + Objects.requireNonNull(sourceId)
                .trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        if (!id.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalStateException("Build Battle region id is invalid");
        return id;
    }

    private static CuboidRegion bounding(List<CuboidRegion> regions) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        UUID world = null;
        for (CuboidRegion region : regions) {
            if (world == null) world = region.worldId();
            else if (!world.equals(region.worldId())) throw new IllegalStateException("Build Battle plots span worlds");
            minX = Math.min(minX, region.minX()); minY = Math.min(minY, region.minY()); minZ = Math.min(minZ, region.minZ());
            maxX = Math.max(maxX, region.maxX()); maxY = Math.max(maxY, region.maxY()); maxZ = Math.max(maxZ, region.maxZ());
        }
        if (world == null) throw new IllegalStateException("Build Battle has no plot geometry");
        return new CuboidRegion(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static long volume(CuboidRegion region) {
        long x = (long) region.maxX() - region.minX() + 1;
        long y = (long) region.maxY() - region.minY() + 1;
        long z = (long) region.maxZ() - region.minZ() + 1;
        if (x <= 0 || y <= 0 || z <= 0 || x > MAX_ARTIFACT_VOLUME || y > MAX_ARTIFACT_VOLUME / x
                || z > MAX_ARTIFACT_VOLUME / (x * y)) return Long.MAX_VALUE;
        return x * y * z;
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
    }
    private static void writeRegion(DataOutputStream out, CuboidRegion region) throws IOException {
        writeUuid(out, region.worldId()); out.writeInt(region.minX()); out.writeInt(region.minY()); out.writeInt(region.minZ());
        out.writeInt(region.maxX()); out.writeInt(region.maxY()); out.writeInt(region.maxZ());
    }
    private static void writeLocation(DataOutputStream out, Location location) {
        try {
            out.writeDouble(location.getX()); out.writeDouble(location.getY()); out.writeDouble(location.getZ());
            out.writeFloat(location.getYaw()); out.writeFloat(location.getPitch());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void writeUtf(DataOutputStream out, String value) {
        try { out.writeUTF(value); } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String safeCode(Throwable failure, String fallback) {
        String message = failure.getMessage();
        if (message == null) return fallback;
        String code = message.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_.-]+", "_")
                .replaceAll("^_+|_+$", "");
        if (code.length() > 64) code = code.substring(0, 64);
        return code.matches("[A-Z0-9][A-Z0-9_.-]{0,63}") ? code : fallback;
    }
}
