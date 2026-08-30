package com.ciaac.minecraft.minigames.paper.module;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import java.time.Duration;
import java.time.Instant;

/** Narrow injected boundary for fixed-controller region admission tokens. */
@FunctionalInterface
public interface RegionTokenFactory {
    RegionAdmissionToken create(GameKey game, AdmissionRequest request, String regionId, Instant now, Duration lifetime);
}
