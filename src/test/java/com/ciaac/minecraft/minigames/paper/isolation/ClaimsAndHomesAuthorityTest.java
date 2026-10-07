package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ClaimsAndHomesAuthorityTest {
    private static final UUID PLAYER = UUID.fromString("cd34263b-26f0-4519-a716-477686114e10");
    private static final int MAGIC = 0x43414831;

    @Test
    void requiresTwoNonemptyAvailableComponentsAndValidatesExactPlayerId() {
        FakeAuthority claims = new FakeAuthority(new byte[] {1, 2});
        FakeAuthority homes = new FakeAuthority(new byte[] {3, 4});
        ClaimsAndHomesAuthority combined = new ClaimsAndHomesAuthority(claims, homes);
        assertTrue(combined.available());
        byte[] payload = combined.read(PLAYER);
        assertTrue(claims.validatedIds.contains(PLAYER));
        assertTrue(homes.validatedIds.contains(PLAYER));
        assertThrows(IllegalArgumentException.class, () -> combined.validate(UUID.randomUUID(), payload));

        claims.enabled.set(false);
        assertFalse(combined.available());
        assertThrows(IllegalStateException.class, () -> combined.read(PLAYER));
        claims.enabled.set(true);
        homes.enabled.set(false);
        assertFalse(combined.available());
        assertThrows(IllegalStateException.class, () -> combined.read(PLAYER));
    }

    @Test
    void eitherComponentValidatorCanRejectCaptureAndRestoreValidation() {
        for (boolean rejectClaims : new boolean[] {true, false}) {
            FakeAuthority claims = new FakeAuthority(new byte[] {10});
            FakeAuthority homes = new FakeAuthority(new byte[] {20});
            ClaimsAndHomesAuthority combined = new ClaimsAndHomesAuthority(claims, homes);
            byte[] valid = combined.read(PLAYER);
            (rejectClaims ? claims : homes).rejectValidation.set(true);

            assertThrows(IllegalStateException.class, () -> combined.read(PLAYER));
            assertThrows(IllegalStateException.class, () -> combined.validate(PLAYER, valid));
        }
    }

    @Test
    void eitherComponentChangeChangesCombinedSnapshot() {
        AtomicReference<byte[]> claimState = new AtomicReference<>(new byte[] {1});
        AtomicReference<byte[]> homeState = new AtomicReference<>(new byte[] {2});
        ClaimsAndHomesAuthority combined = new ClaimsAndHomesAuthority(
                new FakeAuthority(claimState), new FakeAuthority(homeState));
        byte[] initial = combined.read(PLAYER);

        homeState.set(new byte[] {3});
        byte[] changedHomes = combined.read(PLAYER);
        assertFalse(Arrays.equals(initial, changedHomes));
        claimState.set(new byte[] {4});
        byte[] changedClaims = combined.read(PLAYER);
        assertFalse(Arrays.equals(changedHomes, changedClaims));
    }

    @Test
    void rejectsBadHeaderTruncationTrailingEmptyComponentAndOversize() throws Exception {
        FakeAuthority claims = new FakeAuthority(new byte[] {1});
        FakeAuthority homes = new FakeAuthority(new byte[] {2});
        ClaimsAndHomesAuthority combined = new ClaimsAndHomesAuthority(claims, homes);
        byte[] valid = combined.read(PLAYER);

        byte[] badMagic = valid.clone(); badMagic[0] ^= 1;
        byte[] badVersion = valid.clone(); badVersion[4] = 2;
        assertInvalid(combined, badMagic);
        assertInvalid(combined, badVersion);
        assertInvalid(combined, Arrays.copyOf(valid, valid.length - 1));
        assertInvalid(combined, Arrays.copyOf(valid, valid.length + 1));
        assertInvalid(combined, payload(new byte[0], new byte[] {2}));
        assertInvalid(combined, payload(new byte[] {1}, new byte[0]));
        assertInvalid(combined, new byte[7 * 1024 * 1024 + 1]);

        FakeAuthority oversized = new FakeAuthority(new byte[7 * 1024 * 1024]);
        assertThrows(IllegalArgumentException.class,
                () -> new ClaimsAndHomesAuthority(oversized, homes).read(PLAYER));
    }

    @Test
    void componentValidatorCannotMutateCapturedPayload() throws Exception {
        byte[] expectedClaims = {11, 12, 13};
        byte[] expectedHomes = {21, 22, 23};
        FakeAuthority claims = new FakeAuthority(expectedClaims.clone());
        FakeAuthority homes = new FakeAuthority(expectedHomes.clone());
        claims.mutateValidationInput.set(true);
        homes.mutateValidationInput.set(true);

        byte[] captured = new ClaimsAndHomesAuthority(claims, homes).read(PLAYER);
        assertArrayEquals(payload(expectedClaims, expectedHomes), captured);
    }

    private static void assertInvalid(ClaimsAndHomesAuthority authority, byte[] payload) {
        assertThrows(IllegalArgumentException.class, () -> authority.validate(PLAYER, payload));
    }

    private static byte[] payload(byte[] claimState, byte[] homeState) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC); output.writeByte(1);
            output.writeInt(claimState.length); output.write(claimState);
            output.writeInt(homeState.length); output.write(homeState);
        }
        return bytes.toByteArray();
    }

    private static final class FakeAuthority implements ExternalStateAuthority {
        private final AtomicReference<byte[]> state;
        private final AtomicBoolean enabled = new AtomicBoolean(true);
        private final AtomicBoolean rejectValidation = new AtomicBoolean();
        private final AtomicBoolean mutateValidationInput = new AtomicBoolean();
        private final java.util.List<UUID> validatedIds = new java.util.ArrayList<>();

        FakeAuthority(byte[] state) { this(new AtomicReference<>(state)); }
        FakeAuthority(AtomicReference<byte[]> state) { this.state = state; }

        @Override public boolean available() { return enabled.get(); }
        @Override public byte[] read(UUID playerId) { return state.get().clone(); }
        @Override public void validate(UUID playerId, byte[] payload) {
            validatedIds.add(playerId);
            if (!enabled.get() || rejectValidation.get()) throw new IllegalStateException("component rejected validation");
            if (!PLAYER.equals(playerId)) throw new IllegalArgumentException("component player UUID differs");
            if (mutateValidationInput.get() && payload.length > 0) payload[0] ^= 0x7f;
            if (!Arrays.equals(state.get(), payload) && !mutateValidationInput.get()) {
                throw new IllegalArgumentException("component payload differs");
            }
        }
    }
}
