package com.ciaac.minecraft.minigames.retention;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Paper-neutral command presentation. A Bukkit adapter maps these lines to Adventure components. */
public final class PassportCommandService {
    private final PassportService passport;
    public PassportCommandService(PassportService passport) { this.passport = Objects.requireNonNull(passport, "passport"); }

    public List<String> overview(UUID playerId, Instant now) {
        PassportService.PassportSnapshot state = passport.passport(playerId, now);
        return List.of("Passaporte CIAAC — " + state.season().id(),
                "Sequência de entradas: " + state.currentJoinStreak() + " (máxima: " + state.longestJoinStreak() + ")",
                "Dias ativos: " + state.activeDays() + " • objetivos semanais: " + state.weeklyObjectives(),
                "Pontos: " + state.points() + " • congelamento: " + (state.joinFreezeAvailable() ? "disponível" : "indisponível"));
    }

    public List<String> rewards(UUID playerId, Instant now) {
        PassportService.PassportSnapshot state = passport.passport(playerId, now);
        if (state.entitlements().isEmpty()) return List.of("Ainda não tens recompensas desbloqueadas nesta época.");
        return state.entitlements().values().stream().sorted(java.util.Comparator.comparing(Entitlement::rewardId))
                .map(value -> value.rewardId() + " — " + statePtPt(value.state())).toList();
    }
    private static String statePtPt(Entitlement.EntitlementState state) {
        return switch (state) { case EARNED -> "pronta a reclamar"; case GRANTING -> "a atribuir"; case GRANTED -> "atribuída";
            case DELIVERY_PENDING -> "entrega pendente"; case MANUAL_REVIEW -> "precisa de revisão"; };
    }
}
