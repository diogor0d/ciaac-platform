package com.ciaac.minecraft.minigames.paper.isolation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/** Exact EssentialsX 2.22.0 native balance reader; never calls Vault's account-creating reads. */
public final class EssentialsEconomyAuthority implements ExternalStateAuthority {
    static final String ESSENTIALS_VERSION = "2.22.0";
    private final Access access;

    record Account(UUID playerId, UUID configurationId, BigDecimal balance) {}
    interface Access {
        boolean available();
        String vaultVersion();
        Account readLoaded(UUID playerId);
    }

    public EssentialsEconomyAuthority(Server server, Plugin essentials, Plugin vault) {
        this(new ReflectiveAccess(server, essentials, vault));
    }

    EssentialsEconomyAuthority(Access access) {
        this.access = Objects.requireNonNull(access, "access");
        if (access.vaultVersion() == null || access.vaultVersion().isBlank() || access.vaultVersion().length() > 128) {
            throw new IllegalArgumentException("Invalid Vault version");
        }
    }

    @Override public boolean available() { return access.available(); }

    @Override public byte[] read(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!available()) throw new IllegalStateException("Essentials economy authority is unavailable");
        Account account = Objects.requireNonNull(access.readLoaded(playerId), "loaded account");
        if (!playerId.equals(account.playerId()) || !playerId.equals(account.configurationId())) {
            throw new IllegalStateException("Essentials account UUID does not match the authenticated player");
        }
        byte[] payload = encode(playerId, account.balance());
        if (!available()) throw new IllegalStateException("Economy authority changed during capture");
        return payload;
    }

    @Override public void validate(UUID playerId, byte[] payload) {
        Objects.requireNonNull(playerId, "playerId");
        if (payload == null || payload.length == 0 || payload.length > 4096) throw new IllegalArgumentException("Invalid economy payload size");
        try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != 1 || !ESSENTIALS_VERSION.equals(BinaryStateIo.readString(input))
                    || !access.vaultVersion().equals(BinaryStateIo.readString(input))
                    || !playerId.equals(new UUID(input.readLong(), input.readLong()))) {
                throw new IllegalArgumentException("Economy snapshot authority or identity differs");
            }
            BigDecimal balance = new BigDecimal(BinaryStateIo.readString(input));
            BinaryStateIo.requireExhausted(input);
            if (!Arrays.equals(payload, encode(playerId, balance))) throw new IllegalArgumentException("Economy payload is not canonical");
        } catch (IOException exception) { throw new IllegalArgumentException("Malformed economy payload", exception); }
    }

    private byte[] encode(UUID playerId, BigDecimal balance) {
        Objects.requireNonNull(balance, "balance");
        if (balance.precision() > 128 || balance.scale() < -128 || balance.scale() > 128) {
            throw new IllegalArgumentException("Economy balance exceeds supported precision");
        }
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(1);
                BinaryStateIo.writeString(output, ESSENTIALS_VERSION);
                BinaryStateIo.writeString(output, access.vaultVersion());
                output.writeLong(playerId.getMostSignificantBits());
                output.writeLong(playerId.getLeastSignificantBits());
                BinaryStateIo.writeString(output, balance.stripTrailingZeros().toPlainString());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException("Could not encode economy state", impossible); }
    }

    /** Pinned loaded-cache access; no account loader or economy mutation is called. */
    private static final class ReflectiveAccess implements Access {
        private final Server server;
        private final Plugin essentials, vault;
        private final String vaultVersion;
        private final Class<?> providerType;
        private final Method userId, configurationId, balance, settings, disabled, started, layer;
        private final EssentialsLoadedUserAccess loadedUsers;

        private ReflectiveAccess(Server server, Plugin essentials, Plugin vault) {
            this.server = Objects.requireNonNull(server, "server");
            this.essentials = Objects.requireNonNull(essentials, "essentials");
            this.vault = Objects.requireNonNull(vault, "vault");
            if (!essentials.getClass().getName().equals("com.earth2me.essentials.Essentials")
                    || !ESSENTIALS_VERSION.equals(essentials.getDescription().getVersion())) {
                throw new IllegalArgumentException("Unsupported Essentials API version");
            }
            vaultVersion = vault.getDescription().getVersion();
            try {
                ClassLoader loader = essentials.getClass().getClassLoader();
                Class<?> user = Class.forName("com.earth2me.essentials.User", false, loader);
                Class<?> layers = Class.forName("com.earth2me.essentials.economy.EconomyLayers", false, loader);
                providerType = Class.forName("com.earth2me.essentials.economy.vault.VaultEconomyProvider", false, loader);
                loadedUsers = new EssentialsLoadedUserAccess(essentials);
                userId = user.getMethod("getUUID");
                configurationId = user.getMethod("getConfigUUID");
                balance = user.getMethod("getMoney");
                settings = essentials.getClass().getMethod("getSettings");
                disabled = settings.getReturnType().getMethod("isEcoDisabled");
                started = layers.getMethod("isServerStarted");
                layer = layers.getMethod("getSelectedLayer");
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                throw new IllegalArgumentException("Pinned Essentials economy API is unavailable", incompatible);
            }
        }

        @Override public String vaultVersion() { return vaultVersion; }

        @Override public boolean available() {
            if (server.getPluginManager().getPlugin("Essentials") != essentials
                    || server.getPluginManager().getPlugin("Vault") != vault
                    || !essentials.isEnabled() || !vault.isEnabled()
                    || !ESSENTIALS_VERSION.equals(essentials.getDescription().getVersion())
                    || !vaultVersion.equals(vault.getDescription().getVersion())) return false;
            try {
                if (!Boolean.TRUE.equals(started.invoke(null)) || layer.invoke(null) != null
                        || Boolean.TRUE.equals(disabled.invoke(settings.invoke(essentials)))) return false;
                Class<?> economy = null;
                for (Class<?> service : server.getServicesManager().getKnownServices()) {
                    if (!service.getName().equals("net.milkbowl.vault.economy.Economy")) continue;
                    if (economy != null) return false;
                    economy = service;
                }
                if (economy == null) return false;
                var selected = server.getServicesManager().getRegistration(economy);
                return selected != null && selected.getProvider().getClass() == providerType;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
        }

        @Override public Account readLoaded(UUID playerId) {
            try {
                Object user = loadedUsers.readLoaded(playerId);
                if (user == null) throw new IllegalStateException("Essentials account is not already loaded");
                return new Account((UUID)userId.invoke(user), (UUID)configurationId.invoke(user), (BigDecimal)balance.invoke(user));
            } catch (ReflectiveOperationException | ClassCastException incompatible) {
                throw new IllegalStateException("Could not read the pinned Essentials account API", incompatible);
            }
        }
    }
}
