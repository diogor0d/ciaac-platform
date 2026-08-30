package com.ciaac.minecraft.minigames.paper.module;
import com.ciaac.minecraft.minigames.core.GameKey;import com.ciaac.minecraft.minigames.paper.elytrarings.ElytraRingsController;import java.time.Clock;import java.time.Duration;
public final class ElytraRingsModule extends AbstractFixedModule<ElytraRingsController>{
 public ElytraRingsModule(ModuleIdentity identity,FixedControllerPort<ElytraRingsController> port,RegionTokenFactory tokens,String regionId,Duration tokenLifetime,Clock clock){super(GameKey.ELYTRA_RINGS,identity,port,tokens,regionId,tokenLifetime,clock);}
 public ElytraRingsController controller(){return super.controller();}
}
