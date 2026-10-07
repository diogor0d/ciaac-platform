package com.ciaac.minecraft.minigames.paper.isolation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Both real authorities are required for the platform's combined claims/homes facet. */
public final class ClaimsAndHomesAuthority implements ExternalStateAuthority {
    private static final int MAGIC = 0x43414831; // CAH1
    private static final int MAX_PAYLOAD = 7 * 1024 * 1024;
    private final ExternalStateAuthority claims;
    private final ExternalStateAuthority homes;

    public ClaimsAndHomesAuthority(ExternalStateAuthority claims, ExternalStateAuthority homes) {
        this.claims = Objects.requireNonNull(claims, "claims");
        this.homes = Objects.requireNonNull(homes, "homes");
    }

    @Override public boolean available() { return claims.available() && homes.available(); }

    @Override public byte[] read(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!available()) throw new IllegalStateException("Claims/homes authority is unavailable");
        byte[] claimState = Objects.requireNonNull(claims.read(playerId), "claim state").clone();
        byte[] homeState = Objects.requireNonNull(homes.read(playerId), "home state").clone();
        claims.validate(playerId, claimState.clone()); homes.validate(playerId, homeState.clone());
        if (!available()) throw new IllegalStateException("Claims/homes authority changed during capture");
        if (claimState.length == 0 || homeState.length == 0 || (long)claimState.length + homeState.length + 13 > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Invalid combined claims/homes payload size");
        }
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC); output.writeByte(1);
                BinaryStateIo.writeBytes(output, claimState); BinaryStateIo.writeBytes(output, homeState);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException("Could not encode claims/homes", impossible); }
    }

    @Override public void validate(UUID playerId, byte[] payload) {
        Objects.requireNonNull(playerId, "playerId");
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Invalid combined claims/homes payload size");
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != 1) throw new IllegalArgumentException("Unsupported claims/homes snapshot");
            byte[] claimState = BinaryStateIo.readBytes(input), homeState = BinaryStateIo.readBytes(input);
            BinaryStateIo.requireExhausted(input);
            if (claimState.length == 0 || homeState.length == 0) throw new IllegalArgumentException("Missing claims/homes authority payload");
            claims.validate(playerId, claimState); homes.validate(playerId, homeState);
        } catch (IOException malformed) { throw new IllegalArgumentException("Malformed claims/homes payload", malformed); }
    }
}
