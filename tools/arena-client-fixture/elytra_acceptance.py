#!/usr/bin/env python3
"""Real-client Elytra acceptance on the owned synthetic loopback fixture.

Requires an existing, normally authenticated account, the reviewed bridge and
GUI scale, clean recovery state and explicit artifact/configuration digests.
Does not create accounts, reset recovery records, configure worlds or target
production. Minecraft must stay in the background. All raw evidence is private.
"""
import argparse
import os,json,time,subprocess,importlib.util,sqlite3,signal,urllib.request,urllib.parse,urllib.error,stat,hashlib,shutil,re
from pathlib import Path


def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--authorized-loopback-fixture',action='store_true',required=True)
 parser.add_argument('--java',type=Path,required=True)
 parser.add_argument('--private-output',type=Path,required=True)
 parser.add_argument('--expected-plugin-sha256',required=True)
 parser.add_argument('--expected-config-sha256',required=True)
 parser.add_argument('--player-name',required=True)
 parser.add_argument('--credentials',type=Path,required=True,help='Existing owned mode-0600 JSON with password; never put it on the command line')
 parser.add_argument('--bridge-endpoint',type=Path,required=True,help='Existing owned mode-0600 local control endpoint JSON')
 args=parser.parse_args()
 os.umask(0o077)
 OUT=args.private_output.resolve()
 assert not args.private_output.is_symlink() and OUT.is_relative_to(Path('/private/tmp'))
 OUT.mkdir(mode=0o700,exist_ok=False)
 assert OUT.stat().st_uid==os.getuid() and OUT.stat().st_mode&0o077==0
 ROOT=Path('/private/tmp/ciaac-paper-local-test')
 SOURCE=Path(__file__).resolve().with_name('arena_acceptance.py')
 SHA=args.expected_plugin_sha256
 assert ROOT.is_dir() and not ROOT.is_symlink() and ROOT.stat().st_uid==os.getuid() and ROOT.stat().st_mode&0o077==0
 assert hashlib.sha256((ROOT/'paper.jar').read_bytes()).hexdigest()=='defe82c1c89067186895de34cf32983e9f5a2ea387cfe7597c020faebb98ca16'
 assert 'eula=true' in (ROOT/'eula.txt').read_text()
 config_path=ROOT/'plugins/CIAACPlatform/config.yml'
 assert not config_path.is_symlink() and hashlib.sha256(config_path.read_bytes()).hexdigest()==args.expected_config_sha256
 assert hashlib.sha256((ROOT/'plugins/CIAACPlatform.jar').read_bytes()).hexdigest()==SHA
 props=dict(line.split('=',1) for line in (ROOT/'server.properties').read_text().splitlines() if '=' in line and not line.lstrip().startswith('#'))
 assert all(props.get(k)==v for k,v in {'server-ip':'127.0.0.1','server-port':'25567','level-name':'ciaac-synthetic-test','online-mode':'false'}.items())
 assert not subprocess.run(['lsof','-nP','-iTCP:25567','-sTCP:LISTEN'],capture_output=True,text=True).stdout.strip()
 p=ROOT/'plugins/CIAACPlatform/platform.sqlite'
 assert not Path(str(p)+'-wal').exists()
 with sqlite3.connect('file:'+str(p)+'?mode=ro&immutable=1',uri=True) as c:
  assert c.execute("select count(*) from mg_session where phase!='CLOSED'").fetchone()[0]==0
  assert c.execute("select count(*) from mg_snapshot where state!='RESTORED'").fetchone()[0]==0
 spec=importlib.util.spec_from_file_location('arena',SOURCE);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 assert re.fullmatch(r'[A-Za-z0-9_]{1,16}',args.player_name)
 m.NAMES[4]=args.player_name
 FOLDER=OUT
 config=json.loads((ROOT/'plugins/CIAACPlatform/config.yml').read_text())
 elytra=config['modules']['elytra-rings']
 assert config['admission']['enabled'] is True and elytra['enabled'] is True
 assert elytra['world']['name']=='ciaac-elytra-test' and elytra['run-timeout-seconds']==35
 java=args.java.resolve();assert java.is_file()
 r=m.Run(java,SOURCE.parents[2]/'target/local-arena-client',{},FOLDER,config['modules']['arena'])
 class NoRedirect(urllib.request.HTTPRedirectHandler):
  def redirect_request(self,*args,**kwargs):return None
 endpoint=args.bridge_endpoint
 fd=os.open(endpoint,os.O_RDONLY|os.O_NOFOLLOW)
 with os.fdopen(fd) as f:
  metadata=os.fstat(f.fileno());assert stat.S_ISREG(metadata.st_mode) and metadata.st_uid==os.getuid() and metadata.st_mode&0o077==0
  bridge=json.load(f)
 u=urllib.parse.urlparse(bridge['url'])
 assert u.scheme=='http' and u.hostname=='127.0.0.1' and u.port and not any([u.path,u.query,u.fragment,u.username])
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
 def act(action):
  body=json.dumps(action).encode();assert len(body)<=8192
  req=urllib.request.Request(bridge['url']+'/action',data=body,headers={'Authorization':'Bearer '+bridge['token'],'Content-Type':'application/json'})
  try:
   with opener.open(req,timeout=6) as response:return json.load(response)
  except urllib.error.HTTPError as error:
   detail=error.read(8192).decode(errors='replace')
   (FOLDER/('control-rejection-'+str(time.time_ns())+'.private.json')).write_text(json.dumps({'action':action['action'],'body':detail}))
   raise RuntimeError('Native bridge rejected '+action['action']+(': action expired' if 'Client did not respond; action expired' in detail else ': foreground guard' if 'Control actions are disabled while Minecraft is foreground' in detail else ': see private evidence'))
 def status():
  for attempt in range(4):
   try:return act({'action':'status'})
   except (RuntimeError,TimeoutError) as failure:
    if isinstance(failure,RuntimeError) and 'action expired' not in str(failure):raise
    if attempt==3:raise
    time.sleep(.5)

 def emit(v):print(json.dumps(v),flush=True)
 def wait(pred,seconds,label):
  deadline=time.monotonic()+seconds
  while time.monotonic()<deadline:
   if pred():return
   if r.server and r.server.poll() is not None:raise RuntimeError('Paper stopped during '+label)
   time.sleep(.1)
  raise TimeoutError(label)
 def safe_cursor(key):
  for attempt in range(3):
   try:return act({'action':'key','key':key})
   except RuntimeError as failure:
    if 'foreground guard' not in str(failure):raise
    time.sleep(.5)
    current=status()
    assert not current['windowActive'] and (current.get('screen') or {}).get('class')=='DirectJoinServerScreen'
  raise RuntimeError('Cursor input remains protected by foreground guard')
 def login():
  s=status();assert not s['windowActive'] and not s['connected']
  screen=s.get('screen') or {}
  if screen.get('class')=='DisconnectedScreen':
   act({'action':'click','button':'left','x':211,'y':153});time.sleep(.25)
   s=status()
   if (s.get('screen') or {}).get('class')=='DisconnectedScreen':
    act({'action':'click','button':'left','x':211,'y':164});time.sleep(.25)
  s=status()
  if (s.get('screen') or {}).get('class')=='JoinMultiplayerScreen':
   act({'action':'click','button':'left','x':210,'y':198});time.sleep(.25)
  s=status();assert (s.get('screen') or {}).get('class')=='DirectJoinServerScreen', 'Expected native Direct Connect form'
  act({'action':'click','button':'left','x':210,'y':126})
  for _ in range(256):safe_cursor('right')
  for _ in range(256):safe_cursor('backspace')
  act({'action':'type','text':'127.0.0.1:25567'})
  n=len(r.log().splitlines())
  act({'action':'click','button':'left','x':211,'y':177})
  wait(lambda:(status().get('screen') or {}).get('class')=='MultiButtonDialogScreen',20,'normal AuthMe dialog')
  s=status();assert s['screen']['title']=='Login'
  secret=args.credentials
  assert not secret.is_symlink() and secret.stat().st_uid==os.getuid() and secret.stat().st_mode&0o077==0
  secret_fd=os.open(secret,os.O_RDONLY|os.O_NOFOLLOW)
  with os.fdopen(secret_fd) as f:
   metadata=os.fstat(f.fileno());assert stat.S_ISREG(metadata.st_mode) and metadata.st_uid==os.getuid() and metadata.st_mode&0o077==0
   credential=json.load(f)
  assert isinstance(credential.get('password'),str) and 1<=len(credential['password'])<=256
  act({'action':'click','button':'left','x':210,'y':122})
  act({'action':'type','text':credential['password']})
  act({'action':'click','button':'left','x':138,'y':151})
  wait(lambda:status()['connected'] and status().get('screen') is None,20,'native GAME after AuthMe')
  wait(lambda:any('[AuthMe] '+args.player_name+' logged in ' in line for line in r.log().splitlines()[n:]),10,'normal provider authentication')
  emit({'checkpoint':'native-client-authenticated','explicit_target':'127.0.0.1:25567','foreground':status()['windowActive']})
 def command(text):
  assert text in ['/elytra entrar','/elytra sair']
  act({'action':'screen','name':'chat'});act({'action':'type','text':text});act({'action':'key','key':'enter'})
 def sql(q,args=()):
  with sqlite3.connect('file:'+str(p)+'?mode=ro',uri=True) as c:return c.execute(q,args).fetchall()
 def active_match():
  rows=sql("select match_id from mg_session where game_id='elytra-rings' and phase='ACTIVE'")
  assert len(rows)==1,rows
  return rows[0][0]
 def enter(label):
  time.sleep(.8)
  for attempt in range(2):
   command('/elytra entrar')
   end=time.monotonic()+3
   confirmed=False
   while time.monotonic()<end:
    if r.active()==1 and status().get('world',{}).get('dimension')=='minecraft:ciaac-elytra-test':
     confirmed=True;break
    time.sleep(.1)
   if confirmed:break
   if r.active():raise RuntimeError('Active Elytra session has no confirmed native dedicated-world admission')
  else:raise RuntimeError('Elytra admission did not activate')
  state=r.snapshot(label+'-active',(4,))[4]
  assert state['Dimension']=='"minecraft:ciaac-elytra-test"','Paper player dimension differs from the reviewed course world'
  assert state['playerGameType']=='2' and 'elytra' in state['equipment'] and 'firework_rocket' in state['equipment']
  assert 'oak_log' not in state['Inventory']
  return active_match()
 def restored(label,match,expected=None):
  wait(r.clean,50,'all sessions restored')
  actual=r.snapshot(label,(4,))[4]
  checks={k:baseline[4].get(k,'ABSENT')==actual.get(k,'ABSENT') for k in m.FIELDS}
  assert all(checks.values()),checks
  result=sql('select outcome,reason_code from mg_match_result where match_id=?',(match,))
  if expected is None:
   assert not result or all(row[0]=='NO_CONTEST' for row in result),'Cold recovery must not award a ranked result'
  if expected is not None:
   wait(lambda:sql('select outcome,reason_code from mg_match_result where match_id=?',(match,))==[expected],5,'exact Elytra result')
   result=[expected]
  evidence={'checkpoint':label,'all_18_fields_equal':True,'checks':checks,'result':result,'durable_state':r.db()}
  (FOLDER/(label+'.private.json')).write_text(json.dumps(evidence,indent=2))
  emit({k:v for k,v in evidence.items() if k!='checks'})
 def firework_rows(match):
  sessions=sql('select session_id from mg_session where match_id=?',(match,))
  assert len(sessions)==1
  ledger=ROOT/'plugins/CIAACPlatform/arena-world/arena-world.sqlite'
  with sqlite3.connect('file:'+str(ledger)+'?mode=ro',uri=True) as c:
   return c.execute("select entity_id,status from arena_entity where session_id=? and entity_type='ELYTRA_FIREWORK'",(sessions[0][0],)).fetchall()
 def absence(ids,label):
  for entity in ids:
   n=len(r.log().splitlines());r.console('minecraft:execute in minecraft:ciaac-elytra-test run minecraft:data get entity '+entity)
   wait(lambda:any('No entity was found' in line for line in r.log().splitlines()[n:]),5,'exact rocket absence')
  emit({'checkpoint':label,'exact_native_entities_absent':len(ids)})
 def record(label):
  client=status();native=r.snapshot(label+'-'+str(time.time_ns()),(4,))[4]
  observation={'label':label,'client':client,'native':{k:native.get(k) for k in ['Pos','Rotation','FallFlying','OnGround','Motion','playerGameType']}}
  (FOLDER/(label+'-'+str(time.time_ns())+'.observation.private.json')).write_text(json.dumps(observation,indent=2))
  emit({'checkpoint':label,'native':observation['native']})
 def launch(south=False):
  record('before-native-launch')
  if south:
   act({'action':'look','yaw':45,'pitch':0});act({'action':'look','yaw':45,'pitch':0})
  for step in range(5):
   act({'action':'hold','keys':['forward'],'durationMs':400});time.sleep(.45);act({'action':'release'})
   pose=r.snapshot('native-launch-walk-'+str(step)+'-'+str(time.time_ns()),(4,))[4]
   record('after-native-forward-'+str(step))
   if pose.get('OnGround')=='0b':break
  else:raise RuntimeError('Bounded native forward input did not leave the launch platform')
  act({'action':'hold','keys':['jump'],'durationMs':100});time.sleep(.15);act({'action':'release'});record('after-native-glide-request')
  act({'action':'hold','keys':['use'],'durationMs':100});time.sleep(.15);act({'action':'release'});record('after-native-rocket-use')
 def disconnect():
  act({'action':'release'});act({'action':'screen','name':'pause'})
  s=status();assert s['screen']['class']=='PauseScreen'
  options=[w for w in s.get('widgets',[]) if w.get('visible') and w.get('active') and w.get('label') in ['Disconnect','Leave Server']]
  if not options:
   (FOLDER/'pause-widgets.private.json').write_text(json.dumps(s,indent=2))
   raise RuntimeError('Expected native disconnect button was not found')
  w=options[0]
  try:act({'action':'click','button':'left','x':w['x']+w['width']/2,'y':w['y']+w['height']/2})
  except (RuntimeError,TimeoutError) as failure:
   if isinstance(failure,RuntimeError) and 'action expired' not in str(failure):raise
   emit({'checkpoint':'native-disconnect-observation-timeout','click_replayed':False})
  # Disconnect unloads chunks synchronously and can exceed the bridge deadline.
  # Never repeat this mutating click: only observe the actual client state.
  wait(lambda:not status()['connected'],45,'native disconnect observed after single click')

 try:
  assert not status()['windowActive'] and not status()['connected']
  r.start()
  native_status=status()
  assert native_status.get('gui')=={'width':427,'height':240},'Reviewed native GUI scale is required'
  r.console('minecraft:execute in minecraft:ciaac-elytra-test run minecraft:forceload add -32 -32 47 31')
  time.sleep(.5)
  n=len(r.log().splitlines())
  for x in range(-2,9):
   r.console('minecraft:execute in minecraft:ciaac-elytra-test if block '+str(x)+' 119 2 minecraft:stone run minecraft:say CIAAC_FIXTURE_LAUNCH_STONE_X_'+str(x))
  time.sleep(.3)
  blocks=[line.split('CIAAC_FIXTURE_LAUNCH_STONE_X_',1)[1] for line in r.log().splitlines()[n:] if 'CIAAC_FIXTURE_LAUNCH_STONE_X_' in line]
  (FOLDER/'native-launch-stone-readback.private.json').write_text(json.dumps(blocks))
  emit({'checkpoint':'native-launch-stone-readback','stone_x_at_z2':blocks})
  login();baseline=r.baseline('native-owner-baseline',(4,))
  match=enter('elytra-flight')
  act({'action':'hold','keys':['use'],'durationMs':100});time.sleep(.15);act({'action':'release'})
  assert not firework_rows(match), 'Ground input created an authorized rocket'
  launch()
  restored('elytra-native-flight-restored',match,('VICTORY','COMPLETED'))
  rows=firework_rows(match);assert rows and all(state=='REMOVED' for _,state in rows)
  absence([entity for entity,_ in rows],'native-flight-rocket-cleanup')
  match=enter('elytra-leave');command('/elytra sair');restored('elytra-active-leave-restored',match,('NO_CONTEST','PLAYER_LEFT'))
  match=enter('elytra-timeout');restored('elytra-timeout-restored',match,('NO_CONTEST','INVALIDATED'))
  match=enter('elytra-disconnect');disconnect();login();restored('elytra-disconnect-reauth-restored',match,('NO_CONTEST','PLAYER_DISCONNECTED'))
  match=enter('elytra-rocket-crash');launch(south=True)
  wait(lambda:any(state=='CONFIRMED' for _,state in firework_rows(match)),2,'confirmed native rocket before crash')
  ids=[entity for entity,state in firework_rows(match) if state=='CONFIRMED']
  assert r.active()==1
  n=len(r.log().splitlines());r.console('save-all flush')
  wait(lambda:any('Saved the game' in line for line in r.log().splitlines()[n:]),10,'owned native flush')
  assert r.active()==1 and any(state=='CONFIRMED' for _,state in firework_rows(match))
  for entity in ids:
   n=len(r.log().splitlines())
   r.console('minecraft:execute in minecraft:ciaac-elytra-test if entity '+entity+' run minecraft:say CIAAC_FIXTURE_NATIVE_ROCKET_ALIVE')
   wait(lambda:any('CIAAC_FIXTURE_NATIVE_ROCKET_ALIVE' in line for line in r.log().splitlines()[n:]),1,'exact native rocket alive after save')
  r.server.send_signal(signal.SIGKILL);r.server.wait(timeout=10)
  (FOLDER/'confirmed-rockets-before-crash.private.json').write_text(json.dumps(ids))
  emit({'checkpoint':'native-owned-rocket-cold-crash','active_players':1,'confirmed_native_rockets':len(ids)})
  time.sleep(.3);r.start(require_clean=False);login()
  restored('elytra-rocket-cold-authenticated-restored',match)
  rows=firework_rows(match);assert rows and all(state=='REMOVED' for _,state in rows)
  absence(ids,'native-cold-rocket-cleanup')
  (OUT/'terminal.private.json').write_text(json.dumps({'complete':True,'all_success':True,'candidate':SHA,'durable_state':r.db()}))
 except Exception as exc:
  emit({'status':'failed','type':type(exc).__name__,'detail':str(exc)[:900]})
  (OUT/'terminal.private.json').write_text(json.dumps({'complete':False,'all_success':False,'candidate':SHA,'reason':type(exc).__name__,'detail':str(exc)[:900]}))
  raise
 finally:
  try:act({'action':'release'})
  except Exception:pass
  r.close()
  shutil.copy2(ROOT/'server.private.log',OUT/'paper.private.log')


if __name__=='__main__':
 main()
