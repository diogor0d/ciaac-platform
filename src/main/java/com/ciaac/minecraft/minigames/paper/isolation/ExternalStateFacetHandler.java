package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.bukkit.entity.Player;

/** Defensive envelope around one explicitly validated external-state adapter. */
public final class ExternalStateFacetHandler implements FacetSnapshotHandler {
    private static final int VERSION = 2;
    private static final Set<PlayerStateFacet> ALLOWED = EnumSet.of(
            PlayerStateFacet.ECONOMY,
            PlayerStateFacet.PERMISSIONS,
            PlayerStateFacet.CLAIMS_AND_HOMES,
            PlayerStateFacet.TEMPORARY_WORLD_BLOCKS_AND_ENTITIES);
    private final ExternalStateFacetPort port;
    private final String portId;
    private final int portSnapshotVersion;
    private final Set<PlayerStateFacet> facets;
    private final Set<GameKey> supportedGames;

    public ExternalStateFacetHandler(ExternalStateFacetPort port) {
        this.port = Objects.requireNonNull(port, "port");
        if (port.contractVersion() != 2) throw new IllegalArgumentException("Unsupported external adapter contract");
        this.portId = port.id();
        this.portSnapshotVersion = port.snapshotVersion();
        this.facets = Set.copyOf(port.facets());
        this.supportedGames = Set.copyOf(port.supportedGames());
        if (facets.isEmpty() || !ALLOWED.containsAll(facets)) {
            throw new IllegalArgumentException("External port claims unsupported facets");
        }
        if (supportedGames.isEmpty()) throw new IllegalArgumentException("External port supports no games");
        if (portId == null || !portId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("External port ID is invalid");
        }
        if (portSnapshotVersion < 1) throw new IllegalArgumentException("Invalid port snapshot version");
    }

    @Override public Set<PlayerStateFacet> facets() { return facets; }
    @Override public Set<GameKey> supportedGames() { return supportedGames; }

    @Override public void preflight() { requireAvailable(); }

    @Override public void preflight(GameKey game) {
        requireSupportedGame(game);
        requireAvailable();
    }

    @Override public byte[] capture(Player player) { throw contextRequired(); }
    @Override public void validateRestore(byte[] payload) { throw contextRequired(); }
    @Override public void enterTemporaryState(Player player) { throw contextRequired(); }
    @Override public void purgeTemporaryState(Player player) { throw contextRequired(); }
    @Override public void restore(Player player, byte[] payload) { throw contextRequired(); }

    @Override
    public void validateRestore(PlayerStateOperation context, byte[] payload) {
        requireSupportedGame(context.game());
        requireAvailable();
        requireContext(context);
        port.validateRestore(context, portSnapshotVersion, decode(payload, context));
    }

    @Override
    public byte[] capture(Player player, PlayerStateOperation context) {
        requireSupportedGame(context.game());
        requireAvailable();
        requirePlayer(player, context, PlayerStateOperation.Kind.CAPTURE);
        byte[] payload = Objects.requireNonNull(port.capture(player, context), "external payload").clone();
        if (payload.length > BinaryStateIo.MAX_ARRAY_BYTES) {
            throw new IllegalArgumentException("External-state payload is too large");
        }
        port.validateRestore(context, portSnapshotVersion, payload.clone());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                BinaryStateIo.writeString(output, portId);
                output.writeInt(portSnapshotVersion);
                writeUuid(output, context.captureOperationId());
                writeUuid(output, context.snapshotId());
                writeUuid(output, context.sessionId());
                writeUuid(output, context.matchId());
                writeUuid(output, context.playerId());
                writeUuid(output, context.capturedConnectionId());
                BinaryStateIo.writeString(output, context.game().id());
                output.writeLong(context.capturedAt().getEpochSecond());
                output.writeInt(context.capturedAt().getNano());
                BinaryStateIo.writeBytes(output, payload);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode external-state envelope", exception);
        }
    }

    @Override
    public void enterTemporaryState(Player player, PlayerStateOperation context) {
        requireSupportedGame(context.game());
        requireAvailable();
        requirePlayer(player, context, PlayerStateOperation.Kind.ENTER);
        port.enterTemporaryState(player, context);
    }

    @Override
    public void purgeTemporaryState(Player player, PlayerStateOperation context) {
        requireSupportedGame(context.game());
        requireAvailable();
        requirePlayer(player, context, PlayerStateOperation.Kind.PURGE);
        port.purgeTemporaryState(player, context);
    }

    @Override
    public void restore(Player player, PlayerStateOperation context, byte[] payload) {
        requireSupportedGame(context.game());
        requireAvailable();
        requirePlayer(player, context, PlayerStateOperation.Kind.RESTORE);
        byte[] value = decode(payload, context);
        port.validateRestore(context, portSnapshotVersion, value.clone());
        port.restore(player, context, portSnapshotVersion, value);
    }

    private byte[] decode(byte[] payload, PlayerStateOperation context) {
        Objects.requireNonNull(payload, "payload");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != VERSION) throw new IllegalArgumentException("Unsupported external envelope version");
            if (!portId.equals(BinaryStateIo.readString(input))) {
                throw new IllegalStateException("The required external-state adapter changed");
            }
            if (input.readInt() != portSnapshotVersion) {
                throw new IllegalStateException("The external-state adapter version changed");
            }
            if (!readUuid(input).equals(context.captureOperationId())
                    || !readUuid(input).equals(context.snapshotId())
                    || !readUuid(input).equals(context.sessionId())
                    || !readUuid(input).equals(context.matchId())
                    || !readUuid(input).equals(context.playerId())
                    || !readUuid(input).equals(context.capturedConnectionId())
                    || !BinaryStateIo.readString(input).equals(context.game().id())
                    || input.readLong() != context.capturedAt().getEpochSecond()
                    || input.readInt() != context.capturedAt().getNano()) {
                throw new IllegalStateException("External snapshot identity does not match its operation");
            }
            byte[] value = BinaryStateIo.readBytes(input);
            BinaryStateIo.requireExhausted(input);
            return value;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Could not restore external-state snapshot", exception);
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void requireContext(PlayerStateOperation context) {
        Objects.requireNonNull(context, "context");
        if (context.capturedConnectionId() == null) throw contextRequired();
    }

    private static void requirePlayer(Player player, PlayerStateOperation context, PlayerStateOperation.Kind kind) {
        requireContext(context);
        if (context.kind() != kind || !Objects.requireNonNull(player, "player").getUniqueId().equals(context.playerId())) {
            throw new IllegalStateException("External operation does not match its live player or phase");
        }
    }

    private static IllegalStateException contextRequired() {
        return new IllegalStateException("External state requires operation-bound connection identity");
    }

    private void requireAvailable() {
        String liveId = port.id();
        int liveVersion = port.snapshotVersion();
        Set<PlayerStateFacet> liveFacets = Set.copyOf(port.facets());
        Set<GameKey> liveGames = Set.copyOf(port.supportedGames());
        if (port.contractVersion() != 2 || !portId.equals(liveId) || portSnapshotVersion != liveVersion
                || !facets.equals(liveFacets) || !supportedGames.equals(liveGames)) {
            throw new IllegalStateException("The required external-state adapter catalog changed");
        }
        if (!port.available()) throw new IllegalStateException("External-state adapter is unavailable: " + portId);
    }

    private void requireSupportedGame(GameKey game) {
        if (!supportedGames.contains(Objects.requireNonNull(game, "game"))) {
            throw new IllegalStateException("External-state adapter does not support game " + game.id());
        }
    }
}
