#!/usr/bin/env python3
"""Capture bounded C23 native states with fresh XML targets and saved guards.

The root runtime owner runs this script after releasing the device queue. It
never clears app data. These debug appearance captures are separate from release
startup measurements. A capture result is not a visual-review pass.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
PROJECT = ROOT / 'projects/startup-readiness'
sys.path.insert(0, str(ROOT / 'projects/commerce-app/tools'))
from capture_checkpoint_appearance import (CaptureSession, area, bounds, find_pane,
    intersection, matches, pane_key, panes, signature)


def visible_label(raw, label, package):
    """Read a whole non-clickable label within its observed scroll ancestors."""
    tree = ET.fromstring(raw)
    parents = {child: parent for parent in tree.iter() for child in parent}
    for node in tree.iter('node'):
        if node.get('package') != package or not matches(node, label):
            continue
        original = bounds(node)
        visible = original
        parent = node
        while parent in parents:
            parent = parents[parent]
            if parent.get('scrollable') == 'true':
                visible = intersection(visible, bounds(parent))
        if area(original) and visible == original:
            return True
    return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', '--out', dest='output', type=Path, required=True)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--expected-avd', default='StaffAndroid_API_36')
    parser.add_argument('--candidate', default='candidate1')
    parser.add_argument('--apk', type=Path, help='Explicit debug APK; defaults to the retained candidate artifact.')
    parser.add_argument('--configs', nargs='+', choices=['light','dark','large','wide'],
                        default=['light','dark','large','wide'])
    parser.add_argument('--max-scrolls', type=int, default=8)
    parser.add_argument('--settle-timeout', type=float, default=45)
    args = parser.parse_args()
    args.package = 'staff.android.startup'
    if not 1 <= args.max_scrolls <= 20 or not 1 <= args.settle_timeout <= 120:
        parser.error('Use finite scroll and settle bounds.')
    apk = (args.apk or PROJECT/'evidence'/args.candidate/'artifacts/app-debug.apk').resolve()
    if not apk.is_file():
        parser.error('Debug APK does not exist: ' + str(apk))
    if args.output.exists():
        parser.error('Use a new output directory; existing capture evidence is never overwritten.')
    args.output.mkdir(parents=True)
    session = CaptureSession(args)
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    report = {'status':'STARTING', 'package':args.package, 'serial':args.serial,
        'candidate':args.candidate, 'apk':str(apk), 'apkSha256':sha(apk), 'rows':[],
        'restorationErrors':[], 'inputGuards':[],
        'scope':'Debug API36 emulator appearance. Ready Shop, Search camera results, Camera detail, Lab, held/failed hero and retry, one-shot Search failure and retry in four display configurations. Not startup timing, every product, TalkBack certification or physical-device performance.'}
    original = None
    failure = None

    def save():
        (args.output/'result.json').write_text(json.dumps(report, indent=2)+'\n')

    def launch(row, hero='live', search_failure=False):
        session.adb('shell','am','force-stop',args.package)
        command = ['shell','am','start','-W','-n',args.package+'/.MainActivity',
                   '-f','0x10008000','--es','mode','deferred']
        if hero != 'live': command += ['--es','hero',hero]
        if search_failure: command += ['--ez','searchFailure','true']
        session.adb(*command)
        row['launches'].append({'mode':'deferred','hero':hero,'searchFailure':search_failure,
                                'freshProcess':True})
        return session.settled(exact=['shop_ready' if hero == 'live' else 'shop_pending'])

    def scroll(raw, count=1):
        for _ in range(count):
            available = panes(ET.fromstring(raw), args.package)
            if not available: break
            raw = session.swipe(available[-1], 'down')
        return raw

    def reveal(raw, label):
        """Find a displayed status label without trying to tap non-actions."""
        if visible_label(raw, label, args.package): return raw
        keys = [pane_key(p) for p in panes(ET.fromstring(raw), args.package)]
        for key in keys:
            for direction in ['up','down']:
                for _ in range(args.max_scrolls):
                    if visible_label(raw, label, args.package): return raw
                    pane = find_pane(ET.fromstring(raw), args.package, key)
                    if pane is None: break
                    before = signature(pane)
                    raw = session.swipe(pane, direction)
                    after = find_pane(ET.fromstring(raw), args.package, key)
                    if visible_label(raw, label, args.package): return raw
                    if after is None or signature(after) == before: break
        raise RuntimeError('Status label not visible after bounded pane search: '+label)

    def capture(row, suffix, raw, condition):
        frame = session.capture(row['config']+'-'+suffix, raw)
        frame['condition'] = condition
        row['frames'].append(frame)
        save()
        return raw

    def enter_query(row, query):
        raw = session.tap('search_input')
        tree = ET.fromstring(raw)
        focused = [n for n in tree.iter('node') if n.get('package') == args.package
                   and n.get('focused') == 'true' and n.get('class') == 'android.widget.EditText']
        if len(focused) != 1:
            raise RuntimeError('Expected one focused app EditText before typing the fixed fixture query.')
        if focused[0].get('text', '') not in ('','Search products'):
            raise RuntimeError('Query field is not empty; refusing to append to an unexpected value.')
        focus_path = args.output/f"{row['config']}-input-{query}.xml"
        focus_path.write_text(raw)
        guard = {'config':row['config'], 'query':query, 'xml':focus_path.name,
                 'focusedBounds':list(bounds(focused[0])),
                 'xmlSha256':hashlib.sha256(raw.encode()).hexdigest()}
        # Each journey uses a fresh empty query; these literals need no shell escaping.
        assert query in ('camera','c')
        session.adb('shell','input','text',query)
        ime = session.adb('shell','dumpsys','input_method')
        ime_path = args.output/f"{row['config']}-ime-{query}.txt"
        ime_path.write_bytes(ime)
        flags = re.findall(r'\bmInputShown=(true|false)\b',ime.decode(errors='replace'))
        if not flags or len(set(flags)) != 1:
            raise RuntimeError('Cannot unambiguously determine keyboard visibility; refusing blind Back.')
        guard.update(imeDump=ime_path.name,imeSha256=sha(ime_path),keyboardShown=flags[0]=='true')
        report['inputGuards'].append(guard)
        if flags[0] == 'true': session.adb('shell','input','keyevent','KEYCODE_BACK')
        return session.settled(exact=['search_input'])

    try:
        avd = session.adb('emu','avd','name').decode().strip()
        if args.expected_avd not in avd.splitlines(): raise RuntimeError('Unexpected emulator: '+avd)
        report['avd'] = avd
        original = session.settings()
        report['originalSettings'] = original
        session.adb('install','-r',apk)
        installed = session.adb('shell','pm','path',args.package).decode().strip().removeprefix('package:')
        session.adb('pull',installed,args.output/'installed.apk')
        if sha(args.output/'installed.apk') != report['apkSha256']:
            raise RuntimeError('Installed package differs from supplied APK.')
        for config in args.configs:
            session.adb('shell','am','force-stop',args.package)
            session.adb('shell','wm','size','1920x1200' if config=='wide' else 'reset')
            session.adb('shell','wm','density','240' if config=='wide' else 'reset')
            session.adb('shell','settings','put','system','font_scale','2.0' if config=='large' else '1.0')
            session.adb('shell','cmd','uimode','night','yes' if config=='dark' else 'no')
            row = {'config':config,'settings':session.settings(),'status':'STARTING','frames':[],'launches':[]}
            report['rows'].append(row)
            assert float(row['settings']['font']) == (2.0 if config=='large' else 1.0)
            assert ('Night mode: '+('yes' if config=='dark' else 'no')) in row['settings']['night']
            if config=='wide':
                assert 'Override size: 1920x1200' in row['settings']['size'] and 'Override density: 240' in row['settings']['density']
            else: assert 'Override' not in row['settings']['size'] and 'Override' not in row['settings']['density']

            raw = launch(row)
            capture(row,'shop-top',raw,'Deferred launch; accepted featured rows and Camera photo ready.')
            raw = scroll(raw,2)
            capture(row,'shop-scrolled',raw,'Same ready Shop, two bounded downward gestures when scrollable.')
            raw = session.tap('Search the collection',raw)
            raw = session.settled(exact=['search_input','Try camera, tea or reading lamp.'])
            capture(row,'search-idle',raw,'Search opened with an empty query.')
            enter_query(row,'camera')
            raw = session.settled(exact=['search_count','search_results','800 matches','camera'])
            capture(row,'search-results-top',raw,'Fixed query camera; results returned; keyboard dismissed only after visibility check.')
            raw = scroll(raw,2)
            capture(row,'search-results-scrolled',raw,'Same camera results after two bounded result-list gestures.')
            raw = session.tap('Back',raw)
            raw = session.tap('Explore Camera',raw)
            raw = session.settled(exact=['detail'])
            capture(row,'detail-top',raw,'Featured Camera detail reached from Shop.')
            raw = scroll(raw,2)
            capture(row,'detail-lower',raw,'Same Camera detail lower viewport; full content may already fit.')
            raw = session.tap('Back',raw)
            raw = session.tap('Lab',raw)
            raw = session.settled(exact=['Launch investigation'])
            capture(row,'lab-top',raw,'Lab after successful first Search; deferred route is recorded in the panel.')
            available = panes(ET.fromstring(raw),args.package)
            if available:
                coverage,raw = session.seek(pane_key(available[-1]),'down',raw=raw)
                row['labCoverage'] = coverage
                capture(row,'lab-lower',raw,'Lab lower viewport after finite scroll-to-boundary.')

            raw = launch(row,hero='hold')
            raw = reveal(raw,'Getting your collection ready…')
            capture(row,'hero-loading',raw,'Fresh deferred debug launch with hero=hold; Camera photo stays pending deterministically.')
            raw = launch(row,hero='fail')
            raw = reveal(raw,'Photo unavailable')
            capture(row,'hero-error',raw,'Fresh deferred debug launch with hero=fail; unavailable Camera photo does not make Shop ready.')
            raw = session.tap('Retry photo',raw)
            raw = session.settled(exact=['shop_ready'])
            capture(row,'hero-retry-ready',raw,'Retry photo changed the controlled failure to a successful local decode; Shop ready tag observed.')

            raw = launch(row,search_failure=True)
            raw = session.tap('Search the collection',raw)
            enter_query(row,'c')
            raw = session.settled(exact=['The collection could not be searched.','Try again'])
            capture(row,'search-error',raw,'Fresh deferred debug launch searchFailure=true; single-character c prevents later typing from consuming/retrying the one-shot failure.')
            raw = session.tap('Try again',raw)
            raw = session.settled(exact=['search_count','search_results'],contains=['matches'])
            capture(row,'search-retry-results',raw,'Explicit Try again for the same c query; returned results are captured, including an empty result if appropriate.')
            row['status'] = 'CAPTURED_NOT_REVIEWED'
            save()
            print(config+' captured',flush=True)
        if sha(apk) != report['apkSha256']: raise RuntimeError('APK changed during capture.')
        report['status'] = 'CAPTURED_NOT_REVIEWED'
    except Exception as error:
        failure = error
        report['status'] = 'FAILED'
        report['error'] = str(error)
        try: report['failureWitness'] = session.failure_witness()
        except Exception as witness_error: report['failureWitness'] = {'error':str(witness_error)}
    finally:
        commands = [('shell','am','force-stop',args.package)]
        if original:
            commands.append(('shell','settings','delete','system','font_scale') if original['font'] in ('null','') else ('shell','settings','put','system','font_scale',original['font']))
            commands.append(('shell','cmd','uimode','night',original['night'].split(':')[-1].strip()))
            for kind in ['size','density']:
                match = re.search(r'Override '+kind+r':\s*(\S+)',original[kind],re.I)
                commands.append(('shell','wm',kind,match.group(1) if match else 'reset'))
        for command in commands:
            try: session.adb(*command)
            except Exception as error: report['restorationErrors'].append(str(error))
        try:
            report['restoredSettings'] = session.settings()
            if original and report['restoredSettings'] != original:
                report['restorationErrors'].append('Restored settings differ from original settings.')
        except Exception as error: report['restorationErrors'].append(str(error))
        if report['restorationErrors'] and not failure: report['status'] = 'CAPTURED_RESTORE_FAILED'
        save()
        (args.output/'commands.json').write_text(json.dumps(session.commands,indent=2)+'\n')
        (args.output/'tap-guards.json').write_text(json.dumps(session.tap_guards,indent=2)+'\n')
    if failure: raise failure
    if report['restorationErrors']: raise RuntimeError('Settings restoration errors; inspect result.json.')


if __name__ == '__main__': main()
