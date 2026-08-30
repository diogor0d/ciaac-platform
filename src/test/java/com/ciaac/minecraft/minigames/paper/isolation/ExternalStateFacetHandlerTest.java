package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;

class ExternalStateFacetHandlerTest {
    @Test
    void rejectsAdapterIdentityChangeBeforeExternalRestore() {
        MutablePort port = new MutablePort();
        ExternalStateFacetHandler handler = new ExternalStateFacetHandler(port);

        byte[] envelope = handler.capture(null);
        port.id = "changed-adapter";
        port.version = 2;

        assertThrows(IllegalStateException.class, () -> handler.validateRestore(envelope));
        assertThrows(IllegalStateException.class, () -> handler.restore(null, envelope));

        assertEquals(0, port.restoredVersion);
        assertNull(port.restoredPayload);
    }

    @Test
    void validatesEnvelopeWithoutCallingExternalRestore() {
        MutablePort port = new MutablePort();
        ExternalStateFacetHandler handler = new ExternalStateFacetHandler(port);

        byte[] envelope = handler.capture(null);
        handler.validateRestore(envelope);

        assertEquals(0, port.restoredVersion);
        assertNull(port.restoredPayload);
    }

    @Test
    void preflightFailsClosedWhenAdapterBecomesUnavailable() {
        MutablePort port = new MutablePort();
        ExternalStateFacetHandler handler = new ExternalStateFacetHandler(port);
        port.available = false;

        assertThrows(IllegalStateException.class, handler::preflight);
    }

    private static final class MutablePort implements ExternalStateFacetPort {
        private String id = "economy-adapter";
        private int version = 1;
        private boolean available = true;
        private int restoredVersion;
        private byte[] restoredPayload;

        @Override public String id() { return id; }
        @Override public int snapshotVersion() { return version; }
        @Override public Set<PlayerStateFacet> facets() { return Set.of(PlayerStateFacet.ECONOMY); }
        @Override public boolean available() { return available; }
        @Override public byte[] capture(Player player) { return new byte[] {1, 2, 3}; }
        @Override public void enterTemporaryState(Player player) {}
        @Override public void purgeTemporaryState(Player player) {}
        @Override public void restore(Player player, int snapshotVersion, byte[] payload) {
            restoredVersion = snapshotVersion;
            restoredPayload = payload.clone();
        }
    }
}
