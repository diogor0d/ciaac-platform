package com.ciaac.minecraft.minigames.paper.checkpointparkour;
import static org.junit.jupiter.api.Assertions.assertThrows;import org.junit.jupiter.api.Test;
class ParkourPaperSettingsTest{@Test void invalidRegionFailsClosed(){assertThrows(IllegalArgumentException.class,()->new ParkourPaperSettings(false,"Bad",null,java.util.List.of(),java.time.Duration.ofMinutes(1)));}}
