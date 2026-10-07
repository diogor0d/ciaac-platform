package com.ciaac.minecraft.minigames.retention;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/** Strict loader for the independently reloadable Passport policy. */
public final class RetentionConfigurationLoader {
    public RetentionConfiguration load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        require(yaml.getString("timezone", "").equals("Europe/Lisbon"), "O fuso horário tem de ser Europe/Lisbon.");
        require(isApprovedSeasonAnchor(yaml.get("season.anchor")),
                "A primeira época tem de começar em 2026-09-15.");
        require(yaml.getInt("season.calendar-months") == 3, "As épocas têm de durar três meses de calendário.");
        require(yaml.getInt("season.claim-grace-days") == 14, "O período de reclamação tem de durar 14 dias.");
        require(yaml.getInt("qualification.authenticated-join-minutes") == 10,
                "A entrada diária exige 10 minutos autenticados.");
        require(yaml.getInt("qualification.active-sampled-minutes") == 15,
                "Um dia ativo exige 15 minutos amostrados.");
        require(yaml.getInt("qualification.weekly-active-days") == 3,
                "O objetivo semanal exige três dias ativos.");
        require(yaml.getInt("privacy.detailed-event-months") == 12,
                "Os créditos diários detalhados têm retenção de 12 meses.");
        require(yaml.getInt("points.join-day") == 1 && yaml.getInt("points.active-day") == 10
                        && yaml.getInt("points.weekly-objective") == 25,
                "A pontuação tem de respeitar a política aprovada: 1/10/25.");
        for (String integration : List.of("ultracosmetics", "gmusic", "luckperms", "vault")) {
            require(!yaml.getBoolean("integrations." + integration + ".enabled"),
                    "A integração " + integration + " ainda não tem uma versão instalada validada.");
        }
        return new RetentionConfiguration(yaml.getBoolean("enabled"), LisbonSeasonCalendar.LISBON,
                LisbonSeasonCalendar.ANCHOR, 3, 14, Duration.ofMinutes(10), 15, 3, 12,
                yaml.getBoolean("presentation.placeholders-enabled"),
                yaml.getBoolean("presentation.safezone-displays-enabled"),
                yaml.getBoolean("presentation.particles-enabled"), rewards(yaml));
    }

    static List<RewardDescriptor> rewards(YamlConfiguration yaml) {
        List<RewardDescriptor> rewards = new ArrayList<>();
        for (int value : integers(yaml, "rewards.join-streak-milestones")) rewards.add(internal(
                "sequencia-" + value, "Título de sequência " + value,
                RewardDescriptor.RewardMetric.CURRENT_JOIN_STREAK, value));
        for (int value : integers(yaml, "rewards.weekly-completion-milestones")) rewards.add(internal(
                "semanas-" + value, "Distintivo de atividade " + value,
                RewardDescriptor.RewardMetric.WEEKLY_OBJECTIVES, value));
        for (int value : integers(yaml, "rewards.passport-point-milestones")) rewards.add(internal(
                "pontos-" + value, "Emblema do Passaporte " + value,
                RewardDescriptor.RewardMetric.PASSPORT_POINTS, value));
        return List.copyOf(rewards);
    }

    private static boolean isApprovedSeasonAnchor(Object value) {
        LocalDate approved = LisbonSeasonCalendar.ANCHOR;
        if (value instanceof String text) return text.equals(approved.toString());
        if (value instanceof Date date) {
            Instant expected = approved.atStartOfDay(ZoneOffset.UTC).toInstant();
            return date.getTime() == expected.toEpochMilli();
        }
        return false;
    }

    private static List<Integer> integers(YamlConfiguration yaml, String path) {
        List<Integer> values = yaml.getIntegerList(path);
        require(!values.isEmpty() && values.size() <= 32 && values.stream().allMatch(value -> value > 0),
                "A lista de metas em " + path + " é inválida.");
        require(values.stream().distinct().count() == values.size(), "A lista de metas em " + path + " tem valores repetidos.");
        return List.copyOf(values);
    }

    private static RewardDescriptor internal(String id, String title, RewardDescriptor.RewardMetric metric, int threshold) {
        return new RewardDescriptor(id, title, metric, threshold, RewardDescriptor.DeliveryKind.INTERNAL, "internal");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
