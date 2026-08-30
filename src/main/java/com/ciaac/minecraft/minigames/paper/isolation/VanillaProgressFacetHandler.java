package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/** Exact vanilla-statistic and advancement snapshot used with ProgressSuppressionListener. */
public final class VanillaProgressFacetHandler implements FacetSnapshotHandler {
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 100_000;
    private static final Set<PlayerStateFacet> FACETS = Set.of(
            PlayerStateFacet.VANILLA_STATISTICS, PlayerStateFacet.ADVANCEMENTS);
    private final Server server;

    public VanillaProgressFacetHandler(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override public Set<PlayerStateFacet> facets() { return FACETS; }

    @Override
    public byte[] capture(Player player) {
        try {
            List<StatValue> statistics = captureStatistics(player);
            Map<String, Set<String>> advancements = captureAdvancements(player);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                output.writeInt(statistics.size());
                for (StatValue value : statistics) {
                    BinaryStateIo.writeString(output, value.statistic());
                    BinaryStateIo.writeString(output, value.qualifier());
                    output.writeInt(value.value());
                }
                output.writeInt(advancements.size());
                for (Map.Entry<String, Set<String>> entry : advancements.entrySet()) {
                    BinaryStateIo.writeString(output, entry.getKey());
                    output.writeInt(entry.getValue().size());
                    for (String criterion : entry.getValue()) BinaryStateIo.writeString(output, criterion);
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode vanilla progress", exception);
        }
    }

    @Override public void enterTemporaryState(Player player) {}
    @Override public void purgeTemporaryState(Player player) {}

    @Override
    public void validateRestore(byte[] payload) {
        Decoded decoded = decode(payload);
        validateStatistics(decoded.statistics());
        validateAdvancements(decoded.advancements());
    }

    @Override
    public void restore(Player player, byte[] payload) {
        Decoded decoded = decode(payload);
        validateStatistics(decoded.statistics());
        validateAdvancements(decoded.advancements());
        restoreStatistics(player, decoded.statistics());
        restoreAdvancements(player, decoded.advancements());
    }

    private Decoded decode(byte[] payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != VERSION) throw new IllegalArgumentException("Unsupported progress snapshot version");
            int statisticCount = boundedCount(input.readInt());
            Map<StatKey, Integer> statistics = new HashMap<>();
            for (int index = 0; index < statisticCount; index++) {
                StatKey key = new StatKey(BinaryStateIo.readString(input), BinaryStateIo.readString(input));
                int value = input.readInt();
                if (value < 0 || statistics.put(key, value) != null) {
                    throw new IllegalArgumentException("Invalid statistic snapshot entry");
                }
            }
            int advancementCount = boundedCount(input.readInt());
            Map<String, Set<String>> advancements = new HashMap<>();
            for (int index = 0; index < advancementCount; index++) {
                String key = BinaryStateIo.readString(input);
                int criterionCount = boundedCount(input.readInt());
                Set<String> criteria = new HashSet<>();
                for (int criterion = 0; criterion < criterionCount; criterion++) {
                    if (!criteria.add(BinaryStateIo.readString(input))) {
                        throw new IllegalArgumentException("Duplicate advancement criterion");
                    }
                }
                if (advancements.put(key, Set.copyOf(criteria)) != null) {
                    throw new IllegalArgumentException("Duplicate advancement entry");
                }
            }
            BinaryStateIo.requireExhausted(input);
            return new Decoded(Map.copyOf(statistics), Map.copyOf(advancements));
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Could not restore vanilla progress snapshot", exception);
        }
    }

    private record Decoded(Map<StatKey, Integer> statistics, Map<String, Set<String>> advancements) {}

    private static List<StatValue> captureStatistics(Player player) {
        List<StatValue> values = new ArrayList<>();
        forEachStatistic((statistic, qualifier, material, entity) -> {
            int value = read(player, statistic, material, entity);
            if (value != 0) values.add(new StatValue(statistic.name(), qualifier, value));
        });
        if (values.size() > MAX_ENTRIES) throw new IllegalStateException("Too many statistic entries");
        return values;
    }

    private Map<String, Set<String>> captureAdvancements(Player player) {
        Map<String, Set<String>> values = new java.util.TreeMap<>();
        Iterator<Advancement> iterator = server.advancementIterator();
        while (iterator.hasNext()) {
            Advancement advancement = iterator.next();
            Set<String> awarded = Set.copyOf(player.getAdvancementProgress(advancement).getAwardedCriteria());
            if (!awarded.isEmpty()) values.put(advancement.getKey().toString(), awarded);
        }
        return values;
    }

    private static void restoreStatistics(Player player, Map<StatKey, Integer> saved) {
        forEachStatistic((statistic, qualifier, material, entity) -> {
            int value = saved.getOrDefault(new StatKey(statistic.name(), qualifier), 0);
            write(player, statistic, material, entity, value);
        });
    }

    private static void validateStatistics(Map<StatKey, Integer> saved) {
        Set<StatKey> available = new HashSet<>();
        forEachStatistic((statistic, qualifier, material, entity) ->
                available.add(new StatKey(statistic.name(), qualifier)));
        if (!available.containsAll(saved.keySet())) {
            throw new IllegalStateException("A statistic disappeared during the isolated session");
        }
    }

    private void validateAdvancements(Map<String, Set<String>> saved) {
        Set<String> seen = new HashSet<>();
        Iterator<Advancement> iterator = server.advancementIterator();
        while (iterator.hasNext()) {
            Advancement advancement = iterator.next();
            String key = advancement.getKey().toString();
            seen.add(key);
            Set<String> desired = saved.getOrDefault(key, Set.of());
            if (!advancement.getCriteria().containsAll(desired)) {
                throw new IllegalStateException("Advancement criteria changed during isolated session: " + key);
            }
        }
        if (!seen.containsAll(saved.keySet())) {
            throw new IllegalStateException("An advancement disappeared during the isolated session");
        }
    }

    private void restoreAdvancements(Player player, Map<String, Set<String>> saved) {
        Set<String> seen = new HashSet<>();
        Iterator<Advancement> iterator = server.advancementIterator();
        while (iterator.hasNext()) {
            Advancement advancement = iterator.next();
            String key = advancement.getKey().toString();
            seen.add(key);
            Set<String> desired = saved.getOrDefault(key, Set.of());
            if (!advancement.getCriteria().containsAll(desired)) {
                throw new IllegalStateException("Advancement criteria changed during isolated session: " + key);
            }
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            for (String current : List.copyOf(progress.getAwardedCriteria())) {
                if (!desired.contains(current) && !progress.revokeCriteria(current)) {
                    throw new IllegalStateException("Could not revoke advancement criterion: " + key);
                }
            }
            for (String criterion : desired) {
                if (!progress.getAwardedCriteria().contains(criterion) && !progress.awardCriteria(criterion)) {
                    throw new IllegalStateException("Could not restore advancement criterion: " + key);
                }
            }
        }
        if (!seen.containsAll(saved.keySet())) {
            throw new IllegalStateException("An advancement disappeared during the isolated session");
        }
    }

    private interface StatisticConsumer {
        void accept(Statistic statistic, String qualifier, Material material, EntityType entity);
    }

    private static void forEachStatistic(StatisticConsumer consumer) {
        for (Statistic statistic : Statistic.values()) {
            switch (statistic.getType()) {
                case UNTYPED -> consumer.accept(statistic, "", null, null);
                case BLOCK -> {
                    for (Material material : Material.values()) {
                        if (material.isBlock() && !material.isLegacy()) {
                            consumer.accept(statistic, material.name(), material, null);
                        }
                    }
                }
                case ITEM -> {
                    for (Material material : Material.values()) {
                        if (material.isItem() && !material.isLegacy()) {
                            consumer.accept(statistic, material.name(), material, null);
                        }
                    }
                }
                case ENTITY -> {
                    for (EntityType entity : EntityType.values()) {
                        if (entity != EntityType.UNKNOWN && entity.getEntityClass() != null) {
                            consumer.accept(statistic, entity.name(), null, entity);
                        }
                    }
                }
            }
        }
    }

    private static int read(Player player, Statistic statistic, Material material, EntityType entity) {
        if (material != null) return player.getStatistic(statistic, material);
        if (entity != null) return player.getStatistic(statistic, entity);
        return player.getStatistic(statistic);
    }

    private static void write(Player player, Statistic statistic, Material material, EntityType entity, int value) {
        if (material != null) player.setStatistic(statistic, material, value);
        else if (entity != null) player.setStatistic(statistic, entity, value);
        else player.setStatistic(statistic, value);
    }

    private static int boundedCount(int value) {
        if (value < 0 || value > MAX_ENTRIES) throw new IllegalArgumentException("Progress entry count is invalid");
        return value;
    }

    private record StatKey(String statistic, String qualifier) {
        private StatKey {
            Objects.requireNonNull(statistic, "statistic");
            Objects.requireNonNull(qualifier, "qualifier");
        }
    }
    private record StatValue(String statistic, String qualifier, int value) {}
}
