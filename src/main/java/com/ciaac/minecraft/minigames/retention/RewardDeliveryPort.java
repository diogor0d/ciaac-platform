package com.ciaac.minecraft.minigames.retention;

import java.util.UUID;

/** Version-pinned external reward adapter boundary; implementations must not dispatch commands. */
public interface RewardDeliveryPort {
    String id();
    DeliveryResult deliver(UUID operationId, UUID playerId, SeasonWindow season, RewardDescriptor reward);

    enum DeliveryResult {
        DELIVERED,
        DELIVERY_PENDING,
        MANUAL_REVIEW,
        UNAVAILABLE
    }
}
