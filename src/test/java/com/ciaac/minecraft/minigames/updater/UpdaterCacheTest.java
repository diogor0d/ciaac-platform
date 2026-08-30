package com.ciaac.minecraft.minigames.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UpdaterCacheTest {
    @Test
    void storesAndReadsOnlyNonSecretReleaseBodyAndEtag(@TempDir Path temporaryDirectory) throws Exception {
        assumeNoSymlinkAncestors(temporaryDirectory);
        UpdaterCache cache = new UpdaterCache(temporaryDirectory.resolve("updater-cache"));
        cache.store("stable-v1", "{\"id\":123}", Optional.of("\"etag-1\""));

        UpdaterCache.CacheSnapshot snapshot = cache.read("stable-v1");
        assertEquals(Optional.of("{\"id\":123}"), snapshot.rawJson());
        assertEquals(Optional.of("\"etag-1\""), snapshot.etag());
        assertTrue(snapshot.rawJson().isPresent());
    }

    @Test
    void mismatchedBodyAndEtagPairForcesAnUnconditionalRefresh(@TempDir Path temporaryDirectory) throws Exception {
        assumeNoSymlinkAncestors(temporaryDirectory);
        UpdaterCache cache = new UpdaterCache(temporaryDirectory.resolve("updater-cache"));
        cache.store("stable-v1", "{\"id\":123}", Optional.of("\"etag-1\""));
        java.nio.file.Files.writeString(
                cache.directory().resolve("latest-release.json"), "{\"id\":124}");

        UpdaterCache.CacheSnapshot snapshot = cache.read("stable-v1");
        assertEquals(Optional.of("{\"id\":124}"), snapshot.rawJson());
        assertTrue(snapshot.etag().isEmpty());
    }

    @Test
    void changingReleaseChannelInvalidatesTheCachedEtag(@TempDir Path temporaryDirectory) throws Exception {
        assumeNoSymlinkAncestors(temporaryDirectory);
        UpdaterCache cache = new UpdaterCache(temporaryDirectory.resolve("updater-cache"));
        cache.store("stable-v1", "[]", Optional.of("\"etag-1\""));

        UpdaterCache.CacheSnapshot snapshot = cache.read("signed-prereleases-v1");

        assertEquals(Optional.of("[]"), snapshot.rawJson());
        assertTrue(snapshot.etag().isEmpty());
    }

    @Test
    void restartAttemptIsAtomicBoundedAndDurable(@TempDir Path temporaryDirectory) throws Exception {
        assumeNoSymlinkAncestors(temporaryDirectory);
        UpdaterCache cache = new UpdaterCache(temporaryDirectory.resolve("updater-cache"));
        UpdaterCache.RestartAttempt attempt = new UpdaterCache.RestartAttempt(
                42, SemanticVersion.parse("1.2.3"),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                Instant.parse("2026-08-22T12:00:00Z"));

        cache.storeRestartAttempt(attempt);

        assertEquals(Optional.of(attempt), cache.readRestartAttempt());
        cache.clearRestartAttempt();
        assertTrue(cache.readRestartAttempt().isEmpty());
    }

    @Test
    void malformedRestartAttemptFailsClosed(@TempDir Path temporaryDirectory) throws Exception {
        assumeNoSymlinkAncestors(temporaryDirectory);
        UpdaterCache cache = new UpdaterCache(temporaryDirectory.resolve("updater-cache"));
        cache.store("stable-v1", "format=wrong\n", Optional.empty());

        java.nio.file.Files.writeString(
                cache.directory().resolve("restart-attempt.properties"),
                "ciaac-platform-updater-restart-v1\nrelease-id=1\nversion=1.2.3\n"
                        + "sha256=not-a-digest\nattempted-at=2026-08-22T12:00:00Z\n");

        assertThrows(IllegalArgumentException.class, () -> cache.readRestartAttempt());
    }

    @Test
    void restartGuardBlocksOnlyUntilTheMarkedVersionIsInstalled() {
        UpdaterCache.RestartAttempt attempt = new UpdaterCache.RestartAttempt(
                42, SemanticVersion.parse("1.2.3"),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                Instant.parse("2026-08-22T12:00:00Z"));

        assertEquals(UpdaterService.RestartGuardDecision.BLOCKED,
                UpdaterService.restartGuardDecision(Optional.of(attempt), SemanticVersion.parse("1.2.2")));
        assertEquals(UpdaterService.RestartGuardDecision.CONSUMED,
                UpdaterService.restartGuardDecision(Optional.of(attempt), SemanticVersion.parse("1.2.3")));
        assertEquals(UpdaterService.RestartGuardDecision.NONE,
                UpdaterService.restartGuardDecision(Optional.empty(), SemanticVersion.parse("1.2.2")));
    }

    @Test
    void resolvesPaperUpdateFolderRelativeToPluginsDirectory(@TempDir Path temporaryDirectory) throws Exception {
        Path plugins = temporaryDirectory.resolve("plugins");

        assertEquals(plugins.resolve("update").toAbsolutePath().normalize(),
                UpdaterService.resolveUpdateDirectory(plugins, "update"));
    }

    private static void assumeNoSymlinkAncestors(Path path) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            assumeTrue(!java.nio.file.Files.isSymbolicLink(current),
                    "O diretório temporário do sistema contém um antecessor simbólico.");
            current = current.getParent();
        }
    }

    @Test
    void rejectsPaperUpdateFolderThatEscapesPluginsDirectory(@TempDir Path temporaryDirectory) {
        Path plugins = temporaryDirectory.resolve("plugins");

        assertThrows(IOException.class, () -> UpdaterService.resolveUpdateDirectory(
                plugins, Path.of("..", "outside").toString()));
        assertThrows(IOException.class, () -> UpdaterService.resolveUpdateDirectory(
                plugins, temporaryDirectory.resolve("absolute-update").toString()));
    }

    @Test
    void rejectsASymlinkedCacheAncestor(@TempDir Path temporaryDirectory) throws Exception {
        Path outside = temporaryDirectory.resolve("outside");
        Path link = temporaryDirectory.resolve("cache-link");
        java.nio.file.Files.createDirectories(outside);
        try {
            java.nio.file.Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException unsupported) {
            assumeTrue(false, "symbolic links are unavailable in this test environment");
        }

        UpdaterCache cache = new UpdaterCache(link.resolve("updater-cache"));
        assertThrows(IOException.class, () -> cache.store(
                "stable-v1", "{\"id\":123}", Optional.empty()));
    }
}
