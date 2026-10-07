package local.harness;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.paper.RegionProtectionListener;
import com.ciaac.minecraft.minigames.region.*;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Piston;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Actual piston physics in a synthetic local world; no player or admission bypass. */
public final class CiaacPistonProbe extends JavaPlugin implements Listener {
    private World world;
    private int blockedExtensions, allowedExtensions, blockedRetractions;
    private boolean fixedKitVerified, essentialsEconomyVerified, claimsAndHomesVerified;
    private boolean luckPermsAuthorityAndJournalVerified;
    private boolean arenaProjectileOwnershipVerified;
    private boolean arenaWorldPortAndJournalVerified;
    private boolean arenaProjectileLifecycleVerified;
    private boolean arenaWorldRuntimeBootstrapVerified;
    private boolean inventorySnapshotLayoutVerified;
    @Override public void onEnable() {
        if (!Boolean.getBoolean("ciaac.local-test-probes") || getServer().getPort()!=25567
                || !getServer().getIp().equals("127.0.0.1")
                || getServer().getWorlds().isEmpty()
                || !getServer().getWorlds().getFirst().getName().equals("ciaac-synthetic-test")) {
            getLogger().severe("Probe recusado: exige autorização explícita, loopback e mundo descartável.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        String restartPhase = System.getProperty("ciaac.local-test-restart-phase", "");
        if (!restartPhase.isEmpty()) {
            getServer().getScheduler().runTaskLater(this, () -> ArenaWorldRestartProbe.run(this, restartPhase), 30L);
            return;
        }
        getServer().getScheduler().runTaskLater(this, this::prepare, 30L);
    }
    private void prepare() {
        try {
            verifyArenaRuntimeBootstrap();
            verifyFixedKit();
            InventorySnapshotProbe.verify();
            inventorySnapshotLayoutVerified=true;
            EssentialsEconomyProbe.verify(getServer(), getDataFolder().toPath().resolve("economy-fixture-"+UUID.randomUUID()));
            essentialsEconomyVerified=true;
            LuckPermsProbe.verify(getServer(), getDataFolder().toPath().resolve("luckperms-fixture-"+UUID.randomUUID()));
            luckPermsAuthorityAndJournalVerified=true;
            claimsAndHomesVerified=true;
            world=getServer().getWorlds().getFirst();
            for(int x=7;x<=11;x++) for(int z=7;z<=13;z++) for(int y=199;y<=201;y++)
                world.getBlockAt(x,y,z).setType(Material.AIR,false);
            ProtectedRegionRegistry regions=new ProtectedRegionRegistry();
            regions.register(new ProtectedRegion("local-piston-boundary",GameKey.ARENA,
                new CuboidRegion(world.getUID(),9,world.getMinHeight(),8,9,world.getMaxHeight()-1,8),
                ProtectedRegionRole.PARTICIPANT_ONLY,true));
            getServer().getPluginManager().registerEvents(new RegionProtectionListener(regions,
                new RegionAdmissionRegistry(),new SessionRegistry(),(p,s,v)->{},Clock.systemUTC()),this);
            getServer().getPluginManager().registerEvents(this,this);
            world.setChunkForceLoaded(0,0,true);
            world.addPluginChunkTicket(0,0,this);
            getServer().getScheduler().runTaskLater(this,()-> {
                try {
                    ArenaProjectileProbe.verify(this, getDataFolder().toPath().resolve("arena-projectile-fixture-"+UUID.randomUUID()));
                    arenaProjectileOwnershipVerified=true;
                    ArenaWorldPortProbe.verify(this, getDataFolder().toPath().resolve("arena-world-port-fixture-"+UUID.randomUUID()));
                    arenaWorldPortAndJournalVerified=true;
                    ArenaProjectileLifecycleProbe.verify(this, getDataFolder().toPath().resolve("arena-lifecycle-fixture-"+UUID.randomUUID()), failure -> {
                        if (failure != null) { finish(failure); return; }
                        arenaProjectileLifecycleVerified=true;
                        try {
                            piston(8,8,false); piston(8,12,false);
                            world.getBlockAt(8,200,7).setType(Material.REDSTONE_BLOCK,true);
                            world.getBlockAt(8,200,11).setType(Material.REDSTONE_BLOCK,true);
                            getServer().getScheduler().runTaskLater(this,this::checkExtension,20L);
                        } catch(Throwable error) {finish(error);}
                    });
                } catch(Throwable error) {finish(error);}
            },20L);
        } catch(Throwable error) { finish(error); }
    }
    private void verifyFixedKit() throws Exception {
        var platform = getServer().getPluginManager().getPlugin("CIAACPlatform");
        var type = Class.forName("com.ciaac.minecraft.minigames.paper.arena.ArenaFixedKitLayout",
                true, platform.getClass().getClassLoader());
        var arrange = type.getDeclaredMethod("arrange", List.class, int.class);
        arrange.setAccessible(true);
        ItemStack originalHelmet = new ItemStack(Material.IRON_HELMET);
        var kit = List.of(new ItemStack(Material.IRON_SWORD), new ItemStack(Material.SHIELD),
                originalHelmet, new ItemStack(Material.IRON_CHESTPLATE),
                new ItemStack(Material.IRON_LEGGINGS), new ItemStack(Material.IRON_BOOTS));
        Object layout = arrange.invoke(null, kit, 36);
        var storageMethod = layout.getClass().getDeclaredMethod("storage");
        var armorMethod = layout.getClass().getDeclaredMethod("armor");
        var offhandMethod = layout.getClass().getDeclaredMethod("offhand");
        storageMethod.setAccessible(true);armorMethod.setAccessible(true);offhandMethod.setAccessible(true);
        @SuppressWarnings("unchecked") List<ItemStack> storage = (List<ItemStack>)storageMethod.invoke(layout);
        @SuppressWarnings("unchecked") Map<EquipmentSlot,ItemStack> armor = (Map<EquipmentSlot,ItemStack>)armorMethod.invoke(layout);
        ItemStack offhand = (ItemStack)offhandMethod.invoke(layout);
        require(storage.size()==1 && storage.getFirst().getType()==Material.IRON_SWORD,"Sword not in storage");
        require(armor.size()==4 && armor.get(EquipmentSlot.HEAD).getType()==Material.IRON_HELMET
                && armor.get(EquipmentSlot.CHEST).getType()==Material.IRON_CHESTPLATE
                && armor.get(EquipmentSlot.LEGS).getType()==Material.IRON_LEGGINGS
                && armor.get(EquipmentSlot.FEET).getType()==Material.IRON_BOOTS,"Incorrect armor layout");
        require(offhand.getType()==Material.SHIELD,"Shield not in offhand");
        armor.get(EquipmentSlot.HEAD).setAmount(2);
        require(originalHelmet.getAmount()==1,"Configured kit was mutated");
        var tagger = new TemporaryItemTagger(platform);
        UUID session = UUID.randomUUID();
        for (ItemStack item : kit) {
            ItemStack tagged = tagger.tag(item,session,GameKey.ARENA);
            require(tagger.sessionId(tagged).orElseThrow().equals(session),"Missing session tag");
            require(!tagger.isTemporary(item),"Configured kit acquired temporary tag");
        }
        fixedKitVerified=true;
    }
    private void verifyArenaRuntimeBootstrap() throws Exception {
        var platform = getServer().getPluginManager().getPlugin("CIAACPlatform");
        require(platform != null && platform.isEnabled(), "Production CIAAC runtime did not enable");
        var listeners = HandlerList.getRegisteredListeners(platform);
        var lifecycle = listeners.stream().map(registration -> registration.getListener())
                .filter(com.ciaac.minecraft.minigames.paper.isolation.ArenaProjectileLifecycleListener.class::isInstance)
                .distinct().toList();
        require(lifecycle.size() == 1, "Production runtime did not register exactly one Arena lifecycle listener");
        require(listeners.stream().anyMatch(registration -> registration.getListener() instanceof RegionProtectionListener),
                "Production immutable-region listener was not registered");
        var field = lifecycle.getFirst().getClass().getDeclaredField("worldState");
        field.setAccessible(true);
        var port = (com.ciaac.minecraft.minigames.paper.isolation.ArenaWorldStatePort) field.get(lifecycle.getFirst());
        require(port.available() && port.supportedGames().equals(java.util.Set.of(GameKey.ARENA)),
                "Registered production world provider is unavailable or advertises other games");
        require(Files.isRegularFile(platform.getDataFolder().toPath().resolve("arena-world/arena-world.sqlite")),
                "Production runtime did not create its private Arena ledger");
        arenaWorldRuntimeBootstrapVerified=true;
    }
    private void checkExtension() {
        try {
            getLogger().info("Estado do ensaio: protected="+world.getBlockAt(8,200,8).getBlockData().getAsString()
                +" allowed="+world.getBlockAt(8,200,12).getBlockData().getAsString()
                +" ticking="+world.getChunkAt(0,0).isLoaded()
                +" power="+world.getBlockAt(8,200,12).getBlockPower());
            require(blockedExtensions>0 && allowedExtensions>0,"Expected real extend events missing");
            require(!((Piston)world.getBlockAt(8,200,8).getBlockData()).isExtended(),"Protected piston extended");
            require(world.getBlockAt(9,200,8).getType()==Material.AIR,"Protected head changed");
            require(((Piston)world.getBlockAt(8,200,12).getBlockData()).isExtended(),"Unrelated piston blocked");
            require(world.getBlockAt(9,200,12).getType()==Material.PISTON_HEAD,"Unrelated head absent");
            // Seed an already-extended piston as a disposable retraction fixture.
            piston(8,8,true);
            var head=Material.PISTON_HEAD.createBlockData();
            ((org.bukkit.block.data.Directional)head).setFacing(BlockFace.EAST);
            world.getBlockAt(9,200,8).setBlockData(head,false);
            world.getBlockAt(8,200,7).setType(Material.AIR,true);
            getServer().getScheduler().runTaskLater(this,this::checkRetraction,15L);
        } catch(Throwable error) { finish(error); }
    }
    private void checkRetraction() {
        try {
            require(blockedRetractions>0,"Expected real retract event missing");
            require(((Piston)world.getBlockAt(8,200,8).getBlockData()).isExtended(),"Protected piston retracted");
            require(world.getBlockAt(9,200,8).getType()==Material.PISTON_HEAD,"Protected head removed");
            verifyArenaRuntimeBootstrap();
            finish(null);
        } catch(Throwable error) { finish(error); }
    }
    private void piston(int x,int z,boolean extended) {
        Piston data=(Piston)Material.PISTON.createBlockData();
        data.setFacing(BlockFace.EAST);data.setExtended(extended);
        world.getBlockAt(x,200,z).setBlockData(data,false);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=false)
    public void extension(BlockPistonExtendEvent event) {
        getLogger().info("Evento de extensão: x="+event.getBlock().getX()+" y="+event.getBlock().getY()+" z="+event.getBlock().getZ()
            +" cancelled="+event.isCancelled()+" moved="+event.getBlocks().size());
        if(!same(event.getBlock(),8))return;
        if(event.getBlock().getZ()==8 && event.isCancelled() && event.getBlocks().isEmpty()) blockedExtensions++;
        if(event.getBlock().getZ()==12 && !event.isCancelled() && event.getBlocks().isEmpty()) allowedExtensions++;
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=false)
    public void retraction(BlockPistonRetractEvent event) {
        if(same(event.getBlock(),8) && event.getBlock().getZ()==8 && event.isCancelled() && event.getBlocks().isEmpty()) blockedRetractions++;
    }
    private boolean same(Block block,int x) {return block.getWorld()==world && block.getX()==x && block.getY()==200;}
    private void require(boolean ok,String message) {if(!ok)throw new IllegalStateException(message);}
    private void finish(Throwable failure) {
        try {
            String result=failure==null?"PASS":"FAIL";
            String data="{\"result\":\""+result+"\",\"blockedEmptyExtensions\":"+blockedExtensions
                +",\"allowedEmptyExtensions\":"+allowedExtensions+",\"blockedEmptyRetractions\":"+blockedRetractions
                +",\"fixedKitLayoutAndTags\":"+fixedKitVerified
                +",\"inventorySnapshotLayout\":"+inventorySnapshotLayoutVerified
                +",\"essentialsEconomyAuthorityAndJournal\":"+essentialsEconomyVerified
                +",\"essentialsLoadedHomeModel\":"+essentialsEconomyVerified
                +",\"luckPermsAuthorityAndJournal\":"+luckPermsAuthorityAndJournalVerified
                +",\"claimsAndHomesAuthorityAndJournal\":"+claimsAndHomesVerified
                +",\"arenaProjectileOwnership\":"+arenaProjectileOwnershipVerified
                +",\"arenaWorldPortAndJournal\":"+arenaWorldPortAndJournalVerified
                +",\"arenaProjectileLifecycle\":"+arenaProjectileLifecycleVerified
                +",\"arenaWorldRuntimeBootstrap\":"+arenaWorldRuntimeBootstrapVerified+"}\n";
            Files.createDirectories(getDataFolder().toPath());
            Path out=getDataFolder().toPath().resolve("evidence.json");
            Files.writeString(out,data,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
            Files.setPosixFilePermissions(out,PosixFilePermissions.fromString("rw-------"));
            getLogger().info("Ensaio físico de pistões: "+result);
            if(failure!=null)getLogger().log(java.util.logging.Level.SEVERE,"Falha do ensaio local",failure);
        } catch(Exception writeFailure){getLogger().severe(writeFailure.getClass().getSimpleName());}
        finally {if(world!=null){world.removePluginChunkTicket(0,0,this);world.setChunkForceLoaded(0,0,false);}Bukkit.shutdown();}
    }
}
