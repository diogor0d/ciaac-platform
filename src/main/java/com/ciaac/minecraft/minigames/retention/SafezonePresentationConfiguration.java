package com.ciaac.minecraft.minigames.retention;

import java.io.File;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

record SafezonePresentationConfiguration(boolean enabled, String worldName, UUID worldId,
                                         Point center, double radius, Point currentRanking, Point hallOfFame) {
    static SafezonePresentationConfiguration load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        boolean enabled = yaml.getBoolean("presentation.safezone-displays-enabled")
                || yaml.getBoolean("presentation.particles-enabled");
        if (!enabled) return new SafezonePresentationConfiguration(false, "", null,
                new Point(0, 0, 0), 1, new Point(0, 0, 0), new Point(0, 0, 0));
        String name = yaml.getString("presentation.safezone.world.name", "");
        String rawUuid = yaml.getString("presentation.safezone.world.uuid", "");
        if (name.isBlank() || rawUuid.equals("__SET_ME__")) throw new IllegalArgumentException(
                "A apresentação do Passaporte exige o nome e UUID exatos do mundo seguro.");
        UUID uuid;
        try { uuid = UUID.fromString(rawUuid); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("O UUID do mundo seguro é inválido.", failure); }
        Point center = point(yaml, "presentation.safezone.center");
        double radius = yaml.getDouble("presentation.safezone.center.radius");
        if (!Double.isFinite(radius) || radius < 1 || radius > 128) throw new IllegalArgumentException(
                "O raio da zona segura tem de estar entre 1 e 128 blocos.");
        return new SafezonePresentationConfiguration(true, name, uuid, center, radius,
                point(yaml, "presentation.safezone.current-ranking"), point(yaml, "presentation.safezone.hall-of-fame"));
    }

    private static Point point(YamlConfiguration yaml, String path) {
        double x = yaml.getDouble(path + ".x"), y = yaml.getDouble(path + ".y"), z = yaml.getDouble(path + ".z");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException(
                "As coordenadas em " + path + " são inválidas.");
        return new Point(x, y, z);
    }
    record Point(double x, double y, double z) {}
}
