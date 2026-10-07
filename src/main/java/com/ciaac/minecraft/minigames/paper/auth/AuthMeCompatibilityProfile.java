package com.ciaac.minecraft.minigames.paper.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Pure fail-closed validation of the AuthMe profile required by CIAAC. */
public final class AuthMeCompatibilityProfile {
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
    private static final String REQUIRED_EMPTY_STRING = "Hooks.sendPlayerTo";
    private static final Set<String> COMMANDS = Set.of(
            "onJoin", "onLogin", "onSessionLogin", "onFirstLogin", "onRegister", "onUnregister", "onLogout");

    private AuthMeCompatibilityProfile() {}

    /** Returns stable diagnostic codes only; configuration values are never included. */
    public static List<String> validate(YamlConfiguration mainConfig, YamlConfiguration commandsConfig) {
        List<String> diagnostics = new ArrayList<>();
        if (mainConfig == null) {
            diagnostics.add("AUTHME_MAIN_CONFIG_MISSING");
        } else {
            REQUIRED_BOOLEANS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                String path = entry.getKey();
                boolean expected = entry.getValue();
                Object value = mainConfig.get(path);
                if (!(value instanceof Boolean actual) || actual != expected) {
                    diagnostics.add("AUTHME_BOOLEAN_INVALID:" + path);
                }
            });
            Object destination = mainConfig.get(REQUIRED_EMPTY_STRING);
            if (!(destination instanceof String text) || !text.isEmpty()) {
                diagnostics.add("AUTHME_STRING_INVALID:" + REQUIRED_EMPTY_STRING);
            }
        }

        if (commandsConfig == null) {
            diagnostics.add("AUTHME_COMMANDS_CONFIG_MISSING");
        } else {
            COMMANDS.stream().sorted().forEach(name -> {
                ConfigurationSection section = commandsConfig.getConfigurationSection(name);
                if (section == null) {
                    diagnostics.add("AUTHME_COMMAND_SECTION_INVALID:" + name);
                } else if (!section.getKeys(false).isEmpty()) {
                    diagnostics.add("AUTHME_COMMAND_SECTION_NOT_EMPTY:" + name);
                }
            });
            commandsConfig.getKeys(false).stream()
                    .filter(name -> !COMMANDS.contains(name))
                    .sorted()
                    .forEach(name -> diagnostics.add("AUTHME_COMMAND_KEY_UNEXPECTED:" + name));
        }
        return List.copyOf(diagnostics);
    }
}
