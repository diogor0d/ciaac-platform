package com.ciaac.minecraft.minigames.paper.colorfloor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.colorfloor.ColorFloorConfig;
import com.ciaac.minecraft.minigames.colorfloor.FloorColor;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

class ColorFloorPaperSettingsTest {
    @Test void disabledSettingsNeedNoLiveBukkitBlockDataAndRemainClosed() {
        var settings = ColorFloorPaperSettings.disabled(new ColorFloorConfig(1, 4, 3, Duration.ofSeconds(1), 9, "v1"), "color-floor");
        assertFalse(settings.enabled());
        assertEquals(Duration.ofSeconds(1), settings.announceDuration());
        assertTrue(settings.colors().isEmpty());
        assertTrue(settings.template().isEmpty());
    }

    @Test void announcementDurationIsBoundedAndPositive() {
        var config = new ColorFloorConfig(1, 4, 3, Duration.ofSeconds(1), 9, "v1");
        var settings = new ColorFloorPaperSettings(false, config, null, "color-floor", null,
                Map.of(new ColorFloorCell(0, 0, 0), FloorColor.RED),
                Map.of(new ColorFloorCell(0, 0, 0), blockData()),
                Duration.ofSeconds(2));
        assertEquals(Duration.ofSeconds(2), settings.announceDuration());
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorPaperSettings(false, config, null,
                "color-floor", null, settings.colors(), settings.template(), Duration.ZERO));
    }

    private static BlockData blockData() {
        return (BlockData) Proxy.newProxyInstance(
                BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class},
                (proxy, method, args) -> method.getName().equals("clone")
                        ? proxy : defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }
}
