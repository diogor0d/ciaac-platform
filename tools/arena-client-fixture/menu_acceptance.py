#!/usr/bin/env python3
"""Native chest-menu acceptance on the reviewed disposable loopback fixture.

Reuses the Arena fixture gates, accounts, authoritative NBT and shutdown. No
account seeding, permission grants, production connections or forced outcomes.
"""
import json
import os
import re
import time
import sqlite3
import hashlib
import uuid
from arena_acceptance import Run, FIELDS, NAMES, gate, parse_args, require, vector

ROUTES = ('coliseu', 'buildbattle', 'batataquente', 'sumo', 'parkour', 'arco', 'bigornas', 'cores', 'elytra')
CATALOG = (10, 11, 12, 13, 14, 15, 16, 21, 23)


class Menus(Run):
    def menu(self, actor):
        count = len(self.peerlog(actor).splitlines())
        self.native(actor, 'menu-status')
        self.wait(lambda: any(line.startswith('Native menu status=') for line in self.peerlog(actor).splitlines()[count:]),
                  3, 'native container status')
        return [line for line in self.peerlog(actor).splitlines()[count:] if line.startswith('Native menu status=')][-1]

    def open_menu(self, actor, route='minijogos'):
        time.sleep(.2)
        mark = self.peerlog(actor).count('Native menu opened;')
        self.native(actor, route)
        self.wait(lambda: self.peerlog(actor).count('Native menu opened;') > mark, 5, 'native chest open')
        self.wait(lambda: 'stateId=-1' not in self.menu(actor), 3, 'native content')
        return self.menu(actor)

    def click(self, actor, slot, kind='left', wait_new=True):
        time.sleep(.2)
        mark = self.peerlog(actor).count('Native menu opened;')
        self.native(actor, f'menu-click {slot} {kind}')
        if kind == 'left' and slot != 49 and wait_new:
            self.wait(lambda: self.peerlog(actor).count('Native menu opened;') > mark, 5, 'new window after menu action')
            self.wait(lambda: 'stateId=-1' not in self.menu(actor), 3, 'new window content')
        else:
            time.sleep(.3)

    def require_slot(self, actor, slot):
        self.wait(lambda: f'[{slot}:id=' in self.menu(actor), 5, 'menu button slot ' + str(slot))

    def equal(self, before, label, actors=(1, 2)):
        after = self.snapshot(label, actors)
        checks = {str(i): {k: before[i].get(k, 'ABSENT') == after[i].get(k, 'ABSENT') for k in FIELDS} for i in actors}
        require(all(all(values.values()) for values in checks.values()), 'Protected native player state differs: ' + label)
        return checks

    def emit(self, checkpoint, **facts):
        result = {'checkpoint': checkpoint, **facts}
        (self.out / (checkpoint + '.private.json')).write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)

    def leave_menu(self, actor, route):
        self.open_menu(actor, route)
        self.click(actor, 24)
        self.require_slot(actor, 20)
        self.click(actor, 20)

    def catalog_and_transfer_denials(self, before):
        status = self.open_menu(1)
        require('slots=54' in status and all(f'[{slot}:id=' in status for slot in CATALOG), 'All nine game icons must be present')
        require('[47:id=' not in status, 'Ordinary synthetic player must not see administration')
        for kind in ('right', 'shift', 'number', 'double', 'drop'):
            self.click(1, 10, kind)
            require('title=Minijogos CIAAC ' in self.menu(1), 'Unsupported click invoked a catalog action')
        for slot, route in zip(CATALOG, ROUTES):
            self.click(1, slot)
            require('title=CIAAC • ' in self.menu(1), 'Catalog icon did not open game screen: ' + route)
            self.click(1, 45)
            require('title=Minijogos CIAAC ' in self.menu(1), 'Back navigation failed')
        self.click(1, 49)
        self.equal(before, 'catalog-and-transfer-restored')
        self.emit('catalog-and-transfer-denials', games=9, rejected_clicks=5, ordinary_admin_hidden=True, protected_fields_equal=18)

    def wait_code(self, actor, code, before):
        self.wait(lambda: self.peerlog(actor).count('classification=' + code) > before, 5, code)

    def group_action(self, actor, slot, code, target=False, confirm=False):
        count = self.peerlog(actor).count('classification=' + code)
        self.open_menu(actor, 'coliseu'); self.click(actor, 34); self.click(actor, slot)
        if target: self.click(actor, 9)
        if target or confirm: self.click(actor, 20)
        self.wait_code(actor, code, count)

    def waiting_cancel(self, before):
        self.open_menu(1,'sumo'); self.click(1,20)
        self.leave_menu(1,'sumo')
        self.wait(self.clean,10,'prepared waiting cancellation')
        self.equal(before,'sumo-gui-prepared-cancel-restored')
        self.emit('sumo-gui-prepared-cancel',protected_fields_equal=18,no_pending_state=True)

    def arena_social(self, before):
        self.group_action(1, 10, 'ARENA_PARTY_CREATED')
        self.group_action(1, 11, 'ARENA_PARTY_INVITED', target=True)
        self.group_action(2, 12, 'ARENA_PARTY_JOINED', target=True)
        self.group_action(1, 13, 'ARENA_PARTY_KICKED', target=True)
        self.group_action(1, 15, 'ARENA_PARTY_DISBANDED', confirm=True)
        self.equal(before, 'arena-party-restored')
        self.open_menu(1, 'coliseu')
        formats = []
        for _ in range(5):
            formats.append(re.search(r'selection=Formato:([1-3]v[1-3])', self.menu(1)).group(1))
            self.click(1, 10)
        require(set(formats) == {'1v1','2v2','3v3','2v3','3v2'} and 'selection=Formato:1v1' in self.menu(1), 'Configured format cycle failed')
        self.click(1, 12)
        require('selection=Equipamento:equipamento' in self.menu(1), 'Protected equipment option missing')
        self.click(1, 12)
        require('selection=Equipamento:kit' in self.menu(1), 'Disabled stakes must be absent from the cycle')
        self.click(1, 16); self.click(1, 9); self.click(1, 20)
        self.open_menu(2, 'coliseu'); self.click(2, 25); self.click(2, 9); self.click(2, 20)
        for actor in (1, 2):
            self.wait(lambda: '[30:id=' in self.menu(actor), 10, 'challenge ready button')
            self.click(actor, 30, wait_new=False)
        self.wait(lambda: self.active()==2, 10, 'GUI challenge active')
        match=self.active_match_id(); self.leave_menu(1,'coliseu')
        result=self.check_restoration('challenge-gui-restored', before, match, ('VICTORY','PLAYER_LEFT'))
        self.emit('arena-gui-social-options-challenge', party_actions=5, formats=formats, equipment_modes=2, disabled_stakes_hidden=True, **result)

    def build_battle_votes(self, before):
        for actor in (1,2):
            self.open_menu(actor,'buildbattle'); self.click(actor,20)
        marks={i:self.peerlog(i).count('classification=BUILD_BATTLE_THEME_VOTE_ACCEPTED') for i in (1,2)}
        for actor in (1,2):
            self.wait(lambda:'[10:id=' in self.menu(actor),8,'theme buttons')
            self.click(actor,10)
            self.wait_code(actor,'BUILD_BATTLE_THEME_VOTE_ACCEPTED',marks[actor])
        for actor in (1,2):
            self.wait(lambda:'[10:id=' not in self.menu(actor),5,'theme buttons retired during build')
        # Phase changes replace the window; actual review buttons appear after building.
        for actor in (1,2):
            self.wait(lambda:'[10:id=' in self.menu(actor),55,'review score buttons')
            self.click(actor,10 if actor==1 else 12)
        self.wait(self.clean,15,'GUI Build Battle ballots and restoration')
        self.equal(before,'buildbattle-gui-voted-restored')
        from arena_acceptance import ROOT
        with sqlite3.connect(f"file:{ROOT / 'plugins/CIAACPlatform/platform.sqlite'}?mode=ro",uri=True) as c:
            row=c.execute("SELECT result_id,outcome,reason_code FROM mg_match_result WHERE game_id='build-battle' ORDER BY finished_at DESC LIMIT 1").fetchone()
            require(row and row[1:]==('VICTORY','RESULT'),'GUI ballots did not commit a completed Build Battle result')
            votes=c.execute("SELECT sum(metric_value) FROM mg_player_metric WHERE result_id=? AND metric_key='votes'",(row[0],)).fetchone()[0]
            require(votes==2,'Expected two committed GUI review ballots')
        self.emit('buildbattle-gui-theme-and-scoring',theme_votes=2,review_ballots=2,protected_fields_equal=18)

    def start_match(self, baseline, label):
        time.sleep(.8)
        for actor in (1, 2):
            self.open_menu(actor, 'coliseu')
            self.require_slot(actor, 20)
            self.click(actor, 20)
        for actor in (1, 2):
            self.wait(lambda: '[30:id=' in self.menu(actor), 20, 'GUI ready confirmation')
            self.click(actor, 30, wait_new=False)
        self.wait(lambda: self.active() == 2, 20, 'two durable ACTIVE GUI Arena players')
        time.sleep(.5)
        match = self.active_match_id()
        active = self.snapshot(label + '-active')
        expected = {tuple(float(self.arena['locations'][key][axis]) for axis in ('x', 'y', 'z')) for key in ('team-a', 'team-b')}
        require({vector(active[i]['Pos']) for i in (1, 2)} == expected, 'Both GUI-ready participants must teleport to team spawns')
        kit = self.arena['fixed-kits'][sorted(self.arena['fixed-kits'])[0]]
        require(all(all('"minecraft:' + material.lower() + '"' in active[i].get('Inventory', '') + active[i].get('equipment', '')
                        for material in kit) for i in (1, 2)), 'Both GUI-ready participants must receive every fixed-kit material')
        return match

    def arena_menu_leave(self, before):
        match = self.start_match(before, 'arena-gui-leave')
        self.leave_menu(1, 'coliseu')
        result = self.check_restoration('arena-gui-leave-restored', before, match, ('VICTORY', 'PLAYER_LEFT'))
        self.emit('arena-gui-teleport-kit-leave', **result)

    def actor_active(self, actor):
        from arena_acceptance import ROOT
        player = str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + NAMES[actor]).encode()).digest(), version=3))
        with sqlite3.connect(f"file:{ROOT / 'plugins/CIAACPlatform/platform.sqlite'}?mode=ro", uri=True) as c:
            return c.execute("SELECT count(*) FROM mg_session WHERE player_id=? AND phase='ACTIVE'", (player,)).fetchone()[0] == 1

    def other_game_entry_leave(self, before):
        for route in ROUTES[1:]:
            time.sleep(.8)
            actors = (1, 2, 3) if route == "batataquente" else (1,) if route in ("arco", "elytra") else (1, 2)
            for actor in actors:
                self.open_menu(actor, route)
                if route == 'arco':
                    require('selection=Lane:auto' in self.menu(actor), 'Archery must start with automatic lane selection')
                    self.click(actor, 10)
                    require('selection=Lane:' in self.menu(actor) and 'selection=Lane:auto' not in self.menu(actor),
                            'Archery must offer the configured lane')
                self.require_slot(actor, 20)
                self.click(actor, 20)
            self.wait(lambda: self.active() >= 1, 40 if route == 'batataquente' else 20, 'GUI admission ' + route)
            if route == 'buildbattle':
                self.wait(lambda: '[10:id=' not in self.menu(1), 8, 'stable build phase before cancellation')
            # Menu navigation must not permit another game's actions in an active session.
            self.open_menu(1)
            require('[10:id=' in self.menu(1), 'Catalog must remain accessible')
            active_before = self.actor_active(1)
            self.click(1, 10, wait_new=False)
            active_after = self.actor_active(1)
            if active_before and active_after:
                require('title=CIAAC • Coliseu ' not in self.menu(1), 'Active non-Arena player navigated into Arena')
            self.leave_menu(1, route)
            if 2 in actors: self.leave_menu(2, route)
            if 3 in actors: self.leave_menu(3, route)
            self.wait(self.clean, 20, 'complete recovery ' + route)
            self.equal(before, route + '-gui-restored', actors)
            self.emit(route + '-gui-entry-leave', protected_fields_equal=18, durable_state=self.db(), cross_game_navigation_denied=active_before and active_after)
            if route == 'elytra':
                time.sleep(.8)
                self.open_menu(1, route)
                self.require_slot(1, 20)
                self.click(1, 20)
                self.wait(lambda: self.actor_active(1), 10, 'Elytra first entry after course cleanup')
                self.leave_menu(1, route)
                self.wait(self.clean, 20, 'second Elytra restoration')
                self.equal(before, 'elytra-gui-reentry-restored', (1,))
                self.emit('elytra-gui-reentry', first_click_admitted=True, protected_fields_equal=18)


def main():
    args = parse_args(); os.umask(0o077)
    java, client, passwords, out, arena = gate(args)
    run = Menus(java, client, passwords, out, arena)
    try:
        run.start(); run.connect(1); run.connect(2); run.connect(3)
        before = run.baseline('menu-baseline', (1, 2, 3))
        run.catalog_and_transfer_denials(before)
        run.waiting_cancel(before)
        run.arena_social(before)
        run.arena_menu_leave(before)
        run.other_game_entry_leave(before)
        run.build_battle_votes(before)
        run.run_native_combat(before)
        run.emit('menu-acceptance-complete', all_nine_gui_entry_leave=True, arena_native_lethal_combat=True, no_pending_state=run.clean())
    finally:
        run.close()


if __name__ == '__main__':
    main()
