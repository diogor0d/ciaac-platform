import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCreativeModeSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
public class LocalArenaPeerActionsCheck {
 static Class<?> peer=LocalArenaPeer.class;
 static int checks;
 static void check(boolean ok){if(!ok)throw new AssertionError();checks++;}
 static void field(String name,Object value)throws Exception{Field f=peer.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
 static Object get(String name)throws Exception{Field f=peer.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 static Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=peer.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}
 static void rejected(String name,Class<?>[]types,Object...args)throws Exception{try{call(name,types,args);throw new AssertionError();}catch(InvocationTargetException e){check(e.getCause() instanceof IllegalArgumentException||e.getCause() instanceof IllegalStateException);}}
 public static void main(String[]args)throws Exception{
  for(String command:new String[]{"buildbattle", "parkour", "arco", "bigornas", "cores", "elytra"})
   check(call("isAllowedFixtureCommand",new Class[]{String.class},command).equals(true));
  for(String command:new String[]{"register", "login", "coliseu", "minijogos", "passaporte", "sumo", "batataquente"})
   check(call("isAllowedFixtureCommand",new Class[]{String.class},command).equals(true));
  for(String command:new String[]{"op", "stop", "plugins", "minecraft:op", "buildbattle;op", "buildbattle\nstop", "", "say hi"})
   check(call("isAllowedFixtureCommand",new Class[]{String.class},command).equals(false));
  check(call("isBowFixtureCommand",new Class[]{String.class},"bow draw").equals(true));
  check(call("isBowFixtureCommand",new Class[]{String.class},"bow release").equals(true));
  for(String command:new String[]{"bow", "bow draw now", "bow release 1", "bow shoot", "bow\tdraw"})
   check(call("isBowFixtureCommand",new Class[]{String.class},command).equals(false));
  for(int n=1;n<=5;n++){String user="CiaacArenaPeer"+(n==1?"":n);UUID id=UUID.nameUUIDFromBytes(("OfflinePlayer:"+user).getBytes(StandardCharsets.UTF_8));check(call("fixturePeerIndex",new Class[]{UUID.class},id).equals(n));}
  check(call("fixturePeerIndex",new Class[]{UUID.class},UUID.randomUUID()).equals(0));
  List<Object> packets=new ArrayList<>();ClientSession client=(ClientSession)Proxy.newProxyInstance(ClientSession.class.getClassLoader(),new Class[]{ClientSession.class},(self,m,a)->{if(m.getName().equals("isConnected"))return true;if(m.getName().equals("send")){packets.add(a[0]);return null;}return null;});
  field("joined",true);field("positionKnown",true);field("ownPeerIndex",1);
  for(String title:new String[]{"Minijogos CIAAC","CIAAC • Coliseu","CIAAC • Build Battle","CIAAC • Administração"})
   check(call("isAllowedNativeMenuTitle",new Class[]{String.class},title).equals(true));
  for(String title:new String[]{"Inventory","Other Server Shop","minijogos ciaac","Coliseu","CIAACTools","CIAAC: Shop"})
   check(call("isAllowedNativeMenuTitle",new Class[]{String.class},title).equals(false));
  Class<?> nativeMenuType=Class.forName("LocalArenaPeer$NativeMenu");
  Constructor<?> nativeMenuConstructor=nativeMenuType.getDeclaredConstructors()[0];nativeMenuConstructor.setAccessible(true);
  ItemStack[] nativeItems=new ItemStack[27];nativeItems[4]=new ItemStack(1,1);
  field("nativeMenu",nativeMenuConstructor.newInstance(7,19,"GENERIC_9X3","Minijogos CIAAC",Arrays.asList(nativeItems)));
  call("observeNativeMenuContent",new Class[]{int.class,int.class,ItemStack[].class},8,99,nativeItems);
  check(((Number)nativeMenuType.getDeclaredMethod("stateId").invoke(get("nativeMenu"))).intValue()==19);
  call("observeNativeMenuContent",new Class[]{int.class,int.class,ItemStack[].class},7,20,nativeItems);
  check(((Number)nativeMenuType.getDeclaredMethod("stateId").invoke(get("nativeMenu"))).intValue()==20);
  call("observeNativeMenuSlot",new Class[]{int.class,int.class,int.class,ItemStack.class},7,21,4,new ItemStack(2,3));
  check(((Number)nativeMenuType.getDeclaredMethod("stateId").invoke(get("nativeMenu"))).intValue()==21);
  check(((ItemStack)((List)nativeMenuType.getDeclaredMethod("items").invoke(get("nativeMenu"))).get(4)).getId()==2);
  packets.clear();call("sendNativeMenuClick",new Class[]{ClientSession.class,String.class},client,"menu-click 4 left");
  check(packets.size()==1&&packets.getFirst() instanceof ServerboundContainerClickPacket);
  ServerboundContainerClickPacket nativeClick=(ServerboundContainerClickPacket)packets.getFirst();
  check(nativeClick.getContainerId()==7&&nativeClick.getStateId()==21&&nativeClick.getSlot()==4
   &&nativeClick.getAction()==ContainerActionType.CLICK_ITEM&&nativeClick.getParam().getId()==0
   &&nativeClick.getChangedSlots().isEmpty());
  ByteBuf encoded=Unpooled.buffer();nativeClick.serialize(encoded);
  ServerboundContainerClickPacket decodedMenuClick=new ServerboundContainerClickPacket(encoded);
  check(decodedMenuClick.getContainerId()==7&&decodedMenuClick.getStateId()==21&&decodedMenuClick.getSlot()==4
   &&decodedMenuClick.getAction()==ContainerActionType.CLICK_ITEM&&decodedMenuClick.getParam().getId()==0);
  encoded.release();
  for(String[] click:new String[][]{{"menu-click 4 right","CLICK_ITEM","1"},{"menu-click 4 shift","SHIFT_CLICK_ITEM","0"},
    {"menu-click 4 number","MOVE_TO_HOTBAR_SLOT","0"},{"menu-click 4 double","FILL_STACK","0"},{"menu-click 4 drop","DROP_ITEM","0"}}){
   field("selectedHotbarSlot",0);packets.clear();call("sendNativeMenuClick",new Class[]{ClientSession.class,String.class},client,click[0]);
   ServerboundContainerClickPacket sent=(ServerboundContainerClickPacket)packets.getFirst();
   check(sent.getAction().name().equals(click[1])&&sent.getParam().getId()==Integer.parseInt(click[2]));
  }
  for(String command:new String[]{"menu-click","menu-click 4 ","menu-click -1","menu-click 27","menu-click 54","menu-click 4 outside","menu-click 4 shift extra"})
   rejected("sendNativeMenuClick",new Class[]{ClientSession.class,String.class},client,command);
  field("selectedHotbarSlot",-1);rejected("sendNativeMenuClick",new Class[]{ClientSession.class,String.class},client,"menu-click 4 number");
  field("nativeMenu",nativeMenuConstructor.newInstance(0,21,"GENERIC_9X3","Minijogos CIAAC",Arrays.asList(nativeItems)));
  rejected("sendNativeMenuClick",new Class[]{ClientSession.class,String.class},client,"menu-click 4 left");
  rejected("sendNativeMenuClose",new Class[]{ClientSession.class},client);
  field("nativeMenu",nativeMenuConstructor.newInstance(7,-1,"GENERIC_9X3","Minijogos CIAAC",Arrays.asList(nativeItems)));
  rejected("sendNativeMenuClick",new Class[]{ClientSession.class,String.class},client,"menu-click 4 left");
  field("nativeMenu",nativeMenuConstructor.newInstance(7,21,"GENERIC_9X3","Minijogos CIAAC",Arrays.asList(nativeItems)));
  packets.clear();call("sendNativeMenuClose",new Class[]{ClientSession.class},client);
  check(packets.size()==1&&packets.getFirst() instanceof ServerboundContainerClosePacket
   &&((ServerboundContainerClosePacket)packets.getFirst()).getContainerId()==7);
  call("observeNativeMenuClose",new Class[]{int.class},8);check(get("nativeMenu")!=null);
  call("observeNativeMenuClose",new Class[]{int.class},7);check(get("nativeMenu")==null);
  field("bowDrawn",false);field("outgoingSequence",1);field("yaw",45.0f);field("pitch",-20.0f);
  packets.clear();
  Class<?> entity=Class.forName("LocalArenaPeer$FixtureEntity");Constructor<?> c=entity.getDeclaredConstructors()[0];c.setAccessible(true);
  ((Map)get("fixtureEntities")).put(2,c.newInstance(42,1.0,0.0,0.0));
  call("sendFixtureAction",new Class[]{ClientSession.class,String.class},client,"attack 2");
  check(packets.size()==3);check(packets.get(1) instanceof ServerboundSwingPacket);check(packets.get(2) instanceof ServerboundAttackPacket&&((ServerboundAttackPacket)packets.get(2)).getEntityId()==42);
  field("lastActionNanos",0L);packets.clear();call("sendFixtureAction",new Class[]{ClientSession.class,String.class},client,"interact 2");
  check(packets.size()==2);check(packets.get(1) instanceof ServerboundInteractPacket&&((ServerboundInteractPacket)packets.get(1)).getEntityId()==42);
  ServerboundInteractPacket interaction=(ServerboundInteractPacket)packets.get(1);encoded=Unpooled.buffer();interaction.serialize(encoded);
  ServerboundInteractPacket decoded=new ServerboundInteractPacket(encoded);
  check(decoded.getEntityId()==42&&decoded.getHand()==interaction.getHand()&&decoded.getLocation().equals(Vector3d.from(0,1,0)));
  encoded.release();
  field("x",0.0);field("y",0.0);field("z",0.0);
  field("reportedPoseKnown",true);field("reportedX",0.0);field("reportedY",0.0);field("reportedZ",0.0);
  field("reportedYaw",45.0f);field("reportedPitch",-20.0f);
  packets.clear();
  call("sendFixtureLook",new Class[]{ClientSession.class,String.class},client,"look -135.5 42.25");
  check(packets.size()==1&&packets.getFirst() instanceof ServerboundMovePlayerRotPacket);
  ServerboundMovePlayerRotPacket look=(ServerboundMovePlayerRotPacket)packets.getFirst();
  check(look.getYaw()==-135.5f&&look.getPitch()==42.25f&&!look.isOnGround());
  encoded=Unpooled.buffer();look.serialize(encoded);
  ServerboundMovePlayerRotPacket decodedLook=new ServerboundMovePlayerRotPacket(encoded);
  check(decodedLook.getYaw()==-135.5f&&decodedLook.getPitch()==42.25f&&!decodedLook.isOnGround());
  encoded.release();
  for(String command:new String[]{"look", "look 10", "look 10 20 extra", "look  10 20", "look 10  20", "look\t10 20", "look NaN 0", "look Infinity 0", "look 361 0", "look -361 0", "look 0 90.1", "look 0 -90.1"})
   rejected("sendFixtureLook",new Class[]{ClientSession.class,String.class},client,command);
  field("joined",false);packets.clear();rejected("sendFixtureLook",new Class[]{ClientSession.class,String.class},client,"look 0 0");check(packets.isEmpty());field("joined",true);
  field("walkTicksRemaining",1);rejected("sendFixtureLook",new Class[]{ClientSession.class,String.class},client,"look 0 0");field("walkTicksRemaining",0);
  field("walkInputActive",true);rejected("sendFixtureLook",new Class[]{ClientSession.class,String.class},client,"look 0 0");field("walkInputActive",false);
  field("x",12.8);field("y",64.2);field("z",-3.1);field("yaw",45.0f);field("pitch",-20.0f);
  packets.clear();call("sendFixtureBowAction",new Class[]{ClientSession.class,String.class},client,"bow draw");
  check(packets.size()==1&&packets.getFirst() instanceof ServerboundUseItemPacket);
  ServerboundUseItemPacket bowUse=(ServerboundUseItemPacket)packets.getFirst();
  check(bowUse.getHand()==Hand.MAIN_HAND&&bowUse.getSequence()==1&&bowUse.getYRot()==45.0f&&bowUse.getXRot()==-20.0f);
  check((boolean)get("bowDrawn")&&(int)get("outgoingSequence")==2);
  rejected("sendFixtureBowAction",new Class[]{ClientSession.class,String.class},client,"bow draw");
  check(packets.size()==1);
  call("sendFixtureBowAction",new Class[]{ClientSession.class,String.class},client,"bow release");
  check(packets.size()==2&&packets.getLast() instanceof ServerboundPlayerActionPacket);
  ServerboundPlayerActionPacket bowRelease=(ServerboundPlayerActionPacket)packets.getLast();
  check(bowRelease.getAction()==PlayerAction.RELEASE_USE_ITEM&&bowRelease.getPosition().equals(Vector3i.from(12,64,-4))
    &&bowRelease.getFace()==Direction.DOWN&&bowRelease.getSequence()==2);
  check(!(boolean)get("bowDrawn")&&(int)get("outgoingSequence")==3);
  rejected("sendFixtureBowAction",new Class[]{ClientSession.class,String.class},client,"bow release");
  for(String command:new String[]{"bow", "bow draw now", "bow release 1", "bow shoot"})
   rejected("sendFixtureBowAction",new Class[]{ClientSession.class,String.class},client,command);
  packets.clear();call("sendFixtureBowAction",new Class[]{ClientSession.class,String.class},client,"bow draw");
  call("suspendMotionAfterNativeTeleport",new Class[]{Session.class},client);
  check(packets.stream().anyMatch(p->p instanceof ServerboundPlayerActionPacket
    &&((ServerboundPlayerActionPacket)p).getAction()==PlayerAction.RELEASE_USE_ITEM));
  check(!(boolean)get("bowDrawn"));
  packets.clear();call("sendFixtureBowAction",new Class[]{ClientSession.class,String.class},client,"bow draw");
  call("disconnectFixture",new Class[]{ClientSession.class,String.class},client,"offline check");
  check(packets.size()==2&&packets.getLast() instanceof ServerboundPlayerActionPacket
    &&((ServerboundPlayerActionPacket)packets.getLast()).getAction()==PlayerAction.RELEASE_USE_ITEM);
  check(!(boolean)get("bowDrawn"));
  field("joined",true);field("positionKnown",true);
  rejected("sendFixtureBowAction",new Class[]{ClientSession.class,String.class},client,"bow draw ");
  field("positionKnown",false);packets.clear();rejected("sendFixtureBowAction",new Class[]{ClientSession.class,String.class},client,"bow draw");check(packets.isEmpty());field("positionKnown",true);
  field("yaw",Float.NaN);rejected("sendFixtureBowAction",new Class[]{ClientSession.class,String.class},client,"bow draw");check(packets.isEmpty());field("yaw",45.0f);
  field("outgoingSequence",Integer.MAX_VALUE);rejected("sendFixtureBowAction",new Class[]{ClientSession.class,String.class},client,"bow draw");check(packets.isEmpty());field("outgoingSequence",5);
  field("x",0.0);field("y",0.0);field("z",0.0);field("pitch",0.0f);
  float stableYaw=(float)get("yaw");field("yaw",stableYaw+30);packets.clear();
  call("sendNativeMovement",new Class[]{Session.class,boolean.class},client,false);
  check(packets.size()==1&&packets.getFirst() instanceof ServerboundMovePlayerRotPacket);
  field("yaw",stableYaw);packets.clear();call("sendNativeMovement",new Class[]{Session.class,boolean.class},client,false);
  check(packets.size()==1&&packets.getFirst() instanceof ServerboundMovePlayerRotPacket);
  rejected("sendFixtureAction",new Class[]{ClientSession.class,String.class},client,"attack 42");
  rejected("sendFixtureAction",new Class[]{ClientSession.class,String.class},client,"attack 1");
  rejected("sendFixtureMove",new Class[]{ClientSession.class,String.class},client,"move NaN 0");
  rejected("sendFixtureMove",new Class[]{ClientSession.class,String.class},client,"move 0.31 0");
  rejected("sendFixtureMove",new Class[]{ClientSession.class,String.class},client,"move 0 0 0");
  packets.clear();call("sendFixtureMove",new Class[]{ClientSession.class,String.class},client,"move 0.2 0");check((double)get("x")==0.2);check(packets.getFirst() instanceof ServerboundMovePlayerPosPacket);
  rejected("sendFixtureWalk",new Class[]{ClientSession.class,String.class},client,"walk east 1");
  rejected("sendFixtureWalk",new Class[]{ClientSession.class,String.class},client,"walk up 1");
  rejected("sendFixtureWalk",new Class[]{ClientSession.class,String.class},client,"walk east 0");
  rejected("sendFixtureWalk",new Class[]{ClientSession.class,String.class},client,"walk east 101");
  field("y",80.614);
  call("setFlatFloorMotion",new Class[]{ClientSession.class,boolean.class,Double.class},client,true,80.0);
  check((double)get("fixtureFloorY")==80.0);check((boolean)get("flatFloorMotion"));
  rejected("setFlatFloorMotion",new Class[]{ClientSession.class,boolean.class,Double.class},client,true,Double.NaN);
  rejected("setFlatFloorMotion",new Class[]{ClientSession.class,boolean.class,Double.class},client,true,Double.POSITIVE_INFINITY);
  rejected("setFlatFloorMotion",new Class[]{ClientSession.class,boolean.class,Double.class},client,true,321.0);
  rejected("setFlatFloorMotion",new Class[]{ClientSession.class,boolean.class,Double.class},client,true,-65.0);
  rejected("setFlatFloorMotion",new Class[]{ClientSession.class,boolean.class,Double.class},client,true,80.615);
  rejected("setFlatFloorMotion",new Class[]{ClientSession.class,boolean.class,Double.class},client,true,76.613);
  field("y",0.0);
  call("setFlatFloorMotion",new Class[]{ClientSession.class,boolean.class},client,true);
  field("x",0.0);field("y",0.0);field("z",0.0);field("modelVelocityX",0.0);field("modelVelocityY",0.0);field("modelVelocityZ",0.0);
  packets.clear();call("sendFixtureWalk",new Class[]{ClientSession.class,String.class},client,"walk east 1");
  check(packets.getFirst() instanceof ServerboundPlayerInputPacket);check((float)get("yaw")==-90.0f);check((int)get("walkTicksRemaining")==1);
  call("tickFlatFloorMotion",new Class[]{ClientSession.class},client);
  check(Math.abs((double)get("x")-0.098)<1e-9);check(Math.abs((double)get("modelVelocityX")-0.053508)<1e-9);
  check((int)get("walkTicksRemaining")==0);check(!((boolean)get("walkInputActive")));
  check(packets.getLast() instanceof ServerboundPlayerInputPacket);check(packets.stream().anyMatch(p->p instanceof ServerboundMovePlayerPosPacket||p instanceof ServerboundMovePlayerPosRotPacket));
  packets.clear();field("modelVelocityX",0.0);field("modelVelocityY",0.0);field("modelVelocityZ",0.0);
  call("tickFlatFloorMotion",new Class[]{ClientSession.class},client);
  check(packets.isEmpty());
  field("x",0.0);field("y",1.0);field("z",0.0);field("modelVelocityX",0.0);field("modelVelocityY",0.0);field("modelVelocityZ",0.0);
  packets.clear();call("sendFixtureWalk",new Class[]{ClientSession.class,String.class},client,"walk south 1");
  call("tickFlatFloorMotion",new Class[]{ClientSession.class},client);
  check(Math.abs((double)get("z")-0.0196)<1e-9);check(Math.abs((double)get("modelVelocityZ")-0.017836)<1e-9);
  packets.clear();call("sendFixtureWalk",new Class[]{ClientSession.class,String.class},client,"walk west 5");
  call("suspendMotionAfterNativeTeleport",new Class[]{Session.class},client);
  check((int)get("walkTicksRemaining")==0);check(!((boolean)get("walkInputActive")));
  check(!(boolean)get("flatFloorMotion"));
  check(packets.getLast() instanceof ServerboundPlayerInputPacket);
  field("flatFloorMotion",true);field("fixtureFloorY",0.0);field("x",0.2);field("y",0.0);field("modelVelocityX",0.4);field("modelVelocityY",0.4);packets.clear();call("tickFlatFloorMotion",new Class[]{ClientSession.class},client);check(Math.abs((double)get("x")-0.6)<1e-9);check((double)get("y")==0.4);check(!((ServerboundMovePlayerPosRotPacket)packets.getFirst()).isOnGround());
  prepareBuildPlot(1);
  packets.clear();
  call("sendFixtureCreativeSlot",new Class[]{ClientSession.class,String.class},client,"creative-slot stone");
  check(packets.size()==1&&packets.getFirst() instanceof ServerboundSetCreativeModeSlotPacket);
  ServerboundSetCreativeModeSlotPacket stoneSlot=(ServerboundSetCreativeModeSlotPacket)packets.getFirst();
  check(stoneSlot.getSlot()==36&&stoneSlot.getClickedItem().getId()==1&&stoneSlot.getClickedItem().getAmount()==1);
  encoded=Unpooled.buffer();stoneSlot.serialize(encoded);
  ServerboundSetCreativeModeSlotPacket decodedStone=new ServerboundSetCreativeModeSlotPacket(encoded);
  check(decodedStone.getSlot()==36&&decodedStone.getClickedItem().getId()==1&&decodedStone.getClickedItem().getAmount()==1);
  encoded.release();

  field("outgoingSequence",1);field("lastActionNanos",0L);packets.clear();
  call("sendFixtureBlockPlace",new Class[]{ClientSession.class,String.class},client,"place 3 64 2 up");
  check(packets.size()==2&&packets.getFirst() instanceof ServerboundMovePlayerPosRotPacket
    &&packets.getLast() instanceof ServerboundUseItemOnPacket);
  ServerboundMovePlayerPosRotPacket placeAim=(ServerboundMovePlayerPosRotPacket)packets.getFirst();
  check(placeAim.getX()==2.5&&placeAim.getY()==65.0&&placeAim.getZ()==2.5
    &&placeAim.getYaw()==-90.0f&&placeAim.getPitch()>58.0f&&placeAim.getPitch()<59.0f);
  ServerboundUseItemOnPacket place=(ServerboundUseItemOnPacket)packets.getLast();
  check(place.getPosition().equals(Vector3i.from(3,64,2))&&place.getFace()==Direction.UP&&place.getHand()==Hand.MAIN_HAND
    &&place.getCursorX()==0.5f&&place.getCursorY()==1.0f&&place.getCursorZ()==0.5f&&!place.isInsideBlock()
    &&!place.isHitWorldBorder()&&place.getSequence()==1);
  encoded=Unpooled.buffer();place.serialize(encoded);
  ServerboundUseItemOnPacket decodedPlace=new ServerboundUseItemOnPacket(encoded);
  check(decodedPlace.getPosition().equals(place.getPosition())&&decodedPlace.getFace()==Direction.UP
    &&decodedPlace.getHand()==Hand.MAIN_HAND&&decodedPlace.getSequence()==1);
  encoded.release();

  field("lastActionNanos",0L);packets.clear();
  call("sendFixtureBlockBreak",new Class[]{ClientSession.class,String.class},client,"break 3 65 2");
  check(packets.size()==3&&packets.getFirst() instanceof ServerboundMovePlayerRotPacket
    &&packets.get(1) instanceof ServerboundPlayerActionPacket&&packets.get(2) instanceof ServerboundPlayerActionPacket);
  ServerboundPlayerActionPacket startBreak=(ServerboundPlayerActionPacket)packets.get(1);
  ServerboundPlayerActionPacket finishBreak=(ServerboundPlayerActionPacket)packets.get(2);
  check(startBreak.getAction()==PlayerAction.START_DIGGING&&finishBreak.getAction()==PlayerAction.FINISH_DIGGING
    &&startBreak.getPosition().equals(Vector3i.from(3,65,2))&&finishBreak.getPosition().equals(startBreak.getPosition())
    &&startBreak.getFace()==Direction.UP&&finishBreak.getFace()==Direction.UP
    &&startBreak.getSequence()==2&&finishBreak.getSequence()==3);
  for(ServerboundPlayerActionPacket action:List.of(startBreak,finishBreak)){
   encoded=Unpooled.buffer();action.serialize(encoded);ServerboundPlayerActionPacket decodedAction=new ServerboundPlayerActionPacket(encoded);
   check(decodedAction.getAction()==action.getAction()&&decodedAction.getPosition().equals(action.getPosition())
     &&decodedAction.getFace()==action.getFace()&&decodedAction.getSequence()==action.getSequence());encoded.release();
  }

  packets.clear();field("nativeCreativeAbility",false);
  rejected("sendFixtureCreativeSlot",new Class[]{ClientSession.class,String.class},client,"creative-slot stone");
  rejected("sendFixtureBlockPlace",new Class[]{ClientSession.class,String.class},client,"place 2 64 2 up");
  rejected("sendFixtureBlockBreak",new Class[]{ClientSession.class,String.class},client,"break 2 65 2");
  check(packets.isEmpty());
  field("nativeCreativeAbility",true);field("nativeGameMode",GameMode.ADVENTURE);
  rejected("sendFixtureCreativeSlot",new Class[]{ClientSession.class,String.class},client,"creative-slot stone");
  field("nativeGameMode",GameMode.CREATIVE);field("nativeWorldName","minecraft:overworld");
  rejected("sendFixtureBlockPlace",new Class[]{ClientSession.class,String.class},client,"place 2 64 2 up");
  field("nativeWorldName","minecraft:ciaac-build-test");field("nativeX",15.5);field("nativeZ",2.5);
  rejected("sendFixtureBlockBreak",new Class[]{ClientSession.class,String.class},client,"break 2 65 2");
  field("nativeX",2.5);field("nativeZ",2.5);
  for(String command:new String[]{"creative-slot diamond","creative-slot stone 64"})
   rejected("sendFixtureCreativeSlot",new Class[]{ClientSession.class,String.class},client,command);
  for(String command:new String[]{"place 2 64 2 down","place 2 63 2 up","place 2 64 2  up","place 2 64 2 up extra"})
   rejected("sendFixtureBlockPlace",new Class[]{ClientSession.class,String.class},client,command);
  for(String command:new String[]{"break 8 65 2","break 2 68 2","break 2 65 2 extra"})
   rejected("sendFixtureBlockBreak",new Class[]{ClientSession.class,String.class},client,command);
  field("x",0.2);field("y",65.0);field("z",0.2);
  rejected("sendFixtureBlockPlace",new Class[]{ClientSession.class,String.class},client,"place 4 66 4 up");
  rejected("sendFixtureBlockBreak",new Class[]{ClientSession.class,String.class},client,"break 4 67 4");
  check(packets.isEmpty());
  field("selectedHotbarSlot",-1);
  rejected("sendFixtureCreativeSlot",new Class[]{ClientSession.class,String.class},client,"creative-slot stone");
  check(packets.isEmpty());
  check(!call("isAllowedFixtureCommand",new Class[]{String.class},"creative-slot stone").equals(true));
  check(!call("isAllowedFixtureCommand",new Class[]{String.class},"place 2 64 2 up").equals(true));
  check(!call("isAllowedFixtureCommand",new Class[]{String.class},"break 2 65 2").equals(true));
  call("classifyCarrierTitle",new Class[]{String.class},"A batata é tua!");check(get("carrierTitle").equals("HOT_POTATO_SELF_CARRIER"));check(get("carrierPeerIndex").equals(1));
  call("classifyCarrierTitle",new Class[]{String.class},"CiaacArenaPeer2 tem a batata");check(get("carrierPeerIndex").equals(2));
  call("classifyCarrierTitle",new Class[]{String.class},"PrivateUnknownPlayer tem a batata");check(get("carrierTitle").equals("OTHER"));
  call("printStatus",new Class[]{ClientSession.class},client);
 System.out.println("OFFLINE_PACKET_CHECKS="+checks);
 }

 static void prepareBuildPlot(int plot)throws Exception{
  field("joined",true);field("positionKnown",true);field("nativeWorldName","minecraft:ciaac-build-test");
  field("nativeGameMode",GameMode.CREATIVE);field("nativeCreativeAbility",true);field("selectedHotbarSlot",0);
  field("nativeX",plot==1?2.5:15.5);field("nativeY",65.0);field("nativeZ",2.5);
  field("x",plot==1?2.5:15.5);field("y",65.0);field("z",2.5);field("outgoingSequence",1);
  field("yaw",0.0f);field("pitch",0.0f);field("reportedPoseKnown",false);
  field("lastActionNanos",0L);
 }
}
