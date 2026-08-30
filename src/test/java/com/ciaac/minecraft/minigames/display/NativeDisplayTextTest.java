package com.ciaac.minecraft.minigames.display;

import static org.junit.jupiter.api.Assertions.assertTrue;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.ModuleAvailability;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class NativeDisplayTextTest {
    @Test void renderContainsPortugueseStateCapacityAndJoinCommand() {
        ModuleStatus status = new ModuleStatus(GameKey.BUILD_BATTLE, ModuleAvailability.WAITING, true, 2, OptionalInt.of(8), "Fila disponível");
        String text = NativeDisplayText.render(status, "/buildbattle entrar");
        assertTrue(text.contains("Build Battle"));
        assertTrue(text.contains("À espera"));
        assertTrue(text.contains("2/8 participantes"));
        assertTrue(text.contains("/buildbattle entrar"));
    }
}
