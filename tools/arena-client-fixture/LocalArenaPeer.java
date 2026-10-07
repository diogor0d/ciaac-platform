import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.network.*;
import org.geysermc.mcprotocollib.network.event.session.*;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.*;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.*;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundShowDialogConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundClearDialogPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket;

/** Synthetic stationary peer; hard-bound to the disposable loopback Paper fixture. */
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
      yaw=pos.getYRot()+(relative.contains(PositionElement.Y_ROT)?yaw:0);pitch=pos.getXRot()+(relative.contains(PositionElement.X_ROT)?pitch:0);
      if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalStateException("Invalid native teleport");
      session.send(new ServerboundAcceptTeleportationPacket(pos.getId()));
      session.send(new ServerboundMovePlayerPosRotPacket(false,false,x,y,z,yaw,pitch));
      positionKnown=true;
     }
     session.send(ServerboundPlayerLoadedPacket.INSTANCE);
     System.out.println("Native teleport acknowledged.");
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
  expiry.scheduleAtFixedRate(()->{if(client.isConnected()&&joined)client.send(ServerboundClientTickEndPacket.INSTANCE);},50,50,TimeUnit.MILLISECONDS);
  try(var reader=new BufferedReader(new InputStreamReader(System.in))){
   client.connect();
   String command;
   while((command=reader.readLine())!=null){
    if(command.equals("quit"))break;
    if(command.equals("status")){System.out.println("nativeConnected="+client.isConnected()+" gameJoined="+joined+" positionKnown="+positionKnown);continue;}
    if(command.length()>256||command.codePoints().anyMatch(Character::isISOControl))
     throw new IllegalArgumentException("Fixture input exceeds the safe command format");
    if(!joined&&(command.startsWith("register ")||command.equals("register")||command.startsWith("login ")||command.equals("login"))){
     sendPreJoinAuthResponse(client,command);
     continue;
    }
    if(!command.matches("(?:register|login|coliseu|minijogos|passaporte)(?: .*)?"))throw new IllegalArgumentException("Only explicit fixture auth/game commands are allowed");
    if(!client.isConnected()||!joined)throw new IllegalStateException("Peer has not reached the native game state");
    client.send(new ServerboundChatCommandPacket(command));
    System.out.println("Explicit fixture command sent; arguments withheld.");
   }
  }finally{client.disconnect(Component.text("Local fixture complete"));expiry.shutdownNow();}
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
