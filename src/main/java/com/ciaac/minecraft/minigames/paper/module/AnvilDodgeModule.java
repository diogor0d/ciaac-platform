package com.ciaac.minecraft.minigames.paper.module;
import com.ciaac.minecraft.minigames.core.GameKey;import com.ciaac.minecraft.minigames.paper.anvildodge.AnvilDodgeController;import java.time.Clock;import java.time.Duration;import java.util.Objects;
public final class AnvilDodgeModule extends AbstractFixedModule<AnvilDodgeController>{
 public AnvilDodgeModule(ModuleIdentity identity,FixedControllerPort<AnvilDodgeController> port,RegionTokenFactory tokens,String regionId,Duration tokenLifetime,Clock clock){super(GameKey.ANVIL_DODGE,identity,port,tokens,regionId,tokenLifetime,clock);}
 public AnvilDodgeController controller(){return super.controller();}
}
