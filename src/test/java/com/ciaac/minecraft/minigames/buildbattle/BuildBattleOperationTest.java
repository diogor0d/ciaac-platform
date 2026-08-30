package com.ciaac.minecraft.minigames.buildbattle;
import static org.junit.jupiter.api.Assertions.*; import java.util.*; import org.junit.jupiter.api.Test;
class BuildBattleOperationTest{
 @Test void operationIdsRejectReplayAndWrongMatch(){var a=UUID.randomUUID();var b=UUID.randomUUID();var m=new BuildBattleMatch(new BuildBattleConfig(2,2,1,5,new BuildBattleTheme("t","Tema")),List.of(new BuildBattlePlot("a"),new BuildBattlePlot("b")));m.openWaiting(a);var op=new BuildBattleOperationId(a,1);m.join(UUID.randomUUID(),op);assertThrows(IllegalStateException.class,()->m.join(UUID.randomUUID(),op));assertThrows(IllegalArgumentException.class,()->m.join(UUID.randomUUID(),new BuildBattleOperationId(b,2)));}
 @Test void themePoolIsBoundedAndDuplicateFree(){var pool=new BuildBattleThemePool(List.of(new BuildBattleTheme("a","A"),new BuildBattleTheme("b","B")));assertEquals(2,pool.themes().size());assertThrows(IllegalArgumentException.class,()->new BuildBattleThemePool(List.of(new BuildBattleTheme("a","A"),new BuildBattleTheme("a","Outro"))));}
}
