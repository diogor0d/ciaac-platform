package com.ciaac.minecraft.minigames.paper.elytrarings;

import com.ciaac.minecraft.minigames.elytrarings.RingCheckpoint;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Asynchronously prepares the finite chunk footprint of one Elytra course.
 *
 * <p>All Bukkit world and ticket operations are performed from the Paper main
 * thread. Completion callbacks only enqueue immutable observations and ask
 * the scheduler for a later main-thread drain. The class never loads, creates,
 * or deletes a chunk outside {@link #requiredChunks(Location, List, int)}.</p>
 */
public final class ElytraChunkPreparation {
    /** Hard bound protecting configuration and scheduler queues from a huge route. */
    public static final int MAX_REQUIRED_CHUNKS = 65_536;

    public enum Phase {
        DISABLED,
        PREPARING,
        READY,
        FAILED,
        RELEASED
    }

    public record ChunkCoordinate(int x, int z) { }

    public record Status(Phase phase, int requiredChunks, int loadedChunks,
                         int ticketedChunks, String code) {
        public Status {
            phase = Objects.requireNonNull(phase, "phase");
            if (requiredChunks < 0 || loadedChunks < 0 || ticketedChunks < 0
                    || loadedChunks > requiredChunks || ticketedChunks > requiredChunks) {
                throw new IllegalArgumentException("chunk counts are invalid");
            }
            code = Objects.requireNonNull(code, "code");
        }

        public boolean admissionReady() {
            return phase == Phase.READY
                    && loadedChunks == requiredChunks
                    && ticketedChunks == requiredChunks;
        }
    }

    private record Completion(long epoch, ChunkCoordinate coordinate, Chunk chunk,
                              Throwable failure) { }

    private static final class FootprintFailure extends IllegalArgumentException {
        private final String code;

        private FootprintFailure(String code) {
            super(code);
            this.code = code;
        }
    }

    private final ElytraRingsPaperSettings settings;
    private final Plugin plugin;
    private final World world;
    private final Set<ChunkCoordinate> required;
    private final Set<ChunkCoordinate> ticketed = new HashSet<>();
    private final Set<ChunkCoordinate> requested = new HashSet<>();
    private final Set<ChunkCoordinate> loaded = new HashSet<>();
    private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private Phase phase;
    private String code;
    private long epoch;
    private boolean started;

    /** Creates a live preparation owned by the supplied Paper plugin. */
    public ElytraChunkPreparation(ElytraRingsPaperSettings settings, Plugin plugin) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.plugin = plugin;
        this.world = settings.world();
        if (!settings.enabled()) {
            this.required = Set.of();
            this.phase = Phase.DISABLED;
            this.code = "DISABLED";
            return;
        }
        if (plugin == null) {
            this.required = Set.of();
            this.phase = Phase.FAILED;
            this.code = "CHUNK_PREPARER_UNAVAILABLE";
            return;
        }
        Set<ChunkCoordinate> initialRequired;
        Phase initialPhase;
        String initialCode;
        try {
            initialRequired = requiredChunks(settings.start(), settings.config().course().rings(),
                    settings.preloadRadiusChunks(), settings.ringRegions());
            initialPhase = Phase.PREPARING;
            initialCode = "PREPARING";
        } catch (FootprintFailure failure) {
            initialRequired = Set.of();
            initialPhase = Phase.FAILED;
            initialCode = failure.code;
        } catch (RuntimeException invalid) {
            initialRequired = Set.of();
            initialPhase = Phase.FAILED;
            initialCode = "PRELOAD_FOOTPRINT_INVALID";
        }
        this.required = initialRequired;
        this.phase = initialPhase;
        this.code = initialCode;
    }

    /**
     * Creates a fail-closed preparation for legacy callers that do not yet
     * have a Paper plugin lifecycle to own tickets.
     */
    public static ElytraChunkPreparation unavailable(ElytraRingsPaperSettings settings,
                                                      String code) {
        Objects.requireNonNull(settings, "settings");
        return new ElytraChunkPreparation(settings, null, Objects.requireNonNull(code, "code"));
    }

    private ElytraChunkPreparation(ElytraRingsPaperSettings settings, Plugin plugin,
                                   String unavailableCode) {
        this.settings = settings;
        this.plugin = plugin;
        this.world = settings.world();
        this.required = Set.of();
        this.phase = settings.enabled() ? Phase.FAILED : Phase.DISABLED;
        this.code = settings.enabled() ? unavailableCode : "DISABLED";
    }

    /** Returns the immutable, route-covering chunk set for a course. */
    public static Set<ChunkCoordinate> requiredChunks(Location start,
                                                       List<RingCheckpoint> rings,
                                                       int radius) {
        return requiredChunks(start, rings, radius, List.of());
    }

    /** Returns the route/radius footprint plus every chunk intersecting configured ring cuboids. */
    public static Set<ChunkCoordinate> requiredChunks(Location start,
                                                       List<RingCheckpoint> rings,
                                                       int radius,
                                                       List<CuboidRegion> ringRegions) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(rings, "rings");
        Objects.requireNonNull(ringRegions, "ringRegions");
        if (radius < 0 || radius > 8) throw new IllegalArgumentException("radius is invalid");

        List<ChunkCoordinate> anchors = new ArrayList<>(rings.size() + 1);
        anchors.add(chunkOf(start.getBlockX(), start.getBlockZ()));
        for (RingCheckpoint ring : rings) {
            Objects.requireNonNull(ring, "ring");
            anchors.add(chunkOf(ring.x(), ring.z()));
        }

        Set<ChunkCoordinate> route = new LinkedHashSet<>();
        for (int index = 1; index < anchors.size(); index++) {
            addLine(route, anchors.get(index - 1), anchors.get(index));
        }
        if (anchors.size() == 1) route.add(anchors.get(0));

        Set<ChunkCoordinate> expanded = new LinkedHashSet<>();
        for (ChunkCoordinate coordinate : route) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    long x = (long) coordinate.x() + dx;
                    long z = (long) coordinate.z() + dz;
                    if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                            || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
                        throw new FootprintFailure("PRELOAD_COORDINATE_OUT_OF_RANGE");
                    }
                    expanded.add(new ChunkCoordinate((int) x, (int) z));
                    if (expanded.size() > MAX_REQUIRED_CHUNKS) {
                        throw new FootprintFailure("PRELOAD_FOOTPRINT_TOO_LARGE");
                    }
                }
            }
        }
        for (CuboidRegion region : ringRegions) {
            Objects.requireNonNull(region, "ring region");
            int minChunkX = Math.floorDiv(region.minX(), 16);
            int maxChunkX = Math.floorDiv(region.maxX(), 16);
            int minChunkZ = Math.floorDiv(region.minZ(), 16);
            int maxChunkZ = Math.floorDiv(region.maxZ(), 16);
            long width = (long) maxChunkX - minChunkX + 1;
            long depth = (long) maxChunkZ - minChunkZ + 1;
            long area = width * depth;
            if (area <= 0 || area > MAX_REQUIRED_CHUNKS) {
                throw new FootprintFailure("PRELOAD_FOOTPRINT_TOO_LARGE");
            }
            for (long x = minChunkX; x <= maxChunkX; x++) {
                for (long z = minChunkZ; z <= maxChunkZ; z++) {
                    expanded.add(new ChunkCoordinate((int) x, (int) z));
                    if (expanded.size() > MAX_REQUIRED_CHUNKS) {
                        throw new FootprintFailure("PRELOAD_FOOTPRINT_TOO_LARGE");
                    }
                }
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(expanded));
    }

    public synchronized Status status() {
        return new Status(phase, required.size(), loaded.size(), ticketed.size(), code);
    }

    /** Immutable coordinates that this lifecycle is allowed to prepare. */
    public synchronized Set<ChunkCoordinate> requiredChunks() {
        return required;
    }

    public synchronized boolean admissionReady() {
        return status().admissionReady();
    }

    /**
     * Starts requests and drains async completions without ever blocking. The
     * controller calls this from its regular Paper main-thread heartbeat.
     */
    public synchronized void tick() {
        if (phase != Phase.PREPARING || !mainThread()) return;
        drainScheduled.set(false);
        drainCompletions();
        if (phase != Phase.PREPARING) return;
        if (!started) startRequests();
        drainCompletions();
        if (phase == Phase.PREPARING && loaded.size() == required.size()
                && ticketed.size() == required.size()) {
            phase = Phase.READY;
            code = "READY";
        }
    }

    /** Releases only tickets owned by this preparation. Must run on Paper's main thread. */
    public synchronized void release() {
        if (phase == Phase.DISABLED || phase == Phase.RELEASED) return;
        if (!mainThread()) throw new IllegalStateException("MAIN_THREAD_REQUIRED");
        epoch++;
        requested.clear();
        loaded.clear();
        completions.clear();
        drainScheduled.set(false);
        boolean released = releaseTickets();
        if (released) {
            phase = Phase.RELEASED;
            code = "RELEASED";
        } else {
            phase = Phase.FAILED;
            code = "TICKET_RELEASE_FAILED";
        }
    }

    private void startRequests() {
        started = true;
        try {
            for (ChunkCoordinate coordinate : required) {
                if (!world.addPluginChunkTicket(coordinate.x(), coordinate.z(), plugin)) {
                    fail("CHUNK_TICKET_FAILED");
                    return;
                }
                ticketed.add(coordinate);
            }
            long requestEpoch = epoch;
            for (ChunkCoordinate coordinate : required) {
                if (world.isChunkLoaded(coordinate.x(), coordinate.z())) {
                    loaded.add(coordinate);
                    continue;
                }
                requested.add(coordinate);
                CompletableFuture<Chunk> future = world.getChunkAtAsync(
                        coordinate.x(), coordinate.z(), true, false);
                future.whenComplete((chunk, failure) -> enqueue(
                        new Completion(requestEpoch, coordinate, chunk, failure)));
            }
        } catch (RuntimeException failure) {
            fail("CHUNK_LOAD_FAILED");
        }
    }

    private void enqueue(Completion completion) {
        synchronized (this) {
            if (phase != Phase.PREPARING || completion.epoch() != epoch) return;
            completions.add(completion);
        }
        if (plugin == null || !drainScheduled.compareAndSet(false, true)) return;
        try {
            plugin.getServer().getScheduler().runTask(plugin, this::tick);
        } catch (RuntimeException schedulerFailure) {
            drainScheduled.set(false);
            // The regular controller heartbeat will drain this completion on
            // the main thread; no Bukkit object is touched from the callback.
        }
    }

    private void drainCompletions() {
        Completion completion;
        while ((completion = completions.poll()) != null) {
            if (phase != Phase.PREPARING || completion.epoch() != epoch
                    || !requested.remove(completion.coordinate())) continue;
            if (completion.failure() != null || completion.chunk() == null) {
                fail("CHUNK_LOAD_FAILED");
                return;
            }
            Chunk chunk = completion.chunk();
            if (chunk.getWorld() != world || chunk.getX() != completion.coordinate().x()
                    || chunk.getZ() != completion.coordinate().z()) {
                fail("CHUNK_LOAD_INVALID");
                return;
            }
            loaded.add(completion.coordinate());
        }
    }

    private void fail(String failureCode) {
        epoch++;
        requested.clear();
        completions.clear();
        drainScheduled.set(false);
        boolean released = releaseTickets();
        phase = Phase.FAILED;
        code = released ? failureCode : "TICKET_RELEASE_FAILED";
    }

    private boolean releaseTickets() {
        boolean successful = true;
        for (var iterator = ticketed.iterator(); iterator.hasNext();) {
            ChunkCoordinate coordinate = iterator.next();
            try {
                world.removePluginChunkTicket(coordinate.x(), coordinate.z(), plugin);
                iterator.remove();
            } catch (RuntimeException failure) {
                successful = false;
            }
        }
        return successful && ticketed.isEmpty();
    }

    private boolean mainThread() {
        return plugin == null || plugin.getServer().isPrimaryThread();
    }

    private static ChunkCoordinate chunkOf(int blockX, int blockZ) {
        return new ChunkCoordinate(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
    }

    private static ChunkCoordinate chunkOf(double blockX, double blockZ) {
        double x = Math.floor(blockX / 16.0);
        double z = Math.floor(blockZ / 16.0);
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            throw new FootprintFailure("PRELOAD_COORDINATE_OUT_OF_RANGE");
        }
        return new ChunkCoordinate((int) x, (int) z);
    }

    private static void addLine(Set<ChunkCoordinate> route,
                                ChunkCoordinate from, ChunkCoordinate to) {
        long x = from.x();
        long z = from.z();
        long targetX = to.x();
        long targetZ = to.z();
        long dx = targetX - x;
        long dz = targetZ - z;
        long stepX = Long.compare(targetX, x);
        long stepZ = Long.compare(targetZ, z);
        long absX = Math.abs(dx);
        long absZ = Math.abs(dz);
        if (Math.max(absX, absZ) > MAX_REQUIRED_CHUNKS) {
            throw new FootprintFailure("PRELOAD_FOOTPRINT_TOO_LARGE");
        }
        long error = absX - absZ;
        while (true) {
            route.add(new ChunkCoordinate((int) x, (int) z));
            if (route.size() > MAX_REQUIRED_CHUNKS) {
                throw new FootprintFailure("PRELOAD_FOOTPRINT_TOO_LARGE");
            }
            if (x == targetX && z == targetZ) return;
            long twice = 2 * error;
            if (twice > -absZ) {
                error -= absZ;
                x += stepX;
            }
            if (twice < absX) {
                error += absX;
                z += stepZ;
            }
        }
    }
}
