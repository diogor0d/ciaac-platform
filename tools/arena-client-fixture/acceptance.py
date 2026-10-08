import argparse, os, sys, json, time, subprocess, secrets, sqlite3, re, hashlib, math, uuid
from pathlib import Path

parser = argparse.ArgumentParser(description="Acceptance on the owned synthetic loopback fixture only")
parser.add_argument("--authorized-loopback-fixture", action="store_true", required=True)
parser.add_argument("--java", type=Path, required=True)
parser.add_argument("--private-output", type=Path, required=True)
parser.add_argument("--expected-plugin-sha256", required=True)
parser.add_argument("--case", choices=["all", "sumo", "potato", "parkour", "parkour-edges", "archery", "archery-bands", "setup-worlds", "setup-build-battle", "setup-repeat", "build-battle", "build-battle-isolation", "crash-build-battle", "setup-color-floor", "color-floor", "setup-anvil", "anvil", "anvil-edges", "crash-anvil", "crash-sumo", "crash-potato", "crash-parkour", "crash-archery", "crash-archery-launched", "crash-color-floor", "recover-only"], default="all")
args = parser.parse_args()
os.umask(0o077)
ROOT = Path("/private/tmp/ciaac-paper-local-test")
assert ROOT.is_dir() and not ROOT.is_symlink() and ROOT.stat().st_uid == os.getuid()
assert ROOT.stat().st_mode & 0o077 == 0, "Fixture must be private"
OUT = args.private_output.resolve()
OUT.mkdir(parents=True, exist_ok=True, mode=0o700)
assert not args.private_output.is_symlink() and OUT.stat().st_mode & 0o077 == 0
assert OUT.stat().st_uid == os.getuid()
assert OUT.is_relative_to(Path('/private/tmp')), 'Evidence stays in private temporary storage'
assert "eula=true" in (ROOT / "eula.txt").read_text(), "Existing EULA acceptance required"
assert hashlib.sha256((ROOT / "plugins/CIAACPlatform.jar").read_bytes()).hexdigest() == args.expected_plugin_sha256
assert hashlib.sha256((ROOT / "paper.jar").read_bytes()).hexdigest() == "defe82c1c89067186895de34cf32983e9f5a2ea387cfe7597c020faebb98ca16"
color_floor=json.loads((ROOT/'plugins/CIAACPlatform/config.yml').read_text())['modules']['color-floor']['regions']['floor']
assert color_floor['min']==[80,79,0] and color_floor['max'] in ([91,79,7],[143,79,63]), 'Only reviewed synthetic floors are allowed'
COLOR_MAX_X,COLOR_MAX_Z=color_floor['max'][0],color_floor['max'][2]
JAVA = args.java.resolve()
CLIENT = Path(__file__).resolve().parents[2] / "target/local-arena-client"
CP = str(CLIENT) + os.pathsep + (CLIENT / "classpath.txt").read_text().strip()
NAMES={i:'CiaacArenaPeer'+('' if i==1 else str(i)) for i in [1,2,3]}
server=None; peers={}; handles=[];phase='setup'
PW=OUT/'synthetic-credentials.private.json'
passwords=json.loads(PW.read_text()) if PW.exists() else {str(i):secrets.token_hex(12) for i in NAMES}
PW.write_text(json.dumps(passwords));PW.chmod(0o600)
def fields(text):
    assert text.startswith('{') and text.endswith('}')
    parts=[];start=1;depth=0;quote=None;escape=False
    for i,ch in enumerate(text[1:-1],1):
        if quote:
            if escape:escape=False
            elif ch=="\\":escape=True
            elif ch==quote:quote=None
        elif ch in ('"',"'"):quote=ch
        elif ch in '{[':depth+=1
        elif ch in '}]':depth-=1
        elif ch==',' and depth==0:parts.append(text[start:i]);start=i+1
    assert depth==0 and quote is None
    parts.append(text[start:-1])
    result={}
    for part in parts:
        key,value=part.split(':',1);key=key.strip().strip('"');assert key not in result
        result[key]=value.strip()
    return result
FIELDS=('Inventory','EnderItems','equipment','XpLevel','XpP','XpTotal','playerGameType','SelectedItemSlot','Health','foodLevel','foodSaturationLevel','foodExhaustionLevel','abilities','Pos','Rotation','Dimension','WorldUUIDMost','WorldUUIDLeast')
def emit(v):print(json.dumps(v),flush=True)
def wait(pred,seconds=15,label='condition'):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  if pred():return
  time.sleep(.1)
 raise AssertionError('Timed out: '+label)
def textlog():return re.sub(r'\x1b\[[0-9;]*m','',(ROOT/'server.private.log').read_text(errors='replace'))
def console(cmd):
 assert server and server.poll() is None and '\n' not in cmd
 server.stdin.write(cmd+'\n');server.stdin.flush()
def native(i,cmd):
 assert i in peers and peers[i].poll() is None
 peers[i].stdin.write(cmd+'\n');peers[i].stdin.flush()
def peertext(i):return (ROOT/f'peer{i}.private.log').read_text(errors='replace')
def db():
 with sqlite3.connect('file:'+str(ROOT/'plugins/CIAACPlatform/platform.sqlite')+'?mode=ro',uri=True) as c:
  return {t:dict(c.execute('select '+col+',count(*) from '+t+' group by '+col)) for t,col in [('mg_session','phase'),('mg_snapshot','state')]}
def active_count():return db()['mg_session'].get('ACTIVE',0)
def active_match_id(game,indices):
 expected={str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+NAMES[i]).encode()).digest(),version=3)) for i in indices}
 with sqlite3.connect('file:'+str(ROOT/'plugins/CIAACPlatform/platform.sqlite')+'?mode=ro',uri=True) as c:
  rows=c.execute("select match_id,player_id from mg_session where game_id=? and phase='ACTIVE'",(game,)).fetchall()
 assert len(rows)==len(expected) and {player for _,player in rows}==expected,'Active participants differ from the current scenario'
 matches={match for match,_ in rows}
 assert len(matches)==1,'Scenario participants do not share one active match'
 return str(uuid.UUID(next(iter(matches))))
def exact_match_result(game,match):
 with sqlite3.connect('file:'+str(ROOT/'plugins/CIAACPlatform/platform.sqlite')+'?mode=ro',uri=True) as c:
  rows=c.execute('select game_id,outcome,reason_code from mg_match_result where match_id=?',(match,)).fetchall()
 assert len(rows)==1 and rows[0][0]==game,'Current scenario has no unique result for its exact match'
 return rows[0]
def closed():
 d=db();return not any(n for p,n in d['mg_session'].items() if p!='CLOSED') and not any(n for p,n in d['mg_snapshot'].items() if p!='RESTORED')
def start():
 global server
 listener=subprocess.run(['lsof','-nP','-iTCP:25567','-sTCP:LISTEN'],capture_output=True,text=True)
 assert not listener.stdout.strip(), 'An existing listener must not be interrupted'
 props=(ROOT/'server.properties').read_text();assert 'server-ip=127.0.0.1' in props and 'server-port=25567' in props and 'level-name=ciaac-synthetic-test' in props
 h=(ROOT/'server.private.log').open('w');handles.append(h)
 server=subprocess.Popen([str(JAVA),'-Xms512M','-Xmx1536M','-jar','paper.jar','--nogui'],cwd=ROOT,stdin=subprocess.PIPE,stdout=h,stderr=subprocess.STDOUT,text=True)
 wait(lambda:'Done (' in textlog(),45,'Paper startup');assert 'AUTHME_COMPLETION_PROFILE_READY' in textlog()
 emit({'checkpoint':'startup','reviewed_authme_ready':True,'loopback_only':True})
def multiverse_listing():
 n=len(textlog().splitlines());console('mv list')
 wait(lambda:any(re.search(r'INFO\]: ciaac-synthetic-test - NORMAL\s*$',line) for line in textlog().splitlines()[n:]),8,'Multiverse world list')
 names=set()
 for line in textlog().splitlines()[n:]:
  match=re.search(r'INFO\]: (ciaac-(?:synthetic-test|build-test|elytra-test)) - NORMAL\s*$',line)
  if match:names.add(match.group(1))
 return names
def ensure_fixture_worlds():
 for name in ['ciaac-build-test','ciaac-elytra-test']:
  names=multiverse_listing()
  if name in names:
   emit({'checkpoint':'fixture-world-present','world':name,'reused_existing':True})
   continue
  console('mv create '+name+' normal --world-type flat')
  deadline=time.monotonic()+30
  while time.monotonic()<deadline:
   names=multiverse_listing()
   if name in names:break
   time.sleep(.25)
  else:raise AssertionError('Multiverse did not list the created owned fixture world '+name)
  emit({'checkpoint':'fixture-world-created','world':name,'environment':'normal','world_type':'flat'})
 # Multiverse enforces the destination mode one tick after a world change.
 # Keep enforcement enabled and configure the synthetic game worlds themselves.
 for name in ['ciaac-build-test','ciaac-elytra-test']:
  n=len(textlog().splitlines())
  console('mv modify minecraft:'+name+' set gamemode adventure')
  wait(lambda:any("Successfully set 'gamemode' to 'ADVENTURE' in world "+name+'.' in line
                  for line in textlog().splitlines()[n:]),8,'synthetic world Adventure mode')
  emit({'checkpoint':'fixture-world-mode','world':name,'gamemode':'adventure','global_enforcement_preserved':True})
def auth_hash(i):
 with sqlite3.connect(ROOT/'plugins/AuthMe/authme.db') as c:
  row=c.execute('select password from authme where username=?',(NAMES[i].lower(),)).fetchone()
  return row[0] if row else None
def connect(i,initial=False):
 if initial:
  old=auth_hash(i)
  if old is not None:
   console('authme changepassword '+NAMES[i]+' '+passwords[str(i)])
   wait(lambda:auth_hash(i)!=old,10,'synthetic account reset committed before connection')
 n=len(textlog().splitlines())
 h=(ROOT/f'peer{i}.private.log').open('w');handles.append(h)
 args=[str(JAVA),'-cp',CP,'LocalArenaPeer','--authorized-loopback-fixture']+(['--fixture-peer',str(i)] if i!=1 else [])
 peers[i]=subprocess.Popen(args,cwd=ROOT,stdin=subprocess.PIPE,stdout=h,stderr=subprocess.STDOUT,text=True)
 wait(lambda:'AUTH_LOGIN_FORM_READY' in peertext(i) or 'AUTH_REGISTER_FORM_READY' in peertext(i),15,'normal authentication dialog')
 if 'AUTH_REGISTER_FORM_READY' in peertext(i):
  native(i,'register '+passwords[str(i)]+' '+passwords[str(i)])
  wait(lambda:'Peer joined native GAME' in peertext(i),15,'registration')
  native(i,'login '+passwords[str(i)])
 else:
  native(i,'login '+passwords[str(i)])
 wait(lambda:'Peer joined native GAME' in peertext(i) and 'Native teleport acknowledged' in peertext(i),15,'normal login into GAME')
 native(i,'login '+passwords[str(i)])
 wait(lambda:any('[AuthMe] '+NAMES[i]+' logged in ' in l for l in textlog().splitlines()[n:]),10,'provider normal authenticated login')
 assert peers[i].poll() is None
 time.sleep(.7)
 emit({'checkpoint':'authenticated','synthetic_peer':i,'same_password_reconnect':not initial,'secret_exported':False})
def disconnect(i):
 native(i,'quit');peers[i].wait(timeout=10);del peers[i];time.sleep(.3)
def peerstatus(i):
 n=len(peertext(i).splitlines());native(i,'status')
 wait(lambda:any(l.startswith('{"fixtureStatus"') and l.endswith('}') for l in peertext(i).splitlines()[n:]),3,'native status')
 return json.loads([l for l in peertext(i).splitlines()[n:] if l.startswith('{"fixtureStatus"') and l.endswith('}')][-1])
def snapshot(label,actors=None):
 actors=list(peers) if actors is None else actors
 startlen=len(textlog().splitlines())
 for i in actors:console('minecraft:data get entity '+NAMES[i])
 wait(lambda:all(any(NAMES[i]+' has the following entity data:' in l for l in textlog().splitlines()[startlen:]) for i in actors),5,'authoritative native NBT')
 results={}
 for i in actors:
  body=[l for l in textlog().splitlines()[startlen:] if NAMES[i]+' has the following entity data:' in l][-1].split('has the following entity data:',1)[1].strip()
  (OUT/(label+f'-peer{i}.private.snbt')).write_text(body)
  results[i]=fields(body)
 return results
def compare(base,now):
 checks={i:{k:base[i].get(k,'ABSENT')==now[i].get(k,'ABSENT') for k in FIELDS} for i in base}
 return {'fields':len(FIELDS),'all_equal':all(all(v.values()) for v in checks.values()),'checks':checks}
def seed():
 for i,name in NAMES.items():
  console('minecraft:tp '+name+f' -5.5 80 {2.5+2*i} 270 0')
  console('minecraft:item replace entity '+name+' hotbar.0 with minecraft:oak_log 32')
  console('minecraft:item replace entity '+name+' hotbar.1 with minecraft:ender_pearl 2')
  console('minecraft:item replace entity '+name+' armor.chest with minecraft:elytra[minecraft:damage=9]')
  console('minecraft:item replace entity '+name+' weapon.offhand with minecraft:shield[minecraft:damage=4]')
  console('minecraft:experience set '+name+' 7 levels');console('minecraft:experience set '+name+' 4 points')
 time.sleep(.7)
 baseline=snapshot('baseline')
 assert all('oak_log' in v['Inventory'] and 'elytra' in v['equipment'] for v in baseline.values())
 return baseline
def join_sumo():
 native(1,'sumo entrar');time.sleep(.4);native(2,'sumo entrar')
 wait(lambda:active_count()==2,8,'two Sumo ACTIVE sessions');time.sleep(.3)
 return snapshot('sumo-active',[1,2])
def join_potato():
 for i in [1,2,3]:native(i,'batataquente entrar');time.sleep(.2)
 emit({'checkpoint':'hot-potato-countdown','players':3})
 wait(lambda:active_count()==3,28,'three Hot Potato ACTIVE sessions');time.sleep(.3)
 return snapshot('potato-active')
def finish_check(name,baseline):
 wait(closed,15,'all sessions/snapshots restored')
 prefixes={'sumo-':'knockback-sumo','potato-':'hot-potato','parkour-':'checkpoint-parkour',
           'archery-':'archery-range','build-battle-':'build-battle','anvil-':'anvil-dodge','color-floor-':'color-floor'}
 game=next((value for prefix,value in prefixes.items() if name.startswith(prefix)),None)
 assert game is not None,'Scenario must identify its game for exact result verification'
 matches=set()
 with sqlite3.connect('file:'+str(ROOT/'plugins/CIAACPlatform/platform.sqlite')+'?mode=ro',uri=True) as c:
  for i in baseline:
   player=str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+NAMES[i]).encode()).digest(),version=3))
   row=c.execute('select match_id from mg_session where game_id=? and player_id=? order by created_at desc limit 1',(game,player)).fetchone()
   assert row is not None,'Scenario participant has no session for the specified game'
   matches.add(row[0])
 def scenario_results():
  with sqlite3.connect('file:'+str(ROOT/'plugins/CIAACPlatform/platform.sqlite')+'?mode=ro',uri=True) as c:
   return [c.execute('select game_id,outcome,reason_code from mg_match_result where match_id=?',(match,)).fetchone() for match in sorted(matches)]
 if not name.endswith('-crash'):
  wait(lambda:all(row is not None for row in scenario_results()),5,'durable results for every exact restored scenario match')
 results=scenario_results()
 assert all(row is None or row[0]==game for row in results),'Result game differs from its participant session'
 if name.endswith('-crash'):
  assert all(row is None or row[1]=='NO_CONTEST' for row in results),'Cold recovery must not fabricate a ranked result'
  result=None
 else:
  assert len(set(results))==1,'Concurrent scenario matches produced inconsistent terminal outcomes'
  result=results[0]
 if name=='build-battle-disconnect':assert result==('build-battle','NO_CONTEST','DISCONNECT'),str(result)
 if name in ['sumo-victory','sumo-knockback-victory','potato-victory']:assert result and result[1]=='VICTORY',str(result)
 if name=='sumo-timeout':assert result and result[1:] == ('DRAW','ROUND_TIMEOUT'),str(result)
 if name=='color-floor-native-completion':assert result==('color-floor','VICTORY','COMPLETED'),str(result)
 if name in ['sumo-disconnect','potato-disconnect']:assert result and result[1]=='NO_CONTEST',str(result)
 if name.endswith('-crash'):result=None
 emit({'checkpoint':'durable-result','scenario':name,'result':result})
 state=snapshot(name+'-restored',list(baseline));cmp=compare(baseline,state)
 (OUT/(name+'-evidence.json')).write_text(json.dumps({'scenario':name,'comparison':cmp,'db':db()},indent=2)+'\n')
 assert cmp['all_equal'],json.dumps({'native_state_mismatch':cmp['checks']})
 emit({'checkpoint':name,'native_state_fields':len(FIELDS),'exact_restoration':True,'db':db()})
def run_sumo(base):
 active=join_sumo();assert all('stick' in v['Inventory'] and v['playerGameType']=='2' for v in active.values())
 native(3,'sumo entrar');console('minecraft:tp '+NAMES[3]+' 4.5 80 4.5');time.sleep(.3)
 assert active_count()==2 and compare({3:base[3]},snapshot('sumo-outsider',[3]))['all_equal']
 emit({'checkpoint':'sumo-outsider-denial','unauthorized_region_entry_blocked':True})
 # Ordinary native input with bounded flat-floor walking; no event injection.
 for roundno in [1,2]:
  native(2,'flat-floor-motion on')
  st=peerstatus(2);initial_teleports=st['nativeTeleportPackets']
  direction='east' if st['localPose'][0]>4.5 else 'west'
  native(2,'walk '+direction+' 50')
  wait(lambda:active_count()!=2 or peerstatus(2)['nativeTeleportPackets']>initial_teleports,5,'native round reset or completion')
  time.sleep(.4)
  if active_count()!=2:break
  st=peerstatus(2);assert st['lastNativeTeleport'] in [[3.5,80.0,4.5],[5.5,80.0,4.5]],'No safe native round reset'
 emit({'checkpoint':'sumo-native-ring-outs','injected_events':False})
 finish_check('sumo-victory',{i:base[i] for i in [1,2]})
 join_sumo();disconnect(2)
 wait(lambda:db()['mg_session'].get('ACTIVE',0)==1,5,'departed recovery remains pending')
 assert compare({1:base[1]},snapshot('sumo-disconnect-connected',[1]))['all_equal']
 connect(2);finish_check('sumo-disconnect',{i:base[i] for i in [1,2]})
 active=join_sumo()
 for i in [1,2]:native(i,'flat-floor-motion on')
 before=peerstatus(2);native(1,'attack 2');time.sleep(.8)
 after=peerstatus(2);state=snapshot('sumo-hit',[1,2])
 assert after['velocityPackets']>before['velocityPackets'] and any(abs(v)>.01 for v in after['lastNonzeroVelocity'])
 assert state[1]['Health']==active[1]['Health'] and state[2]['Health']==active[2]['Health']
 emit({'checkpoint':'sumo-native-hit','health_damage_blocked':True,'received_velocity':after['lastNonzeroVelocity'],'paper_position_changed':state[2]['Pos']!=active[2]['Pos']})
 # Approach through ordinary input rather than exceeding the fixture's attack range.
 for attempt in range(8):
  if active_count()!=2:break
  for i in [1,2]:native(i,'flat-floor-motion on 80')
  attacker=peerstatus(1)['localPose'];target=peerstatus(2)['localPose']
  if abs(target[0]-attacker[0])>2.5:
   direction='east' if target[0]>attacker[0] else 'west'
   native(1,'walk '+direction+' 10');time.sleep(.55)
  if active_count()!=2:break
  native(1,'attack 2');time.sleep(.8)
 finish_check('sumo-knockback-victory',{i:base[i] for i in [1,2]})
 join_sumo()
 emit({'checkpoint':'sumo-waiting-for-round-timeout'});wait(closed,32,'sumo timeout')
 finish_check('sumo-timeout',{i:base[i] for i in [1,2]})
def run_potato(base):
 for i in [1,2,3]:native(i,'batataquente entrar');time.sleep(.2)
 time.sleep(.5);native(3,'batataquente sair');time.sleep(.3)
 assert active_count()==0
 assert compare(base,snapshot('potato-countdown-leave'))['all_equal']
 native(1,'batataquente sair');native(2,'batataquente sair');time.sleep(.4)
 emit({'checkpoint':'hot-potato-countdown-leave','survival_state_unchanged':True})
 active=join_potato();assert all(v['Dimension']=='"minecraft:ciaac-potato-test"' for v in active.values())
 console('minecraft:tp '+NAMES[3]+' 35.5 80 4.5');time.sleep(.3)
 state=snapshot('potato-boundary-denial')
 assert all(state[i]['Pos']==active[i]['Pos'] for i in [1,2,3])
 emit({'checkpoint':'hot-potato-boundary-denial','external_teleport_blocked':True})
 ss={i:peerstatus(i) for i in [1,2,3]};carrier=next(i for i,s in ss.items() if s['carrierTitle']=='HOT_POTATO_SELF_CARRIER');target=next(i for i in [1,2,3] if i!=carrier)
 native(carrier,'interact '+str(target));time.sleep(.4)
 assert peerstatus(target)['carrierTitle']=='HOT_POTATO_SELF_CARRIER'
 emit({'checkpoint':'hot-potato-native-pass','carrier_before':carrier,'carrier_after':target,'injected_events':False})
 native(target,'interact '+str(carrier));time.sleep(.2)
 assert peerstatus(target)['carrierTitle']=='HOT_POTATO_SELF_CARRIER','Cooldown did not reject immediate pass-back'
 emit({'checkpoint':'hot-potato-cooldown-denial','carrier_unchanged':True})
 time.sleep(.6);native(target,'interact '+str(carrier));time.sleep(.4)
 assert peerstatus(carrier)['carrierTitle']=='HOT_POTATO_SELF_CARRIER'
 emit({'checkpoint':'hot-potato-pass-back','normal_native_interaction':True})
 wait(lambda:any(s['playerGameType']=='3' for s in snapshot('potato-elimination').values()),20,'fuse spectator elimination')
 emit({'checkpoint':'hot-potato-fuse-elimination','spectator_observed':True})
 finish_check('potato-victory',base)
 join_potato();disconnect(3);assert not closed();connect(3);finish_check('potato-disconnect',base)
 native(3,'batataquente entrar');time.sleep(.2);disconnect(3);connect(3)
 native(1,'batataquente entrar');native(2,'batataquente entrar');time.sleep(.3);native(1,'batataquente estado');native(2,'batataquente sair');native(1,'batataquente sair')
 wait(closed,3,'queue disconnect/leave no sessions');emit({'checkpoint':'hot-potato-queue-disconnect','no_mutated_sessions':True})
def run_crash(mode, base, launched=False):
 arrow_id=None
 anvil_ids=[]
 if mode == 'sumo':join_sumo()
 elif mode == 'potato':join_potato()
 elif mode == 'parkour':join_parkour()
 elif mode == 'archery':
  join_archery()
  if launched:
   native(1,'look 270 -89');native(1,'bow draw');time.sleep(1.2);native(1,'bow release')
   def confirmed_arrow():
    with sqlite3.connect('file:'+str(ROOT/'plugins/CIAACPlatform/arena-world/arena-world.sqlite')+'?mode=ro',uri=True) as c:
     return c.execute("select entity_id from arena_entity where status='CONFIRMED' order by entity_id").fetchall()
   wait(lambda:len(confirmed_arrow())==1,3,'one durably confirmed native arrow')
   arrow_id=confirmed_arrow()[0][0]
   n=len(textlog().splitlines())
   console('minecraft:execute as '+arrow_id+' if entity @s[type=minecraft:arrow] run minecraft:say CIAAC_FIXTURE_ARROW_ALIVE')
   wait(lambda:any('CIAAC_FIXTURE_ARROW_ALIVE' in l for l in textlog().splitlines()[n:]),2,'exact arrow alive before crash')
   emit({'checkpoint':'archery-native-arrow-before-crash','durable_claim':'CONFIRMED','real_bow_input':True})
 elif mode=='anvil':
  join_anvil();anvil_ids=observe_anvil_marker()
 elif mode=='build-battle':
  join_buildbattle();cell=place_and_break_buildbattle_block(1)
  emit({'checkpoint':'build-battle-native-mutated-before-crash','cell':cell,'gameplay_packet':True})
 else:
  # Establish an all-AIR reference using a native result count before the match starts.
  reference_cells=(COLOR_MAX_X-79)*(COLOR_MAX_Z+1)
  console('minecraft:fill 80 120 0 '+str(COLOR_MAX_X)+' 120 '+str(COLOR_MAX_Z)+' minecraft:stone')
  time.sleep(.15)
  n=len(textlog().splitlines())
  console('minecraft:execute store result storage ciaac_fixture:color reference_air_count int 1 run minecraft:fill 80 120 0 '+str(COLOR_MAX_X)+' 120 '+str(COLOR_MAX_Z)+' minecraft:air')
  console('minecraft:data get storage ciaac_fixture:color reference_air_count')
  wait(lambda:any('Storage ciaac_fixture:color has the following contents: '+str(reference_cells) in l for l in textlog().splitlines()[n:]),4,'exact native AIR reference count')
  join_color_floor()
  # Crash only after native block queries prove that the game removed real cells.
  n=len(textlog().splitlines());deadline=time.monotonic()+8
  while time.monotonic()<deadline:
   for color,min_x,max_x in [('RED',80,85),('BLUE',86,COLOR_MAX_X)]:
    console('minecraft:execute if blocks '+str(min_x)+' 79 0 '+str(max_x)+' 79 '+str(COLOR_MAX_Z)+' '+str(min_x)+' 120 0 all run minecraft:say CIAAC_FIXTURE_COLOR_AIR_'+color)
   time.sleep(.08)
   if any('CIAAC_FIXTURE_COLOR_AIR_' in l for l in textlog().splitlines()[n:]):break
  else:raise AssertionError('No native temporary Color Floor AIR observed before crash')
  removed_color=re.search(r'CIAAC_FIXTURE_COLOR_AIR_(RED|BLUE)','\n'.join(textlog().splitlines()[n:])).group(1)
  removed_cells=(6 if removed_color=='RED' else COLOR_MAX_X-85)*(COLOR_MAX_Z+1)
  emit({'checkpoint':'color-floor-native-air-before-crash','native_removed_cells':removed_cells,'world_repaired_by_test':False})
 n=len(textlog().splitlines());console('save-all flush')
 wait(lambda:any('Saved the game' in l for l in textlog().splitlines()[n:]),10,'native player save')
 server.kill();server.wait(timeout=10)
 for i,p in list(peers.items()):
  p.stdin.write('quit\n');p.stdin.flush();p.wait(timeout=10);del peers[i]
 emit({'checkpoint':'owned-local-crash','mode':mode,'pending':db()})
 assert active_count()==({'sumo':2,'potato':3,'parkour':1,'archery':1,'color-floor':2,'anvil':2,'build-battle':2}[mode])
 start()
 for i in [1,2,3]:connect(i)
 actors={'sumo':[1,2],'potato':[1,2,3],'parkour':[1],'archery':[1],'color-floor':[1,2],'anvil':[1,2],'build-battle':[1,2]}[mode]
 finish_check(mode+('-launched' if launched else '')+'-authenticated-crash',{i:base[i] for i in actors})
 if mode=='build-battle':verify_buildbattle_world('crash')
 if anvil_ids:
  verify_anvil_cleanup(anvil_ids);verify_anvil_floor()
 if arrow_id is not None:
  with sqlite3.connect('file:'+str(ROOT/'plugins/CIAACPlatform/arena-world/arena-world.sqlite')+'?mode=ro',uri=True) as c:
   assert c.execute('select status from arena_entity where entity_id=?',(arrow_id,)).fetchone()==('REMOVED',)
  n=len(textlog().splitlines())
  console('minecraft:execute unless entity '+arrow_id+' run minecraft:say CIAAC_FIXTURE_ARROW_ABSENT')
  wait(lambda:any('CIAAC_FIXTURE_ARROW_ABSENT' in l for l in textlog().splitlines()[n:]),2,'exact prior-process arrow absent after restore')
  emit({'checkpoint':'archery-native-arrow-crash-reconciled','durable_claim':'REMOVED','exact_native_uuid_absent':True})
def join_parkour():
 # A leave/rejoin sequence must respect the public per-action 750 ms limiter.
 time.sleep(.8)
 native(1,'parkour entrar')
 wait(lambda:active_count()==1,8,'one Parkour ACTIVE session')
 state=snapshot('parkour-active',[1])
 assert 'feather' in state[1]['Inventory'] and state[1]['playerGameType']=='2'
 return state
def run_parkour(base):
 join_parkour()
 blocked_before=peertext(1).count('classification=CIAAC_COMMAND_BLOCKED')
 native(1,'parkour entrar');native(1,'parkour entrar')
 wait(lambda:peertext(1).count('classification=CIAAC_COMMAND_BLOCKED')>=blocked_before+2,3,
      'native active-session reentry rejection')
 assert active_count()==1,'Repeated entry changed the active Parkour session count'
 emit({'checkpoint':'parkour-native-active-reentry-denial','repeated_entry_rejected':True,'session_count_unchanged':True})
 native(1,'flat-floor-motion on');native(1,'walk east 60')
 wait(closed,12,'native ordered-checkpoint completion')
 with sqlite3.connect(ROOT/'plugins/CIAACPlatform/platform.sqlite') as c:
  result=c.execute('select game_id,outcome,reason_code from mg_match_result order by finished_at desc limit 1').fetchone()
 assert result==('checkpoint-parkour','VICTORY','COMPLETED'),str(result)
 finish_check('parkour-native-completion',{1:base[1]})
 join_parkour();native(1,'parkour sair');finish_check('parkour-leave',{1:base[1]})
 join_parkour();disconnect(1);connect(1);finish_check('parkour-disconnect',{1:base[1]})
def parkour_limits():
 config=json.loads((ROOT/'plugins/CIAACPlatform/config.yml').read_text())['modules']['checkpoint-parkour']
 return int(config['run-timeout-seconds']),int(config['concurrent-runners'])
def run_parkour_edges(base):
 timeout,capacity=parkour_limits()
 assert 1<=timeout<=120,'Parkour edge case timeout exceeds the bounded local scenario limit'
 join_parkour()
 if capacity>=2:
  time.sleep(.8);native(2,'parkour entrar')
  wait(lambda:active_count()==2,8,'second native Parkour run within configured capacity')
  active=snapshot('parkour-concurrent-active',[1,2])
  assert all('feather' in active[i]['Inventory'] and active[i]['playerGameType']=='2' for i in [1,2])
  # Active inventory/game mode are intentionally changed; both runs must
  # remain independently active until ordinary leave actions restore them.
  native(1,'parkour sair');native(2,'parkour sair')
  finish_check('parkour-concurrent-leave',{1:base[1],2:base[2]})
 else:
  native(2,'parkour entrar');time.sleep(.4)
  assert active_count()==1 and compare({2:base[2]},snapshot('parkour-capacity-denial',[2]))['all_equal']
  emit({'checkpoint':'parkour-capacity-denial','configured_capacity':capacity,'second_peer_unchanged':True})
  native(1,'parkour sair');finish_check('parkour-capacity-denial-leave',{1:base[1]})
 join_parkour()
 scenario_match=active_match_id('checkpoint-parkour',[1])
 native(1,'flat-floor-motion on 80')
 teleports_before=peerstatus(1)['nativeTeleportPackets']
 native(1,'walk west 24')
 wait(lambda:peerstatus(1)['walkTicksRemaining']==0,8,'native Parkour boundary walk correction')
 time.sleep(.3)
 boundary_state=snapshot('parkour-boundary-reset',[1])[1]
 boundary_pos=[float(value.rstrip('d')) for value in re.findall(r'-?\d+(?:\.\d+)?d?',boundary_state['Pos'])]
 assert len(boundary_pos)==3 and abs(boundary_pos[0]-41.5)<.7 and abs(boundary_pos[2]-4.5)<.7, boundary_pos
 assert active_count()==1 and 'feather' in boundary_state['Inventory'] and boundary_state['playerGameType']=='2'
 assert peerstatus(1)['nativeTeleportPackets']>teleports_before,'Parkour boundary attempt did not produce the source reset teleport'
 emit({'checkpoint':'parkour-native-boundary-reset','outside_walk_returned_to_gate':True,
       'still_active':True,'server_position':boundary_pos,'injected_teleport':False})
 native(1,'parkour sair');finish_check('parkour-boundary-leave',{1:base[1]})
 result=exact_match_result('checkpoint-parkour',scenario_match)
 assert result==('checkpoint-parkour','NO_CONTEST','PLAYER_LEFT'),str(result)
 join_parkour()
 scenario_match=active_match_id('checkpoint-parkour',[1])
 native(1,'flat-floor-motion on 80')
 def walk_observed(direction,ticks):
  native(1,'walk '+direction+' '+str(ticks))
  wait(lambda:peerstatus(1)['walkTicksRemaining']==0,8,'bounded native Parkour walk '+direction)
  return peerstatus(1)['localPose']
 def native_position(label):
  raw=snapshot(label,[1])[1]['Pos']
  values=[float(value.rstrip('d')) for value in re.findall(r'-?\d+(?:\.\d+)?d?',raw)]
  assert len(values)==3,raw
  return values
 start_pos=native_position('parkour-out-of-order-start')
 assert abs(start_pos[0]-41.5)<.6 and abs(start_pos[2]-4.5)<.6,start_pos
 local=peerstatus(1)['localPose'];steps=0
 while local[2]>=3.0 and steps<8:
  local=walk_observed('north',4);steps+=1
 pos=native_position('parkour-before-out-of-order-east')
 assert 0.5<=pos[2]<3.0 and 40.0<pos[0]<43.0,{'native':pos,'local':local}
 local=peerstatus(1)['localPose'];steps=0
 while local[0]<49.1 and steps<16:
  local=walk_observed('east',4);steps+=1
 pos=native_position('parkour-before-out-of-order-finish')
 assert 49.1<=pos[0]<=50.9 and 0.5<=pos[2]<3.0,{'native':pos,'local':local}
 rejected_before=peertext(1).count('classification=OTHER')
 walk_observed('south',12);time.sleep(.5)
 wait(lambda:peertext(1).count('classification=OTHER')>rejected_before,3,
      'native finish-before-first-checkpoint rejection feedback')
 assert active_count()==1,'Out-of-order finish crossing completed or terminated Parkour'
 rejected_pos=native_position('parkour-out-of-order-finish-rejected')
 assert 49.0<=rejected_pos[0]<=51.0 and rejected_pos[2]<3.0, \
        {'server_position_after_rejection':rejected_pos}
 emit({'checkpoint':'parkour-native-out-of-order-finish','finish_crossed_before_first_checkpoint':True,
       'crossing_rejected':True,'still_active':True,'server_position_readback':rejected_pos,
       'collision_or_fall_physics_claimed':False})
 native(1,'parkour sair');finish_check('parkour-out-of-order-leave',{1:base[1]})
 result=exact_match_result('checkpoint-parkour',scenario_match)
 assert result==('checkpoint-parkour','NO_CONTEST','PLAYER_LEFT'),str(result)
 join_parkour()
 scenario_match=active_match_id('checkpoint-parkour',[1])
 wait(closed,timeout+6,'Parkour configured time limit and restoration')
 result=exact_match_result('checkpoint-parkour',scenario_match)
 assert result==('checkpoint-parkour','NO_CONTEST','TIME_LIMIT'),str(result)
 finish_check('parkour-timeout',{1:base[1]})
def join_archery():
 # Exercise the public route at its documented rate; never disable its limiter.
 time.sleep(.8)
 native(1,'arco entrar 1')
 wait(lambda:active_count()==1,8,'one Archery ACTIVE lane')
 state=snapshot('archery-active',[1])
 assert 'bow' in state[1]['Inventory'] and 'arrow' in state[1]['equipment'] and state[1]['playerGameType']=='2'
 return state
def run_archery(base):
 # Removing one scoring band must close admission without preparing a session.
 console('minecraft:kill @e[type=minecraft:armor_stand,tag=ciaac-fixture-archery-outer]')
 time.sleep(.3);native(1,'arco entrar 1');time.sleep(.4)
 assert closed() and compare({1:base[1]},snapshot('archery-missing-target',[1]))['all_equal']
 emit({'checkpoint':'archery-missing-target-denial','state_unchanged':True})
 summon_archery_target('outer',9.5)
 time.sleep(.3);join_archery()
 native(2,'arco entrar 1');time.sleep(.3)
 assert active_count()==1 and compare({2:base[2]},snapshot('archery-busy-lane',[2]))['all_equal']
 emit({'checkpoint':'archery-busy-lane-denial','second_player_unchanged':True})
 for shot in [1,2]:
  native(1,'bow draw');time.sleep(1.2);native(1,'bow release');time.sleep(.7)
 wait(closed,8,'native Archery two-shot completion')
 with sqlite3.connect('file:'+str(ROOT/'plugins/CIAACPlatform/platform.sqlite')+'?mode=ro',uri=True) as c:
  result=c.execute('select game_id,outcome,reason_code from mg_match_result order by finished_at desc limit 1').fetchone()
 assert result==('archery-range','VICTORY','COMPLETED'),str(result)
 emit({'checkpoint':'archery-native-bow-completion','real_projectile_events':True,'injected_events':False})
 finish_check('archery-native-completion',{1:base[1]})
 join_archery();native(1,'arco sair');finish_check('archery-leave',{1:base[1]})
 join_archery();disconnect(1);connect(1);finish_check('archery-disconnect',{1:base[1]})
 join_archery();wait(closed,34,'Archery timeout');finish_check('archery-timeout',{1:base[1]})
def summon_archery_target(band,z):
 assert band in ['bullseye','inner','middle','outer']
 console('minecraft:summon minecraft:armor_stand 70.5 80 '+str(z)+' {NoGravity:1b,Invulnerable:1b,Tags:["ciaac-fixture-archery","ciaac-fixture-archery-'+band+'","ciaac-archery-target:target-one:'+band+'"]}')
def archery_band_config():
 config=json.loads((ROOT/'plugins/CIAACPlatform/config.yml').read_text())['modules']['archery-range']
 count=int(config['shots-per-attempt']);score_map={key:int(value) for key,value in config['target-scores'].items()}
 assert set(score_map)=={'bullseye','inner','middle','outer'} and len(set(score_map.values()))==4
 assert count==2,'The reviewed local band scenario expects two shots per attempt'
 return score_map
def run_archery_bands(base):
 score_map=archery_band_config()
 positions={'bullseye':4.5,'inner':6.5,'middle':8.5,'outer':9.5}
 for band in ['bullseye','inner','middle','outer']:
  join_archery()
  for shot in range(2):
   z=positions[band];pose=peerstatus(1)['localPose']
   dx=70.5-pose[0];dy=80.9-(pose[1]+1.62);dz=z-pose[2]
   yaw=math.degrees(math.atan2(-dx,dz));pitch=-math.degrees(math.atan2(dy,math.hypot(dx,dz)))
   native(1,'look '+str(yaw)+' '+str(pitch));time.sleep(.15)
   native(1,'bow draw');time.sleep(1.2);native(1,'bow release');time.sleep(.9)
  wait(closed,10,'native arrows score configured Archery band '+band)
  with sqlite3.connect('file:'+str(ROOT/'plugins/CIAACPlatform/platform.sqlite')+'?mode=ro',uri=True) as c:
   row=c.execute("select result_id,outcome,reason_code from mg_match_result where game_id='archery-range' order by finished_at desc limit 1").fetchone()
   assert row and row[1:]==('VICTORY','COMPLETED'),str(row)
   metrics=dict(c.execute('select metric_key,metric_value from mg_player_metric where result_id=?',(row[0],)).fetchall())
  expected_score=2*score_map[band];expected_bullseyes=2 if band=='bullseye' else 0
  assert metrics.get('shots')==2 and metrics.get('score')==expected_score and metrics.get('bullseyes')==expected_bullseyes,{'band':band,'metrics':metrics}
  emit({'checkpoint':'archery-band-score','band':band,'outcome':'VICTORY/COMPLETED',
        'shots':2,'score':expected_score,'bullseyes':expected_bullseyes,'native_bow_packets':True})
  finish_check('archery-band-'+band,{1:base[1]})
def join_color_floor():
 time.sleep(.8);native(1,'cores entrar');time.sleep(.2);native(2,'cores entrar')
 wait(lambda:active_count()==2,8,'two Color Floor ACTIVE sessions')
 native(2,'flat-floor-motion on');native(2,'walk east 28')
 return snapshot('color-floor-active',[1,2])
def verify_color_floor():
 n=len(textlog().splitlines())
 # Check every reviewed cell against its exact template material, never repair it here.
 for x in range(80,COLOR_MAX_X+1):
  material='red_concrete' if x<86 else 'blue_concrete'
  for z in range(COLOR_MAX_Z+1):
   console('minecraft:execute if block '+str(x)+' 79 '+str(z)+' minecraft:'+material+' run minecraft:say CIAAC_FIXTURE_COLOR_OK_'+str(x)+'_'+str(z))
 expected={(str(x),str(z)) for x in range(80,COLOR_MAX_X+1) for z in range(COLOR_MAX_Z+1)}
 wait(lambda:set(re.findall(r'CIAAC_FIXTURE_COLOR_OK_(\d+)_(\d+)', '\n'.join(textlog().splitlines()[n:])))==expected,20,'all native floor block comparisons')
 emit({'checkpoint':'color-floor-template-readback','exact_cells':len(expected),'world_repaired_by_test':False})
def run_color_floor(base):
 join_color_floor();wait(closed,16,'native Color Floor elimination and restore')
 finish_check('color-floor-native-completion',{i:base[i] for i in [1,2]});verify_color_floor()
 time.sleep(.8);native(1,'cores entrar');wait(lambda:active_count()==1,8,'Color Floor waiting session')
 native(1,'cores sair');finish_check('color-floor-leave',{1:base[1]});verify_color_floor()
 join_color_floor();disconnect(1);connect(1)
 finish_check('color-floor-disconnect',{i:base[i] for i in [1,2]});verify_color_floor()
def anvil_claims():
 with sqlite3.connect('file:'+str(ROOT/'plugins/CIAACPlatform/arena-world/arena-world.sqlite')+'?mode=ro',uri=True) as c:
  return c.execute("select entity_id,status from arena_entity where entity_type='ANVIL_MARKER' order by entity_id").fetchall()
def join_anvil():
 time.sleep(.8);native(1,'bigornas entrar');time.sleep(.2);native(2,'bigornas entrar')
 wait(lambda:active_count()==2,8,'two Anvil ACTIVE sessions')
 state=snapshot('anvil-active',[1,2])
 for s in state.values():
  assert s['playerGameType']=='2' and s['Inventory']=='[]'
  assert s['Pos']=='[161.5d, 80.0d, 4.5d]',s['Pos']
 return state
def observe_anvil_marker():
 wait(lambda:any(status=='CONFIRMED' for _,status in anvil_claims()),4,'confirmed native Anvil marker')
 ids=[entity for entity,status in anvil_claims() if status=='CONFIRMED']
 assert ids
 n=len(textlog().splitlines())
 for index,entity in enumerate(ids):
  console('minecraft:execute as '+entity+' if entity @s[type=minecraft:armor_stand] run minecraft:say CIAAC_FIXTURE_ANVIL_ALIVE_'+str(index))
 wait(lambda:all('CIAAC_FIXTURE_ANVIL_ALIVE_'+str(i) in '\n'.join(textlog().splitlines()[n:]) for i in range(len(ids))),2,'exact Anvil marker UUIDs alive')
 emit({'checkpoint':'anvil-native-marker-alive','durable_confirmed':len(ids),'injected_events':False})
 return ids
def verify_anvil_cleanup(ids):
 claims=dict(anvil_claims());assert all(claims.get(entity)=='REMOVED' for entity in ids)
 n=len(textlog().splitlines())
 for index,entity in enumerate(ids):
  console('minecraft:execute unless entity '+entity+' run minecraft:say CIAAC_FIXTURE_ANVIL_ABSENT_'+str(index))
 wait(lambda:all('CIAAC_FIXTURE_ANVIL_ABSENT_'+str(i) in '\n'.join(textlog().splitlines()[n:]) for i in range(len(ids))),3,'exact Anvil marker UUIDs absent')
 emit({'checkpoint':'anvil-native-marker-cleanup','durable_removed':len(ids),'exact_native_absence':True})
def verify_anvil_floor():
 n=len(textlog().splitlines())
 for x in range(160,168):
  for z in range(8):
   console('minecraft:execute if block '+str(x)+' 79 '+str(z)+' minecraft:stone run minecraft:say CIAAC_FIXTURE_ANVIL_FLOOR_'+str(x)+'_'+str(z))
 expected={(str(x),str(z)) for x in range(160,168) for z in range(8)}
 wait(lambda:set(re.findall(r'CIAAC_FIXTURE_ANVIL_FLOOR_(\d+)_(\d+)', '\n'.join(textlog().splitlines()[n:])))==expected,6,'all native Anvil floor comparisons')
 emit({'checkpoint':'anvil-native-floor-readback','unchanged_cells':64,'world_repaired_by_test':False})
BB_WORLD='ciaac-build-test'
BB_PLOTS={
 'plot-a':{'min':[0,64,0],'max':[4,67,4],'spawn':[2.5,65,2.5]},
 'plot-b':{'min':[13,64,0],'max':[17,67,4],'spawn':[15.5,65,2.5]},
}
BB_LOBBY={'min':[0,64,10],'max':[4,67,14]}
BB_GAP_SENTINEL=(8,64,2,'minecraft:gold_block')
BB_LOBBY_SENTINEL=(0,64,10,'minecraft:sea_lantern')
def buildbattle_config():
 config=json.loads((ROOT/'plugins/CIAACPlatform/config.yml').read_text())['modules']['build-battle']
 assert config['enabled'] is True and config['world']['name']==BB_WORLD
 assert config['minimum-players']==2 and config['maximum-players']==2
 assert config['queue-seconds']==3 and config['theme-vote-seconds']==3 and config['build-seconds']>=40
 assert config['voting']['seconds-per-plot']==5
 themes=[re.split('[:|]',item,1)[0].strip() for item in config['theme-pool']]
 assert len(themes)==3 and len(set(themes))==3,'Three fixed themes are required so all displayed options are addressable through the native command'
 assert config['regions']['lobby']['min']==BB_LOBBY['min'] and config['regions']['lobby']['max']==BB_LOBBY['max']
 assert set(config['plots'])==set(BB_PLOTS)
 for plot_id,expected in BB_PLOTS.items():
  actual=config['regions'][config['plots'][plot_id]['region-id']]
  assert actual['min']==expected['min'] and actual['max']==expected['max'],(plot_id,actual)
  spawn=config['plots'][plot_id]['spawn']
  assert [spawn['x'],spawn['y'],spawn['z']]==expected['spawn'],(plot_id,spawn)
 assert config['locations']['lobby']['x']==2.5 and config['locations']['lobby']['y']==65 and config['locations']['lobby']['z']==12.5
 assert config['world-template-marker']=='fixture-build-template'
 config['_fixture-theme-ids']=themes
 return config
def bbconsole(command):
 assert command.startswith('minecraft:')
 console('minecraft:execute in minecraft:'+BB_WORLD+' run '+command)
def prepare_buildbattle_chunks():
 n=len(textlog().splitlines())
 bbconsole('minecraft:forceload add 0 0 17 14')
 expected={'CIAAC_FIXTURE_BB_CHUNK_0','CIAAC_FIXTURE_BB_CHUNK_1'}
 deadline=time.monotonic()+12
 while time.monotonic()<deadline:
  for x in [0,1]:
   bbconsole(f'minecraft:execute if loaded {x*16} 64 0 run minecraft:say CIAAC_FIXTURE_BB_CHUNK_{x}')
  time.sleep(.15)
  if expected.issubset(set(re.findall(r'CIAAC_FIXTURE_BB_CHUNK_[01]', '\n'.join(textlog().splitlines()[n:])))):return
 raise AssertionError('Build Battle synthetic chunks did not finish loading')
def setup_buildbattle():
 config=buildbattle_config()
 prepare_buildbattle_chunks()
 plot_volumes=[(plot['min'],plot['max']) for plot in BB_PLOTS.values()]
 min_x=min(v[0][0] for v in plot_volumes);max_x=max(v[1][0] for v in plot_volumes)
 min_y=min(v[0][1] for v in plot_volumes);max_y=max(v[1][1] for v in plot_volumes)
 min_z=min(v[0][2] for v in plot_volumes);max_z=max(v[1][2] for v in plot_volumes)
 # Set only the reviewed facility envelope and lobby in the dedicated owned fixture world.
 bbconsole('minecraft:fill 0 64 10 4 64 14 minecraft:stone')
 bbconsole(f'minecraft:fill {min_x} {min_y} {min_z} {max_x} {max_y} {max_z} minecraft:air')
 for plot in BB_PLOTS.values():
  x0,y0,z0=plot['min'];x1,_,z1=plot['max']
  bbconsole(f'minecraft:fill {x0} {y0} {z0} {x1} {y0} {z1} minecraft:stone')
 gx,gy,gz,_=BB_GAP_SENTINEL;lx,ly,lz,_=BB_LOBBY_SENTINEL
 bbconsole(f'minecraft:setblock {gx} {gy} {gz} minecraft:gold_block')
 console(f'minecraft:execute in minecraft:{BB_WORLD} run minecraft:setblock {lx} {ly} {lz} minecraft:sea_lantern')
 time.sleep(.4)
 verify_buildbattle_world('setup')
 marker=config['world-template-marker']
 assert re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}',marker)
 artifact=ROOT/'plugins/CIAACPlatform/templates'/ (marker+'.template')
 assert not artifact.exists() and not artifact.is_symlink(),'Create-only Build Battle template already exists'
 n=len(textlog().splitlines());console('ciaac instalações capturar-construcao')
 wait(lambda:artifact.is_file() and any('Template criado e verificado:' in line for line in textlog().splitlines()[n:]),12,'native console Build Battle template capture')
 lines=artifact.read_text().splitlines()
 assert 'artifact-id='+marker in lines and 'world-name='+BB_WORLD in lines
 assert 'revision='+config.get('ruleset-revision','build-battle-1') in lines
 assert f'bounds='+str(config['world']['uuid'])+',0,64,0,17,67,4' in lines
 assert sum(line.startswith('block=') for line in lines)==360
 assert f'block={gx},{gy},{gz}|minecraft:gold_block' in lines
 assert not any(line.startswith('color=') for line in lines)
 emit({'checkpoint':'build-battle-console-capture','artifact_id':marker,'cells':360,'plot_bounds':2,
       'gap_and_lobby_sentinels_preserved':True,'create_only':True,'manual_uuid_or_checksum':False})
def verify_buildbattle_world(label):
 n=len(textlog().splitlines());expected=set()
 for plot_id,plot in BB_PLOTS.items():
  (x0,y0,z0),(x1,y1,z1)=plot['min'],plot['max']
  for x in range(x0,x1+1):
   for y in range(y0,y1+1):
    for z in range(z0,z1+1):
     material='stone' if y==y0 else 'air'
     token=f'CIAAC_FIXTURE_BB_{label}_{plot_id}_{x}_{y}_{z}'
     bbconsole(f'minecraft:execute if block {x} {y} {z} minecraft:{material} run minecraft:say {token}')
     expected.add(token)
 for prefix,(x,y,z,material) in [('gap',BB_GAP_SENTINEL),('lobby',BB_LOBBY_SENTINEL)]:
  token=f'CIAAC_FIXTURE_BB_{label}_{prefix}'
  console(f'minecraft:execute in minecraft:{BB_WORLD} if block {x} {y} {z} {material} run minecraft:say {token}')
  expected.add(token)
 wait(lambda:expected.issubset(set(re.findall(r'CIAAC_FIXTURE_BB_[A-Za-z0-9_-]+','\n'.join(textlog().splitlines()[n:])))),10,
      'every owned Build Battle plot cell and gap/lobby sentinel unchanged')
 emit({'checkpoint':'build-battle-native-block-readback','label':label,'all_plot_cells':True,
       'gap_sentinel':True,'lobby_sentinel':True,'world_repaired_by_test':False})
def join_buildbattle():
 prepare_buildbattle_chunks()
 config=buildbattle_config();chat_before={i:peertext(i).count('classification=BUILD_BATTLE_THEME_OPTION') for i in [1,2]}
 votes_before={i:peertext(i).count('classification=BUILD_BATTLE_THEME_VOTE_ACCEPTED') for i in [1,2]}
 time.sleep(.8);native(1,'buildbattle entrar');time.sleep(.2);native(2,'buildbattle entrar')
 wait(lambda:all(peertext(i).count('classification=BUILD_BATTLE_THEME_OPTION')>=chat_before[i]+3 for i in [1,2]),8,
      'native clickable theme options delivered to both players')
 theme_id=config['_fixture-theme-ids'][0]
 native(1,'buildbattle tema '+theme_id);native(2,'buildbattle tema '+theme_id)
 wait(lambda:all(peertext(i).count('classification=BUILD_BATTLE_THEME_VOTE_ACCEPTED')>votes_before[i] for i in [1,2]),3,
      'both native theme votes accepted before building')
 emit({'checkpoint':'build-battle-native-theme-vote','public_command':'buildbattle tema','both_voted':True,
       'theme_id_exported':False,'native_ui_received':True})
 wait(lambda:active_count()==2,10,'two Build Battle authenticated sessions')
 def at_build_world():
  state=snapshot('build-battle-world-entry',[1,2])
  return all(v['Dimension']=='"minecraft:'+BB_WORLD+'"' for v in state.values())
 wait(at_build_world,55,'native players assigned to Build Battle plots')
 state=snapshot('build-battle-building',[1,2])
 assert all(v['playerGameType']=='1' for v in state.values()),'Peers did not enter the native creative building phase'
 owners={i:plot_for_position(state[i]['Pos']) for i in [1,2]}
 assert set(owners.values())==set(BB_PLOTS),owners
 expected={i:[float(v.rstrip('d')) for v in re.findall(r'-?\d+(?:\.\d+)?d?',state[i]['Pos'])] for i in [1,2]}
 def client_positions_confirmed():
  for i in [1,2]:
   observed=peerstatus(i)
   if not observed['nativeConnected'] or not observed['positionKnown']:return False
   if any(abs(observed['localPose'][axis]-expected[i][axis])>.05 for axis in range(3)):return False
  return True
 wait(client_positions_confirmed,8,'both native clients received the Paper plot positions before gameplay')
 return state,owners
def plot_for_position(raw_pos):
 coordinates=[float(value) for value in re.findall(r'-?\d+(?:\.\d+)?',raw_pos)]
 assert len(coordinates)>=3,raw_pos
 x,z=coordinates[0],coordinates[2]
 found=[plot_id for plot_id,value in BB_PLOTS.items()
        if value['min'][0] <= x < value['max'][0]+1 and value['min'][2] <= z < value['max'][2]+1]
 assert len(found)==1,('Position is outside exact configured Build Battle plots',raw_pos)
 return found[0]
def place_and_break_buildbattle_block(index):
 status=peerstatus(index);x,y,z=status['localPose'][:3]
 plot=next(((plot_id,value) for plot_id,value in BB_PLOTS.items()
            if value['min'][0] <= x <= value['max'][0] and value['min'][2] <= z <= value['max'][2]),None)
 assert plot is not None,('Peer is outside the configured plots',status['localPose'])
 plot_id,definition=plot
 x=definition['min'][0]+3;floor_y=definition['min'][1];z=definition['min'][2]+2
 native(index,'creative-slot stone')
 native(index,f'place {x} {floor_y} {z} up')
 query_buildbattle_block(x,floor_y+1,z,'stone',f'placed-{index}')
 time.sleep(.3)
 native(index,f'break {x} {floor_y+1} {z}')
 query_buildbattle_block(x,floor_y+1,z,'air',f'broken-{index}')
 time.sleep(.3)
 native(index,f'place {x} {floor_y} {z} up')
 query_buildbattle_block(x,floor_y+1,z,'stone',f'replaced-{index}')
 emit({'checkpoint':'build-battle-native-place-break','peer':index,'assigned_plot':plot_id,
       'ordinary_native_packets':True,'injected_events':False,'break_succeeded':True})
 return x,floor_y+1,z
def query_buildbattle_block(x,y,z,material,label):
 n=len(textlog().splitlines());token='CIAAC_FIXTURE_BB_BLOCK_'+label
 deadline=time.monotonic()+5
 while time.monotonic()<deadline:
  # Read again if the console overtook the peer's ordinary block-action packet.
  # This never changes the world to satisfy the assertion.
  console(f'minecraft:execute in minecraft:{BB_WORLD} if block {x} {y} {z} minecraft:{material} run minecraft:say {token}')
  time.sleep(.15)
  if any(token in line for line in textlog().splitlines()[n:]):return
 raise AssertionError('Timed out: native Build Battle block state '+label)
def buildbattle_result_count():
 with sqlite3.connect('file:'+str(ROOT/'plugins/CIAACPlatform/platform.sqlite')+'?mode=ro',uri=True) as c:
  return c.execute("select count(*) from mg_match_result where game_id='build-battle'").fetchone()[0]
def await_buildbattle_voting(owners):
 config=buildbattle_config();teleports={i:peerstatus(i)['nativeTeleportPackets'] for i in [1,2]}
 chat_before={i:peertext(i).count('classification=OTHER') for i in [1,2]}
 wait(lambda:all(peerstatus(i)['nativeTeleportPackets']>teleports[i] for i in [1,2]),
      int(config['build-seconds'])+10,'native Build Battle review teleports')
 review={i:peerstatus(i)['localPose'] for i in [1,2]}
 review_plots={i:plot_for_position('['+str(pose[0])+','+str(pose[1])+','+str(pose[2])+']') for i,pose in review.items()}
 assert all(review_plots[i]!=owners[i] for i in [1,2]),{'owners':owners,'review_plots':review_plots}
 for i in [1,2]:
  native(i,'flat-floor-motion on');native(i,'walk east 4')
 wait(lambda:all(peerstatus(i)['walkTicksRemaining']==0 for i in [1,2]),4,'bounded native review walk')
 after={i:peerstatus(i)['localPose'] for i in [1,2]}
 assert all(abs(after[i][0]-review[i][0])>.01
            and plot_for_position('['+str(after[i][0])+','+str(after[i][1])+','+str(after[i][2])+']')==review_plots[i]
            for i in [1,2]),{'before':review,'after':after}
 wait(lambda:all(peertext(i).count('classification=OTHER')>=chat_before[i]+7 for i in [1,2]),10,
      'native Build Battle voting UI for each reviewed plot')
 emit({'checkpoint':'build-battle-native-review-voting-ui','review_plots':review_plots,
       'bounded_native_walk':True,'clickable_vote_ui_received':True})
 return review_plots
def assert_latest_buildbattle_result(outcome,reason,expected_score_total=None,expected_votes=None):
 with sqlite3.connect('file:'+str(ROOT/'plugins/CIAACPlatform/platform.sqlite')+'?mode=ro',uri=True) as c:
  row=c.execute("select result_id,outcome,reason_code from mg_match_result where game_id='build-battle' order by finished_at desc limit 1").fetchone()
  assert row and row[1:]==(outcome,reason),str(row)
  players=c.execute('select count(*) from mg_player_result where result_id=?',(row[0],)).fetchone()[0]
  assert players==2,players
  score=c.execute("select coalesce(sum(metric_value),0) from mg_player_metric where result_id=? and metric_key='score'",(row[0],)).fetchone()[0]
  votes=c.execute("select coalesce(sum(metric_value),0) from mg_player_metric where result_id=? and metric_key='votes'",(row[0],)).fetchone()[0]
  if expected_score_total is not None:assert score==expected_score_total,score
  if expected_votes is not None:assert votes==expected_votes,votes
 emit({'checkpoint':'build-battle-result-persisted','outcome':outcome,'reason':reason,
       'player_results':players,'score_metric_total':score,'vote_metric_total':votes})
def run_buildbattle(base):
 join_buildbattle();cell=place_and_break_buildbattle_block(1)
 native(1,'buildbattle sair');finish_check('build-battle-leave',{i:base[i] for i in [1,2]})
 with sqlite3.connect(ROOT/'plugins/CIAACPlatform/platform.sqlite') as c:
  result=c.execute('select game_id,outcome,reason_code from mg_match_result order by finished_at desc limit 1').fetchone()
 assert result and result[0]=='build-battle' and result[1]=='NO_CONTEST',str(result)
 verify_buildbattle_world('leave')
 join_buildbattle();place_and_break_buildbattle_block(1)
 disconnect(2);connect(2);finish_check('build-battle-disconnect',{i:base[i] for i in [1,2]})
 with sqlite3.connect(ROOT/'plugins/CIAACPlatform/platform.sqlite') as c:
  result=c.execute('select game_id,outcome,reason_code from mg_match_result order by finished_at desc limit 1').fetchone()
 assert result and result[0]=='build-battle' and result[1]=='NO_CONTEST',str(result)
 verify_buildbattle_world('disconnect')
 prior_results=buildbattle_result_count()
 _,owners=join_buildbattle();place_and_break_buildbattle_block(1)
 # A third authenticated player cannot join once the two-slot match is ACTIVE.
 native(3,'buildbattle entrar');time.sleep(.4)
 assert active_count()==2 and compare({3:base[3]},snapshot('build-battle-late-join',[3]))['all_equal'], \
        'Late Build Battle entry changed the queued peer state or match capacity'
 emit({'checkpoint':'build-battle-late-join-denial','third_peer_state_unchanged':True})
 review_plots=await_buildbattle_voting(owners)
 native(1,'buildbattle avaliar '+owners[1]+' 5')
 native(1,'buildbattle avaliar '+review_plots[1]+' 0')
 time.sleep(.4)
 assert (active_count()==2 and buildbattle_result_count()==prior_results), \
        'A self-vote or out-of-range ballot was accepted before a valid ballot was submitted'
 native(1,'buildbattle avaliar '+review_plots[1]+' 5')
 native(2,'buildbattle avaliar '+review_plots[2]+' 3')
 finish_check('build-battle-native-completion',{i:base[i] for i in [1,2]})
 assert_latest_buildbattle_result('VICTORY','RESULT',8,2)
 verify_buildbattle_world('completion')

 config=buildbattle_config();_,owners=join_buildbattle();place_and_break_buildbattle_block(1)
 await_buildbattle_voting(owners)
 wait(closed,int(config['voting']['seconds-per-plot'])*config['maximum-players']
      +int(config['reset']['timeout-seconds'])+15,'strict Build Battle missing-ballot timeout and restoration')
 finish_check('build-battle-voting-timeout',{i:base[i] for i in [1,2]})
 assert_latest_buildbattle_result('NO_CONTEST','VOTING_TIMEOUT')
 verify_buildbattle_world('voting-timeout')
def run_buildbattle_isolation(base):
 _,owners=join_buildbattle()
 match=active_match_id('build-battle',[1,2])
 for i in [1,2]:
  def client_at_plot():
   pose=peerstatus(i)['localPose'];definition=BB_PLOTS[owners[i]]
   return definition['min'][0]<=pose[0]<definition['max'][0]+1 and abs(pose[1]-65)<.1 and definition['min'][2]<=pose[2]<definition['max'][2]+1
  wait(client_at_plot,8,'native client plot teleport delivered before boundary movement')
  # Keep this boundary path unobstructed. Own-plot block construction is
  # already checked by build-battle; a block collision is not this gate.
  stable_since=time.monotonic();last_teleports=peerstatus(i)['nativeTeleportPackets']
  def teleport_settled():
   nonlocal stable_since,last_teleports
   observation=peerstatus(i);count=observation['nativeTeleportPackets']
   if count!=last_teleports:
    last_teleports=count;stable_since=time.monotonic()
   return time.monotonic()-stable_since>=.75 and client_at_plot()
  wait(teleport_settled,8,'native client plot teleports settled before movement')
  native(i,'look '+('270' if owners[i]=='plot-a' else '90')+' 0')
  initial_teleports=peerstatus(i)['nativeTeleportPackets']
  direction='east' if owners[i]=='plot-a' else 'west'
  # Send these consecutively; a status round-trip between enabling the
  # model and walking can receive a teleport that correctly pauses input.
  native(i,'flat-floor-motion on 65')
  native(i,'walk '+direction+' 100')
  wait(lambda i=i:peerstatus(i)['walkTicksRemaining']==0,12,
       'bounded native Build Battle plot-boundary walk for peer '+str(i))
  time.sleep(.3)
  state=snapshot('build-battle-isolation-peer'+str(i),[i])[i]
  pos=[float(value.rstrip('d')) for value in re.findall(r'-?\d+(?:\.\d+)?d?',state['Pos'])]
  assert len(pos)==3 and state['Dimension']=='"minecraft:'+BB_WORLD+'"',state
  assert plot_for_position(state['Pos'])==owners[i],('Native movement left the participant plot',i,owners[i],pos)
  boundary=BB_PLOTS[owners[i]]['max'][0]+1 if direction=='east' else BB_PLOTS[owners[i]]['min'][0]
  assert abs(pos[0]-boundary)<.7,('Correction did not occur near the actual plot boundary',i,pos,boundary)
  assert active_count()==2 and state['playerGameType']=='1', \
         ('Movement attempt ended or altered the active session',i,state)
  assert peerstatus(i)['nativeTeleportPackets']>initial_teleports, \
         ('No server correction was observed after crossing the plot boundary',i,direction,pos)
  emit({'checkpoint':'build-battle-native-plot-boundary-denial','peer':i,
        'owned_plot':owners[i],'direction':direction,'server_position':pos,
        'native_server_correction':True,'still_active':True})
 # The other plot starts more than native block interaction reach from either
 # owned plot. Do not label a distant place/break packet as an isolation test.
 emit({'checkpoint':'build-battle-foreign-plot-edit-limit','direct_native_edit_reachable':False,
       'reason':'configured plots are separated by a gap wider than ordinary block reach'})
 native(1,'buildbattle sair')
 finish_check('build-battle-isolation',{i:base[i] for i in [1,2]})
 assert exact_match_result('build-battle',match)==('build-battle','NO_CONTEST','PLAYER_LEFT')
 verify_buildbattle_world('isolation')
def run_anvil(base):
 join_anvil();ids=observe_anvil_marker()
 native(3,'bigornas entrar');time.sleep(.3)
 assert active_count()==2 and compare({3:base[3]},snapshot('anvil-late-join',[3]))['all_equal']
 emit({'checkpoint':'anvil-late-join-denial','state_unchanged':True})
 wait(closed,20,'two native Anvil waves and restoration')
 with sqlite3.connect(ROOT/'plugins/CIAACPlatform/platform.sqlite') as c:
  result=c.execute('select game_id,outcome,reason_code from mg_match_result order by finished_at desc limit 1').fetchone()
 assert result==('anvil-dodge','VICTORY','COMPLETED'),str(result)
 finish_check('anvil-native-completion',{i:base[i] for i in [1,2]});verify_anvil_cleanup(ids);verify_anvil_floor()
 join_anvil();ids=observe_anvil_marker();native(1,'bigornas sair')
 finish_check('anvil-leave',{i:base[i] for i in [1,2]});verify_anvil_cleanup(ids);verify_anvil_floor()
 join_anvil();ids=observe_anvil_marker();disconnect(1);connect(1)
 finish_check('anvil-disconnect',{i:base[i] for i in [1,2]});verify_anvil_cleanup(ids);verify_anvil_floor()
def anvil_marker_positions(ids,label):
 result=[]
 for entity in ids:
  n=len(textlog().splitlines());console('minecraft:data get entity '+entity+' Pos')
  wait(lambda:any('has the following entity data:' in line and '[' in line
                  for line in textlog().splitlines()[n:]),3,'native Anvil marker position readback')
  line=next(line for line in reversed(textlog().splitlines()[n:])
            if 'has the following entity data:' in line and '[' in line)
  match=re.search(r'has the following entity data: \[(-?[0-9.]+)d?, (-?[0-9.]+)d?, (-?[0-9.]+)d?\]',line)
  assert match,line
  x,y,z=map(float,match.groups())
  assert abs((x-math.floor(x))-.5)<.01 and abs((z-math.floor(z))-.5)<.01,(x,y,z)
  result.append((math.floor(x)-160,math.floor(z),y))
 emit({'checkpoint':label,'native_marker_positions':len(result),
       'floor_cells':[[x,z] for x,z,_ in result]})
 return [(x,z) for x,z,_ in result]
def anvil_peer_cell(index,label):
 pos=snapshot(label,[index])[index]['Pos']
 values=[float(value.rstrip('d')) for value in re.findall(r'-?\d+(?:\.\d+)?d?',pos)]
 assert len(values)==3,pos
 return (math.floor(values[0])-160,math.floor(values[2])),values
def walk_peer_to_anvil_cell(index,target,label):
 native(index,'flat-floor-motion on 80')
 for axis,target_block in [(0,target[0]+160),(2,target[1])]:
  for _ in range(40):
   pose=peerstatus(index)['localPose'];current=math.floor(pose[axis])
   if current==target_block:break
   direction=('east' if current<target_block else 'west') if axis==0 else ('south' if current<target_block else 'north')
   distance=abs(target_block+.5-pose[axis]);ticks=4 if distance>1.0 else 1
   native(index,'walk '+direction+' '+str(ticks))
   wait(lambda:peerstatus(index)['walkTicksRemaining']==0,5,'native bounded Anvil walk '+direction)
  else:raise AssertionError('Native peer could not reach Anvil cell '+str(target))
 cell,pos=anvil_peer_cell(index,label)
 assert cell==target and 79.5<=pos[1]<=81.0,{'peer':index,'expected_cell':target,'actual_cell':cell,'position':pos}
 return pos
def anvil_match_observation(match):
 with sqlite3.connect('file:'+str(ROOT/'plugins/CIAACPlatform/platform.sqlite')+'?mode=ro',uri=True) as c:
  row=c.execute("select result_id,outcome,reason_code from mg_match_result where game_id='anvil-dodge' and match_id=?",(match,)).fetchone()
  assert row and row[1:]==('VICTORY','COMPLETED'),str(row)
  players={player_id:(placement,bool(winner)) for player_id,placement,winner in
           c.execute('select player_id,placement,winner from mg_player_result where result_id=?',(row[0],)).fetchall()}
  metrics={player_id:dict(c.execute('select metric_key,metric_value from mg_player_metric where result_id=? and player_id=?',
                                    (row[0],player_id)).fetchall()) for player_id in players}
 expected={i:str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+NAMES[i]).encode()).digest(),version=3)) for i in [1,2]}
 assert set(players)==set(expected.values()),{'expected_peers':expected,'results':players}
 assert players[expected[1]]==(2,False) and players[expected[2]]==(1,True),players
 assert all(metrics[player]['waves']==2 for player in players),metrics
 assert metrics[expected[2]]['dodges']>=2,metrics
 emit({'checkpoint':'anvil-native-elimination-dodge-result','outcome':'VICTORY/COMPLETED',
       'surviving_peer':2,'waves':2,'survivor_dodges':metrics[expected[2]]['dodges'],
       'eliminated_peer_mode_remained_adventure_until_restore':True})
def run_anvil_edges(base):
 # Admit one player first so the game is WAITING and the boundary can be
 # checked before the configured two-player match starts.
 native(1,'bigornas entrar');wait(lambda:active_count()==1,8,'one Anvil WAITING session')
 before=peerstatus(1)['nativeTeleportPackets'];native(1,'flat-floor-motion on 80');native(1,'walk west 24')
 wait(lambda:peerstatus(1)['walkTicksRemaining']==0,8,'Anvil WAITING boundary walk')
 boundary_cell,_=anvil_peer_cell(1,'anvil-waiting-boundary-readback')
 assert 0<=boundary_cell[0]<8 and 0<=boundary_cell[1]<8 and active_count()==1,boundary_cell
 assert peerstatus(1)['nativeTeleportPackets']>before,'Anvil outside-boundary movement was not corrected'
 emit({'checkpoint':'anvil-waiting-boundary-denial','session_remained_active':True,
       'server_cell_inside_floor':boundary_cell,'outside_walk_was_native':True})
 native(2,'bigornas entrar');wait(lambda:active_count()==2,8,'two Anvil ACTIVE sessions')
 scenario_match=active_match_id('anvil-dodge',[1,2])
 active=snapshot('anvil-edges-active',[1,2])
 assert all(state['playerGameType']=='2' for state in active.values())
 ids1=observe_anvil_marker();danger1=anvil_marker_positions(ids1,'anvil-wave-one-cells')
 assert len(danger1)==1,'Reviewed fixture expects one hazard per wave'
 marked=danger1[0];safe=(1,4) if marked!=(1,4) else (2,4)
 pos1=walk_peer_to_anvil_cell(1,marked,'anvil-peer1-marked-cell')
 pos2=walk_peer_to_anvil_cell(2,safe,'anvil-peer2-safe-cell')
 cells1={anvil_peer_cell(i,'anvil-before-first-impact-'+str(i))[0] for i in [1,2]}
 assert marked in cells1 and safe in cells1 and marked!=safe,{'marked':marked,'safe':safe,'positions':[pos1,pos2]}
 message_before=peertext(1).count('classification=OTHER')
 wait(lambda:any(status=='CONFIRMED' and entity not in ids1 for entity,status in anvil_claims()),11,
      'second-wave native hazard marker')
 ids2=[entity for entity,status in anvil_claims() if status=='CONFIRMED' and entity not in ids1]
 assert len(ids2)==1,ids2
 assert peertext(1).count('classification=OTHER')>message_before, \
        'Peer targeted by the native first-wave hazard did not receive elimination feedback'
 assert active_count()==2,'Elimination ended the whole match before the survivor completed configured waves'
 eliminated=snapshot('anvil-eliminated-peer-still-active',[1,2])
 assert eliminated[1]['playerGameType']=='2' and eliminated[2]['playerGameType']=='2'
 danger2=anvil_marker_positions(ids2,'anvil-wave-two-cells')
 assert len(danger2)==1,danger2
 safe2=(1,4) if danger2[0]!=(1,4) else (2,4)
 survivor_pos=walk_peer_to_anvil_cell(2,safe2,'anvil-survivor-wave-two-safe-cell')
 assert safe2 not in danger2 and math.floor(survivor_pos[0])-160==safe2[0]
 wait(closed,20,'native Anvil survivor completes wave two and restores both players')
 anvil_match_observation(scenario_match)
 all_ids=ids1+ids2;verify_anvil_cleanup(all_ids);verify_anvil_floor()
 finish_check('anvil-native-elimination-dodge',{i:base[i] for i in [1,2]})
def setup_repeat_capture(command):
 n=len(textlog().splitlines());console(command)
 wait(lambda:any('A preparação foi recusada; verifica mundo, chunks, configuração e template existente.' in line
                 for line in textlog().splitlines()[n:]),8,'create-only capture refuses an existing artifact')
 return True
def setup_repeat_snapshot():
 config=ROOT/'plugins/CIAACPlatform/config.yml'
 template_dir=ROOT/'plugins/CIAACPlatform/templates'
 settings=json.loads(config.read_text())['modules']
 marker=settings['build-battle']['world-template-marker']
 artifacts=[template_dir/(marker+'.template'),template_dir/'immutable-floor-template.template']
 assert all(path.is_file() and not path.is_symlink() for path in artifacts),'Both reviewed create-only artifacts must already exist'
 return hashlib.sha256(config.read_bytes()).hexdigest(),{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in artifacts}
def setup_repeat_worlds():
 names=['ciaac-synthetic-test','ciaac-build-test','ciaac-elytra-test'];result={}
 for name in names:
  n=len(textlog().splitlines());console('ciaac instalações mundo '+name)
  wait(lambda:any('Mundo: '+name+'; UUID: ' in line for line in textlog().splitlines()[n:]),5,'native exact-name world identity')
  line=next(line for line in textlog().splitlines()[n:] if 'Mundo: '+name+'; UUID: ' in line)
  match=re.search(r'Mundo: '+re.escape(name)+r'; UUID: ([0-9a-fA-F-]{36}); alturas: (-?\d+)\.\.(-?\d+)\.',line)
  assert match,line
  result[name]=(str(uuid.UUID(match.group(1))),int(match.group(2)),int(match.group(3)))
 return result
def setup_repeat_validation():
 n=len(textlog().splitlines());console('ciaac instalações validar')
 expected={'Coliseu','Build Battle','Batata Quente','Knockback Sumo','Parkour Cronometrado',
           'Campo de Tiro com Arco','Fuga às Bigornas','Chão de Cores','Anéis de Elytra'}
 def lines():return [line for line in textlog().splitlines()[n:] if 'configuração=' in line and 'módulo=' in line]
 wait(lambda:len(lines())>=9,8,'nine native configuration/availability lines')
 wait(lambda:any('não substitui testes de jogo e recuperação' in line for line in textlog().splitlines()[n:]),3,
      'native configuration validation footer')
 parsed={}
 for line in lines():
  clean=re.sub(r'§[0-9a-fk-or]','',line)
  match=re.search(r'INFO\]:\s+(.+?): configuração=(válida|fechada); módulo=([A-Z_]+)\.',clean)
  if match:parsed[match.group(1)]=(match.group(2),match.group(3))
 assert set(parsed)==expected,parsed
 assert all(config=='válida' for config,_ in parsed.values()),parsed
 assert all(availability=='WAITING' for _,availability in parsed.values()),parsed
 return parsed
def setup_repeat_help():
 suffix='instalações <mundo <nome-exato>|validar|capturar-cores|capturar-construcao>.'
 for command in ['ciaac','ciaac __setup_repeat_unknown__']:
  n=len(textlog().splitlines());console(command)
  wait(lambda:any('Preparação de instalações (consola local): /ciaac '+suffix in line
                  for line in textlog().splitlines()[n:]),5,'native console setup help includes Build Battle capture')
 emit({'checkpoint':'setup-console-help','root_and_unknown_command_show_capture_help':True})
def restart_owned_server():
 global server
 assert server and server.poll() is None
 console('stop');server.wait(timeout=60)
 for h in handles:h.close()
 handles.clear();server=None
 start()
def run_setup_repeat():
 wait(closed,10,'clean fixture sessions and snapshots before setup repetition')
 initial_db=db()
 assert closed(),'Setup-repeat requires every prior session CLOSED and snapshot RESTORED'
 emit({'checkpoint':'setup-clean-database-gate','session_snapshot_counts':initial_db,
       'no_pending_sessions_or_snapshots':True})
 before=setup_repeat_snapshot();worlds_before=setup_repeat_worlds();availability_before=setup_repeat_validation()
 setup_repeat_help()
 setup_repeat_capture('ciaac instalações capturar-construcao')
 setup_repeat_capture('ciaac instalações capturar-cores')
 assert before==setup_repeat_snapshot(),'Capture refusal changed config or an existing artifact digest'
 restart_owned_server()
 assert before==setup_repeat_snapshot(),'Ordinary restart changed config or a captured artifact digest'
 worlds_after=setup_repeat_worlds();availability_after=setup_repeat_validation()
 assert worlds_before==worlds_after,'Native world identities/heights changed across ordinary restart'
 assert availability_before==availability_after,'Nine-mode configuration/availability changed across restart'
 emit({'checkpoint':'setup-create-only-repeat','both_existing_artifacts_refused':True,
       'config_and_template_digests_unchanged':True,'ordinary_restart':True,
       'world_identity_and_heights_stable':True,'all_nine_validated':True,
       'availability':availability_after,'manual_uuid_or_checksum_edits':False})
def stop():
 global server
 for i in list(peers):
  try:disconnect(i)
  except Exception:pass
 if server and server.poll() is None:
  console('stop');server.wait(timeout=60)
 for h in handles:h.close()
try:
 start()
 if args.case=='setup-repeat':
  run_setup_repeat();emit({'acceptance_success':True,'case':args.case});sys.exit(0)
 if args.case=='setup-worlds':
  ensure_fixture_worlds();emit({'acceptance_success':True,'case':args.case});sys.exit(0)
 for i in [1,2,3]:connect(i,initial=True)
 wait(closed,15,'old synthetic recovery closure')
 if args.case=='setup-build-battle':
  setup_buildbattle();emit({'acceptance_success':True,'case':args.case});sys.exit(0)
 if args.case in ['parkour','parkour-edges','crash-parkour']:
  console('minecraft:fill 38 79 -2 56 79 10 minecraft:stone')
  console('minecraft:forceload add 32 0 63 15')
  time.sleep(.3)
 if args.case in ['archery','archery-bands','crash-archery','crash-archery-launched']:
  console('minecraft:fill 59 79 -2 78 79 12 minecraft:stone')
  console('minecraft:forceload add 48 0 79 15')
  console('minecraft:kill @e[type=minecraft:armor_stand,tag=ciaac-fixture-archery]')
  for band,z in [('bullseye',4.5),('inner',6.5),('middle',8.5),('outer',9.5)]:summon_archery_target(band,z)
  time.sleep(.4)
 if args.case=='setup-color-floor':
  console('minecraft:forceload add 80 0 '+str(COLOR_MAX_X)+' '+str(COLOR_MAX_Z))
  n=len(textlog().splitlines());deadline=time.monotonic()+12
  expected_chunks={(str(x),str(z)) for x in range(5,(COLOR_MAX_X>>4)+1) for z in range((COLOR_MAX_Z>>4)+1)}
  while time.monotonic()<deadline:
   for x,z in expected_chunks:
    console('minecraft:execute if loaded '+str(int(x)*16)+' 79 '+str(int(z)*16)+' run minecraft:say CIAAC_FIXTURE_COLOR_CHUNK_'+x+'_'+z)
   time.sleep(.15)
   if set(re.findall(r'CIAAC_FIXTURE_COLOR_CHUNK_(\d+)_(\d+)', '\n'.join(textlog().splitlines()[n:])))==expected_chunks:break
  else:raise AssertionError('Color Floor synthetic chunks did not finish loading')
  console('minecraft:fill 80 79 0 85 79 '+str(COLOR_MAX_Z)+' minecraft:red_concrete')
  console('minecraft:fill 86 79 0 '+str(COLOR_MAX_X)+' 79 '+str(COLOR_MAX_Z)+' minecraft:blue_concrete')
  # Native all-AIR reference plane for read-only whole-color crash assertions.
  console('minecraft:fill 80 120 0 '+str(COLOR_MAX_X)+' 120 '+str(COLOR_MAX_Z)+' minecraft:air')
  time.sleep(.4)
  n=len(textlog().splitlines())
  console('ciaac instalações capturar-cores')
  wait(lambda:(ROOT/'plugins/CIAACPlatform/templates/immutable-floor-template.template').exists()
       and any('Template criado e verificado:' in line for line in textlog().splitlines()[n:]),8,'console captured and verified template')
  emit({'checkpoint':'color-floor-console-capture','create_only':True,'manual_uuid_or_checksum':False})
 if args.case in ['setup-anvil','anvil','anvil-edges','crash-anvil']:
  anvil=json.loads((ROOT/'plugins/CIAACPlatform/config.yml').read_text())['modules']['anvil-dodge']
  assert anvil['regions']['floor']=={'min':[160,79,0],'max':[167,80,7]}
  assert anvil['minimum-players']==2 and anvil['wave-count']==2
  console('minecraft:forceload add 160 0 167 7')
  n=len(textlog().splitlines());deadline=time.monotonic()+12
  while time.monotonic()<deadline:
   console('minecraft:execute if loaded 160 79 0 run minecraft:say CIAAC_FIXTURE_ANVIL_CHUNK_READY')
   time.sleep(.15)
   if any('CIAAC_FIXTURE_ANVIL_CHUNK_READY' in l for l in textlog().splitlines()[n:]):break
  else:raise AssertionError('Anvil chunk not loaded')
  if args.case=='setup-anvil':
   console('minecraft:fill 160 79 0 167 79 7 minecraft:stone');time.sleep(.3)
  verify_anvil_floor()
 base=seed();emit({'checkpoint':'seeded-baselines','nonempty_inventory_equipment_xp':True})
 case=args.case
 if case=='crash-archery-launched':run_crash('archery',base,launched=True)
 elif case.startswith('crash-'):run_crash(case.removeprefix('crash-'),base)
 if case in ['all','sumo']:run_sumo(base)
 if case in ['all','potato']:run_potato(base)
 if case=='parkour':run_parkour(base)
 if case=='parkour-edges':run_parkour_edges(base)
 if case=='archery':run_archery(base)
 if case=='archery-bands':run_archery_bands(base)
 if case=='build-battle':run_buildbattle(base)
 if case=='build-battle-isolation':run_buildbattle_isolation(base)
 if case=='color-floor':run_color_floor(base)
 if case=='anvil':run_anvil(base)
 if case=='anvil-edges':run_anvil_edges(base)
 if case=='crash-color-floor':verify_color_floor()
 emit({'acceptance_success':True,'case':case})
except Exception as e:
 import traceback; traceback.print_exc()
 emit({'acceptance_failure':type(e).__name__,'detail':str(e)[:1800],'db':db() if server else {}})
 sys.exit(1)
finally:stop()
