package com.ciaac.minecraft.minigames.paper.arena.staked;

import com.ciaac.minecraft.minigames.paper.arena.ArenaLoadoutSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * Exact, detached bytes for the three inventory facets used by staked arena
 * play.  The bytes are the Paper serialization, not a lossy item manifest.
 * Every accessor returns a copy so a caller cannot alter the value that was
 * hashed or persisted.
 */
public final class StakedInventoryPayload {
    public static final int MAX_SECTION_BYTES = 1_048_576;
    public static final int MAX_TOTAL_BYTES = MAX_SECTION_BYTES * 3;

    private final UUID playerId;
    private final String manifestDigest;
    private final byte[] storageBytes;
    private final byte[] armorBytes;
    private final byte[] extraBytes;
    private final String storageSha256;
    private final String armorSha256;
    private final String extraSha256;
    private final String payloadSha256;

    private StakedInventoryPayload(UUID playerId, String manifestDigest, byte[] storageBytes,
                                   byte[] armorBytes, byte[] extraBytes) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.manifestDigest = requireDigest(manifestDigest, "manifestDigest");
        this.storageBytes = checkedCopy(storageBytes, "storageBytes");
        this.armorBytes = checkedCopy(armorBytes, "armorBytes");
        this.extraBytes = checkedCopy(extraBytes, "extraBytes");
        int total = this.storageBytes.length + this.armorBytes.length + this.extraBytes.length;
        if (total > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("Staked inventory payload is too large");
        }
        this.storageSha256 = sha256(this.storageBytes);
        this.armorSha256 = sha256(this.armorBytes);
        this.extraSha256 = sha256(this.extraBytes);
        this.payloadSha256 = payloadDigest(playerId, this.manifestDigest,
                this.storageBytes, this.armorBytes, this.extraBytes);
    }

    /** Captures exact serialized bytes from a detached Paper loadout. */
    public static StakedInventoryPayload capture(UUID playerId, String manifestDigest,
                                                  ArenaLoadoutSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return fromSerialized(playerId, manifestDigest,
                serialize(snapshot.storage()), serialize(snapshot.armor()), serialize(snapshot.extra()));
    }

    /**
     * Creates a payload from bytes read from durable storage or a trusted
     * adapter.  The constructor recomputes every digest before accepting it.
     */
    public static StakedInventoryPayload fromSerialized(UUID playerId, String manifestDigest,
                                                         byte[] storageBytes, byte[] armorBytes,
                                                         byte[] extraBytes) {
        return new StakedInventoryPayload(playerId, manifestDigest, storageBytes, armorBytes, extraBytes);
    }

    public UUID playerId() { return playerId; }
    public String manifestDigest() { return manifestDigest; }
    public byte[] storageBytes() { return storageBytes.clone(); }
    public byte[] armorBytes() { return armorBytes.clone(); }
    public byte[] extraBytes() { return extraBytes.clone(); }
    public String storageSha256() { return storageSha256; }
    public String armorSha256() { return armorSha256; }
    public String extraSha256() { return extraSha256; }
    public String payloadSha256() { return payloadSha256; }
    public int totalBytes() { return storageBytes.length + armorBytes.length + extraBytes.length; }

    public boolean exactlyMatches(StakedInventoryPayload other) {
        return other != null
                && playerId.equals(other.playerId)
                && manifestDigest.equals(other.manifestDigest)
                && Arrays.equals(storageBytes, other.storageBytes)
                && Arrays.equals(armorBytes, other.armorBytes)
                && Arrays.equals(extraBytes, other.extraBytes);
    }

    private static byte[] serialize(ItemStack[] contents) {
        try {
            return ItemStack.serializeItemsAsBytes(Objects.requireNonNull(contents, "contents"));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not serialize the staked inventory safely", exception);
        }
    }

    private static byte[] checkedCopy(byte[] bytes, String name) {
        Objects.requireNonNull(bytes, name);
        if (bytes.length > MAX_SECTION_BYTES) {
            throw new IllegalArgumentException(name + " exceeds the bounded escrow payload size");
        }
        return bytes.clone();
    }

    private static String requireDigest(String digest, String name) {
        String value = Objects.requireNonNull(digest, name).trim();
        if (value.isEmpty() || value.length() > 256) {
            throw new IllegalArgumentException(name + " must be a bounded non-blank digest");
        }
        return value;
    }

    private static String payloadDigest(UUID playerId, String manifestDigest,
                                        byte[] storage, byte[] armor, byte[] extra) {
        MessageDigest digest = sha256Digest();
        update(digest, "player\0" + playerId + "\0manifest\0" + manifestDigest + "\0storage\0");
        digest.update(storage);
        update(digest, "\0armor\0");
        digest.update(armor);
        update(digest, "\0extra\0");
        digest.update(extra);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("JRE must provide SHA-256", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }
}
