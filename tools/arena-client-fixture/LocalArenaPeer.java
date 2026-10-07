import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.network.*;
import org.geysermc.mcprotocollib.network.event.session.*;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.*;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.*;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundShowDialogConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundClearDialogPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket;

/** Synthetic packet peer; hard-bound to the disposable loopback Paper fixture. */
public final class LocalArenaPeer {
 private static volatile boolean joined;
 private static volatile boolean positionKnown;
 private static volatile AuthDialog pendingAuthDialog=AuthDialog.NONE;
 private static volatile boolean pendingRegisterConfirmation;
 private static final Key AUTHME_REGISTER_SUBMIT=Key.key("authme:prejoin-register/submit");
 private static final Key AUTHME_LOGIN_SUBMIT=Key.key("authme:prejoin-login/submit");
 private enum AuthDialog { NONE, REGISTER, LOGIN }
 private static final Object poseLock = new Object();
 private static double x, y, z;
 private static double nativeX,nativeY,nativeZ,velocityX,velocityY,velocityZ,lastNonzeroVelocityX,lastNonzeroVelocityY,lastNonzeroVelocityZ;
 private static long lastMoveNanos,lastActionNanos,velocityPackets,nonzeroVelocityPackets,nativeTeleportPackets;
 private static boolean flatFloorMotion;
 private static double fixtureFloorY,modelVelocityX,modelVelocityY,modelVelocityZ;
 private static int walkTicksRemaining;
 private static double walkDirectionX,walkDirectionZ;
 private static boolean walkInputActive;
 private static boolean reportedPoseKnown;
 private static double reportedX,reportedY,reportedZ;
 private static float reportedYaw,reportedPitch;
 private static int stationaryMoveTicks;
 private static int ownEntityId=-1,ownPeerIndex;
 private static String carrierTitle="OTHER";
 private static int carrierPeerIndex;
 private static final Map<Integer,FixtureEntity> fixtureEntities=new HashMap<>();
 private record FixtureEntity(int entityId,double x,double y,double z) {}
 private static float yaw, pitch;
 private static final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor();
 public static void main(String[] args) throws Exception {
  if ((args.length != 1 && args.length != 3) || !args[0].equals("--authorized-loopback-fixture"))
   throw new IllegalArgumentException("Expected --authorized-loopback-fixture [--fixture-peer 2|3|4|5]");
  String username="CiaacArenaPeer";
  if(args.length==3){
   if(!args[1].equals("--fixture-peer") || !args[2].matches("[2345]"))
    throw new IllegalArgumentException("Optional fixture peer must be exactly 2, 3, 4, or 5");
   username+=args[2];
  }
  ownPeerIndex=args.length==3?Integer.parseInt(args[2]):1;
  if (!MinecraftCodec.CODEC.getMinecraftVersion().equals("26.2") || MinecraftCodec.CODEC.getProtocolVersion() != 776)
   throw new IllegalStateException("Exact Minecraft 26.2 protocol required");
  Path root=Path.of("/private/tmp/ciaac-paper-local-test");
  Properties props=new Properties();try(var reader=Files.newBufferedReader(root.resolve("server.properties"))){props.load(reader);}
  if (!props.getProperty("server-ip").equals("127.0.0.1") || !props.getProperty("server-port").equals("25567")
   || !props.getProperty("level-name").equals("ciaac-synthetic-test") || !props.getProperty("online-mode").equals("false"))
   throw new IllegalStateException("Refusing a non-synthetic or non-loopback target");
  MinecraftProtocol protocol=new MinecraftProtocol(username);
  ClientSession client=ClientNetworkSessionFactory.factory().setRemoteSocketAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),25567)).setProtocol(protocol).create();
  client.addListener(new SessionAdapter(){
   @Override public void packetReceived(Session session,Packet packet){
    if (packet instanceof ClientboundLoginPacket login){
     if(login.isOnlineMode())throw new IllegalStateException("Fixture unexpectedly requires Mojang identity");
     pendingAuthDialog=AuthDialog.NONE;
     pendingRegisterConfirmation=false;
     joined=true;System.out.println("Peer joined native GAME state; provider authentication is still required.");
     ownEntityId=login.getEntityId();
    } else if(packet instanceof ClientboundShowDialogConfigurationPacket dialog){
     pendingRegisterConfirmation=false;
     pendingAuthDialog=classifyAuthDialog(dialog.getDialog());
     System.out.println("Native configuration dialog received; classification="+
      (pendingAuthDialog==AuthDialog.REGISTER?"AUTH_REGISTER_FORM_READY":
       pendingAuthDialog==AuthDialog.LOGIN?"AUTH_LOGIN_FORM_READY":"AUTH_DIALOG_UNSUPPORTED"));
    } else if(packet instanceof ClientboundPingPacket ping){
     session.send(new ServerboundPongPacket(ping.getId()));
    } else if(packet instanceof ClientboundClearDialogPacket){
     pendingAuthDialog=AuthDialog.NONE;
     pendingRegisterConfirmation=false;
    } else if(packet instanceof ClientboundPlayerPositionPacket pos){
     synchronized(poseLock){
      var relative=pos.getRelatives();var p=pos.getPosition();
      x=p.getX()+(relative.contains(PositionElement.X)?x:0);y=p.getY()+(relative.contains(PositionElement.Y)?y:0);z=p.getZ()+(relative.contains(PositionElement.Z)?z:0);
      nativeX=x;nativeY=y;nativeZ=z;nativeTeleportPackets++;
      // Every native teleport suspends the model; the driver must affirm the new flat floor explicitly.
      suspendMotionAfterNativeTeleport(session);
      yaw=pos.getYRot()+(relative.contains(PositionElement.Y_ROT)?yaw:0);pitch=pos.getXRot()+(relative.contains(PositionElement.X_ROT)?pitch:0);
      if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalStateException("Invalid native teleport");
      session.send(new ServerboundAcceptTeleportationPacket(pos.getId()));
      reportedPoseKnown=false;
      sendNativeMovement(session,false);
      positionKnown=true;
     }
     session.send(ServerboundPlayerLoadedPacket.INSTANCE);
     System.out.println("Native teleport acknowledged.");
    } else if(packet instanceof ClientboundAddEntityPacket entity){
     int index=fixturePeerIndex(entity.getUuid());
     if(index>0 && index!=ownPeerIndex && entity.getType()==EntityType.PLAYER){
      synchronized(poseLock){fixtureEntities.put(index,new FixtureEntity(entity.getEntityId(),entity.getX(),entity.getY(),entity.getZ()));}
      System.out.println("Native allowlisted fixture peer resolved; index="+index);
     }
    } else if(packet instanceof ClientboundRemoveEntitiesPacket removed){
     synchronized(poseLock){for(int id:removed.getEntityIds())fixtureEntities.values().removeIf(entity->entity.entityId()==id);}
    } else if(packet instanceof ClientboundMoveEntityPosPacket moved){
     updateFixturePosition(moved.getEntityId(),moved.getMoveX(),moved.getMoveY(),moved.getMoveZ(),true);
    } else if(packet instanceof ClientboundMoveEntityPosRotPacket moved){
     updateFixturePosition(moved.getEntityId(),moved.getMoveX(),moved.getMoveY(),moved.getMoveZ(),true);
    } else if(packet instanceof ClientboundEntityPositionSyncPacket synced){
     var p=synced.getPosition();updateFixturePosition(synced.getId(),p.getX(),p.getY(),p.getZ(),false);
    } else if(packet instanceof ClientboundTeleportEntityPacket teleported){
     synchronized(poseLock){
      var p=teleported.getPosition();var relative=teleported.getRelatives();
      fixtureEntities.replaceAll((index,entity)->entity.entityId()!=teleported.getId()?entity:new FixtureEntity(entity.entityId(),
       p.getX()+(relative.contains(PositionElement.X)?entity.x():0),
       p.getY()+(relative.contains(PositionElement.Y)?entity.y():0),
       p.getZ()+(relative.contains(PositionElement.Z)?entity.z():0)));
     }
    } else if(packet instanceof ClientboundSetEntityMotionPacket motion && motion.getEntityId()==ownEntityId){
     synchronized(poseLock){var v=motion.getMovement();velocityX=v.getX();velocityY=v.getY();velocityZ=v.getZ();velocityPackets++;
      if(velocityX!=0||velocityY!=0||velocityZ!=0){lastNonzeroVelocityX=velocityX;lastNonzeroVelocityY=velocityY;lastNonzeroVelocityZ=velocityZ;nonzeroVelocityPackets++;}
      if(Double.isFinite(velocityX)&&Double.isFinite(velocityY)&&Double.isFinite(velocityZ)
       &&velocityX*velocityX+velocityY*velocityY+velocityZ*velocityZ<=16){
       modelVelocityX=velocityX;modelVelocityY=velocityY;modelVelocityZ=velocityZ;
      }else flatFloorMotion=false;
     }
     System.out.println("Native own-entity velocity received.");
    } else if(packet instanceof ClientboundSetTitleTextPacket title){
     classifyCarrierTitle(plain(title.getText()));
    } else if(packet instanceof ClientboundChunkBatchFinishedPacket){
     session.send(new ServerboundChunkBatchReceivedPacket(2.0f));
    } else if(packet instanceof ClientboundSystemChatPacket chat){
     // Do not print arbitrary server chat or reflected commands/passwords.
     Component content=chat.getContent();
     String code=containsReadyPrompt(content,null)?"ARENA_READY_PROMPT":classifyText(plain(content));
     System.out.println("Native system message received; classification="+code);
    }
   }
   @Override public void disconnected(DisconnectedEvent event){joined=false;positionKnown=false;System.out.println("Peer disconnected; cause="+(event.getCause()==null?"none":event.getCause().getClass().getSimpleName()));expiry.shutdownNow();}
   @Override public void packetError(PacketErrorEvent event){System.err.println("Packet error: "+event.getCause().getClass().getSimpleName());}
  });
  expiry.schedule(()->client.disconnect(Component.text("Local fixture expiry")),15,TimeUnit.MINUTES);
  expiry.scheduleAtFixedRate(()->{if(client.isConnected()&&joined){tickFlatFloorMotion(client);client.send(ServerboundClientTickEndPacket.INSTANCE);}},50,50,TimeUnit.MILLISECONDS);
  try(var reader=new BufferedReader(new InputStreamReader(System.in))){
   client.connect();
   String command;
   while((command=reader.readLine())!=null){
    if(command.equals("quit"))break;
    if(command.equals("status")){printStatus(client);continue;}
    if(command.length()>256||command.codePoints().anyMatch(Character::isISOControl))
     throw new IllegalArgumentException("Fixture input exceeds the safe command format");
    if(!joined&&(command.startsWith("register ")||command.equals("register")||command.startsWith("login ")||command.equals("login"))){
     sendPreJoinAuthResponse(client,command);
     continue;
    }
    if(command.startsWith("attack ")||command.startsWith("interact ")){sendFixtureAction(client,command);continue;}
    if(command.startsWith("move ")){sendFixtureMove(client,command);continue;}
    if(command.startsWith("walk ")){sendFixtureWalk(client,command);continue;}
    if(command.equals("flat-floor-motion on")||command.equals("flat-floor-motion off")){
     setFlatFloorMotion(client,command.endsWith(" on"),null);
     System.out.println("Explicit fixture motion model="+(flatFloorMotion?"FLAT_FLOOR_APPROXIMATION":"NONE"));continue;
    }
    if(command.startsWith("flat-floor-motion on ")){
     String value=command.substring("flat-floor-motion on ".length());
     if(value.isEmpty()||value.indexOf(' ')>=0)throw new IllegalArgumentException("Expected flat-floor-motion on [floor-y]");
     double floorY;
     try{floorY=Double.parseDouble(value);}catch(NumberFormatException invalid){throw new IllegalArgumentException("Floor Y must be a finite number",invalid);}
     setFlatFloorMotion(client,true,floorY);
     System.out.println("Explicit fixture motion model="+(flatFloorMotion?"FLAT_FLOOR_APPROXIMATION":"NONE"));continue;
    }
    if(!command.matches("(?:register|login|coliseu|minijogos|passaporte|sumo|batataquente)(?: .*)?"))throw new IllegalArgumentException("Only explicit fixture auth/game commands are allowed");
    if(!client.isConnected()||!joined)throw new IllegalStateException("Peer has not reached the native game state");
    client.send(new ServerboundChatCommandPacket(command));
    System.out.println("Explicit fixture command sent; arguments withheld.");
   }
  }finally{client.disconnect(Component.text("Local fixture complete"));expiry.shutdownNow();}
 }

 private static int fixturePeerIndex(UUID uuid) {
  for(int index=1;index<=5;index++){
   String name="CiaacArenaPeer"+(index==1?"":index);
   if(UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8)).equals(uuid))return index;
  }
  return 0;
 }

 private static void updateFixturePosition(int entityId,double px,double py,double pz,boolean relative) {
  synchronized(poseLock){fixtureEntities.replaceAll((index,entity)->entity.entityId()!=entityId?entity:
   new FixtureEntity(entityId,px+(relative?entity.x():0),py+(relative?entity.y():0),pz+(relative?entity.z():0)));}
 }

 private static void requireGamePose(ClientSession client) {
  if(!client.isConnected()||!joined||!positionKnown)throw new IllegalStateException("No native game pose is available");
 }

 private static void sendFixtureAction(ClientSession client,String command) {
  if(!command.matches("(?:attack|interact) [1-5]"))throw new IllegalArgumentException("Target must be an allowlisted fixture peer index 1..5");
  requireGamePose(client);
  synchronized(poseLock){
   FixtureEntity target=fixtureEntities.get(Integer.parseInt(command.substring(command.indexOf(' ')+1)));
   if(target==null)throw new IllegalStateException("Target has no current native allowlisted player spawn");
   double dx=target.x()-x,dy=target.y()-y,dz=target.z()-z;
   if(dx*dx+dy*dy+dz*dz>3.2*3.2)throw new IllegalStateException("Target exceeds bounded fixture reach");
   long now=System.nanoTime();if(now-lastActionNanos<250_000_000L)throw new IllegalStateException("Fixture actions require a 250ms interval");
   yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));pitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
   sendNativeMovement(client,flatFloorMotion&&y<=fixtureFloorY);
   client.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
   if(command.startsWith("attack "))client.send(new ServerboundAttackPacket(target.entityId()));
   else client.send(new ServerboundInteractPacket(target.entityId(),Hand.MAIN_HAND,Vector3d.from(0,1,0),false));
   lastActionNanos=now;
  }
  System.out.println("Explicit native fixture action sent; classification="+(command.startsWith("attack ")?"ATTACK":"INTERACT"));
 }

 private static void sendFixtureMove(ClientSession client,String command) {
  String[] parts=command.split(" ",-1);
  if(parts.length!=3)throw new IllegalArgumentException("Expected move dx dz");
  double dx=Double.parseDouble(parts[1]),dz=Double.parseDouble(parts[2]);
  if(!Double.isFinite(dx)||!Double.isFinite(dz)||Math.hypot(dx,dz)>0.3)throw new IllegalArgumentException("Each fixture move is limited to 0.3 horizontal blocks");
  requireGamePose(client);
  synchronized(poseLock){
   long now=System.nanoTime();if(now-lastMoveNanos<50_000_000L)throw new IllegalStateException("Fixture moves require a 50ms interval");
   x+=dx;z+=dz;sendNativeMovement(client,flatFloorMotion&&y<=fixtureFloorY);lastMoveNanos=now;
  }
  System.out.println("Explicit bounded native horizontal move sent.");
 }

 private static void sendFixtureWalk(ClientSession client,String command) {
  String[] parts=command.split(" ",-1);
  if(parts.length!=3||!parts[2].matches("[0-9]{1,3}"))throw new IllegalArgumentException("Expected walk <east|west|north|south> <ticks 1..100>");
  int ticks=Integer.parseInt(parts[2]);
  if(ticks<1||ticks>100)throw new IllegalArgumentException("Walk duration must be 1..100 ticks");
  double directionX,directionZ;
  switch(parts[1]){
   case "east"->{directionX=1;directionZ=0;}
   case "west"->{directionX=-1;directionZ=0;}
   case "south"->{directionX=0;directionZ=1;}
   case "north"->{directionX=0;directionZ=-1;}
   default->throw new IllegalArgumentException("Walk direction must be east, west, north, or south");
  }
  requireGamePose(client);
  synchronized(poseLock){
   if(!flatFloorMotion)throw new IllegalStateException("Walk requires flat-floor-motion on");
   if(walkTicksRemaining>0)throw new IllegalStateException("A bounded walk is already active");
   walkDirectionX=directionX;walkDirectionZ=directionZ;walkTicksRemaining=ticks;walkInputActive=true;
   yaw=(float)Math.toDegrees(Math.atan2(-directionX,directionZ));
   client.send(new ServerboundPlayerInputPacket(true,false,false,false,false,false,false));
  }
  System.out.println("Explicit bounded native forward input sent; ticks="+ticks);
 }

 private static void cancelWalkInput(Session client) {
  if(walkInputActive){
   client.send(new ServerboundPlayerInputPacket(false,false,false,false,false,false,false));
   walkInputActive=false;
  }
  walkTicksRemaining=0;
 }

 private static void setFlatFloorMotion(ClientSession client,boolean enabled) {
  setFlatFloorMotion(client,enabled,null);
 }

 private static void setFlatFloorMotion(ClientSession client,boolean enabled,Double requestedFloorY) {
  requireGamePose(client);
  synchronized(poseLock){
   double selectedFloor=enabled&&requestedFloorY!=null?requestedFloorY:y;
   if(enabled){
    if(!Double.isFinite(y))throw new IllegalStateException("Current player Y must be finite before enabling floor motion");
    if(!Double.isFinite(selectedFloor)||selectedFloor < -64.0||selectedFloor > 320.0)
     throw new IllegalArgumentException("Floor Y must be finite and within the native build height -64..320");
    if(selectedFloor>y)throw new IllegalArgumentException("Floor Y cannot be above the current player position");
    if(y-selectedFloor>4.0)throw new IllegalArgumentException("Floor Y cannot be more than 4 blocks below the current player position");
   }
   if(!enabled)cancelWalkInput(client);
   flatFloorMotion=enabled;fixtureFloorY=selectedFloor;modelVelocityX=0;modelVelocityY=0;modelVelocityZ=0;
  }
 }

 private static void suspendMotionAfterNativeTeleport(Session client) {
  cancelWalkInput(client);flatFloorMotion=false;modelVelocityX=0;modelVelocityY=0;modelVelocityZ=0;
 }

 /** Opt-in model for a declared infinite flat synthetic floor; no block/world collision simulation. */
 private static void tickFlatFloorMotion(ClientSession client) {
  synchronized(poseLock){
   if(!flatFloorMotion||!positionKnown)return;
   boolean wasOnGround=y<=fixtureFloorY;
   if(walkTicksRemaining>0){
    client.send(new ServerboundPlayerInputPacket(true,false,false,false,false,false,false));
    double acceleration=wasOnGround?0.098:0.0196;
    modelVelocityX+=walkDirectionX*acceleration;modelVelocityZ+=walkDirectionZ*acceleration;
   }
   x+=modelVelocityX;y+=modelVelocityY;z+=modelVelocityZ;
   boolean onGround=y<=fixtureFloorY;
   if(onGround){y=fixtureFloorY;modelVelocityY=0;}
   sendNativeMovement(client,onGround);
   modelVelocityX*=wasOnGround?0.546:0.91;modelVelocityZ*=wasOnGround?0.546:0.91;
   modelVelocityY=(modelVelocityY-0.08)*0.98;
   if(Math.abs(modelVelocityX)<0.003)modelVelocityX=0;
   if(Math.abs(modelVelocityZ)<0.003)modelVelocityZ=0;
   if(walkTicksRemaining>0&&--walkTicksRemaining==0)cancelWalkInput(client);
  }
 }

 /** Sends the narrow native movement packet that matches the pose fields which changed. */
 private static void sendNativeMovement(Session client,boolean onGround) {
  boolean positionChanged=!reportedPoseKnown||Double.compare(x,reportedX)!=0||Double.compare(y,reportedY)!=0||Double.compare(z,reportedZ)!=0;
  boolean rotationChanged=!reportedPoseKnown||Float.compare(yaw,reportedYaw)!=0||Float.compare(pitch,reportedPitch)!=0;
  if(positionChanged&&rotationChanged)client.send(new ServerboundMovePlayerPosRotPacket(onGround,false,x,y,z,yaw,pitch));
  else if(positionChanged)client.send(new ServerboundMovePlayerPosPacket(onGround,false,x,y,z));
  else if(rotationChanged)client.send(new ServerboundMovePlayerRotPacket(onGround,false,yaw,pitch));
  else if(++stationaryMoveTicks>=20){
   client.send(new ServerboundMovePlayerStatusOnlyPacket(onGround,false));
   stationaryMoveTicks=0;
  }else return;
  reportedPoseKnown=true;reportedX=x;reportedY=y;reportedZ=z;reportedYaw=yaw;reportedPitch=pitch;
  stationaryMoveTicks=0;
 }

 private static void classifyCarrierTitle(String text) {
  synchronized(poseLock){
   carrierTitle="OTHER";carrierPeerIndex=0;
   if(text.equals("A batata é tua!")){carrierTitle="HOT_POTATO_SELF_CARRIER";carrierPeerIndex=ownPeerIndex;}
   else for(int index=1;index<=5;index++)if(text.equals("CiaacArenaPeer"+(index==1?"":index)+" tem a batata")){
    carrierTitle="HOT_POTATO_OTHER_CARRIER";carrierPeerIndex=index;break;
   }
   System.out.println("Native title received; classification="+carrierTitle);
  }
 }

 private static void printStatus(ClientSession client) {
  synchronized(poseLock){
   System.out.println(String.format(Locale.ROOT,"{\"fixtureStatus\":true,\"nativeConnected\":%s,\"gameJoined\":%s,\"positionKnown\":%s,\"peerIndex\":%d,\"localPose\":[%.9f,%.9f,%.9f,%.6f,%.6f],\"lastNativeTeleport\":[%.9f,%.9f,%.9f],\"nativeTeleportPackets\":%d,\"receivedVelocity\":[%.9f,%.9f,%.9f],\"velocityPackets\":%d,\"lastNonzeroVelocity\":[%.9f,%.9f,%.9f],\"nonzeroVelocityPackets\":%d,\"walkTicksRemaining\":%d,\"carrierTitle\":\"%s\",\"carrierPeerIndex\":%d,\"motionModel\":\"%s\"}",
    client.isConnected(),joined,positionKnown,ownPeerIndex,x,y,z,yaw,pitch,nativeX,nativeY,nativeZ,nativeTeleportPackets,velocityX,velocityY,velocityZ,velocityPackets,lastNonzeroVelocityX,lastNonzeroVelocityY,lastNonzeroVelocityZ,nonzeroVelocityPackets,walkTicksRemaining,carrierTitle,carrierPeerIndex,flatFloorMotion?"FLAT_FLOOR_APPROXIMATION":"NONE"));
  }
 }

 private static AuthDialog classifyAuthDialog(NbtMap dialog) {
  Set<String> actions=new HashSet<>();collectValuesForKey(dialog,"id",actions);
  Set<String> inputKeys=new HashSet<>();collectDialogInputKeys(dialog,false,inputKeys);
  if(actions.contains(AUTHME_LOGIN_SUBMIT.asString())&&inputKeys.equals(Set.of("password")))return AuthDialog.LOGIN;
  if(actions.contains(AUTHME_REGISTER_SUBMIT.asString())
   &&(inputKeys.equals(Set.of("password"))||inputKeys.equals(Set.of("password","confirm")))){
   pendingRegisterConfirmation=inputKeys.contains("confirm");
   return AuthDialog.REGISTER;
  }
  return AuthDialog.NONE;
 }

 private static void collectValuesForKey(Object value,String key,Set<String> found) {
  if(value instanceof Map<?,?> map){
   Object candidate=map.get(key);
   if(candidate instanceof String text)found.add(text);
   for(Object child:map.values())collectValuesForKey(child,key,found);
  }else if(value instanceof Iterable<?> values){
   for(Object child:values)collectValuesForKey(child,key,found);
  }
 }

 private static void collectDialogInputKeys(Object value,boolean insideInputs,Set<String> found) {
  if(value instanceof Map<?,?> map){
   boolean isInputSubtree=insideInputs;
   for(var entry:map.entrySet()){
    if("inputs".equals(entry.getKey()))collectDialogInputKeys(entry.getValue(),true,found);
    else if(isInputSubtree&&"key".equals(entry.getKey())&&entry.getValue() instanceof String text)found.add(text);
    else collectDialogInputKeys(entry.getValue(),isInputSubtree,found);
   }
  }else if(value instanceof Iterable<?> values){
   for(Object child:values)collectDialogInputKeys(child,insideInputs,found);
  }
 }

 private static void sendPreJoinAuthResponse(ClientSession client,String command) {
  AuthDialog dialog=pendingAuthDialog;
  String[] parts=command.split(" ",-1);
  if(!client.isConnected()||joined||dialog==AuthDialog.NONE)
   throw new IllegalStateException("No supported pending AuthMe pre-join dialog");
  var payload=NbtMap.builder();
  Key action;
  if(dialog==AuthDialog.REGISTER&&parts[0].equals("register")
   &&((pendingRegisterConfirmation&&parts.length==3)||(!pendingRegisterConfirmation&&parts.length==2))
   &&!parts[1].isEmpty()&&parts[1].length()<=100
   &&(!pendingRegisterConfirmation||(!parts[2].isEmpty()&&parts[2].length()<=100))){
   action=AUTHME_REGISTER_SUBMIT;
   payload.putString("password",parts[1]);
   if(pendingRegisterConfirmation){payload.putString("confirm",parts[2]);}
  }else if(dialog==AuthDialog.LOGIN&&parts.length==2&&parts[0].equals("login")
   &&!parts[1].isEmpty()&&parts[1].length()<=100){
   action=AUTHME_LOGIN_SUBMIT;
   payload.putString("password",parts[1]);
  }else{
   throw new IllegalArgumentException("Auth command does not match the pending supported dialog");
  }
  pendingAuthDialog=AuthDialog.NONE;
  client.send(new ServerboundCustomClickActionPacket(action,payload.build()));
  System.out.println("Explicit AuthMe pre-join form response sent; classification="+
   (action.equals(AUTHME_REGISTER_SUBMIT)?"AUTH_REGISTER_RESPONSE_SENT":"AUTH_LOGIN_RESPONSE_SENT")+"; values withheld.");
 }
 private static String plain(Component component) {
  StringBuilder value=new StringBuilder();
  if(component instanceof net.kyori.adventure.text.TextComponent text)value.append(text.content());
  for(Component child:component.children())value.append(plain(child));
  return value.toString();
 }

 private static boolean containsReadyPrompt(Component component, ClickEvent<?> inherited) {
  ClickEvent<?> click=component.style().clickEvent();
  if(click==null)click=inherited;
  if(click!=null&&click.action().equals(ClickEvent.Action.RUN_COMMAND)
   &&click.payload() instanceof ClickEvent.Payload.Text text
   &&text.value().equals("/coliseu pronto"))return true;
  for(Component child:component.children())if(containsReadyPrompt(child,click))return true;
  return false;
 }

 private static String classifyText(String text) {
  String normalized=text.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();
  if(Set.of("you have been registered!","you have successfully registered!","successfully registered!",
    "you are now registered!","registado com sucesso!","registaste-te com sucesso!")
    .contains(normalized))return "AUTH_REGISTRATION_SUCCESS";
  if(Set.of("you are now logged in!","you have successfully logged in!","successfully logged in!",
    "successful login",
    "sessão iniciada com sucesso!","iniciaste sessão com sucesso!")
    .contains(normalized))return "AUTH_LOGIN_SUCCESS";
  if(normalized.equals("o combate começou."))return "ARENA_COMBAT_STARTED";
  if(normalized.equals("wrong password")||normalized.equals("wrong password!"))return "AUTH_PASSWORD_REJECTED";
  if(normalized.equals("esse comando não está disponível durante um minijogo."))return "CIAAC_COMMAND_BLOCKED";
  if(normalized.equals("not allowed")||normalized.equals("permission denied"))return "COMMAND_DENIED";
  return "OTHER";
 }
}
