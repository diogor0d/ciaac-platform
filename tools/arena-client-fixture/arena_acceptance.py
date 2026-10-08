#!/usr/bin/env python3
"""Bounded native Arena acceptance for the disposable 127.0.0.1 fixture.

Consumes an already prepared fixture and existing synthetic credentials. Never
edits configuration, resets accounts, grants permissions, or forces gameplay.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import signal
import shutil
import sqlite3
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT = Path('/private/tmp/ciaac-paper-local-test')
NAMES = {1: 'CiaacArenaPeer', 2: 'CiaacArenaPeer2', 3: 'CiaacArenaPeer3'}
FIELDS = ('Inventory', 'EnderItems', 'equipment', 'XpLevel', 'XpP', 'XpTotal',
          'playerGameType', 'SelectedItemSlot', 'Health', 'foodLevel',
          'foodSaturationLevel', 'foodExhaustionLevel', 'abilities', 'Pos',
          'Rotation', 'Dimension', 'WorldUUIDMost', 'WorldUUIDLeast')


def parse_args():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--authorized-loopback-fixture', action='store_true', required=True)
    p.add_argument('--java', type=Path, required=True)
    p.add_argument('--private-output', type=Path, required=True)
    p.add_argument('--expected-plugin-sha256', required=True)
    p.add_argument('--expected-config-sha256', required=True,
                   help='SHA-256 of reviewed, already-enabled synthetic arena config')
    p.add_argument('--credentials', type=Path, required=True,
                   help='Existing mode-0600 synthetic-credentials.private.json')
    return p.parse_args()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(path):
    require(path.is_file() and not path.is_symlink(), f'Refusing missing or linked artifact: {path.name}')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def gate(args):
    require(ROOT.is_dir() and not ROOT.is_symlink() and ROOT.stat().st_uid == os.getuid(),
            'Owned private fixture is absent or has wrong owner')
    require(ROOT.stat().st_mode & 0o077 == 0, 'Fixture directory must be private')
    props = {}
    for line in (ROOT / 'server.properties').read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            k, v = line.split('=', 1); props[k] = v
    require(props.get('server-ip') == '127.0.0.1' and props.get('server-port') == '25567'
            and props.get('level-name') == 'ciaac-synthetic-test'
            and props.get('online-mode') == 'false', 'Refusing a non-fixture target')
    require('eula=true' in (ROOT / 'eula.txt').read_text(), 'Existing EULA acceptance required')
    config = ROOT / 'plugins/CIAACPlatform/config.yml'
    require(sha(config) == args.expected_config_sha256,
            'Current arena config differs from reviewed digest; refusing to proceed')
    settings = json.loads(config.read_text())
    arena = settings['modules']['arena']
    require(settings['admission']['enabled'] is True and arena['enabled'] is True,
            'Admission and Arena must already be enabled')
    require(arena['world']['name'] == 'ciaac-synthetic-test'
            and re.fullmatch(r'[0-9a-fA-F-]{36}', arena['world']['uuid'] or ''),
            'Arena must identify the synthetic world and concrete UUID')
    lo, hi = arena['regions']['combat-floor']['min'], arena['regions']['combat-floor']['max']
    require(len(lo) == len(hi) == 3 and all(type(x) is int for x in lo + hi)
            and all(lo[i] <= hi[i] for i in range(3)), 'Combat bounds are incomplete')
    spectator = arena['regions']['spectator-benches']
    slo, shi = spectator['min'], spectator['max']
    require(len(slo) == len(shi) == 3 and all(type(x) is int for x in slo + shi)
            and all(slo[i] <= shi[i] for i in range(3)), 'Spectator bounds are incomplete')
    require(any(hi[i] < slo[i] or shi[i] < lo[i] for i in range(3)),
            'Combat and spectator regions must not overlap')
    require('fixed' in arena['kit-modes'], 'Fixed-kit acceptance is not configured')
    require('team-a' in arena['locations'] and 'team-b' in arena['locations'],
            'Both reviewed team spawns are required')
    a, b = arena['locations']['team-a'], arena['locations']['team-b']
    for location in (a, b):
        require(all(lo[i] <= location[k] <= hi[i] for i, k in enumerate(('x', 'y', 'z'))),
                'Team spawn is outside the reviewed combat bounds')
    require(sum((a[k] - b[k]) ** 2 for k in ('x', 'y', 'z')) <= 3.2 ** 2,
            'Reviewed team spawns exceed the native peer action reach')
    require(sha(ROOT / 'plugins/CIAACPlatform.jar') == args.expected_plugin_sha256,
            'Plugin SHA-256 differs from reviewed artifact')
    require(sha(ROOT / 'paper.jar') == 'defe82c1c89067186895de34cf32983e9f5a2ea387cfe7597c020faebb98ca16',
            'Paper artifact differs from reviewed fixture')
    java = args.java.resolve(strict=True)
    cred = args.credentials.resolve(strict=True)
    require(not args.credentials.is_symlink() and cred.stat().st_uid == os.getuid()
            and cred.stat().st_mode & 0o077 == 0, 'Credentials must be owned and private')
    passwords = json.loads(cred.read_text())
    require(all(isinstance(passwords.get(str(i)), str) and len(passwords[str(i)]) >= 12
                for i in (1, 2, 3)), 'Existing credentials for synthetic peers 1, 2, and 3 are required')
    out = args.private_output.resolve()
    out.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(not args.private_output.is_symlink() and out.is_relative_to(Path('/private/tmp'))
            and out.stat().st_uid == os.getuid() and out.stat().st_mode & 0o077 == 0,
            'Evidence must be owned, private, and under /private/tmp')
    require(not any(out.iterdir()), 'Evidence output must be a fresh empty directory')
    client = Path(__file__).resolve().parents[2] / 'target/local-arena-client'
    require((client / 'classpath.txt').is_file(), 'Pinned native client must already be built')
    require(float(arena['round-seconds']) >= 20, 'Combat timeout is too short for bounded native testing')
    dbpath = ROOT / 'plugins/CIAACPlatform/platform.sqlite'
    require(dbpath.is_file(), 'Existing platform database is required; no fresh database is created')
    listener = subprocess.run(['lsof', '-nP', '-iTCP:25567', '-sTCP:LISTEN'],
                              capture_output=True, text=True)
    require(not listener.stdout.strip(), 'Fixture must be stopped before the offline state gate')
    require(not Path(str(dbpath) + '-wal').exists(),
            'Offline immutable inspection requires an absent WAL after normal shutdown')
    with sqlite3.connect(f'file:{dbpath}?mode=ro&immutable=1', uri=True) as c:
        pending_sessions = c.execute("SELECT count(*) FROM mg_session WHERE phase!='CLOSED'").fetchone()[0]
        pending_snapshots = c.execute("SELECT count(*) FROM mg_snapshot WHERE state!='RESTORED'").fetchone()[0]
    require(pending_sessions == 0 and pending_snapshots == 0,
            'Existing pending/quarantined state must be handled before this new acceptance run')
    return java, client, passwords, out, arena


def parse_snbt(body):
    parts, start, depth, quote, escape = [], 1, 0, None, False
    for i, ch in enumerate(body[1:-1], 1):
        if quote:
            if escape: escape = False
            elif ch == '\\': escape = True
            elif ch == quote: quote = None
        elif ch in ('"', "'"): quote = ch
        elif ch in '{[': depth += 1
        elif ch in '}]': depth -= 1
        elif ch == ',' and depth == 0: parts.append(body[start:i]); start = i + 1
    parts.append(body[start:-1])
    result = {}
    for item in parts:
        k, v = item.split(':', 1); result[k.strip().strip('"')] = v.strip()
    return result


def vector(value):
    numbers = re.findall(r'-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?', value)
    require(len(numbers) >= 3, 'Native NBT position is missing coordinates')
    return tuple(float(x) for x in numbers[:3])


class Run:
    def __init__(self, java, client, passwords, out, arena):
        self.java, self.client, self.passwords, self.out, self.arena = java, client, passwords, out, arena
        self.server, self.peers, self.handles = None, {}, []

    def log(self):
        return re.sub(r'\x1b\[[0-9;]*m', '', (ROOT / 'server.private.log').read_text(errors='replace'))

    def wait(self, predicate, seconds, label):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if predicate(): return
            if self.server and self.server.poll() is not None:
                raise RuntimeError('Paper exited during ' + label)
            time.sleep(.1)
        raise TimeoutError('Timed out waiting for ' + label)

    def console(self, command):
        require(self.server and self.server.poll() is None and '\n' not in command, 'Paper is not running')
        self.server.stdin.write(command + '\n'); self.server.stdin.flush()

    def peerlog(self, i):
        return (ROOT / f'peer{i}.private.log').read_text(errors='replace')

    def native(self, i, command):
        p = self.peers[i]
        require(p.poll() is None and '\n' not in command, 'Synthetic peer is not connected')
        p.stdin.write(command + '\n'); p.stdin.flush()

    def peerstatus(self, i):
        n = len(self.peerlog(i).splitlines())
        self.native(i, 'status')
        self.wait(lambda: any(x.startswith('{"fixtureStatus"') and x.endswith('}')
                              for x in self.peerlog(i).splitlines()[n:]), 3, 'native peer status')
        line = [x for x in self.peerlog(i).splitlines()[n:]
                if x.startswith('{"fixtureStatus"') and x.endswith('}')][-1]
        return json.loads(line)

    def start(self, require_clean=True):
        listener = subprocess.run(['lsof', '-nP', '-iTCP:25567', '-sTCP:LISTEN'], capture_output=True, text=True)
        require(not listener.stdout.strip(), 'Port 25567 already has a listener; refusing to interrupt it')
        self.preserve(ROOT / 'server.private.log', f'server-before-start-{time.time_ns()}.private.log')
        h = (ROOT / 'server.private.log').open('w'); self.handles.append(h)
        self.server = subprocess.Popen([str(self.java), '-Xms512M', '-Xmx1536M', '-jar', 'paper.jar', '--nogui'],
                                       cwd=ROOT, stdin=subprocess.PIPE, stdout=h,
                                       stderr=subprocess.STDOUT, text=True)
        self.wait(lambda: 'Done (' in self.log(), 60, 'Paper startup')
        require('AUTHME_COMPLETION_PROFILE_READY' in self.log(), 'Normal AuthMe profile was not observed')
        if require_clean: self.assert_no_pending_state()

    def assert_no_pending_state(self):
        dbpath = ROOT / 'plugins/CIAACPlatform/platform.sqlite'
        require(dbpath.is_file(), 'Existing platform database is required; refusing to initialize or replace it')
        with sqlite3.connect(f'file:{dbpath}?mode=ro', uri=True) as c:
            sessions = c.execute("SELECT count(*) FROM mg_session WHERE phase!='CLOSED'").fetchone()[0]
            snapshots = c.execute("SELECT count(*) FROM mg_snapshot WHERE state!='RESTORED'").fetchone()[0]
        require(sessions == 0 and snapshots == 0,
                'Fixture has pending or quarantined recovery state; preserve it and stop before testing')

    def connect(self, i):
        self.preserve(ROOT / f'peer{i}.private.log', f'peer{i}-before-reconnect-{time.time_ns()}.private.log')
        h = (ROOT / f'peer{i}.private.log').open('w'); self.handles.append(h)
        argv = [str(self.java), '-cp', str(self.client) + os.pathsep +
                (self.client / 'classpath.txt').read_text().strip(), 'LocalArenaPeer',
                '--authorized-loopback-fixture']
        if i != 1: argv += ['--fixture-peer', str(i)]
        self.peers[i] = subprocess.Popen(argv, cwd=ROOT, stdin=subprocess.PIPE, stdout=h,
                                         stderr=subprocess.STDOUT, text=True)
        self.wait(lambda: any(x in self.peerlog(i) for x in ('AUTH_LOGIN_FORM_READY', 'AUTH_REGISTER_FORM_READY')),
                  20, f'AuthMe dialog for peer {i}')
        require('AUTH_REGISTER_FORM_READY' not in self.peerlog(i),
                f'Peer {i} has no existing account; registration is forbidden')
        n = len(self.log().splitlines())
        self.native(i, 'login ' + self.passwords[str(i)])
        self.wait(lambda: 'Peer joined native GAME' in self.peerlog(i)
                  and 'Native teleport acknowledged.' in self.peerlog(i), 20, 'native login')
        self.native(i, 'login ' + self.passwords[str(i)])
        self.wait(lambda: any(f'[AuthMe] {NAMES[i]} logged in ' in line
                              for line in self.log().splitlines()[n:]), 15, 'normal AuthMe authentication')
        time.sleep(.5)

    def db(self):
        with sqlite3.connect(f"file:{ROOT / 'plugins/CIAACPlatform/platform.sqlite'}?mode=ro", uri=True) as c:
            return {t: dict(c.execute(f'SELECT {col},count(*) FROM {t} GROUP BY {col}'))
                    for t, col in [('mg_session', 'phase'), ('mg_snapshot', 'state')]}

    def preserve(self, source, name):
        if source.exists():
            require(source.is_file() and not source.is_symlink() and source.stat().st_uid == os.getuid()
                    and source.stat().st_mode & 0o077 == 0,
                    'Existing private log must be a regular owned mode-0600 file')
            target = self.out / name
            shutil.copyfile(source, target)
            target.chmod(0o600)

    def active(self):
        return self.db()['mg_session'].get('ACTIVE', 0)

    def snapshot(self, label, actors=(1, 2)):
        n = len(self.log().splitlines())
        for i in actors: self.console('minecraft:data get entity ' + NAMES[i])
        self.wait(lambda: all(any(NAMES[i] + ' has the following entity data:' in l
                                  for l in self.log().splitlines()[n:]) for i in actors), 8, 'native NBT')
        lines, result = self.log().splitlines()[n:], {}
        for i in actors:
            body = [l.split('has the following entity data:', 1)[1].strip() for l in lines
                    if NAMES[i] + ' has the following entity data:' in l][-1]
            (self.out / f'{label}-peer{i}.private.snbt').write_text(body)
            result[i] = parse_snbt(body)
        return result

    def outsider_denial(self):
        before = self.snapshot('outsider-before', (3,))
        target = self.arena['locations']['team-a']
        self.console('minecraft:tp ' + NAMES[3] + ' %.4f %.4f %.4f' % (target['x'], target['y'], target['z']))
        time.sleep(.7)
        after = self.snapshot('outsider-after', (3,))
        unchanged = {k: before[3].get(k, 'ABSENT') == after[3].get(k, 'ABSENT') for k in FIELDS}
        require(all(unchanged.values()), 'Nonparticipant state changed during Arena-region teleport denial')
        result = {'checkpoint': 'outsider-region-denial', 'native_state_unchanged': True,
                  'fields': len(FIELDS), 'checks': unchanged}
        (self.out / 'arena-outsider-evidence.private.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)

    def active_match_id(self):
        actors = []
        for i in (1, 2):
            raw = hashlib.md5(('OfflinePlayer:' + NAMES[i]).encode()).digest()
            actors.append(str(uuid.UUID(bytes=raw, version=3)))
        with sqlite3.connect(f"file:{ROOT / 'plugins/CIAACPlatform/platform.sqlite'}?mode=ro", uri=True) as c:
            rows = c.execute("SELECT match_id,player_id FROM mg_session WHERE game_id='arena' AND phase='ACTIVE'").fetchall()
        found = [(m, p) for m, p in rows if p.lower() in {v.lower() for v in actors}]
        require(len(found) == 2 and len({m for m, _ in found}) == 1,
                'Both exact synthetic actors must own the same active Arena match')
        return found[0][0]

    def result_for(self, match_id):
        with sqlite3.connect(f"file:{ROOT / 'plugins/CIAACPlatform/platform.sqlite'}?mode=ro", uri=True) as c:
            return c.execute('SELECT outcome,reason_code FROM mg_match_result WHERE match_id=?', (match_id,)).fetchone()

    def recovery_events(self):
        events = []
        for token in re.findall(r'CIAAC_SECURITY_EVENT_V1 ([A-Za-z0-9_-]+)', self.log()):
            try:
                events.append(json.loads(base64.urlsafe_b64decode(token + '=' * (-len(token) % 4))))
            except (ValueError, json.JSONDecodeError):
                continue
        return events

    def winner_for(self, match_id):
        with sqlite3.connect(f"file:{ROOT / 'plugins/CIAACPlatform/platform.sqlite'}?mode=ro", uri=True) as c:
            return c.execute('''SELECT p.player_id FROM mg_match_result r
                JOIN mg_player_result p ON p.result_id=r.result_id
                WHERE r.match_id=? AND p.winner=1''', (match_id,)).fetchall()

    def baseline(self, label, actors=(1, 2)):
        state = self.snapshot(label, actors)
        require(all(all(k in state[i] for k in FIELDS) for i in actors),
                'Baseline NBT must contain all 18 protected fields')
        require(all('oak_log' in state[i].get('Inventory', '').lower()
                    and 'elytra' in state[i].get('equipment', '').lower() for i in actors),
                'Existing nonempty sentinel baseline required; no seeding/reset is performed')
        return state

    def start_match(self, baseline, label):
        # The public entry limiter applies across successive completed matches.
        time.sleep(.8)
        prompts = {i: self.peerlog(i).count('ARENA_READY_PROMPT') for i in (1, 2)}
        started = {i: self.peerlog(i).count('ARENA_COMBAT_STARTED') for i in (1, 2)}
        for i in (1, 2): self.native(i, 'coliseu entrar 1v1 kit')
        self.wait(lambda: all(self.peerlog(i).count('ARENA_READY_PROMPT') > prompts[i] for i in (1, 2)),
                  20, 'native ready prompt')
        for i in (1, 2): self.native(i, 'coliseu pronto')
        self.wait(lambda: self.active() == 2, 20, 'two durable ACTIVE Arena sessions')
        time.sleep(.5)
        match_id = self.active_match_id()
        active = self.snapshot(label + '-active')
        expected_spawns = {tuple(float(self.arena['locations'][key][axis]) for axis in ('x', 'y', 'z'))
                           for key in ('team-a', 'team-b')}
        actual_spawns = {vector(active[i]['Pos']) for i in (1, 2)}
        require(actual_spawns == expected_spawns,
                f'Native positions must equal the two reviewed team spawns; got {actual_spawns}')
        require(all(any(active[i].get(k) != baseline[i].get(k) for k in ('Inventory', 'equipment', 'SelectedItemSlot'))
                    for i in (1, 2)),
                'Both fixed kits must replace the nonempty survival baseline in authoritative NBT')
        kit = self.arena['fixed-kits'][sorted(self.arena['fixed-kits'])[0]]
        require(all(all('"minecraft:' + material.lower() + '"' in
                        active[i].get('Inventory', '') + active[i].get('equipment', '')
                        for material in kit) for i in (1, 2)),
                'Both native loadouts must contain every material in the configured fixed kit')
        # The command response goes to the final player who confirms ready;
        # both actors' ACTIVE records and native spawns prove their admission.
        self.wait(lambda: any(self.peerlog(i).count('ARENA_COMBAT_STARTED') > started[i] for i in (1, 2)),
                  10, 'native combat-start response after both ready confirmations')
        return match_id

    def clean(self):
        d = self.db()
        return all(phase == 'CLOSED' for phase in d['mg_session']) and all(
            state == 'RESTORED' for state in d['mg_snapshot'])

    def check_restoration(self, label, baseline, match_id, expected_result=None):
        self.wait(self.clean, 20, 'all Arena sessions CLOSED and snapshots RESTORED')
        actual = self.snapshot(label)
        checks = {i: {k: baseline[i].get(k, 'ABSENT') == actual[i].get(k, 'ABSENT') for k in FIELDS}
                  for i in (1, 2)}
        require(all(all(v.values()) for v in checks.values()), 'Native player state failed exact restoration')
        result = self.result_for(match_id)
        if expected_result is not None:
            self.wait(lambda: self.result_for(match_id) == expected_result, 5, 'exact match result commit')
            result = self.result_for(match_id)
            require(result == expected_result, f'Expected exact durable result {expected_result}; got {result}')
        return {'match_id': match_id, 'result': result, 'all_18_fields_equal': True,
                'checks': checks, 'durable_state': self.db()}

    def run_leave_and_disconnect(self, baseline):
        match_id = self.start_match(baseline, 'arena-leave')
        self.native(1, 'coliseu sair')
        leave = self.check_restoration('arena-leave-restored', baseline, match_id, ('VICTORY', 'PLAYER_LEFT'))
        print(json.dumps({'checkpoint': 'active-player-leave', **leave}), flush=True)
        match_id = self.start_match(baseline, 'arena-disconnect')
        self.native(1, 'quit'); self.peers[1].wait(timeout=10); del self.peers[1]
        self.connect(1)
        disc = self.check_restoration('arena-disconnect-restored', baseline, match_id, ('VICTORY', 'DISCONNECT'))
        reauth = self.snapshot('arena-disconnect-reauth')
        require(all(all(baseline[i].get(k, 'ABSENT') == reauth[i].get(k, 'ABSENT') for k in FIELDS)
                    for i in (1, 2)), 'Same-account reauthentication did not preserve baseline after ACTIVE disconnect')
        print(json.dumps({'checkpoint': 'active-disconnect-reauth', **disc, 'baseline_restored': True}), flush=True)

    def run_native_combat(self, baseline):
        match_id = self.start_match(baseline, 'arena-combat')
        floor_y = float(self.arena['locations']['team-a']['y'])
        require(floor_y == float(self.arena['locations']['team-b']['y']),
                'The bounded flat-floor model requires equal reviewed team spawn heights')
        spawns = self.snapshot('arena-combat-client-sync')
        for i in (1, 2):
            expected = vector(spawns[i]['Pos'])
            self.wait(lambda: all(abs(a - b) < .05 for a, b in
                                  zip(self.peerstatus(i)['localPose'][:3], expected)),
                      8, 'native peer pose matching its authoritative Arena spawn')
        poses = {i: vector(spawns[i]['Pos']) for i in (1, 2)}
        lo = self.arena['regions']['combat-floor']['min']
        hi = self.arena['regions']['combat-floor']['max']
        # Team assignment varies between matches. Attack toward the larger
        # reviewed floor margin so knockback tests combat rather than escape.
        # This selects ordinary player input; it never moves a player by console.
        def knockback_margin(attacker):
            defender = 3 - attacker
            delta = [poses[defender][axis] - poses[attacker][axis] for axis in (0, 2)]
            length = sum(d * d for d in delta) ** .5
            require(length > 0, 'Distinct horizontal combat spawns are required')
            margins = []
            for axis, direction in zip((0, 2), delta):
                if direction:
                    boundary = hi[axis] if direction > 0 else lo[axis]
                    margins.append((boundary - poses[defender][axis]) / (direction / length))
            return min(margins)
        attacker = max((1, 2), key=knockback_margin)
        defender = 3 - attacker
        self.native(1, f'flat-floor-motion on {floor_y:g}')
        self.native(2, f'flat-floor-motion on {floor_y:g}')
        start = time.monotonic()
        max_seconds = min(float(self.arena['round-seconds']) - 3.0, 60.0)
        max_attacks = 90
        attacks = 0
        target_velocity_before = self.peerstatus(defender)['nonzeroVelocityPackets']
        while self.active() == 2 and time.monotonic() - start < max_seconds and attacks < max_attacks:
            s1, s2 = self.peerstatus(1), self.peerstatus(2)
            for i, status in ((1, s1), (2, s2)):
                if status['motionModel'] == 'NONE':
                    # Server corrections deliberately suspend the approximation.
                    # Reaffirm only this reviewed flat STONE floor; never override
                    # the corrected pose, velocity, or server anti-cheat policy.
                    pose = status['localPose']
                    lo = self.arena['regions']['combat-floor']['min']
                    hi = self.arena['regions']['combat-floor']['max']
                    require(lo[0] <= pose[0] <= hi[0] and lo[2] <= pose[2] <= hi[2]
                            and floor_y <= pose[1] <= floor_y + 4,
                            'Native correction left the reviewed flat-floor model')
                    self.native(i, f'flat-floor-motion on {floor_y:g}')
            statuses = {1: s1, 2: s2}
            p1, p2 = statuses[attacker]['localPose'], statuses[defender]['localPose']
            dx, dz = p2[0] - p1[0], p2[2] - p1[2]
            distance = (dx * dx + (p2[1] - p1[1]) ** 2 + dz * dz) ** .5
            if distance > 3.0:
                direction = ('east' if dx > 0 else 'west') if abs(dx) >= abs(dz) else ('south' if dz > 0 else 'north')
                ticks = max(1, min(8, int((distance - 2.6) * 4)))
                self.native(attacker, f'walk {direction} {ticks}')
                opposite = {'east': 'west', 'west': 'east', 'south': 'north', 'north': 'south'}[direction]
                self.native(defender, f'walk {opposite} {ticks}')
                time.sleep(ticks * .055 + .15)
                continue
            self.native(attacker, f'attack {defender}')
            attacks += 1
            time.sleep(.55)
        if self.active() != 0:
            state = self.snapshot('arena-combat-cap')
            diagnostic = {'native_attacks': attacks, 'elapsed_seconds': time.monotonic() - start,
                          'protected_state': self.db(),
                          'native_health': {i: state[i].get('Health') for i in (1, 2)}}
            (self.out / 'arena-combat-cap.private.json').write_text(json.dumps(diagnostic, indent=2) + '\n')
        require(self.active() == 0,
                'Bounded native movement/attacks did not finish the match before the combat cap; no forced outcome used')
        combat_elapsed = time.monotonic() - start
        require(combat_elapsed < max_seconds, 'Combat ended after the bounded native-play time budget')
        self.wait(lambda: self.result_for(match_id) is not None, 5, 'durable match result commit')
        outcome = self.result_for(match_id)
        require(outcome == ('VICTORY', 'LETHAL_DAMAGE'),
                f'Expected native lethal victory for exact match {match_id}; got {outcome}')
        offline_id = str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + NAMES[attacker]).encode()).digest(), version=3))
        require(self.winner_for(match_id) == [(offline_id,)],
                'Exact durable result must identify the native attacker as lethal winner')
        require(attacks > 0, 'No native attack packet was issued')
        status_after = self.peerstatus(defender)
        require(status_after['nonzeroVelocityPackets'] > target_velocity_before,
                'No server velocity response followed native attacks')
        restored = self.check_restoration('arena-combat-restored', baseline, match_id,
                                          ('VICTORY', 'LETHAL_DAMAGE'))
        result = {'checkpoint': 'arena-native-lethal-combat', **restored,
                  'native_attacks': attacks, 'walked_with_native_input': True,
                  'attacker_peer': attacker, 'defender_peer': defender,
                  'server_velocity_observed': True, 'round_timeout_seconds': self.arena['round-seconds'],
                  'combat_elapsed_seconds': round(combat_elapsed, 2)}
        (self.out / 'arena-native-combat.private.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)

    def run_cold_crash_recovery(self, baseline):
        match_id = self.start_match(baseline, 'arena-crash')
        n = len(self.log().splitlines())
        self.console('save-all flush')
        self.wait(lambda: any('Saved the game' in line for line in self.log().splitlines()[n:]),
                  15, 'native world/player save before owned-process crash')
        require(self.active() == 2, 'Both actors must remain ACTIVE immediately before crash')
        self.server.send_signal(signal.SIGKILL)
        self.server.wait(timeout=10)
        for i, p in list(self.peers.items()):
            try: p.wait(timeout=5)
            except subprocess.TimeoutExpired: p.terminate(); p.wait(timeout=5)
            del self.peers[i]
        require(self.active() == 2, 'Cold crash must leave both sessions durably ACTIVE for recovery')
        self.start(require_clean=False)
        self.connect(1); self.connect(2); self.connect(3)
        self.wait(self.clean, 20, 'authenticated crash recovery to CLOSED/RESTORED')
        events = self.recovery_events()
        recovered = {e.get('authenticatedActorName') for e in events
                     if e.get('type') == 'SESSION_RECOVERY'
                     and e.get('attributes', {}).get('result_code') == 'RECOVERED'}
        require({NAMES[1], NAMES[2]} <= recovered,
                'Post-authentication SESSION_RECOVERY/RECOVERED evidence missing for both actors')
        actual = self.snapshot('arena-crash-recovered')
        checks = {i: {k: baseline[i].get(k, 'ABSENT') == actual[i].get(k, 'ABSENT') for k in FIELDS}
                  for i in (1, 2)}
        require(all(all(v.values()) for v in checks.values()), 'Cold crash recovery failed exact 18-field restore')
        # A cold restart restores durable sessions without reconstructing the
        # volatile match controller. It must not fabricate a ranked victory.
        crash_result = self.result_for(match_id)
        require(crash_result is None or crash_result[0] == 'NO_CONTEST',
                'An interrupted cold match must not award a ranked result')
        result = {'checkpoint': 'active-cold-crash-authenticated-recovery', 'match_id': match_id,
                  'authenticated_RECOVERED': True, 'all_18_fields_equal': True, 'checks': checks,
                  'durable_result': crash_result, 'durable_state': self.db()}
        (self.out / 'arena-crash-recovery.private.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)

    def close(self):
        for i in list(self.peers):
            try: self.native(i, 'quit'); self.peers[i].wait(timeout=5)
            except Exception: self.peers[i].terminate()
        if self.server and self.server.poll() is None:
            self.server.stdin.write('stop\n'); self.server.stdin.flush()
            try: self.server.wait(timeout=30)
            except subprocess.TimeoutExpired: self.server.terminate(); self.server.wait(timeout=5)
        for h in self.handles: h.close()


def main():
    args = parse_args(); os.umask(0o077)
    java, client, passwords, out, arena = gate(args)
    run = Run(java, client, passwords, out, arena)
    try:
        run.start(); run.connect(1); run.connect(2); run.connect(3)
        baseline = run.baseline('arena-baseline')
        outsider = run.baseline('outsider-baseline', (3,))
        # Require a meaningful pre-game state for every participant, and retain it
        # through all scenarios. No synthetic inventory/XP seeding is performed.
        run.outsider_denial()
        run.run_native_combat(baseline)
        run.run_leave_and_disconnect(baseline)
        run.run_cold_crash_recovery(baseline)
        print(json.dumps({'status': 'completed', 'fixture': '127.0.0.1:25567',
                          'scenarios': ['native-lethal-1v1', 'active-leave', 'active-disconnect-reauth',
                                        'active-cold-crash-authenticated-recovery', 'outsider-region-denial']}), flush=True)
        return 0
    except Exception as exc:
        print(json.dumps({'status': 'failed', 'reason': type(exc).__name__, 'detail': str(exc)}), file=sys.stderr)
        return 1
    finally:
        run.close()


if __name__ == '__main__':
    raise SystemExit(main())
