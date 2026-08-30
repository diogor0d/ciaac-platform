package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class VitalsFacetHandler implements FacetSnapshotHandler {
    private static final int LEGACY_VERSION = 1;
    private static final int VERSION = 2;
    private static final int MAX_EFFECTS = 128;
    private static final int MAX_EFFECT_CHAIN_DEPTH = 16;
    private static final Set<PlayerStateFacet> FACETS = Set.of(
            PlayerStateFacet.EXPERIENCE,
            PlayerStateFacet.HEALTH,
            PlayerStateFacet.FOOD_AND_SATURATION,
            PlayerStateFacet.POTION_EFFECTS,
            PlayerStateFacet.FIRE_FREEZE_AND_AIR);
    private final ToDoubleFunction<Player> maximumHealthReader;

    public VitalsFacetHandler() {
        this(player -> {
            var attribute = player.getAttribute(Attribute.MAX_HEALTH);
            if (attribute == null) {
                throw new IllegalStateException("Player maximum-health attribute is unavailable");
            }
            return attribute.getValue();
        });
    }

    VitalsFacetHandler(ToDoubleFunction<Player> maximumHealthReader) {
        this.maximumHealthReader = Objects.requireNonNull(maximumHealthReader, "maximumHealthReader");
    }

    @Override
    public Set<PlayerStateFacet> facets() {
        return FACETS;
    }

    @Override
    public byte[] capture(Player player) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                output.writeFloat(player.getExp());
                output.writeInt(player.getLevel());
                output.writeInt(player.getTotalExperience());
                output.writeDouble(player.getHealth());
                output.writeDouble(player.getAbsorptionAmount());
                output.writeInt(player.getFoodLevel());
                output.writeFloat(player.getSaturation());
                output.writeFloat(player.getExhaustion());
                output.writeInt(player.getFireTicks());
                output.writeInt(player.getFreezeTicks());
                output.writeInt(player.getRemainingAir());
                List<PotionEffect> effects = new ArrayList<>(player.getActivePotionEffects());
                effects.sort(Comparator.comparing(effect -> effect.getType().getKey().toString()));
                if (effects.size() > MAX_EFFECTS) {
                    throw new IllegalStateException("Player has too many potion effects to snapshot safely");
                }
                output.writeInt(effects.size());
                for (PotionEffect effect : effects) {
                    writePotionEffect(output, effect, 1);
                }
            }
            byte[] payload = bytes.toByteArray();
            decode(payload);
            return payload;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode player vitals", exception);
        }
    }

    @Override
    public void validateRestore(byte[] payload) {
        decode(payload);
    }

    @Override
    public void enterTemporaryState(Player player) {
        reset(player);
    }

    @Override
    public void purgeTemporaryState(Player player) {
        reset(player);
    }

    @Override
    public void restore(Player player, byte[] payload) {
        Decoded decoded = decode(payload);
        maximumHealth(player);
        reset(player);
        player.setExp(decoded.experience());
        player.setLevel(decoded.level());
        player.setTotalExperience(decoded.totalExperience());
        player.setFoodLevel(decoded.food());
        player.setSaturation(decoded.saturation());
        player.setExhaustion(decoded.exhaustion());
        player.setFireTicks(decoded.fireTicks());
        player.setFreezeTicks(decoded.freezeTicks());
        player.setRemainingAir(decoded.air());
        if (!decoded.effects().isEmpty() && !player.addPotionEffects(decoded.effects())) {
            throw new IllegalStateException("Could not restore every saved potion effect");
        }
        player.setHealth(Math.min(decoded.health(), maximumHealth(player)));
        player.setAbsorptionAmount(decoded.absorption());
    }

    private static Decoded decode(byte[] payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = input.readInt();
            if (version != LEGACY_VERSION && version != VERSION) {
                throw new IllegalArgumentException("Unsupported vitals snapshot version");
            }
            float experience = input.readFloat();
            int level = input.readInt();
            int totalExperience = input.readInt();
            double health = input.readDouble();
            double absorption = input.readDouble();
            int food = input.readInt();
            float saturation = input.readFloat();
            float exhaustion = input.readFloat();
            int fireTicks = input.readInt();
            int freezeTicks = input.readInt();
            int air = input.readInt();
            int effectCount = input.readInt();
            if (effectCount < 0 || effectCount > MAX_EFFECTS) {
                throw new IllegalArgumentException("Potion-effect count is invalid");
            }
            List<PotionEffect> effects = new ArrayList<>();
            for (int index = 0; index < effectCount; index++) {
                effects.add(version == LEGACY_VERSION
                        ? readLegacyPotionEffect(input)
                        : readPotionEffect(input, 1));
            }
            BinaryStateIo.requireExhausted(input);
            if (!Float.isFinite(experience) || experience < 0 || experience > 1
                    || level < 0 || totalExperience < 0
                    || !Double.isFinite(health) || health <= 0
                    || !Double.isFinite(absorption) || absorption < 0
                    || food < 0 || food > 20
                    || !Float.isFinite(saturation) || saturation < 0 || saturation > food
                    || !Float.isFinite(exhaustion) || exhaustion < 0 || exhaustion > 4
                    || fireTicks < 0 || freezeTicks < 0 || air < 0) {
                throw new IllegalArgumentException("Vitals snapshot values are invalid");
            }
            return new Decoded(experience, level, totalExperience, health, absorption, food,
                    saturation, exhaustion, fireTicks, freezeTicks, air, List.copyOf(effects));
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Could not restore player vitals snapshot", exception);
        }
    }

    private static void writePotionEffect(DataOutputStream output, PotionEffect effect, int depth)
            throws IOException {
        if (depth > MAX_EFFECT_CHAIN_DEPTH) {
            throw new IllegalStateException("Potion-effect hidden chain is too deep to snapshot safely");
        }
        BinaryStateIo.writeString(output, effect.getType().getKey().toString());
        output.writeInt(effect.getDuration());
        output.writeInt(effect.getAmplifier());
        output.writeBoolean(effect.isAmbient());
        output.writeBoolean(effect.hasParticles());
        output.writeBoolean(effect.hasIcon());
        PotionEffect hidden = effect.getHiddenPotionEffect();
        output.writeBoolean(hidden != null);
        if (hidden != null) {
            writePotionEffect(output, hidden, depth + 1);
        }
    }

    private static PotionEffect readPotionEffect(DataInputStream input, int depth) throws IOException {
        if (depth > MAX_EFFECT_CHAIN_DEPTH) {
            throw new IllegalArgumentException("Potion-effect hidden chain is too deep");
        }
        NamespacedKey key = NamespacedKey.fromString(BinaryStateIo.readString(input));
        PotionEffectType type = key == null ? null : Registry.MOB_EFFECT.get(key);
        int duration = input.readInt();
        int amplifier = input.readInt();
        boolean ambient = input.readBoolean();
        boolean particles = input.readBoolean();
        boolean icon = input.readBoolean();
        boolean hasHiddenEffect = input.readBoolean();
        if (type == null || !validPotionDuration(duration) || amplifier < 0) {
            throw new IllegalArgumentException("Potion-effect snapshot entry is invalid");
        }
        PotionEffect hidden = hasHiddenEffect ? readPotionEffect(input, depth + 1) : null;
        return new PotionEffect(type, duration, amplifier, ambient, particles, icon, hidden);
    }

    private static PotionEffect readLegacyPotionEffect(DataInputStream input) throws IOException {
        NamespacedKey key = NamespacedKey.fromString(BinaryStateIo.readString(input));
        PotionEffectType type = key == null ? null : Registry.MOB_EFFECT.get(key);
        int duration = input.readInt();
        int amplifier = input.readInt();
        boolean ambient = input.readBoolean();
        boolean particles = input.readBoolean();
        boolean icon = input.readBoolean();
        if (type == null || !validPotionDuration(duration) || amplifier < 0) {
            throw new IllegalArgumentException("Legacy potion-effect snapshot entry is invalid");
        }
        return new PotionEffect(type, duration, amplifier, ambient, particles, icon);
    }

    static boolean validPotionDuration(int duration) {
        return duration >= 0 || duration == PotionEffect.INFINITE_DURATION;
    }

    private record Decoded(
            float experience,
            int level,
            int totalExperience,
            double health,
            double absorption,
            int food,
            float saturation,
            float exhaustion,
            int fireTicks,
            int freezeTicks,
            int air,
            List<PotionEffect> effects) {}

    private double maximumHealth(Player player) {
        double value = maximumHealthReader.applyAsDouble(player);
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalStateException("Player maximum-health attribute is invalid");
        }
        return value;
    }

    private void reset(Player player) {
        maximumHealth(player);
        player.clearActivePotionEffects();
        player.setExp(0);
        player.setLevel(0);
        player.setTotalExperience(0);
        player.setHealth(maximumHealth(player));
        player.setAbsorptionAmount(0);
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setExhaustion(0);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setRemainingAir(player.getMaximumAir());
    }
}
