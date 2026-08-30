package com.ciaac.minecraft.minigames.paper.buildbattle;
import static org.junit.jupiter.api.Assertions.assertThrows;import com.ciaac.minecraft.minigames.buildbattle.*;import java.util.*;import org.junit.jupiter.api.Test;
class BuildBattlePaperSettingsTest{@Test void invalidRegionIsRejected(){var config=new BuildBattleConfig(2,2,1,5,new BuildBattleTheme("t","Tema"));var themes=new BuildBattleThemePool(List.of(config.theme()));assertThrows(IllegalArgumentException.class,()->BuildBattlePaperSettings.disabled(config,themes,"Bad",Map.of()));}}
