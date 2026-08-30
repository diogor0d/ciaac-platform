package com.ciaac.minecraft.minigames.paper.knockbacksumo;
import static org.junit.jupiter.api.Assertions.assertThrows;import org.junit.jupiter.api.Test;
class SumoPaperSettingsTest{@Test void invalidRegionFailsClosed(){assertThrows(IllegalArgumentException.class,()->new SumoPaperSettings(false,"Bad",null,null,java.time.Duration.ofMinutes(1)));}}
