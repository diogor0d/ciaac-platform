package com.ciaac.minecraft.minigames.bootstrap;

import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import java.util.Objects;

public final class FailClosedModuleAssembler implements ModuleAssembler {
    private final String reasonPtPt;

    public FailClosedModuleAssembler(String reasonPtPt) {
        if (reasonPtPt == null || reasonPtPt.isBlank()) throw new IllegalArgumentException("Missing reason");
        this.reasonPtPt = reasonPtPt;
    }

    @Override
    public MinigameModuleRegistry assemble(PlatformServices services) {
        Objects.requireNonNull(services, "services");
        return MinigameModuleRegistry.allUnavailable(reasonPtPt);
    }
}
