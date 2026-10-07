import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.*;
public class LocalArenaPeerActionsCheck {
 static Class<?> peer=LocalArenaPeer.class;
 static int checks;
 static void check(boolean ok){if(!ok)throw new AssertionError();checks++;}
 static void field(String name,Object value)throws Exception{Field f=peer.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
 static Object get(String name)throws Exception{Field f=peer.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 static Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=peer.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}
 static void rejected(String name,Class<?>[]types,Object...args)throws Exception{try{call(name,types,args);throw new AssertionError();}catch(InvocationTargetException e){check(e.getCause() instanceof IllegalArgumentException||e.getCause() instanceof IllegalStateException);}}
 public static void main(String[]args)throws Exception{
  for(int n=1;n<=5;n++){String user="CiaacArenaPeer"+(n==1?"":n);UUID id=UUID.nameUUIDFromBytes(("OfflinePlayer:"+user).getBytes(StandardCharsets.UTF_8));check(call("fixturePeerIndex",new Class[]{UUID.class},id).equals(n));}
  check(call("fixturePeerIndex",new Class[]{UUID.class},UUID.randomUUID()).equals(0));
  List<Object> packets=new ArrayList<>();ClientSession client=(ClientSession)Proxy.newProxyInstance(ClientSession.class.getClassLoader(),new Class[]{ClientSession.class},(self,m,a)->{if(m.getName().equals("isConnected"))return true;if(m.getName().equals("send")){packets.add(a[0]);return null;}return null;});
  field("joined",true);field("positionKnown",true);field("ownPeerIndex",1);
  Class<?> entity=Class.forName("LocalArenaPeer$FixtureEntity");Constructor<?> c=entity.getDeclaredConstructors()[0];c.setAccessible(true);
  ((Map)get("fixtureEntities")).put(2,c.newInstance(42,1.0,0.0,0.0));
  call("sendFixtureAction",new Class[]{ClientSession.class,String.class},client,"attack 2");
  check(packets.size()==3);check(packets.get(1) instanceof ServerboundSwingPacket);check(packets.get(2) instanceof ServerboundAttackPacket&&((ServerboundAttackPacket)packets.get(2)).getEntityId()==42);
  field("lastActionNanos",0L);packets.clear();call("sendFixtureAction",new Class[]{ClientSession.class,String.class},client,"interact 2");
  check(packets.size()==2);check(packets.get(1) instanceof ServerboundInteractPacket&&((ServerboundInteractPacket)packets.get(1)).getEntityId()==42);
  ServerboundInteractPacket interaction=(ServerboundInteractPacket)packets.get(1);ByteBuf encoded=Unpooled.buffer();interaction.serialize(encoded);
  ServerboundInteractPacket decoded=new ServerboundInteractPacket(encoded);
  check(decoded.getEntityId()==42&&decoded.getHand()==interaction.getHand()&&decoded.getLocation().equals(Vector3d.from(0,1,0)));
  encoded.release();
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
  check(packets.getLast() instanceof ServerboundPlayerInputPacket);check(packets.stream().anyMatch(p->p instanceof ServerboundMovePlayerPosPacket));
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
  call("classifyCarrierTitle",new Class[]{String.class},"A batata é tua!");check(get("carrierTitle").equals("HOT_POTATO_SELF_CARRIER"));check(get("carrierPeerIndex").equals(1));
  call("classifyCarrierTitle",new Class[]{String.class},"CiaacArenaPeer2 tem a batata");check(get("carrierPeerIndex").equals(2));
  call("classifyCarrierTitle",new Class[]{String.class},"PrivateUnknownPlayer tem a batata");check(get("carrierTitle").equals("OTHER"));
  call("printStatus",new Class[]{ClientSession.class},client);
  System.out.println("OFFLINE_PACKET_CHECKS="+checks);
 }
}
