package com.ciaac.minecraft.minigames.paper.module;
import com.ciaac.minecraft.minigames.core.GameKey;import com.ciaac.minecraft.minigames.paper.colorfloor.ColorFloorController;import java.time.Clock;import java.time.Duration;
public final class ColorFloorModule extends AbstractFixedModule<ColorFloorController>{
 public ColorFloorModule(ModuleIdentity identity,FixedControllerPort<ColorFloorController> port,RegionTokenFactory tokens,String regionId,Duration tokenLifetime,Clock clock){super(GameKey.COLOR_FLOOR,identity,port,tokens,regionId,tokenLifetime,clock);}
 public ColorFloorController controller(){return super.controller();}
}
