package com.ciaac.minecraft.minigames.paper.module;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequestFactory;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Connection epoch plus complete admission identity factory. */
public record ModuleIdentity(ConnectionRegistry connections, AdmissionRequestFactory requests) {
    public ModuleIdentity { Objects.requireNonNull(connections, "connections"); Objects.requireNonNull(requests, "requests"); }
    public Optional<AdmissionRequest> request(Player player, GameKey game, UUID matchId) { return requests.create(Objects.requireNonNull(player), game, Objects.requireNonNull(matchId)); }
}
