package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import java.util.Set;
import org.bukkit.entity.Player;

/**
 * Versioned boundary for Vault/LuckPerms/Essentials/claims/world-reset adapters.
 * A missing or unavailable port means the corresponding facets remain unsupported.
 */
public interface ExternalStateFacetPort {
    String id();
    int snapshotVersion();
    Set<PlayerStateFacet> facets();
    boolean available();
    byte[] capture(Player player);
    void enterTemporaryState(Player player);
    void purgeTemporaryState(Player player);
    void restore(Player player, int snapshotVersion, byte[] payload);
}
