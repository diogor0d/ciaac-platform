package local.harness;

import com.ciaac.minecraft.minigames.paper.isolation.EssentialsHomesAuthority;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/** Loaded real Essentials home model fixture, including an unresolved world UUID. */
final class EssentialsHomesProbe {
    private EssentialsHomesProbe() {}

    static void verify(Server server, Plugin essentials, Object user, UUID playerId) throws Exception {
        var authority = new EssentialsHomesAuthority(server, essentials);
        require(authority.available(), "Real home authority is unavailable");
        byte[] empty = authority.read(playerId);
        authority.validate(playerId, empty);
        expectRejected(() -> authority.read(UUID.randomUUID()));
        var setHome = user.getClass().getMethod("setHome", String.class, Location.class);
        Location first = new Location(server.getWorlds().getFirst(), 14.25, 100.5, 18.75, 25, -5);
        setHome.invoke(user, "2", first);
        setHome.invoke(user, "base", new Location(first.getWorld(), 15.5, 101.75, 19.25, 30, 10));
        byte[] original = authority.read(playerId);
        authority.validate(playerId, original);
        require(!Arrays.equals(empty, original), "Created homes absent from snapshot");
        expectRejected(() -> authority.validate(UUID.randomUUID(), original));
        @SuppressWarnings("unchecked") List<String> names = (List<String>)user.getClass().getMethod("getHomes").invoke(user);
        require(names.equals(List.of("2", "base")), "Numeric home fixture differs");

        ClassLoader loader = essentials.getClass().getClassLoader();
        Class<?> data = Class.forName("com.earth2me.essentials.UserData", false, loader);
        Field field = data.getDeclaredField("holder");
        require(field.trySetAccessible(), "Pinned raw home fixture is inaccessible");
        Object holder = field.get(user);
        @SuppressWarnings("unchecked") Map<String, Object> homes = (Map<String, Object>)holder.getClass().getMethod("homes").invoke(holder);
        Class<?> lazy = Class.forName("com.earth2me.essentials.config.entities.LazyLocation", true, loader);
        UUID unresolved = UUID.randomUUID();
        require(server.getWorld(unresolved) == null, "Unresolved world unexpectedly exists");
        Object location = lazy.getConstructor(String.class, String.class, double.class, double.class, double.class, float.class, float.class)
                .newInstance(unresolved.toString(), first.getWorld().getName(), 16.5, 102.5, 20.25, 35F, 15F);
        homes.put("base", location);
        byte[] unresolvedState = authority.read(playerId);
        authority.validate(playerId, unresolvedState);
        require(!Arrays.equals(original, unresolvedState), "Unresolved world UUID drift was ignored");
        require(lazy.getMethod("world").invoke(location).equals(unresolved.toString()), "Read rewrote the stored world UUID");
        require(Arrays.equals(unresolvedState, authority.read(playerId)), "Home snapshot is unstable");
        require(server.getWorld(unresolved) == null, "Read loaded an unresolved world");
        setHome.invoke(user, "base", new Location(first.getWorld(), 17.5, 103.5, 21.25, 40, 20));
        require(!Arrays.equals(unresolvedState, authority.read(playerId)), "Changed home was ignored");
    }

    private static void expectRejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new IllegalStateException("Unsafe home operation was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
