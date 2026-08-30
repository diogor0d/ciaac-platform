package com.ciaac.minecraft.minigames.paper.module;

import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import java.time.Instant;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Typed factory/operation port used by fixed-instance Paper controllers. */
public interface FixedControllerPort<C> {
    ModuleStatus inactiveStatus();
    C create(UUID matchId, Player initialPlayer);
    ModuleStatus status(C controller, UUID matchId);
    AdmissionResult join(C controller, Player player, AdmissionRequest request, RegionAdmissionToken token);
    void leave(C controller, UUID playerId, UUID operationId);
    void tick(C controller, Instant now);
    void shutdown(C controller, UUID operationId);
    boolean terminal(C controller);
}
