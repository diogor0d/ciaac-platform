package com.ciaac.minecraft.minigames.paper.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class AuthMeCompatibilityProfileTest {
    private static final Map<String, Boolean> REQUIRED_BOOLEANS = Map.ofEntries(
            Map.entry("settings.restrictions.noTeleport", true),
            Map.entry("settings.GameMode.ForceSurvivalMode", false),
            Map.entry("Hooks.bungeecord", false),
            Map.entry("Hooks.useEssentialsMotd", false),
            Map.entry("Hooks.disableSocialSpy", false),
            Map.entry("GroupOptions.enablePermissionCheck", false),
            Map.entry("settings.registration.forceKickAfterRegister", false),
            Map.entry("settings.registration.forceLoginAfterRegister", true),
            Map.entry("limbo.recreateEnderPearls", false),
            Map.entry("settings.registration.force", true));
    private static final List<String> COMMANDS = List.of(
            "onJoin", "onLogin", "onSessionLogin", "onFirstLogin", "onRegister", "onUnregister", "onLogout");

    @Test
    void acceptsCompleteProfileAndEmptyCommandSections() {
        assertTrue(AuthMeCompatibilityProfile.validate(validMainConfig(), validCommandsConfig()).isEmpty());
    }

    @Test
    void failsClosedWhenEitherConfigurationIsMissing() {
        assertEquals(List.of("AUTHME_MAIN_CONFIG_MISSING", "AUTHME_COMMANDS_CONFIG_MISSING"),
                AuthMeCompatibilityProfile.validate(null, null));

        assertEquals(List.of(
                        "AUTHME_COMMAND_SECTION_INVALID:onFirstLogin",
                        "AUTHME_COMMAND_SECTION_INVALID:onJoin",
                        "AUTHME_COMMAND_SECTION_INVALID:onLogin",
                        "AUTHME_COMMAND_SECTION_INVALID:onLogout",
                        "AUTHME_COMMAND_SECTION_INVALID:onRegister",
                        "AUTHME_COMMAND_SECTION_INVALID:onSessionLogin",
                        "AUTHME_COMMAND_SECTION_INVALID:onUnregister"),
                AuthMeCompatibilityProfile.validate(validMainConfig(), new YamlConfiguration()));
    }

    @Test
    void rejectsEachMissingOrIncorrectBoolean() {
        REQUIRED_BOOLEANS.forEach((path, expected) -> {
            YamlConfiguration missing = validMainConfig();
            missing.set(path, null);
            assertEquals(List.of("AUTHME_BOOLEAN_INVALID:" + path),
                    AuthMeCompatibilityProfile.validate(missing, validCommandsConfig()), "missing " + path);

            YamlConfiguration incorrect = validMainConfig();
            incorrect.set(path, !expected);
            assertEquals(List.of("AUTHME_BOOLEAN_INVALID:" + path),
                    AuthMeCompatibilityProfile.validate(incorrect, validCommandsConfig()), "incorrect " + path);

            YamlConfiguration wrongType = validMainConfig();
            wrongType.set(path, Boolean.toString(expected));
            assertEquals(List.of("AUTHME_BOOLEAN_INVALID:" + path),
                    AuthMeCompatibilityProfile.validate(wrongType, validCommandsConfig()), "wrong type " + path);
        });
    }

    @Test
    void requiresAnExplicitEmptyStringDestination() {
        for (Object invalid : List.of("server", 4, false)) {
            YamlConfiguration config = validMainConfig();
            config.set("Hooks.sendPlayerTo", invalid);
            assertEquals(List.of("AUTHME_STRING_INVALID:Hooks.sendPlayerTo"),
                    AuthMeCompatibilityProfile.validate(config, validCommandsConfig()));
        }
        YamlConfiguration missing = validMainConfig();
        missing.set("Hooks.sendPlayerTo", null);
        assertEquals(List.of("AUTHME_STRING_INVALID:Hooks.sendPlayerTo"),
                AuthMeCompatibilityProfile.validate(missing, validCommandsConfig()));
    }

    @Test
    void rejectsMissingAndNonSectionCommandEntries() {
        for (String command : COMMANDS) {
            YamlConfiguration missing = validCommandsConfig();
            missing.set(command, null);
            assertTrue(AuthMeCompatibilityProfile.validate(validMainConfig(), missing)
                    .contains("AUTHME_COMMAND_SECTION_INVALID:" + command));

            YamlConfiguration nonSection = validCommandsConfig();
            nonSection.set(command, "login");
            assertTrue(AuthMeCompatibilityProfile.validate(validMainConfig(), nonSection)
                    .contains("AUTHME_COMMAND_SECTION_INVALID:" + command));
        }
    }

    @Test
    void rejectsCommandsIncludingNestedDelayedCommandsAndUnexpectedKeys() {
        YamlConfiguration config = validCommandsConfig();
        config.set("onLogin.delayed", 20);
        config.set("onLogin.delayedCommands", List.of("login"));
        config.createSection("onUnknown");

        assertEquals(List.of(
                        "AUTHME_COMMAND_SECTION_NOT_EMPTY:onLogin",
                        "AUTHME_COMMAND_KEY_UNEXPECTED:onUnknown"),
                AuthMeCompatibilityProfile.validate(validMainConfig(), config));
    }

    @Test
    void acceptsOfficialAuthMeTopLevelEventSectionsAndRejectsNestedWrapper() {
        // AuthMe 6.0.1 commands.yml declares each event at the YAML document root.
        YamlConfiguration officialLayout = validCommandsConfig();
        assertTrue(AuthMeCompatibilityProfile.validate(validMainConfig(), officialLayout).isEmpty());

        YamlConfiguration wrongWrapper = new YamlConfiguration();
        COMMANDS.forEach(command -> wrongWrapper.createSection("commands." + command));
        List<String> expected = COMMANDS.stream().sorted()
                .map(command -> "AUTHME_COMMAND_SECTION_INVALID:" + command)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        expected.add("AUTHME_COMMAND_KEY_UNEXPECTED:commands");
        assertEquals(expected, AuthMeCompatibilityProfile.validate(validMainConfig(), wrongWrapper));
    }

    private static YamlConfiguration validMainConfig() {
        YamlConfiguration config = new YamlConfiguration();
        REQUIRED_BOOLEANS.forEach(config::set);
        config.set("Hooks.sendPlayerTo", "");
        return config;
    }

    private static YamlConfiguration validCommandsConfig() {
        YamlConfiguration config = new YamlConfiguration();
        for (String command : COMMANDS) config.createSection(command);
        return config;
    }
}
