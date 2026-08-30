package com.ciaac.minecraft.minigames.buildbattle;
import java.util.Objects; import java.util.UUID;
/** Adapter/persistence idempotency key scoped to one match. */
public record BuildBattleOperationId(UUID matchId,long sequence){public BuildBattleOperationId{Objects.requireNonNull(matchId);if(sequence<=0)throw new IllegalArgumentException("sequence must be positive");}}
