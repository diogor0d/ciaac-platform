package com.ciaac.minecraft.minigames.checkpointparkour;
import java.time.Duration; import java.util.UUID;
public record ParkourResult(UUID resultId,UUID sessionId,String rulesetRevision,Duration elapsed,int checkpoints,boolean valid,String reasonCode){public ParkourResult{if(resultId==null||sessionId==null||rulesetRevision==null||elapsed==null||elapsed.isNegative()||checkpoints<0||reasonCode==null||reasonCode.isBlank())throw new IllegalArgumentException("invalid result");}}
