package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedElytraRingsConfiguration;
import com.ciaac.minecraft.minigames.paper.elytrarings.ElytraChunkPreparation;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Server;

/** Frozen, bounded Elytra course manifest and solo firework cleanup lifecycle. */
public final class ElytraWorldState {
    private static final int MAGIC = 0x43414557; // CAEW
    private static final int VERSION = 1;
    private static final int MAX_MANIFEST = 128 * 1024;
    private static final int MAX_CHUNKS = ElytraChunkPreparation.MAX_REQUIRED_CHUNKS;
    private static final byte[] EMPTY = new byte[0];

    /** Native firework ownership is deliberately injected by the Paper lifecycle adapter. */
    public interface EntityCleanup {
        /** Read-only validation of exact durable firework owners for the captured session. */
        void validate(PlayerStateOperation capture);

        /** Purges exact durable firework owners and completes the ledger purge. */
        void purge(PlayerStateOperation capture);
    }

    private final String providerId;
    private final Server server;
    private final ProtectedRegionRegistry regions;
    private final ArenaWorldLedger ledger;
    private final ExternalOperationJournal journal;
    private final AuditRepository audit;
    private final Supplier<ResolvedElytraRingsConfiguration> configuration;
    private final EntityCleanup cleanup;

    public ElytraWorldState(String providerId, Server server, ProtectedRegionRegistry regions,
            ArenaWorldLedger ledger, ExternalOperationJournal journal, AuditRepository audit,
            Supplier<ResolvedElytraRingsConfiguration> configuration, EntityCleanup cleanup) {
        this.providerId = Objects.requireNonNull(providerId);
        this.server = Objects.requireNonNull(server);
        this.regions = Objects.requireNonNull(regions);
        this.ledger = Objects.requireNonNull(ledger);
        this.journal = Objects.requireNonNull(journal);
        this.audit = Objects.requireNonNull(audit);
        this.configuration = Objects.requireNonNull(configuration);
        this.cleanup = Objects.requireNonNull(cleanup);
    }

    public byte[] capture(PlayerStateOperation context) {
        require(context, PlayerStateOperation.Kind.CAPTURE);
        byte[] payload = manifest();
        ExternalOperationJournal.State state = journal.begin(providerId, VERSION, context, EMPTY);
        if (state == ExternalOperationJournal.State.COMMITTED) {
            if (!Arrays.equals(payload, journal.committedResult(providerId, VERSION, context, EMPTY))
                    || !Arrays.equals(payload, ledger.requireLease(context).manifest()))
                throw new IllegalStateException("Elytra capture conflicts with durable state");
            audit(context, EMPTY);
            return payload;
        }
        ledger.capture(context, payload);
        journal.commit(providerId, VERSION, context, EMPTY, payload);
        audit(context, EMPTY);
        return payload;
    }

    public void validate(PlayerStateOperation context, int version, byte[] payload) {
        require(context, null);
        if (version != VERSION || payload == null || payload.length == 0 || payload.length > MAX_MANIFEST)
            throw new IllegalArgumentException("Unsupported Elytra world snapshot");
        validateManifest(payload);
        ArenaWorldLedger.Lease lease = ledger.requireLease(context);
        byte[] current = manifest();
        if (!Arrays.equals(payload, lease.manifest()) || !Arrays.equals(payload, current)
                || !Arrays.equals(payload, captured(context)))
            throw new IllegalStateException("Elytra course or durable capture differs");
        switch (context.kind()) {
            case CAPTURE -> { if (lease.status() != ArenaWorldLedger.Status.CAPTURED)
                    throw new IllegalStateException("Elytra capture lease is not in its captured phase"); }
            case ENTER -> { if (lease.status() != ArenaWorldLedger.Status.CAPTURED
                    && lease.status() != ArenaWorldLedger.Status.ARMED)
                    throw new IllegalStateException("Elytra lease cannot be entered from " + lease.status()); }
            case PURGE -> { /* Purge replay after restoration is already proven complete. */ }
            case RESTORE -> { if (lease.status() != ArenaWorldLedger.Status.PURGED
                    && lease.status() != ArenaWorldLedger.Status.RESTORED)
                    throw new IllegalStateException("Elytra firework purge must finish before player restoration"); }
        }
        cleanup.validate(lease.capture());
    }

    public void enter(PlayerStateOperation context) {
        require(context, PlayerStateOperation.Kind.ENTER);
        byte[] payload = captured(context);
        validate(context, VERSION, payload);
        ExternalOperationJournal.State state = journal.begin(providerId, VERSION, context, payload);
        if (state == ExternalOperationJournal.State.COMMITTED) {
            requireEnteredOrAdvanced(context);
            if (journal.committedResult(providerId, VERSION, context, payload).length != 0)
                throw new IllegalStateException("Elytra enter replay has an invalid result");
            audit(context, payload);
            return;
        }
        ArenaWorldLedger.Status leaseStatus = ledger.requireLease(context).status();
        if (leaseStatus == ArenaWorldLedger.Status.CAPTURED) ledger.arm(context);
        else requireEnteredOrAdvanced(context);
        journal.commit(providerId, VERSION, context, payload, EMPTY);
        audit(context, payload);
    }

    public void purge(PlayerStateOperation context) {
        require(context, PlayerStateOperation.Kind.PURGE);
        byte[] payload = captured(context);
        validate(context, VERSION, payload);
        ExternalOperationJournal.State state = journal.begin(providerId, VERSION, context, payload);
        if (state == ExternalOperationJournal.State.COMMITTED) {
            requirePurged(context);
            if (journal.committedResult(providerId, VERSION, context, payload).length != 0)
                throw new IllegalStateException("Elytra purge replay has an invalid result");
            audit(context, payload);
            return;
        }
        if (ledger.requireLease(context).status() != ArenaWorldLedger.Status.RESTORED)
            cleanup.purge(ledger.requireLease(context).capture());
        requirePurged(context);
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
                throw new IllegalStateException("Elytra restore replay conflicts with durable state");
            audit(context, payload);
            return;
        }
        ledger.markRestored(context);
        journal.commit(providerId, VERSION, context, payload, EMPTY);
        audit(context, payload);
    }

    private void requireEnteredOrAdvanced(PlayerStateOperation context) {
        ArenaWorldLedger.Status actual = ledger.requireLease(context).status();
        if (actual != ArenaWorldLedger.Status.ARMED && actual != ArenaWorldLedger.Status.PURGING
                && actual != ArenaWorldLedger.Status.PURGED && actual != ArenaWorldLedger.Status.RESTORED)
            throw new IllegalStateException("Elytra enter replay conflicts with lease state");
    }

    private void requirePurged(PlayerStateOperation context) {
        ArenaWorldLedger.Status status = ledger.requireLease(context).status();
        if (status != ArenaWorldLedger.Status.PURGED && status != ArenaWorldLedger.Status.RESTORED)
            throw new IllegalStateException("Elytra firework ownership did not complete its purge");
    }

    private byte[] manifest() {
        ResolvedElytraRingsConfiguration value = Objects.requireNonNull(configuration.get(), "Elytra configuration");
        var world = value.world();
        UUID worldId = world.getUID();
        var boundary = regions.find("elytra-rings.course-boundary").orElseThrow();
        if (server.getWorld(worldId) != world || server.getWorld(world.getName()) != world
                || regions.all().stream().filter(region -> region.game() == GameKey.ELYTRA_RINGS).count() != 1
                || boundary.game() != GameKey.ELYTRA_RINGS
                || boundary.role() != ProtectedRegionRole.GAME_WORLD_BOUNDARY || !boundary.immutable()
                || !boundary.bounds().equals(value.regions().get("course-boundary"))
                || !boundary.bounds().worldId().equals(worldId)
                || !worldId.toString().equals(value.course().worldId())
                || !value.domain().course().equals(value.course())
                || value.concurrentRunners() != 1
                || !value.ringRegions().keySet().equals(new java.util.HashSet<>(value.ringOrder()))
                || value.ringOrder().size() != value.course().rings().size()
                || value.locations().get("start") == null || value.locations().get("exit") == null
                || value.ringTargetInput().unresolved())
            throw new IllegalStateException("Elytra protection, course, or world identity is unavailable");
        Location start = value.locations().get("start");
        Location exit = value.locations().get("exit");
        int minHeight = world.getMinHeight(), maxHeight = world.getMaxHeight();
        if (start.getWorld() != world || exit.getWorld() != world
                || boundary.bounds().minY() < minHeight || boundary.bounds().maxY() >= maxHeight
                || start.getY() < minHeight || start.getY() >= maxHeight
                || exit.getY() < minHeight || exit.getY() >= maxHeight)
            throw new IllegalStateException("Elytra start or exit world differs from its course world");
        List<com.ciaac.minecraft.minigames.region.CuboidRegion> orderedRings = value.ringOrder().stream()
                .map(value.ringRegions()::get).toList();
        for (int i = 0; i < value.ringOrder().size(); i++) {
            String id = value.ringOrder().get(i);
            var region = orderedRings.get(i);
            var checkpoint = value.course().rings().get(i);
            if (!region.worldId().equals(worldId)
                    || region.minY() < minHeight || region.maxY() >= maxHeight
                    || checkpoint.order() != i + 1
                    || checkpoint.x() != ((double) region.minX() + region.maxX()) / 2.0
                    || checkpoint.y() != ((double) region.minY() + region.maxY()) / 2.0
                    || checkpoint.z() != ((double) region.minZ() + region.maxZ()) / 2.0
                    || !contains(boundary.bounds(), region))
                throw new IllegalStateException("Elytra ring order or protected geometry differs");
        }
        var footprint = ElytraChunkPreparation.requiredChunks(start, value.course().rings(),
                value.preloadRadiusChunks(), orderedRings);
        if (footprint.size() > MAX_CHUNKS) throw new IllegalStateException("Elytra course chunk footprint exceeds its bound");
        for (var coordinate : footprint) {
            if (!world.isChunkLoaded(coordinate.x(), coordinate.z()))
                throw new IllegalStateException("Elytra course chunk is not loaded");
        }
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC); out.writeInt(VERSION);
                out.writeUTF(server.getVersion()); out.writeUTF(world.getName());
                out.writeLong(worldId.getMostSignificantBits()); out.writeLong(worldId.getLeastSignificantBits());
                out.writeUTF(boundary.id()); out.writeUTF(boundary.role().name());
                writeRegion(out, boundary.bounds());
                writeLocation(out, start); writeLocation(out, exit);
                out.writeUTF(value.worldTemplateMarker());
                out.writeUTF(value.ringTargetInput().state().name());
                out.writeUTF(value.ringTargetInput().identifier());
                out.writeUTF("none-no-world-block-mutation-v1");
                out.writeUTF(value.course().revision()); out.writeUTF(value.course().worldId());
                out.writeLong(value.domain().timeout().toNanos());
                out.writeInt(value.preloadRadiusChunks()); out.writeInt(value.fireworkRockets());
                out.writeBoolean(value.allowRockets()); out.writeInt(value.concurrentRunners());
                out.writeDouble(value.ringRadius());
                out.writeInt(value.domain().isolation().protectedFacets().size());
                value.domain().isolation().protectedFacets().stream().map(Enum::name).sorted().forEach(name -> writeUtf(out, name));
                out.writeInt(value.ringOrder().size());
                for (String id : value.ringOrder()) {
                    out.writeUTF(id); writeRegion(out, value.ringRegions().get(id));
                    var ring = value.course().rings().get(value.ringOrder().indexOf(id));
                    out.writeInt(ring.order()); out.writeDouble(ring.x()); out.writeDouble(ring.y()); out.writeDouble(ring.z());
                }
                out.writeInt(footprint.size());
                footprint.stream().sorted(java.util.Comparator.comparingInt(ElytraChunkPreparation.ChunkCoordinate::x)
                        .thenComparingInt(ElytraChunkPreparation.ChunkCoordinate::z))
                        .forEach(point -> { writeInt(out, point.x()); writeInt(out, point.z()); });
            }
            byte[] payload = bytes.toByteArray();
            if (payload.length > MAX_MANIFEST) throw new IllegalStateException("Elytra manifest exceeds its bound");
            return payload;
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean contains(com.ciaac.minecraft.minigames.region.CuboidRegion outer,
            com.ciaac.minecraft.minigames.region.CuboidRegion inner) {
        return outer.worldId().equals(inner.worldId()) && outer.minX() <= inner.minX() && outer.maxX() >= inner.maxX()
                && outer.minY() <= inner.minY() && outer.maxY() >= inner.maxY()
                && outer.minZ() <= inner.minZ() && outer.maxZ() >= inner.maxZ();
    }

    private static void writeRegion(DataOutputStream out, com.ciaac.minecraft.minigames.region.CuboidRegion region) throws IOException {
        out.writeLong(region.worldId().getMostSignificantBits()); out.writeLong(region.worldId().getLeastSignificantBits());
        out.writeInt(region.minX()); out.writeInt(region.minY()); out.writeInt(region.minZ());
        out.writeInt(region.maxX()); out.writeInt(region.maxY()); out.writeInt(region.maxZ());
    }

    private static void writeLocation(DataOutputStream out, Location location) throws IOException {
        out.writeDouble(location.getX()); out.writeDouble(location.getY()); out.writeDouble(location.getZ());
        out.writeFloat(location.getYaw()); out.writeFloat(location.getPitch());
    }

    private static void writeUtf(DataOutputStream out, String value) {
        try { out.writeUTF(value); } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void writeInt(DataOutputStream out, int value) {
        try { out.writeInt(value); } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void validateManifest(byte[] payload) {
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IllegalArgumentException("Unsupported Elytra snapshot");
            if (in.readUTF().isBlank() || in.readUTF().isBlank()) throw new IllegalArgumentException("Invalid Elytra world identity");
            in.readLong(); in.readLong(); in.readUTF(); in.readUTF();
            in.readLong(); in.readLong();
            for (int i = 0; i < 6; i++) in.readInt();
            for (int location = 0; location < 2; location++) {
                in.readDouble(); in.readDouble(); in.readDouble(); in.readFloat(); in.readFloat();
            }
            in.readUTF(); in.readUTF(); in.readUTF(); in.readUTF(); in.readUTF(); in.readUTF();
            in.readLong(); in.readInt(); in.readInt(); in.readBoolean(); in.readInt(); in.readDouble();
            int facets = in.readInt();
            if (facets < 1 || facets > 64) throw new IllegalArgumentException("Invalid Elytra isolation manifest");
            for (int i = 0; i < facets; i++) in.readUTF();
            int rings = in.readInt();
            if (rings < 2 || rings > 256) throw new IllegalArgumentException("Invalid Elytra ring manifest");
            for (int i = 0; i < rings; i++) {
                in.readUTF(); in.readLong(); in.readLong();
                for (int j = 0; j < 6; j++) in.readInt();
                in.readInt(); in.readDouble(); in.readDouble(); in.readDouble();
            }
            int chunks = in.readInt();
            if (chunks < 0 || chunks > MAX_CHUNKS) throw new IllegalArgumentException("Invalid Elytra chunk manifest");
            for (int i = 0; i < chunks; i++) { in.readInt(); in.readInt(); }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing Elytra snapshot data");
        } catch (IOException malformed) { throw new IllegalArgumentException("Truncated Elytra snapshot", malformed); }
    }

    /** Reads the frozen world UUID prefix for firework ownership validation. */
    static UUID capturedWorld(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_MANIFEST)
            throw new IllegalArgumentException("Invalid Elytra course snapshot");
        try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION)
                throw new IllegalArgumentException("Unsupported Elytra course snapshot");
            if (input.readUTF().isBlank() || input.readUTF().isBlank())
                throw new IllegalArgumentException("Invalid Elytra world identity");
            return new UUID(input.readLong(), input.readLong());
        } catch (IOException malformed) { throw new IllegalArgumentException("Truncated Elytra snapshot", malformed); }
    }

    private byte[] captured(PlayerStateOperation context) {
        return journal.committedResult(providerId, VERSION, ledger.requireLease(context).capture(), EMPTY);
    }

    private void audit(PlayerStateOperation context, byte[] request) {
        audit.append(new AuditEvent(OperationIds.derive(context.operationId(), "ELYTRA_WORLD"),
                context.operationId(), journal.committedAt(providerId, VERSION, context, request),
                "ELYTRA_WORLD_" + context.kind(), "SESSION", context.sessionId().toString(), "VERIFIED", providerId));
    }

    private void require(PlayerStateOperation context, PlayerStateOperation.Kind kind) {
        if (!server.isPrimaryThread()) throw new IllegalStateException("Elytra world operations require the primary thread");
        if (context == null || context.game() != GameKey.ELYTRA_RINGS || context.capturedConnectionId() == null
                || (kind != null && context.kind() != kind))
            throw new IllegalStateException("Elytra world operation has an incompatible capture identity");
    }
}
