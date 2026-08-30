package com.ciaac.minecraft.minigames.bootstrap;

import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;

/** Late assembly hook: shared security services exist before game controllers are constructed. */
@FunctionalInterface
public interface ModuleAssembler {
    MinigameModuleRegistry assemble(PlatformServices services);
}
