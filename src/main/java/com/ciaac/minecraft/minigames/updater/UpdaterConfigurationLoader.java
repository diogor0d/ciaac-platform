package com.ciaac.minecraft.minigames.updater;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.configuration.file.FileConfiguration;

/** Parses updater configuration without weakening the main runtime configuration. */
public final class UpdaterConfigurationLoader {
    public LoadResult load(FileConfiguration source) {
        Objects.requireNonNull(source, "source");
        boolean requested = source.getBoolean("updater.enabled", false);
        if (!requested) {
            return new LoadResult(UpdaterConfiguration.disabled(), Optional.empty());
        }
        try {
            UpdaterConfiguration configuration = new UpdaterConfiguration(
                    requested,
                    source.getString("updater.github.owner", "SET_OWNER"),
                    source.getString("updater.github.repository", "SET_REPOSITORY"),
                    source.getBoolean("updater.github.allow-prereleases", false),
                    source.getString("updater.assets.jar", "ciaac-platform.jar"),
                    source.getString("updater.assets.manifest", "ciaac-platform-update.properties"),
                    source.getString("updater.assets.signature", "ciaac-platform-update.properties.sig"),
                    UpdaterConfiguration.decodePublicKey(
                            source.getString("updater.trusted-ed25519-public-key", "")),
                    Duration.ofSeconds(source.getLong("updater.connect-timeout-seconds", 5L)),
                    Duration.ofSeconds(source.getLong("updater.request-timeout-seconds", 20L)),
                    source.getLong("updater.maximum-jar-bytes", 32 * 1024 * 1024L),
                    restartWhenEmpty(source));
            return new LoadResult(configuration, Optional.empty());
        } catch (RuntimeException invalid) {
            return new LoadResult(UpdaterConfiguration.disabled(),
                    Optional.of(requested
                            ? "A atualização automática ficou fechada [UPDATER_CONFIG_INVALID]."
                            : "A atualização automática está desativada [UPDATER_DISABLED]."));
        }
    }

    private static boolean restartWhenEmpty(FileConfiguration source) {
        if (source.contains("updater.restart-empty-server-after-staging")) {
            return source.getBoolean("updater.restart-empty-server-after-staging", false);
        }
        // Read the old name only for existing operator configurations; the
        // template and all new documentation use the explicit restart name.
        return source.getBoolean("updater.stop-empty-server-after-staging", false);
    }

    public record LoadResult(UpdaterConfiguration configuration, Optional<String> diagnosticPtPt) {
        public LoadResult {
            java.util.Objects.requireNonNull(configuration, "configuration");
            java.util.Objects.requireNonNull(diagnosticPtPt, "diagnosticPtPt");
        }
    }
}
