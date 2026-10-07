package com.ciaac.minecraft.minigames.paper.auth;

import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.Plugin;

/** Public-API binding for the reviewed AuthMe 6.0.1 Paper release; no account data is read. */
public final class AuthMeCompletionBinding {
    private static final String VERSION = "6.0.1-b2770";
    private static final Map<String, String> REVIEWED_CLASSES = Map.ofEntries(
            Map.entry("process/login/ProcessSyncPlayerLogin", "879793db274bba676898362c2f45c87449b38a3df5be7931eb9d66ca257c778a"),
            Map.entry("data/limbo/LimboService", "417477c8bcd6c1be8968c7951d83d34af4faf24c767b9c958093660786f16811"),
            Map.entry("service/TeleportationService", "6d23785640fda9b5d2f9716e418687b7e428fb4e3665340f6c8d5ec48ccb43e7"),
            Map.entry("service/BukkitService", "a9689647893da8e6e00db890f020819138f3262d9426970c03bd111c6b018e65"),
            Map.entry("listener/PlayerListener", "64d0a569b574a6fb80042e0106c6bf112990c2f0f1770e630797a03dd162bc98"),
            Map.entry("settings/commandconfig/CommandManager", "62022307118e871c71e4046f464d5cc93e4f504262fa7e963f63c12d7d84211f"),
            Map.entry("service/bungeecord/BungeeSender", "3f717984de4e45122e01ca496f311b78b8301efaf809f82a828e43eb6a5500bb"),
            Map.entry("process/logout/ProcessSyncPlayerLogout", "0b097f93da57abcddad35994df86a2b533a47cc6f3559a70f68fe0bd57be016b"),
            Map.entry("events/LoginEvent", "398843bf11e4675a0f6b257e90772754a523fed4238d82f3cc1886e59b136cea"),
            Map.entry("events/LogoutEvent", "bf8488a817e75a0d1d297fb900393d6901b8ebd9fa866b39eaacdf62bead553c"),
            Map.entry("api/v3/AuthMeApi", "8b8d1c7f747deef639f67530db928dd7583d2f9e03b14ee6a7188c7843da2ee4"));

    private AuthMeCompletionBinding() {}

    public static void register(Plugin owner, Plugin provider, ConnectionRegistry connections,
            AuthenticationRegistry authentication, Clock clock, Consumer<Player> authenticatedHook) {
        ProfileProof proof = null;
        try {
            if (!VERSION.equals(provider.getPluginMeta().getVersion())) {
                throw new IllegalStateException("AUTHME_VERSION_UNREVIEWED");
            }
            ClassLoader loader = provider.getClass().getClassLoader();
            for (var entry : REVIEWED_CLASSES.entrySet()) {
                try (InputStream stream = loader.getResourceAsStream("fr/xephi/authme/" + entry.getKey() + ".class")) {
                    if (stream == null || !entry.getValue().equals(HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())))) {
                        throw new IllegalStateException("AUTHME_IMPLEMENTATION_UNREVIEWED");
                    }
                }
            }
            Class<?> apiClass = Class.forName("fr.xephi.authme.api.v3.AuthMeApi", true, loader);
            Method instance = apiClass.getMethod("getInstance");
            Object api = Objects.requireNonNull(instance.invoke(null));
            Method authenticated = apiClass.getMethod("isAuthenticated", Player.class);
            proof = new ProfileProof(owner, provider, api, instance, authenticated);
            AuthMeAuthenticationListener listener = new AuthMeAuthenticationListener(
                    owner, provider, connections, authentication, clock, authenticatedHook, proof);
            var manager = owner.getServer().getPluginManager();
            manager.registerEvents(listener, owner);
            registerEvent(owner, loader, "LoginEvent", listener, listener::onLogin, proof);
            registerEvent(owner, loader, "LogoutEvent", listener, listener::onLogout, proof);
        } catch (Exception | LinkageError failure) {
            if (proof != null) proof.revoke();
            owner.getLogger().severe("AUTHME_BINDING_UNAVAILABLE: " + failure.getClass().getSimpleName()
                    + "; admissão e recuperação permanecem fechadas.");
        }
    }

    private static void registerEvent(Plugin owner, ClassLoader loader, String name,
            AuthMeAuthenticationListener listener, Consumer<Player> handler, ProfileProof proof) throws Exception {
        Class<? extends Event> eventType = Class.forName("fr.xephi.authme.events." + name, true, loader)
                .asSubclass(Event.class);
        Method getPlayer = eventType.getMethod("getPlayer");
        owner.getServer().getPluginManager().registerEvent(eventType, listener, EventPriority.MONITOR,
                (ignored, event) -> {
                    if (event.getClass() != eventType || event.isAsynchronous()) return;
                    try { handler.accept((Player) getPlayer.invoke(event)); }
                    catch (Exception | LinkageError failure) { proof.revoke(); }
                }, owner, true);
    }

    private record FileStamp(Path path, long size, FileTime modified, Object key) {
        static FileStamp capture(Path path) throws Exception {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > 1_048_576) {
                throw new IllegalStateException("AUTHME_PROFILE_FILE_INVALID");
            }
            return new FileStamp(path, attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        }

        boolean unchanged() throws Exception { return equals(capture(path)); }
    }

    private static final class ProfileProof implements AuthMeAuthenticationListener.Proof {
        private final Plugin owner;
        private final Plugin provider;
        private final Object api;
        private final Method instance;
        private final Method authenticated;
        private volatile boolean revoked;
        private volatile List<FileStamp> files = List.of();

        private ProfileProof(Plugin owner, Plugin provider, Object api, Method instance, Method authenticated) {
            this.owner = owner;
            this.provider = provider;
            this.api = api;
            this.instance = instance;
            this.authenticated = authenticated;
        }

        @Override public boolean establish() {
            if (revoked || !files.isEmpty()) return false;
            try {
                Path folder = provider.getDataFolder().toPath().toRealPath();
                FileStamp main = FileStamp.capture(folder.resolve("config.yml"));
                FileStamp commands = FileStamp.capture(folder.resolve("commands.yml"));
                YamlConfiguration mainConfig = load(main.path());
                YamlConfiguration commandsConfig = load(commands.path());
                List<String> diagnostics = AuthMeCompatibilityProfile.validate(mainConfig, commandsConfig);
                if (!diagnostics.isEmpty()) {
                    diagnostics.forEach(code -> owner.getLogger().severe(code));
                    revoke();
                    return false;
                }
                files = List.of(main, commands);
                return valid();
            } catch (Exception | LinkageError failure) {
                revoke();
                return false;
            }
        }

        private static YamlConfiguration load(Path path) throws Exception {
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.loadFromString(Files.readString(path));
            return configuration;
        }

        @Override public boolean valid() {
            if (revoked || files.isEmpty()) return false;
            try {
                if (!owner.isEnabled() || !provider.isEnabled()
                        || owner.getServer().getPluginManager().getPlugin("AuthMe") != provider
                        || owner.getServer().getPluginManager().isPluginEnabled("nLogin")
                        || instance.invoke(null) != api) {
                    revoke();
                    return false;
                }
                for (FileStamp file : files) if (!file.unchanged()) { revoke(); return false; }
                return true;
            } catch (Exception | LinkageError failure) { revoke(); return false; }
        }

        @Override public boolean authenticated(Player player) {
            try { return Boolean.TRUE.equals(authenticated.invoke(api, player)); }
            catch (Exception | LinkageError failure) { revoke(); return false; }
        }

        @Override public void revoke() { revoked = true; }
    }
}
