import argparse, os, sys, json, time, subprocess, secrets, sqlite3, re, hashlib
from pathlib import Path

parser = argparse.ArgumentParser(description="Acceptance on the owned synthetic loopback fixture only")
parser.add_argument("--authorized-loopback-fixture", action="store_true", required=True)
parser.add_argument("--java", type=Path, required=True)
parser.add_argument("--private-output", type=Path, required=True)
parser.add_argument("--expected-plugin-sha256", required=True)
parser.add_argument("--case", choices=["all", "sumo", "potato", "crash-sumo", "crash-potato", "recover-only"], default="all")
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
 with sqlite3.connect(ROOT/'plugins/CIAACPlatform/platform.sqlite') as c:
  result=c.execute('select game_id,outcome,reason_code from mg_match_result order by finished_at desc limit 1').fetchone()
 if name in ['sumo-victory','sumo-knockback-victory','potato-victory']:assert result and result[1]=='VICTORY',str(result)
 if name=='sumo-timeout':assert result and result[1:] == ('DRAW','ROUND_TIMEOUT'),str(result)
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
def run_crash(mode, base):
 if mode == 'sumo':join_sumo()
 else:join_potato()
 n=len(textlog().splitlines());console('save-all flush')
 wait(lambda:any('Saved the game' in l for l in textlog().splitlines()[n:]),10,'native player save')
 server.kill();server.wait(timeout=10)
 for i,p in list(peers.items()):
  p.stdin.write('quit\n');p.stdin.flush();p.wait(timeout=10);del peers[i]
 emit({'checkpoint':'owned-local-crash','mode':mode,'pending':db()})
 assert active_count()==(2 if mode=='sumo' else 3)
 start()
 for i in [1,2,3]:connect(i)
 finish_check(mode+'-authenticated-crash',base if mode!='sumo' else {i:base[i] for i in [1,2]})
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
 for i in [1,2,3]:connect(i,initial=True)
 wait(closed,15,'old synthetic recovery closure')
 base=seed();emit({'checkpoint':'seeded-baselines','nonempty_inventory_equipment_xp':True})
 case=args.case
 if case.startswith('crash-'):run_crash(case.removeprefix('crash-'),base)
 if case in ['all','sumo']:run_sumo(base)
 if case in ['all','potato']:run_potato(base)
 emit({'acceptance_success':True,'case':case})
except Exception as e:
 import traceback; traceback.print_exc()
 emit({'acceptance_failure':type(e).__name__,'detail':str(e)[:1800],'db':db() if server else {}})
 sys.exit(1)
finally:stop()
