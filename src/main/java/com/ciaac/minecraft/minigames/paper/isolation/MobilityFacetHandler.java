package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

public final class MobilityFacetHandler implements FacetSnapshotHandler {
    private static final int VERSION = 1;
    private static final Set<PlayerStateFacet> FACETS = Set.of(
            PlayerStateFacet.GAMEMODE,
            PlayerStateFacet.FLIGHT,
            PlayerStateFacet.LOCATION_AND_WORLD);
    private final Server server;

    public MobilityFacetHandler(Server server) {
        this.server = java.util.Objects.requireNonNull(server, "server");
    }

    @Override
    public Set<PlayerStateFacet> facets() {
        return FACETS;
    }

    @Override
    public byte[] capture(Player player) {
        Location location = player.getLocation();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                UUID worldId = location.getWorld().getUID();
                output.writeLong(worldId.getMostSignificantBits());
                output.writeLong(worldId.getLeastSignificantBits());
                BinaryStateIo.writeString(output, location.getWorld().getName());
                output.writeDouble(location.getX());
                output.writeDouble(location.getY());
                output.writeDouble(location.getZ());
                output.writeFloat(location.getYaw());
                output.writeFloat(location.getPitch());
                BinaryStateIo.writeString(output, player.getGameMode().name());
                output.writeBoolean(player.getAllowFlight());
                output.writeBoolean(player.isFlying());
                output.writeFloat(player.getFlySpeed());
                output.writeFloat(player.getWalkSpeed());
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode player mobility", exception);
        }
    }

    @Override
    public void validateRestore(byte[] payload) {
        Decoded decoded = decode(payload);
        requireWorld(decoded);
    }

    @Override
    public void enterTemporaryState(Player player) {
        neutralize(player);
        player.setGameMode(GameMode.ADVENTURE);
    }

    @Override
    public void purgeTemporaryState(Player player) {
        neutralize(player);
    }

    @Override
    public void restore(Player player, byte[] payload) {
        Decoded decoded = decode(payload);
        World world = requireWorld(decoded);
        neutralize(player);
        if (!player.teleport(
                new Location(world, decoded.x(), decoded.y(), decoded.z(), decoded.yaw(), decoded.pitch()),
                PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            throw new IllegalStateException("Paper rejected the recovery teleport");
        }
        player.setGameMode(decoded.gameMode());
        player.setAllowFlight(decoded.allowFlight());
        player.setFlying(decoded.allowFlight() && decoded.flying());
        player.setFlySpeed(decoded.flySpeed());
        player.setWalkSpeed(decoded.walkSpeed());
    }

    private Decoded decode(byte[] payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException("Unsupported mobility snapshot version");
            }
            UUID worldId = new UUID(input.readLong(), input.readLong());
            String worldName = BinaryStateIo.readString(input);
            double x = input.readDouble();
            double y = input.readDouble();
            double z = input.readDouble();
            float yaw = input.readFloat();
            float pitch = input.readFloat();
            GameMode gameMode = GameMode.valueOf(BinaryStateIo.readString(input));
            boolean allowFlight = input.readBoolean();
            boolean flying = input.readBoolean();
            float flySpeed = input.readFloat();
            float walkSpeed = input.readFloat();
            BinaryStateIo.requireExhausted(input);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                    || !Float.isFinite(flySpeed) || flySpeed < 0 || flySpeed > 1
                    || !Float.isFinite(walkSpeed) || walkSpeed < 0 || walkSpeed > 1) {
                throw new IllegalArgumentException("Snapshot location is not finite");
            }
            return new Decoded(worldId, worldName, x, y, z, yaw, pitch, gameMode,
                    allowFlight, flying, flySpeed, walkSpeed);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Could not restore player mobility snapshot", exception);
        }
    }

    private World requireWorld(Decoded decoded) {
        World world = server.getWorld(decoded.worldId());
        if (world == null || !world.getName().equals(decoded.worldName())) {
            throw new IllegalStateException("Snapshot world is unavailable or has a conflicting identity");
        }
        return world;
    }

    private record Decoded(
            UUID worldId,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            GameMode gameMode,
            boolean allowFlight,
            boolean flying,
            float flySpeed,
            float walkSpeed) {}

    private static void neutralize(Player player) {
        player.leaveVehicle();
        player.setGliding(false);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setVelocity(new Vector());
        player.setFallDistance(0);
    }
}
