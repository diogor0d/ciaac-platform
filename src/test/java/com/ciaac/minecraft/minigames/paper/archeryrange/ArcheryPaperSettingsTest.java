package com.ciaac.minecraft.minigames.paper.archeryrange;
import static org.junit.jupiter.api.Assertions.assertThrows;import org.junit.jupiter.api.Test;
class ArcheryPaperSettingsTest{@Test void invalidRegionFailsClosed(){assertThrows(IllegalArgumentException.class,()->new ArcheryPaperSettings(false,"Bad",0,null,1,java.time.Duration.ofMinutes(1)));}}
