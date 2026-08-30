package com.ciaac.minecraft.minigames.colorfloor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ciaac.minecraft.minigames.hotpotato.OperationId;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ColorFloorGameTest {
    private static final UUID MATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void rejectedOverCapacityJoinDoesNotMutateRoster() {
        ColorFloorGame game = new ColorFloorGame(MATCH_ID,
                new ColorFloorConfig(1, 1, 1, Duration.ofSeconds(1), 9, "v1"));
        game.open(operation(1));
        game.join(PLAYER_A, operation(2));

        assertThrows(IllegalStateException.class, () -> game.join(PLAYER_B, operation(3)));
        game.start(operation(4));

        assertEquals(java.util.Set.of(PLAYER_A), game.livePlayers());
    }

    private static OperationId operation(long sequence) {
        return new OperationId(MATCH_ID, sequence);
    }
}
