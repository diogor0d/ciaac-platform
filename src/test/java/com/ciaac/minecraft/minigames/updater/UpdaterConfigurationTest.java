package com.ciaac.minecraft.minigames.updater;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class UpdaterConfigurationTest {
    @Test
    void disabledDefaultsKeepAutomaticRestartOff() {
        assertFalse(UpdaterConfiguration.disabled().enabled());
        assertFalse(UpdaterConfiguration.disabled().restartEmptyServerAfterStaging());
    }

    @Test
    void restartSettingRetainsTheLegacyAccessorWithoutChangingItsOptInDefault() {
        UpdaterConfiguration configuration = new UpdaterConfiguration(
                false, "ciaac", "releases", false, "ciaac-platform.jar",
                "ciaac-platform-update.properties", "ciaac-platform-update.properties.sig",
                Optional.empty(), Duration.ofSeconds(1), Duration.ofSeconds(1),
                32 * 1024 * 1024L, true);

        assertTrue(configuration.restartEmptyServerAfterStaging());
        assertTrue(configuration.stopEmptyServerAfterStaging());
    }

    @Test
    void enabledConfigurationRequiresARealEd25519KeyRatherThanOnlyAByteCount() {
        assertThrows(IllegalArgumentException.class, () -> new UpdaterConfiguration(
                true, "ciaac", "releases", false, "ciaac-platform.jar",
                "ciaac-platform-update.properties", "ciaac-platform-update.properties.sig",
                Optional.of(new byte[33]), Duration.ofSeconds(1), Duration.ofSeconds(1),
                32 * 1024 * 1024L, false));
    }
}
