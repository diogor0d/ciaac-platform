package com.ciaac.minecraft.minigames.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class GitHubReleaseClientTest {
    @Test
    void prereleaseChannelChoosesHighestSemverIndependentlyOfApiOrder() {
        String json = "[" + release(3, "v0.1.0-alpha.2", false, true, true)
                + "," + release(1, "v0.1.0-alpha.1", false, true, true)
                + "," + release(2, "v0.0.9", false, false, true) + "]";

        assertEquals("v0.1.0-alpha.2",
                GitHubReleaseClient.selectLatestRelease(json, true).tag());
    }

    @Test
    void stableChannelExcludesPrereleases() {
        String json = "[" + release(1, "v0.2.0-alpha.1", false, true, true)
                + "," + release(2, "v0.1.0", false, false, true) + "]";

        assertEquals("v0.1.0",
                GitHubReleaseClient.selectLatestRelease(json, false).tag());
    }

    @Test
    void excludesDraftMutableMalformedAndInconsistentReleases() {
        String json = "[" + release(1, "v9.0.0", true, false, true)
                + "," + release(2, "v8.0.0", false, false, false)
                + "," + release(3, "v7.0.0-alpha.1", false, false, true)
                + "," + release(4, "latest", false, false, true)
                + "," + release(5, "v1.0.0", false, false, true) + "]";

        assertEquals("v1.0.0",
                GitHubReleaseClient.selectLatestRelease(json, true).tag());
    }

    @Test
    void rejectsAmbiguousSemanticVersionsAndOversizedLists() {
        String duplicates = "[" + release(1, "v1.0.0", false, false, true)
                + "," + release(2, "v1.0.0", false, false, true) + "]";
        assertThrows(IllegalArgumentException.class,
                () -> GitHubReleaseClient.selectLatestRelease(duplicates, false));

        StringBuilder oversized = new StringBuilder("[");
        for (int index = 0; index < 101; index++) {
            if (index > 0) oversized.append(',');
            oversized.append(release(index + 1L, "v1.0." + index, false, false, true));
        }
        oversized.append(']');
        assertThrows(IllegalArgumentException.class,
                () -> GitHubReleaseClient.selectLatestRelease(oversized.toString(), false));
    }

    private static String release(long id, String tag, boolean draft,
                                  boolean prerelease, boolean immutable) {
        return "{\"id\":" + id + ",\"tag_name\":\"" + tag
                + "\",\"draft\":" + draft + ",\"prerelease\":" + prerelease
                + ",\"immutable\":" + immutable + ",\"assets\":[]}";
    }
}
