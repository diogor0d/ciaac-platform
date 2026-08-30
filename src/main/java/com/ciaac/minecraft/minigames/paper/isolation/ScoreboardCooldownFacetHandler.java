package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Exact item cooldown snapshot. Admission is rejected unless the player's
 * survival scoreboard is the main scoreboard, which is restart-reconstructible.
 */
public final class ScoreboardCooldownFacetHandler implements FacetSnapshotHandler {
    private static final int VERSION = 1;
    private final Server server;

    public ScoreboardCooldownFacetHandler(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override public Set<PlayerStateFacet> facets() {
        return Set.of(PlayerStateFacet.SCOREBOARDS_AND_COOLDOWNS);
    }

    @Override
    public byte[] capture(Player player) {
        Scoreboard main = Objects.requireNonNull(server.getScoreboardManager(), "scoreboard manager")
                .getMainScoreboard();
        if (player.getScoreboard() != main) {
            throw new IllegalStateException(
                    "Player uses a non-main scoreboard; a validated scoreboard adapter is required");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                Map<Material, Integer> cooldowns = cooldowns(player);
                output.writeInt(cooldowns.size());
                for (Map.Entry<Material, Integer> entry : cooldowns.entrySet()) {
                    BinaryStateIo.writeString(output, entry.getKey().name());
                    output.writeInt(entry.getValue());
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode player cooldowns", exception);
        }
    }

    @Override public void enterTemporaryState(Player player) { clear(player); }
    @Override public void purgeTemporaryState(Player player) { clear(player); }

    @Override
    public void validateRestore(byte[] payload) {
        decode(payload);
        mainScoreboard();
    }

    @Override
    public void restore(Player player, byte[] payload) {
        Map<Material, Integer> saved = decode(payload);
        Scoreboard main = mainScoreboard();
        clear(player);
        player.setScoreboard(main);
        saved.forEach(player::setCooldown);
    }

    private Map<Material, Integer> decode(byte[] payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != VERSION) throw new IllegalArgumentException("Unsupported cooldown snapshot version");
            int count = input.readInt();
            if (count < 0 || count > Material.values().length) throw new IllegalArgumentException("Invalid cooldown count");
            Map<Material, Integer> saved = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                Material material = Material.valueOf(BinaryStateIo.readString(input));
                int ticks = input.readInt();
                if (!material.isItem() || material.isLegacy() || ticks <= 0
                        || saved.put(material, ticks) != null) {
                    throw new IllegalArgumentException("Invalid cooldown entry");
                }
            }
            BinaryStateIo.requireExhausted(input);
            return Map.copyOf(saved);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Could not restore cooldown snapshot", exception);
        }
    }

    private Scoreboard mainScoreboard() {
        return Objects.requireNonNull(
                Objects.requireNonNull(server.getScoreboardManager(), "scoreboard manager").getMainScoreboard(),
                "main scoreboard");
    }

    private static Map<Material, Integer> cooldowns(Player player) {
        Map<Material, Integer> values = new LinkedHashMap<>();
        for (Material material : Material.values()) {
            if (material.isItem() && !material.isLegacy()) {
                int ticks = player.getCooldown(material);
                if (ticks > 0) values.put(material, ticks);
            }
        }
        return values;
    }

    private static void clear(Player player) {
        for (Material material : Material.values()) {
            if (material.isItem() && !material.isLegacy() && player.hasCooldown(material)) {
                player.setCooldown(material, 0);
            }
        }
    }
}
