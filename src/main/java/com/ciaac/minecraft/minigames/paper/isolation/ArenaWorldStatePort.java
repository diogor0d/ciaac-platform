package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import io.papermc.paper.ServerBuildInfo;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/** Immutable facilities; owned arrows are supported for Arena and Archery. */
public final class ArenaWorldStatePort implements ExternalStateFacetPort {
    private static final String ID = "paper-26.2-84-arena-world-v1";
    private static final byte[] EMPTY = new byte[0];
    private final Server server;
    private final ProtectedRegionRegistry regions;
    private final ArenaWorldLedger ledger;
    private final ArenaProjectileOwnership projectiles;
    private final ExternalOperationJournal journal;
    private final AuditRepository audit;
    private final ImmutableMinigameWorldState immutableGames;
    private ColorFloorWorldState colorFloor;
    private AnvilWorldState anvil;
    private ElytraWorldState elytra;
    private BuildBattleWorldState buildBattle;
    private boolean lifecycleReady;
    private boolean lifecycleFailed;

    public ArenaWorldStatePort(Server server, ProtectedRegionRegistry regions, ArenaWorldLedger ledger,
            ArenaProjectileOwnership projectiles, ExternalOperationJournal journal, AuditRepository audit) {
        this.server = Objects.requireNonNull(server, "server");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.projectiles = Objects.requireNonNull(projectiles, "projectiles");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.immutableGames = new ImmutableMinigameWorldState(ID, server, regions, journal, audit);
    }

    /** Installed before handlers advertise their game coverage. */
    public void colorFloor(ColorFloorWorldState provider) {
        requireThread();
        if (lifecycleReady || colorFloor != null) throw new IllegalStateException("Color Floor isolation is already frozen");
        colorFloor = Objects.requireNonNull(provider, "provider");
    }

    public void anvil(AnvilWorldState provider) {
        requireThread();
        if (lifecycleReady || anvil != null) throw new IllegalStateException("Anvil isolation is already frozen");
        anvil = Objects.requireNonNull(provider, "provider");
    }

    public void elytra(ElytraWorldState provider) {
        requireThread();
        if (lifecycleReady || elytra != null) throw new IllegalStateException("Elytra isolation is already frozen");
        elytra = Objects.requireNonNull(provider, "provider");
    }

    public void buildBattle(BuildBattleWorldState provider) {
        requireThread();
        if (lifecycleReady || buildBattle != null) throw new IllegalStateException("Build Battle isolation is already frozen");
        buildBattle = Objects.requireNonNull(provider, "provider");
    }

    /** Runtime must call only after installing and verifying the required protection/lifecycle listeners. */
    public void lifecycleReady() {
        requireThread();
        if (lifecycleFailed) throw new IllegalStateException("Arena world lifecycle requires operator review");
        if (!nativeBuildMatches()) throw new IllegalStateException("Arena world isolation requires the exact reviewed Paper build");
        lifecycleReady = true;
    }

    /** An ambiguous entity event closes this provider for the rest of this runtime. */
    public void lifecycleFailed() {
        requireThread();
        lifecycleFailed = true;
        lifecycleReady = false;
    }

    @Override public int contractVersion() { return 2; }
    @Override public String id() { return ID; }
    @Override public int snapshotVersion() { return 1; }
    @Override public Set<PlayerStateFacet> facets() { return Set.of(PlayerStateFacet.TEMPORARY_WORLD_BLOCKS_AND_ENTITIES); }
    @Override public Set<GameKey> supportedGames() {
        var supported = java.util.EnumSet.of(GameKey.ARENA, GameKey.KNOCKBACK_SUMO, GameKey.HOT_POTATO,
                GameKey.CHECKPOINT_PARKOUR, GameKey.ARCHERY_RANGE);
        if (colorFloor != null) supported.add(GameKey.COLOR_FLOOR);
        if (anvil != null) supported.add(GameKey.ANVIL_DODGE);
        if (elytra != null) supported.add(GameKey.ELYTRA_RINGS);
        if (buildBattle != null) supported.add(GameKey.BUILD_BATTLE);
        return Set.copyOf(supported);
    }
    @Override public boolean available() { return lifecycleReady && !lifecycleFailed && nativeBuildMatches(); }

    @Override public byte[] capture(Player player, PlayerStateOperation context) {
        requirePlayer(player, context, PlayerStateOperation.Kind.CAPTURE);
        if (context.game() == GameKey.COLOR_FLOOR) return colorFloor.capture(context);
        if (context.game() == GameKey.ANVIL_DODGE) return anvil.capture(context);
        if (context.game() == GameKey.ELYTRA_RINGS) return elytra.capture(context);
        if (context.game() == GameKey.BUILD_BATTLE) return buildBattle.capture(context);
        if (immutableGame(context.game())) return immutableGames.capture(context);
        byte[] manifest = currentManifest(context.game());
        // Neither a replay nor a failed capture can replace the frozen world/region policy.
        ledger.capture(context, manifest);
        journal.begin(ID, snapshotVersion(), context, EMPTY);
        journal.commit(ID, snapshotVersion(), context, EMPTY, manifest);
        audit(context, EMPTY);
        return manifest.clone();
    }

    @Override public void validateRestore(PlayerStateOperation context, int version, byte[] payload) {
        requireContext(context);
        if (context.game() == GameKey.COLOR_FLOOR) { colorFloor.validate(context, version, payload); return; }
        if (context.game() == GameKey.ANVIL_DODGE) { anvil.validate(context, version, payload); return; }
        if (context.game() == GameKey.ELYTRA_RINGS) { elytra.validate(context, version, payload); return; }
        if (context.game() == GameKey.BUILD_BATTLE) { buildBattle.validate(context, version, payload); return; }
        if (immutableGame(context.game())) { immutableGames.validate(context, version, payload); return; }
        if (version != snapshotVersion()) throw new IllegalArgumentException("Unsupported Arena world snapshot version");
        if (context.game() == GameKey.ARENA) ArenaWorldManifest.decode(payload);
        else ArcheryWorldManifest.decode(payload);
        var lease = ledger.requireLease(context);
        if (!Arrays.equals(payload, lease.manifest()) || !Arrays.equals(payload, currentManifest(context.game()))
                || !Arrays.equals(payload, captured(context)))
            throw new IllegalStateException("Arena world snapshot differs from durable or current protection policy");
        projectiles.validatePurge(context);
        if (context.kind() == PlayerStateOperation.Kind.RESTORE
                && lease.status() != ArenaWorldLedger.Status.PURGED && lease.status() != ArenaWorldLedger.Status.RESTORED)
            throw new IllegalStateException("Arena projectile purge must finish before player restoration");
    }

    @Override public void enterTemporaryState(Player player, PlayerStateOperation context) {
        requirePlayer(player, context, PlayerStateOperation.Kind.ENTER);
        if (context.game() == GameKey.COLOR_FLOOR) { colorFloor.enter(context); return; }
        if (context.game() == GameKey.ANVIL_DODGE) { anvil.enter(context); return; }
        if (context.game() == GameKey.ELYTRA_RINGS) { elytra.enter(context); return; }
        if (context.game() == GameKey.BUILD_BATTLE) { buildBattle.enter(context); return; }
        if (immutableGame(context.game())) { immutableGames.checkpoint(context); return; }
        byte[] payload = captured(context);
        validateRestore(context, snapshotVersion(), payload);
        journal.begin(ID, snapshotVersion(), context, payload);
        ledger.arm(context);
        journal.commit(ID, snapshotVersion(), context, payload, EMPTY);
        audit(context, payload);
    }

    @Override public void purgeTemporaryState(Player player, PlayerStateOperation context) {
        requirePlayer(player, context, PlayerStateOperation.Kind.PURGE);
        if (context.game() == GameKey.COLOR_FLOOR) { colorFloor.purge(context); return; }
        if (context.game() == GameKey.ANVIL_DODGE) { anvil.purge(context); return; }
        if (context.game() == GameKey.ELYTRA_RINGS) { elytra.purge(context); return; }
        if (context.game() == GameKey.BUILD_BATTLE) { buildBattle.purge(context); return; }
        if (immutableGame(context.game())) { immutableGames.checkpoint(context); return; }
        byte[] payload = captured(context);
        validateRestore(context, snapshotVersion(), payload);
        journal.begin(ID, snapshotVersion(), context, payload);
        // A pending retry validates each durable entity again instead of repeating an unproven deletion.
        projectiles.purge(context);
        journal.commit(ID, snapshotVersion(), context, payload, EMPTY);
        audit(context, payload);
    }

    @Override public void restore(Player player, PlayerStateOperation context, int version, byte[] payload) {
        requirePlayer(player, context, PlayerStateOperation.Kind.RESTORE);
        validateRestore(context, version, payload);
        if (context.game() == GameKey.COLOR_FLOOR) { colorFloor.restore(context, version, payload); return; }
        if (context.game() == GameKey.ANVIL_DODGE) { anvil.restore(context, version, payload); return; }
        if (context.game() == GameKey.ELYTRA_RINGS) { elytra.restore(context, version, payload); return; }
        if (context.game() == GameKey.BUILD_BATTLE) { buildBattle.restore(context, version, payload); return; }
        if (immutableGame(context.game())) { immutableGames.checkpoint(context); return; }
        journal.begin(ID, snapshotVersion(), context, payload);
        ledger.markRestored(context);
        journal.commit(ID, snapshotVersion(), context, payload, EMPTY);
        audit(context, payload);
    }

    private byte[] currentManifest(GameKey game) {
        var owned = regions.all().stream().filter(region -> region.game() == game).toList();
        byte[] encoded = game == GameKey.ARENA
                ? new ArenaWorldManifest(server.getVersion(), owned).encode()
                : new ArcheryWorldManifest(server.getVersion(), owned).encode();
        var world = server.getWorld(owned.getFirst().bounds().worldId());
        if (world == null) throw new IllegalStateException("Arena world is not loaded");
        for (var region : owned) {
            if (region.bounds().minY() < world.getMinHeight() || region.bounds().maxY() >= world.getMaxHeight())
                throw new IllegalStateException("Arena region exceeds the current world's height");
        }
        return encoded;
    }

    private static boolean immutableGame(GameKey game) {
        return game == GameKey.KNOCKBACK_SUMO || game == GameKey.HOT_POTATO
                || game == GameKey.CHECKPOINT_PARKOUR;
    }

    private byte[] captured(PlayerStateOperation context) {
        var capture = ledger.requireLease(context).capture();
        return journal.committedResult(ID, snapshotVersion(), capture, EMPTY);
    }

    private void audit(PlayerStateOperation context, byte[] request) {
        audit.append(new AuditEvent(OperationIds.derive(context.operationId(), "ARENA_WORLD"),
                context.operationId(), journal.committedAt(ID, snapshotVersion(), context, request),
                "ARENA_WORLD_" + context.kind(), "SESSION", context.sessionId().toString(), "VERIFIED", ID));
    }

    private void requirePlayer(Player player, PlayerStateOperation context, PlayerStateOperation.Kind kind) {
        requireContext(context);
        if (context.kind() != kind || !Objects.requireNonNull(player, "player").getUniqueId().equals(context.playerId()))
            throw new IllegalStateException("Arena world operation has the wrong player or phase");
        // The composite gateway supplies current nLogin authentication and exact entity/connection checks.
    }

    private void requireContext(PlayerStateOperation context) {
        requireThread();
        if (!available()) throw new IllegalStateException("Arena world lifecycle is unavailable");
        if (context == null || !supportedGames().contains(context.game()) || context.capturedConnectionId() == null)
            throw new IllegalStateException("Arena world operation requires an Arena capture identity");
    }

    /** Capability check only; availability additionally requires installed, healthy lifecycle listeners. */
    public static boolean nativeBuildMatches() {
        try {
            var build = ServerBuildInfo.buildInfo();
            return build.brandId().equals(ServerBuildInfo.BRAND_PAPER_ID)
                    && build.minecraftVersionId().equals("26.2") && build.buildNumber().orElse(-1) == 84
                    && build.gitCommit().orElse("").equals("26e81c4");
        } catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    private void requireThread() {
        if (!server.isPrimaryThread()) throw new IllegalStateException("Arena world operations require the primary server thread");
    }
}
