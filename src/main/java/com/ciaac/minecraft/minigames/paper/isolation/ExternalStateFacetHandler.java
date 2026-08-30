package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
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
    private static final int VERSION = 1;
    private static final Set<PlayerStateFacet> ALLOWED = EnumSet.of(
            PlayerStateFacet.ECONOMY,
            PlayerStateFacet.PERMISSIONS,
            PlayerStateFacet.CLAIMS_AND_HOMES,
            PlayerStateFacet.TEMPORARY_WORLD_BLOCKS_AND_ENTITIES);
    private final ExternalStateFacetPort port;
    private final String portId;
    private final int portSnapshotVersion;
    private final Set<PlayerStateFacet> facets;

    public ExternalStateFacetHandler(ExternalStateFacetPort port) {
        this.port = Objects.requireNonNull(port, "port");
        this.portId = port.id();
        this.portSnapshotVersion = port.snapshotVersion();
        this.facets = Set.copyOf(port.facets());
        if (facets.isEmpty() || !ALLOWED.containsAll(facets)) {
            throw new IllegalArgumentException("External port claims unsupported facets");
        }
        if (portId == null || !portId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("External port ID is invalid");
        }
        if (portSnapshotVersion < 1) throw new IllegalArgumentException("Invalid port snapshot version");
    }

    @Override public Set<PlayerStateFacet> facets() { return facets; }

    @Override public void preflight() { requireAvailable(); }

    @Override
    public void validateRestore(byte[] payload) {
        requireAvailable();
        decode(payload);
    }

    @Override
    public byte[] capture(Player player) {
        requireAvailable();
        byte[] payload = Objects.requireNonNull(port.capture(player), "external payload");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                BinaryStateIo.writeString(output, portId);
                output.writeInt(portSnapshotVersion);
                BinaryStateIo.writeBytes(output, payload);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode external-state envelope", exception);
        }
    }

    @Override public void enterTemporaryState(Player player) { requireAvailable(); port.enterTemporaryState(player); }
    @Override public void purgeTemporaryState(Player player) { requireAvailable(); port.purgeTemporaryState(player); }

    @Override
    public void restore(Player player, byte[] payload) {
        requireAvailable();
        byte[] value = decode(payload);
        port.restore(player, portSnapshotVersion, value);
    }

    private byte[] decode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != VERSION) throw new IllegalArgumentException("Unsupported external envelope version");
            if (!portId.equals(BinaryStateIo.readString(input))) {
                throw new IllegalStateException("The required external-state adapter changed");
            }
            int portVersion = input.readInt();
            if (portVersion != portSnapshotVersion) {
                throw new IllegalStateException("The external-state adapter version changed");
            }
            byte[] value = BinaryStateIo.readBytes(input);
            BinaryStateIo.requireExhausted(input);
            return value;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Could not restore external-state snapshot", exception);
        }
    }

    private void requireAvailable() {
        String liveId = port.id();
        int liveVersion = port.snapshotVersion();
        Set<PlayerStateFacet> liveFacets = Set.copyOf(port.facets());
        if (!portId.equals(liveId) || portSnapshotVersion != liveVersion || !facets.equals(liveFacets)) {
            throw new IllegalStateException("The required external-state adapter catalog changed");
        }
        if (!port.available()) throw new IllegalStateException("External-state adapter is unavailable: " + portId);
    }
}
