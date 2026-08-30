package com.ciaac.minecraft.minigames.archeryrange;
public record ArcheryConfig(String rulesetRevision,int laneId,int shotCount,int maxScorePerShot){public ArcheryConfig{if(rulesetRevision==null||rulesetRevision.isBlank()||laneId<0||shotCount<1||maxScorePerShot<1)throw new IllegalArgumentException("invalid archery config");}}
