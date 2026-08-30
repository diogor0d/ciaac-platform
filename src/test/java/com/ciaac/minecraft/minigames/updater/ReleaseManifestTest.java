package com.ciaac.minecraft.minigames.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class ReleaseManifestTest {
    private static final long MAXIMUM_JAR_BYTES = 32 * 1024 * 1024L;
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void parsesOnlyTheExactSignedContract() {
        ReleaseManifest manifest = ReleaseManifest.parse(manifest("size=16384\n").getBytes(StandardCharsets.UTF_8),
                "ciaac-platform.jar", MAXIMUM_JAR_BYTES);

        assertEquals(123L, manifest.releaseId());
        assertEquals(SemanticVersion.parse("0.2.0"), manifest.version());
        assertEquals(16_384L, manifest.size());
        assertEquals(HASH, manifest.sha256());
    }

    @Test
    void rejectsDuplicateUnknownAndAmbiguousFields() {
        assertThrows(IllegalArgumentException.class, () -> ReleaseManifest.parse(
                manifest("size=16384\nsize=16384\n").getBytes(StandardCharsets.UTF_8),
                "ciaac-platform.jar", MAXIMUM_JAR_BYTES));
        assertThrows(IllegalArgumentException.class, () -> ReleaseManifest.parse(
                manifest("size=16384\nextra=value\n").getBytes(StandardCharsets.UTF_8),
                "ciaac-platform.jar", MAXIMUM_JAR_BYTES));
        assertThrows(IllegalArgumentException.class, () -> ReleaseManifest.parse(
                manifest("size=16384\r\n").getBytes(StandardCharsets.UTF_8),
                "ciaac-platform.jar", MAXIMUM_JAR_BYTES));
    }

    private static String manifest(String replacement) {
        return "format=ciaac-platform-update-v1\n"
                + "release-id=123\n"
                + "version=0.2.0\n"
                + "artifact=ciaac-platform.jar\n"
                + replacement
                + "sha256=" + HASH + "\n"
                + "plugin-name=CIAACPlatform\n"
                + "plugin-main=com.ciaac.minecraft.minigames.CiaacMinigamesPlugin\n";
    }
}
