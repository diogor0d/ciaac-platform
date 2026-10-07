package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EssentialsHomesAuthorityTest {
    private static final UUID PLAYER = UUID.fromString("4eec5106-659c-464e-a551-6524693e88b2");
    private static final UUID WORLD = UUID.fromString("e90f5fd5-4169-46a4-9c42-c96e89cdfbc2");
    private static final UUID OTHER_WORLD = UUID.fromString("bc052205-8ef2-42ea-a6dc-dd7c516f0fd7");

    @Test
    void rejectsLoadedUserAndConfigurationUuidMismatchesAndMissingProvider() {
        assertThrows(IllegalStateException.class, () -> authority(true,
                new EssentialsHomesAuthority.Account(UUID.randomUUID(), PLAYER, List.of(home("home")))).read(PLAYER));
        assertThrows(IllegalStateException.class, () -> authority(true,
                new EssentialsHomesAuthority.Account(PLAYER, UUID.randomUUID(), List.of(home("home")))).read(PLAYER));

        EssentialsHomesAuthority missing = authority(false,
                new EssentialsHomesAuthority.Account(PLAYER, PLAYER, List.of()));
        assertFalse(missing.available());
        assertThrows(IllegalStateException.class, () -> missing.read(PLAYER));
    }

    @Test
    void snapshotChangesForEveryHomeFieldIncludingRawWorldNameAndCoordinates() {
        EssentialsHomesAuthority.Home baseline = home("1");
        byte[] original = snapshot(List.of(baseline));

        assertDrift(original, home("renamed"));
        assertDrift(original, new EssentialsHomesAuthority.Home("1", OTHER_WORLD, "world", 1, 2, 3, 4, 5));
        assertDrift(original, new EssentialsHomesAuthority.Home("1", WORLD, "raw-world-renamed", 1, 2, 3, 4, 5));
        assertDrift(original, new EssentialsHomesAuthority.Home("1", WORLD, "world", 1.25, 2, 3, 4, 5));
        assertDrift(original, new EssentialsHomesAuthority.Home("1", WORLD, "world", 1, 2.25, 3, 4, 5));
        assertDrift(original, new EssentialsHomesAuthority.Home("1", WORLD, "world", 1, 2, 3.25, 4, 5));
        assertDrift(original, new EssentialsHomesAuthority.Home("1", WORLD, "world", 1, 2, 3, 4.25f, 5));
        assertDrift(original, new EssentialsHomesAuthority.Home("1", WORLD, "world", 1, 2, 3, 4, 5.25f));
        assertDrift(original, new EssentialsHomesAuthority.Home("1", WORLD, null, 1, 2, 3, 4, 5));
    }

    @Test
    void preservesOrderBecauseNumericHomeAliasesDependOnIt() {
        byte[] forward = snapshot(List.of(home("1"), home("2")));
        byte[] reversed = snapshot(List.of(home("2"), home("1")));

        assertFalse(java.util.Arrays.equals(forward, reversed));
        assertDoesNotThrow(() -> authority(true,
                new EssentialsHomesAuthority.Account(PLAYER, PLAYER, List.of(home("1"), home("2"))))
                .validate(PLAYER, forward));
    }

    @Test
    void acceptsEmptyHomesOnlyWhenALoadedAccountExists() {
        EssentialsHomesAuthority empty = authority(true,
                new EssentialsHomesAuthority.Account(PLAYER, PLAYER, List.of()));
        byte[] payload = empty.read(PLAYER);
        assertDoesNotThrow(() -> empty.validate(PLAYER, payload));

        EssentialsHomesAuthority noAccount = authority(true, null);
        assertThrows(NullPointerException.class, () -> noAccount.read(PLAYER));
    }

    @Test
    void boundsHomeCountNamesAndFiniteLocationValues() {
        List<EssentialsHomesAuthority.Home> tooMany = new ArrayList<>();
        for (int index = 0; index < 2049; index++) tooMany.add(home("h" + index));
        assertThrows(IllegalArgumentException.class, () -> snapshot(tooMany));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(home("  "))));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(home("duplicate"), home("duplicate"))));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(home("a".repeat(513)))));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(home("\ud800"))));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(new EssentialsHomesAuthority.Home(
                "invalid", WORLD, "world", Double.NaN, 0, 0, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(new EssentialsHomesAuthority.Home(
                "invalid", WORLD, "world", 0, 0, 0, 0, Float.POSITIVE_INFINITY))));
    }

    @Test
    void rejectsWrongUuidVersionTruncationTrailingDuplicateAndNoncanonicalUtf() throws Exception {
        byte[] valid = snapshot(List.of(home("home")));
        assertThrows(IllegalArgumentException.class, () -> authority(true,
                new EssentialsHomesAuthority.Account(PLAYER, PLAYER, List.of(home("home"))))
                .validate(UUID.randomUUID(), valid));

        byte[] wrongVersion = valid.clone();
        wrongVersion[4] = 2;
        assertInvalid(wrongVersion);
        assertInvalid(java.util.Arrays.copyOf(valid, valid.length - 1));
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertInvalid(trailing);
        assertInvalid(duplicateHomePayload());
        assertInvalid(noncanonicalUtfPayload());
    }

    private static void assertDrift(byte[] baseline, EssentialsHomesAuthority.Home changed) {
        assertFalse(java.util.Arrays.equals(baseline, snapshot(List.of(changed))));
    }

    private static byte[] snapshot(List<EssentialsHomesAuthority.Home> homes) {
        return authority(true, new EssentialsHomesAuthority.Account(PLAYER, PLAYER, homes)).read(PLAYER);
    }

    private static EssentialsHomesAuthority authority(boolean available, EssentialsHomesAuthority.Account account) {
        return new EssentialsHomesAuthority(new EssentialsHomesAuthority.Access() {
            @Override public boolean available() { return available; }
            @Override public EssentialsHomesAuthority.Account readLoaded(UUID playerId) { return account; }
        });
    }

    private static EssentialsHomesAuthority.Home home(String name) {
        return new EssentialsHomesAuthority.Home(name, WORLD, "world", 1, 2, 3, 4, 5);
    }

    private static void assertInvalid(byte[] payload) {
        assertThrows(IllegalArgumentException.class, () -> authority(true,
                new EssentialsHomesAuthority.Account(PLAYER, PLAYER, List.of()))
                .validate(PLAYER, payload));
    }

    private static byte[] duplicateHomePayload() throws Exception {
        return payloadPrefix(2, output -> {
            writeHome(output, "same");
            writeHome(output, "same");
        });
    }

    private static byte[] noncanonicalUtfPayload() throws Exception {
        return payloadPrefix(1, output -> {
            output.writeInt(1);
            output.writeByte(0xc3); // invalid standalone UTF-8 lead byte
            writeUuid(output, WORLD);
            output.writeByte(0);
            output.writeDouble(1); output.writeDouble(2); output.writeDouble(3);
            output.writeFloat(4); output.writeFloat(5);
        });
    }

    private static byte[] payloadPrefix(int count, IoConsumer<DataOutputStream> homes) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x484f4d31); output.writeByte(1);
            writeString(output, "2.22.0"); writeUuid(output, PLAYER); output.writeInt(count);
            homes.accept(output);
        }
        return bytes.toByteArray();
    }

    private static void writeHome(DataOutputStream output, String name) throws Exception {
        writeString(output, name); writeUuid(output, WORLD); output.writeByte(1); writeString(output, "world");
        output.writeDouble(1); output.writeDouble(2); output.writeDouble(3);
        output.writeFloat(4); output.writeFloat(5);
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length); output.write(bytes);
    }

    private static void writeUuid(DataOutputStream output, UUID uuid) throws Exception {
        output.writeLong(uuid.getMostSignificantBits()); output.writeLong(uuid.getLeastSignificantBits());
    }

    @FunctionalInterface
    private interface IoConsumer<T> { void accept(T value) throws Exception; }
}
