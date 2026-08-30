package com.ciaac.minecraft.minigames.paper.isolation;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class BinaryStateIo {
    static final int MAX_ARRAY_BYTES = 8 * 1024 * 1024;

    private BinaryStateIo() {}

    static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        if (value.length > MAX_ARRAY_BYTES) {
            throw new IllegalArgumentException("Serialized player-state array is too large");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_ARRAY_BYTES) {
            throw new IllegalArgumentException("Serialized player-state array length is invalid");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new IllegalArgumentException("Serialized player-state array is truncated");
        }
        return value;
    }

    static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 512) {
            throw new IllegalArgumentException("Serialized player-state string is too long");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 512) {
            throw new IllegalArgumentException("Serialized player-state string length is invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalArgumentException("Serialized player-state string is truncated");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void requireExhausted(DataInputStream input) throws IOException {
        if (input.available() != 0) {
            throw new IllegalArgumentException("Serialized player state contains trailing bytes");
        }
    }
}
