package com.ciaac.minecraft.minigames.minecart;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Fronteira limitada usada pelos comandos de teste de carrinhos. */
public interface MinecartSpeedControl {
    String statusPtPt();

    ReloadResult reload();

    Optional<WorldIdentity> loadedWorldExact(String name);

    List<String> loadedWorldNames();

    ChangeResult setWorldOverride(WorldIdentity world, double blocksPerSecond);

    ChangeResult setDefaultOverride(double blocksPerSecond);

    ChangeResult clearWorldOverride(WorldIdentity world);

    ChangeResult clearAllOverrides();

    record WorldIdentity(String name, UUID worldId) {
        public WorldIdentity {
            name = Objects.requireNonNull(name, "name").strip();
            if (name.isEmpty() || name.length() > 64 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("O nome do mundo é inválido.");
            }
            Objects.requireNonNull(worldId, "worldId");
        }
    }

    record WorldChange(WorldIdentity world, Optional<Double> previousSpeed, Optional<Double> newSpeed) {
        public WorldChange {
            Objects.requireNonNull(world, "world");
            previousSpeed = Objects.requireNonNull(previousSpeed, "previousSpeed");
            newSpeed = Objects.requireNonNull(newSpeed, "newSpeed");
        }
    }

    record ChangeResult(
            boolean accepted,
            boolean changed,
            UUID operationId,
            String messagePtPt,
            List<WorldChange> worldChanges) {
        public ChangeResult {
            Objects.requireNonNull(operationId, "operationId");
            messagePtPt = Objects.requireNonNull(messagePtPt, "messagePtPt");
            worldChanges = List.copyOf(Objects.requireNonNull(worldChanges, "worldChanges"));
            if (!accepted && changed) throw new IllegalArgumentException("Uma alteração rejeitada não pode ser aplicada.");
        }

        public static ChangeResult rejected(UUID operationId, String message) {
            return new ChangeResult(false, false, operationId, message, List.of());
        }
    }

    record ReloadResult(
            boolean accepted,
            String messagePtPt,
            Optional<ChangeResult> clearedOverrides) {
        public ReloadResult {
            Objects.requireNonNull(messagePtPt, "messagePtPt");
            clearedOverrides = Objects.requireNonNull(clearedOverrides, "clearedOverrides");
            if (!accepted && clearedOverrides.isPresent()) {
                throw new IllegalArgumentException("Um recarregamento rejeitado não pode apagar substituições.");
            }
        }

        public ReloadResult(boolean accepted, String messagePtPt) {
            this(accepted, messagePtPt, Optional.empty());
        }

        public static ReloadResult success(String message, Optional<ChangeResult> clearedOverrides) {
            return new ReloadResult(true, message, clearedOverrides);
        }

        public static ReloadResult success(String message) {
            return success(message, Optional.empty());
        }

        public static ReloadResult failure(String message) {
            return new ReloadResult(false, message, Optional.empty());
        }
    }
}
