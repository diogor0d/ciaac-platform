package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.plugin.Plugin;

/** Explicitly implemented authorities only; missing or incompatible plugins never get substitute ports. */
public final class BuiltinExternalStateAdapters {
    private BuiltinExternalStateAdapters() {}

    public static List<ExternalStateFacetPort> create(Plugin owner, ExternalOperationJournal journal, AuditRepository audit) {
        List<ExternalStateFacetPort> ports = new ArrayList<>();
        var server = owner.getServer();
        var essentials = server.getPluginManager().getPlugin("Essentials");
        var vault = server.getPluginManager().getPlugin("Vault");
        if (essentials != null && essentials.isEnabled() && vault != null && vault.isEnabled()) {
            try {
                ports.add(new ReadGuardedExternalStatePort("essentials-2.22.0-economy-guard",
                        Set.of(PlayerStateFacet.ECONOMY), new EssentialsEconomyAuthority(server, essentials, vault), journal, audit));
            } catch (RuntimeException | LinkageError incompatible) {
                owner.getLogger().warning("O adaptador de economia ficou fechado: a API exata Essentials/Vault não foi confirmada.");
            }
        }
        var luckPerms = server.getPluginManager().getPlugin("LuckPerms");
        if (luckPerms != null && luckPerms.isEnabled()) {
            try {
                ports.add(new ReadGuardedExternalStatePort("luckperms-5.5.65-permissions-guard",
                        Set.of(PlayerStateFacet.PERMISSIONS), LuckPermsAuthorityBinding.create(server, luckPerms), journal, audit));
            } catch (RuntimeException | LinkageError incompatible) {
                owner.getLogger().warning("O adaptador de permissões ficou fechado: a API exata LuckPerms não foi confirmada.");
            }
        }
        var claims = server.getPluginManager().getPlugin("SimpleClaimSystem");
        if (essentials != null && essentials.isEnabled() && claims != null && claims.isEnabled()) {
            try {
                ports.add(new ReadGuardedExternalStatePort("scs-1.13.1-essentials-2.22.0-claims-homes-guard",
                        Set.of(PlayerStateFacet.CLAIMS_AND_HOMES), new ClaimsAndHomesAuthority(
                                SimpleClaimStateBinding.create(server, claims),
                                new EssentialsHomesAuthority(server, essentials)), journal, audit));
            } catch (RuntimeException | LinkageError incompatible) {
                owner.getLogger().warning("O adaptador de claims/homes ficou fechado: os modelos exatos SimpleClaimSystem/Essentials não foram confirmados.");
            }
        }
        return List.copyOf(ports);
    }
}
