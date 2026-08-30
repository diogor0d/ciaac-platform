package com.ciaac.minecraft.minigames.checkpointparkour;
import java.time.Duration;
public record ParkourConfig(String rulesetRevision,int checkpointCount,Duration penalty,Duration maximumDuration){
 public ParkourConfig{if(rulesetRevision==null||rulesetRevision.isBlank()||checkpointCount<1||penalty==null||penalty.isNegative()||maximumDuration==null||maximumDuration.isNegative()||maximumDuration.isZero())throw new IllegalArgumentException("invalid parkour config");}
}
