package com.ciaac.minecraft.minigames.paper.isolation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.plugin.Plugin;

/** Read-only access to the actual loaded cache in the pinned Essentials version. */
final class EssentialsLoadedUserAccess {
    private final Plugin essentials;
    private final Method users;
    private final Field cache;
    private final Method getIfPresent;

    EssentialsLoadedUserAccess(Plugin essentials) throws ReflectiveOperationException {
        this.essentials = essentials;
        if (!essentials.getClass().getName().equals("com.earth2me.essentials.Essentials")
                || !EssentialsEconomyAuthority.ESSENTIALS_VERSION.equals(essentials.getDescription().getVersion())) {
            throw new IllegalArgumentException("Unsupported Essentials loaded cache version");
        }
        users = essentials.getClass().getMethod("getUsers");
        Class<?> map = users.getReturnType();
        if (!map.getName().equals("com.earth2me.essentials.userstorage.ModernUserMap")) {
            throw new IllegalArgumentException("Unsupported Essentials user map");
        }
        cache = map.getDeclaredField("userCache");
        if (!cache.getType().getName().equals("com.google.common.cache.LoadingCache") || !cache.trySetAccessible()) {
            throw new IllegalArgumentException("Loaded Essentials cache is inaccessible");
        }
        // getUser(UUID) can run a loader; getOnlineUserCache() is unused in 2.22.0.
        // Read this exact field, then use only the non-loading public cache getter.
        getIfPresent = Class.forName("com.google.common.cache.Cache", false, map.getClassLoader())
                .getMethod("getIfPresent", Object.class);
    }

    Object readLoaded(UUID playerId) throws ReflectiveOperationException {
        Object loaded = cache.get(users.invoke(essentials));
        if (loaded == null || !getIfPresent.getDeclaringClass().isInstance(loaded)) {
            throw new IllegalStateException("Invalid Essentials loaded cache");
        }
        return getIfPresent.invoke(loaded, playerId);
    }
}
