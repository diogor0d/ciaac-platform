package com.ciaac.minecraft.minigames.paper.hotpotato;
import static org.junit.jupiter.api.Assertions.assertThrows;import com.ciaac.minecraft.minigames.hotpotato.HotPotatoConfig;import java.time.Duration;import org.junit.jupiter.api.Test;
class HotPotatoPaperSettingsTest{@Test void invalidRegionIsRejected(){var config=new HotPotatoConfig(2,2,Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ZERO,4,Duration.ofMinutes(1),"r1");assertThrows(IllegalArgumentException.class,()->HotPotatoPaperSettings.disabled(config,"Bad"));}}
