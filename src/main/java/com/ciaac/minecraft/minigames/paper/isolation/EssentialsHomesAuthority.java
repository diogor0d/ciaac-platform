package com.ciaac.minecraft.minigames.paper.isolation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/** Pinned, read-only loaded home model. getHome() is avoided because it rewrites world references. */
public final class EssentialsHomesAuthority implements ExternalStateAuthority {
    private static final String ESSENTIALS_VERSION = "2.22.0";
    private static final int MAGIC = 0x484f4d31; // HOM1
    private static final int MAX_HOMES = 2048;
    private static final int MAX_PAYLOAD = 3 * 1024 * 1024;
    private final Access access;

    record Home(String name, UUID worldId, String worldName, double x, double y, double z, float yaw, float pitch) {}
    record Account(UUID playerId, UUID configurationId, List<Home> homes) {}
    interface Access {
        boolean available();
        Account readLoaded(UUID playerId);
    }

    public EssentialsHomesAuthority(Server server, Plugin essentials) {
        this(new ReflectiveAccess(server, essentials));
    }

    EssentialsHomesAuthority(Access access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    @Override public boolean available() { return access.available(); }

    @Override public byte[] read(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!available()) throw new IllegalStateException("Essentials homes authority is unavailable");
        Account account = Objects.requireNonNull(access.readLoaded(playerId), "loaded home account");
        if (!playerId.equals(account.playerId()) || !playerId.equals(account.configurationId())) {
            throw new IllegalStateException("Essentials home account UUID differs from the player");
        }
        byte[] payload = encode(playerId, account.homes());
        if (!available()) throw new IllegalStateException("Essentials homes authority changed during capture");
        return payload;
    }

    @Override public void validate(UUID playerId, byte[] payload) {
        Objects.requireNonNull(playerId, "playerId");
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Invalid homes payload size");
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != 1
                    || !ESSENTIALS_VERSION.equals(BinaryStateIo.readString(input))
                    || !playerId.equals(readUuid(input))) {
                throw new IllegalArgumentException("Homes snapshot authority or identity differs");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_HOMES) throw new IllegalArgumentException("Invalid home count");
            List<Home> homes = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String name = BinaryStateIo.readString(input);
                UUID world = readUuid(input);
                int hasName = input.readUnsignedByte();
                if (hasName > 1) throw new IllegalArgumentException("Invalid world-name presence flag");
                String worldName = hasName == 1 ? BinaryStateIo.readString(input) : null;
                homes.add(new Home(name, world, worldName, input.readDouble(), input.readDouble(), input.readDouble(),
                        input.readFloat(), input.readFloat()));
            }
            BinaryStateIo.requireExhausted(input);
            if (!Arrays.equals(payload, encode(playerId, homes))) throw new IllegalArgumentException("Homes payload is not canonical");
        } catch (IOException exception) { throw new IllegalArgumentException("Malformed homes payload", exception); }
    }

    private static byte[] encode(UUID playerId, List<Home> homes) {
        Objects.requireNonNull(homes, "homes");
        if (homes.size() > MAX_HOMES) throw new IllegalArgumentException("Too many homes");
        HashSet<String> names = new HashSet<>();
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC); output.writeByte(1);
                writeString(output, ESSENTIALS_VERSION); writeUuid(output, playerId);
                output.writeInt(homes.size());
                // Order affects Essentials' numeric home aliases; preserve it as part of the authority.
                for (Home home : homes) {
                    Objects.requireNonNull(home, "home");
                    if (home.name() == null || home.name().isBlank() || !names.add(home.name())) {
                        throw new IllegalArgumentException("Empty or duplicate home name");
                    }
                    if (!Double.isFinite(home.x()) || !Double.isFinite(home.y()) || !Double.isFinite(home.z())
                            || !Float.isFinite(home.yaw()) || !Float.isFinite(home.pitch())) {
                        throw new IllegalArgumentException("Non-finite home location");
                    }
                    writeString(output, home.name()); writeUuid(output, Objects.requireNonNull(home.worldId(), "home world UUID"));
                    output.writeByte(home.worldName() == null ? 0 : 1);
                    if (home.worldName() != null) writeString(output, home.worldName());
                    output.writeDouble(home.x()); output.writeDouble(home.y()); output.writeDouble(home.z());
                    output.writeFloat(home.yaw()); output.writeFloat(home.pitch());
                }
            }
            if (bytes.size() > MAX_PAYLOAD) throw new IllegalArgumentException("Homes payload is too large");
            return bytes.toByteArray();
        } catch (IOException exception) { throw new IllegalArgumentException("Could not encode homes", exception); }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        if (value.length() > 512) throw new IllegalArgumentException("Home string is too long");
        StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
        BinaryStateIo.writeString(output, value);
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeUuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits()); output.writeLong(id.getLeastSignificantBits());
    }

    private static final class ReflectiveAccess implements Access {
        private final Server server;
        private final Plugin essentials;
        private final Method userId, configurationId, homes, world, worldName, x, y, z, yaw, pitch;
        private final EssentialsLoadedUserAccess loadedUsers;
        private final Field holder;

        private ReflectiveAccess(Server server, Plugin essentials) {
            this.server = Objects.requireNonNull(server, "server");
            this.essentials = Objects.requireNonNull(essentials, "essentials");
            if (!essentials.getClass().getName().equals("com.earth2me.essentials.Essentials")
                    || !ESSENTIALS_VERSION.equals(essentials.getDescription().getVersion())) {
                throw new IllegalArgumentException("Unsupported Essentials homes API version");
            }
            try {
                ClassLoader loader = essentials.getClass().getClassLoader();
                Class<?> user = Class.forName("com.earth2me.essentials.User", false, loader);
                Class<?> data = Class.forName("com.earth2me.essentials.UserData", false, loader);
                Class<?> location = Class.forName("com.earth2me.essentials.config.entities.LazyLocation", false, loader);
                loadedUsers = new EssentialsLoadedUserAccess(essentials);
                userId = user.getMethod("getUUID"); configurationId = user.getMethod("getConfigUUID");
                // This exact 2.22.0 field is the loaded authority. No field writes or location() calls.
                holder = data.getDeclaredField("holder");
                if (!holder.trySetAccessible()) throw new IllegalArgumentException("Loaded home model is inaccessible");
                homes = holder.getType().getMethod("homes");
                world = location.getMethod("world"); worldName = location.getMethod("worldName");
                x = location.getMethod("x"); y = location.getMethod("y"); z = location.getMethod("z");
                yaw = location.getMethod("yaw"); pitch = location.getMethod("pitch");
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                throw new IllegalArgumentException("Pinned Essentials home model is unavailable", incompatible);
            }
        }

        @Override public boolean available() {
            return essentials.isEnabled() && server.getPluginManager().getPlugin("Essentials") == essentials
                    && ESSENTIALS_VERSION.equals(essentials.getDescription().getVersion());
        }

        @Override public Account readLoaded(UUID playerId) {
            try {
                Object user = loadedUsers.readLoaded(playerId);
                if (user == null) throw new IllegalStateException("Essentials home account is not already loaded");
                UUID id = (UUID)userId.invoke(user), config = (UUID)configurationId.invoke(user);
                if (!playerId.equals(id) || !playerId.equals(config)) throw new IllegalStateException("Loaded home account UUID differs");
                Object model = holder.get(user);
                Object raw = homes.invoke(model);
                if (!(raw instanceof Map<?, ?> map) || map.size() > MAX_HOMES) throw new IllegalStateException("Invalid loaded home map");
                List<Home> captured = new ArrayList<>();
                for (var entry : map.entrySet()) {
                    if (captured.size() >= MAX_HOMES || !(entry.getKey() instanceof String name) || entry.getValue() == null) {
                        throw new IllegalStateException("Invalid loaded home entry");
                    }
                    Object location = entry.getValue();
                    String rawWorld = (String)world.invoke(location);
                    UUID worldId = UUID.fromString(rawWorld);
                    if (!worldId.toString().equals(rawWorld)) throw new IllegalStateException("Home world UUID is not canonical");
                    captured.add(new Home(name, worldId, (String)worldName.invoke(location),
                            (Double)x.invoke(location), (Double)y.invoke(location), (Double)z.invoke(location),
                            (Float)yaw.invoke(location), (Float)pitch.invoke(location)));
                }
                if (holder.get(user) != model || loadedUsers.readLoaded(playerId) != user) throw new IllegalStateException("Loaded home model changed during capture");
                return new Account(id, config, List.copyOf(captured));
            } catch (ReflectiveOperationException | ClassCastException incompatible) {
                throw new IllegalStateException("Could not read the pinned home model", incompatible);
            }
        }
    }
}
