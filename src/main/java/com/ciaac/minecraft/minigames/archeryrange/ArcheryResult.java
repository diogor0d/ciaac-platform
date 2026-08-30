package com.ciaac.minecraft.minigames.archeryrange;
import java.util.UUID;
public record ArcheryResult(UUID resultId,UUID sessionId,String rulesetRevision,int laneId,int shots,int score,int bullseyes,String reasonCode){public ArcheryResult{if(resultId==null||sessionId==null||rulesetRevision==null||laneId<0||shots<0||score<0||bullseyes<0||reasonCode==null||reasonCode.isBlank())throw new IllegalArgumentException("invalid result");}}
