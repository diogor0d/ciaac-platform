package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EssentialsEconomyAuthorityTest {
    private final UUID player = UUID.randomUUID();
    private final Access access = new Access();
    private final EssentialsEconomyAuthority authority = new EssentialsEconomyAuthority(access);

    @Test void exactDecimalBalancesAreCanonicalAndDriftChangesTheSnapshot() {
        byte[] first = authority.read(player);
        authority.validate(player, first);
        access.balance = new BigDecimal("12.3400");
        assertArrayEquals(first, authority.read(player));
        access.balance = new BigDecimal("12.34000000000000001");
        assertFalse(Arrays.equals(first, authority.read(player)));
        assertEquals(3, access.reads);
    }

    @Test void missingOrForeignLoadedAccountsAndProviderLossAreRejected() {
        access.configId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> authority.read(player));
        access.configId = player;
        access.userId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> authority.read(player));
        access.available = false;
        int reads = access.reads;
        assertThrows(IllegalStateException.class, () -> authority.read(player));
        assertEquals(reads, access.reads);
    }

    @Test void rejectsWrongIdentityVersionTruncationTrailingAndUnboundedDecimalScale() throws Exception {
        byte[] snapshot = authority.read(player);
        assertThrows(IllegalArgumentException.class, () -> authority.validate(UUID.randomUUID(), snapshot));
        assertThrows(IllegalArgumentException.class, () -> authority.validate(player, Arrays.copyOf(snapshot, snapshot.length-1)));
        assertThrows(IllegalArgumentException.class, () -> authority.validate(player, Arrays.copyOf(snapshot, snapshot.length+1)));
        byte[] wrongVersion = snapshot.clone();wrongVersion[3]=2;
        assertThrows(IllegalArgumentException.class, () -> authority.validate(player, wrongVersion));
        for (String value : new String[] {"1E+999999999", "1E-999999999", "NaN", "12.3400", "+12.34"}) {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(1);BinaryStateIo.writeString(output,"2.22.0");BinaryStateIo.writeString(output,"1.7.3-b131");
                output.writeLong(player.getMostSignificantBits());output.writeLong(player.getLeastSignificantBits());
                BinaryStateIo.writeString(output,value);
            }
            assertThrows(IllegalArgumentException.class, () -> authority.validate(player, bytes.toByteArray()), value);
        }
    }

    private final class Access implements EssentialsEconomyAuthority.Access {
        UUID userId = player, configId = player;
        BigDecimal balance = new BigDecimal("12.34");
        boolean available = true;
        int reads;
        public boolean available() { return available; }
        public String vaultVersion() { return "1.7.3-b131"; }
        public EssentialsEconomyAuthority.Account readLoaded(UUID id) {
            assertEquals(player,id);reads++;
            return new EssentialsEconomyAuthority.Account(userId,configId,balance);
        }
    }
}
