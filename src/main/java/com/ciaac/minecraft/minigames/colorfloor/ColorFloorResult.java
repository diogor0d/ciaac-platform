package com.ciaac.minecraft.minigames.colorfloor;
import java.util.*;
public record ColorFloorResult(UUID matchId,String revision,Set<UUID> winners,int roundsSurvived,Map<UUID,Integer> eliminations,String reason){public ColorFloorResult{Objects.requireNonNull(matchId);Objects.requireNonNull(revision);winners=Set.copyOf(winners);eliminations=Map.copyOf(eliminations);reason=Objects.requireNonNull(reason);}}
