package com.ciaac.minecraft.platform.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class AdminPermissionDeclarationTest {
    @Test void administrativeAndMinecartActionPermissionsAreDeclaredDefaultFalse() {
        InputStream resource = getClass().getClassLoader().getResourceAsStream("plugin.yml");
        assertNotNull(resource, "plugin.yml must be available on the test classpath");
        YamlConfiguration plugin;
        try (resource; InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            plugin = YamlConfiguration.loadConfiguration(reader);
        } catch (Exception failure) {
            throw new AssertionError("Could not read plugin.yml", failure);
        }

        ConfigurationSection permissions = plugin.getConfigurationSection("permissions");
        assertNotNull(permissions);
        for (String node : new String[] {
                "ciaac.minecarts.reload",
                "ciaac.minecarts.test",
                "ciaac.minigames.admin",
                "ciaac.retention.admin.view",
                "ciaac.retention.admin.review",
                "ciaac.retention.admin.season"
        }) {
            ConfigurationSection declaration = permissions.getConfigurationSection(node);
            assertNotNull(declaration, node + " must be declared");
            assertFalse(declaration.getBoolean("default", true),
                    node + " must default to false");
        }
    }
}
