#!/usr/bin/env python3
"""Checks a wireframe gallery's recording: one tool for the iOS and the
Android SDK (traceway-ios CI/wireframe/, traceway-android tools/wireframe/,
byte-identical copies).

The gallery runs real screens of native views (iOS: the Simulator on CI,
Tests/TracewayTests/WireframeGalleryTests.swift; Android: Robolectric),
records them with the real WireframeRecorder while scenarios take them
through their states, and at every checkpoint saves the picture the app drew
and the native frames of named elements. It writes events.json, run.json,
app/<checkpoint>.png and tree/<checkpoint>.txt. This script checks:

  1. structure: rrweb events in time order, a Meta event and a full
     snapshot first, mutations and touches;
  2. every mutation applies, played through a strict model of rrweb's
     replayer (a node added twice, a missing parent or sibling fails);
  3. at every checkpoint the replayed page shows the texts the scenario
     expects and none it must hide; no secret appears anywhere; animations
     recorded at least the mutations they asked for;
  4. geometry: every `boxes` entry of a checkpoint is within 1.5 px
     (position) and 2 px (size) of the replayed element whose own text is
     its text (getBoundingClientRect in rrweb-player's iframe);
  5. visual (replay.mjs, in Playwright): rrweb-player renders every
     checkpoint, compared with the app's picture (fidelity: SSIM, edge
     alignment, color) and with the accepted baseline rendering;
  6. size: the bytes each scenario recorded, against the baseline;
  7. with --dashboard (dashboard.mjs): the run uploaded to a running
     Traceway as a real session, played by the dashboard's session page at
     every checkpoint: no rrweb errors, the texts, the geometry, and the
     same picture as the standalone rendering.

  # The gallery of a CI run (needs gh):
  python3 CI/wireframe/run.py --gh-run 123456789
  # A gallery on disk (WIREFRAME_GALLERY_OUT):
  python3 CI/wireframe/run.py --dir .build/wireframe-gallery
  # A clip with checkpoints.json and views/ (Android's WireframeSessionTest):
  python3 tools/wireframe/run.py --dir traceway/build/wireframe-sessions/views
  # The same through a running Traceway and its dashboard:
  python3 CI/wireframe/run.py --dir … --dashboard http://127.0.0.1:15183 --traceway http://127.0.0.1:18182

  --update-baseline  accepts the run as the new baseline (review report.html)
  --out DIR          where the run's files and report.html go

Needs Node with Playwright (--playwright), Chromium (--chromium) and
rrweb-player (--rrweb-player, default the dashboard's copy in
../traceway/frontend/node_modules).
"""
import argparse, fnmatch, html, json, os, re, shutil, subprocess, sys, urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
HOME_PLATFORM = 'android' if 'android' in os.path.basename(REPO) else 'ios'
SCREEN_NODE_ID = 8
PLATFORM_NAMES = {'ios': 'iOS', 'android': 'Android', 'flutter': 'Flutter'}
SEEDED = {  # examples/devtesting-embedded's projects
    'ios': ('iOS App', 'ios-dev-token'),
    'android': ('Android App', 'android-dev-token'),
    'flutter': ('Flutter App', 'flutter-dev-token'),
}


class Check:
    def __init__(self):
        self.failures = []

    def __call__(self, ok, what, detail=''):
        print(('  PASS  ' if ok else '  FAIL  ') + what + (f'   [{detail}]' if detail else ''), flush=True)
        if not ok:
            self.failures.append(what + (f' [{detail}]' if detail else ''))
        return ok


# ---- rrweb, strictly --------------------------------------------------------------

class Mirror:
    """rrweb's replayer, strictly: see the SDKs' WireframeTestSupport."""

    def __init__(self):
        self.nodes = {}
        self.errors = []

    def _build(self, n, parent, with_children):
        nid = n['id']
        if nid in self.nodes:
            self.errors.append(f'id {nid} added while on the page')
        node = {'id': nid, 'tag': n.get('tagName') if n['type'] == 2 else None,
                'text': n.get('textContent') if n['type'] == 3 else None,
                'attrs': dict(n.get('attributes') or {}), 'children': [], 'parent': parent}
        self.nodes[nid] = node
        if with_children:
            for child in n.get('childNodes') or []:
                node['children'].append(self._build(child, node, True))
        return node

    def _unmap(self, node):
        self.nodes.pop(node['id'], None)
        for c in node['children']:
            self._unmap(c)

    def full(self, node):
        self.nodes = {}
        self._build(node, None, True)

    def mutation(self, d, at):
        for key in ('removes', 'adds', 'texts', 'attributes'):
            if not isinstance(d.get(key), list):
                self.errors.append(f'{at}: {key} is not a list')
                return
        for r in d['removes']:
            node, parent = self.nodes.get(r['id']), self.nodes.get(r['parentId'])
            if node is None or parent is None or node not in parent['children']:
                self.errors.append(f'{at}: bad remove {r}')
                continue
            parent['children'].remove(node)
            self._unmap(node)
        for a in d['adds']:
            parent = self.nodes.get(a['parentId'])
            if parent is None:
                self.errors.append(f'{at}: add under missing {a["parentId"]}')
                continue
            node = self._build(a['node'], parent, False)
            nxt = a.get('nextId')
            if nxt is None:
                parent['children'].append(node)
            elif nxt in self.nodes and self.nodes[nxt] in parent['children']:
                parent['children'].insert(parent['children'].index(self.nodes[nxt]), node)
            else:
                self.errors.append(f'{at}: add before missing {nxt}')
                parent['children'].append(node)
        for t in d['texts']:
            node = self.nodes.get(t['id'])
            if node is None or node['tag'] is not None:
                self.errors.append(f'{at}: bad text {t["id"]}')
                continue
            node['text'] = t['value']
        for a in d['attributes']:
            node = self.nodes.get(a['id'])
            if node is None:
                self.errors.append(f'{at}: attributes of missing {a["id"]}')
                continue
            for k, v in a['attributes'].items():
                if v is None:
                    node['attrs'].pop(k, None)
                else:
                    node['attrs'][k] = v

    def texts(self):
        out = []

        def walk(n):
            if n['text'] is not None:
                out.append(n['text'])
            for c in n['children']:
                walk(c)
        screen = self.nodes.get(SCREEN_NODE_ID)
        if screen:
            walk(screen)
        return out


def play(events, moments):
    """Plays [events] and returns the text on screen at each moment (ms): what rrweb's replayer shows when it seeks
    there, the events from before that moment (one at the very moment is still to come)."""
    mirror = Mirror()
    shown = {}
    pending = sorted(moments.items(), key=lambda kv: kv[1])
    for i, e in enumerate(events):
        while pending and pending[0][1] <= e['timestamp']:
            name, _ = pending.pop(0)
            shown[name] = mirror.texts()
        if e['type'] == 2:
            mirror.full(e['data']['node'])
        elif e['type'] == 3 and e['data'].get('source') == 0:
            mirror.mutation(e['data'], f'event {i}')
        elif e['type'] == 3 and e['data'].get('source') == 2 and e['data'].get('id') not in mirror.nodes:
            mirror.errors.append(f'event {i}: touch on a node not on the page')
    for name, _ in pending:
        shown[name] = mirror.texts()
    return mirror, shown


# ---- input -------------------------------------------------------------------------

def platform_family(value):
    """'ios-simulator' and 'iOS' are ios; the baseline directory takes the family."""
    value = (value or 'ios').lower()
    for family in PLATFORM_NAMES:
        if value.startswith(family):
            return family
    return value


TREE_LINE = re.compile(r'^( *)\S+ view=(\S+)(?: text="(.*?)")?.*?frame=\((-?[\d.]+),(-?[\d.]+) (-?[\d.]+)x(-?[\d.]+)\)(.*)$')


def boxes_from_tree(path, width, height, secrets):
    """The frames of the visible, uniquely labelled UILabels in an iOS layer dump (tree/<checkpoint>.txt), which
    stand in for run.json `boxes` until the gallery writes them. The dump's frames are window coordinates; a label
    under a hidden or transparent layer, outside a clipping ancestor or the window, or holding a secret is left out."""
    if not os.path.exists(path):
        return []
    found, stack = [], []
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            m = TREE_LINE.match(line.replace('corners=<', 'corners=').rstrip('\n'))
            if not m:
                continue
            depth = len(m.group(1))
            while stack and stack[-1]['depth'] >= depth:
                stack.pop()
            x, y, w, h = (float(m.group(i)) for i in range(4, 8))
            flags = line
            hidden = 'HIDDEN-VIEW' in flags or ' hidden' in m.group(8) or 'opacity=0.00' in m.group(8)
            node = {'depth': depth, 'frame': (x, y, w, h), 'clips': ' clips' in m.group(8),
                    'hidden': hidden or any(a['hidden'] for a in stack)}
            stack.append(node)
            text = m.group(3)
            if m.group(2) != 'UILabel' or not text or not text.strip() or node['hidden'] or w <= 0 or h <= 0:
                continue
            visible = [(0, 0, width, height)] + [a['frame'] for a in stack[:-1] if a['clips']]
            if any(x + w <= cx or y + h <= cy or x >= cx + cw or y >= cy + ch for cx, cy, cw, ch in visible):
                continue
            if any(secret in text for secret in secrets):
                continue
            found.append({'text': text, 'x': x, 'y': y, 'w': w, 'h': h, 'source': 'tree'})
    counts = {}
    for b in found:
        counts[b['text']] = counts.get(b['text'], 0) + 1
    return [b for b in found if counts[b['text']] == 1]


def fetch(args, out):
    """Copies the run into [out] from --dir or the artifact of --gh-run, and returns where it came from."""
    if args.dir:
        source = os.path.abspath(args.dir)
    else:
        source = os.path.join(out, 'download')
        shutil.rmtree(source, ignore_errors=True)
        subprocess.run(['gh', 'run', 'download', str(args.gh_run), '-n', args.artifact, '-D', source,
                        '-R', args.repo], check=True)
    if os.path.abspath(source) != os.path.abspath(out):
        shutil.copy(os.path.join(source, 'events.json'), os.path.join(out, 'events.json'))
        for name in ('run.json', 'checkpoints.json'):
            if os.path.exists(os.path.join(source, name)):
                shutil.copy(os.path.join(source, name), os.path.join(out, name))
        pictures = next((os.path.join(source, d) for d in ('app', 'views') if os.path.isdir(os.path.join(source, d))), None)
        shutil.rmtree(os.path.join(out, 'app'), ignore_errors=True)
        if pictures:
            shutil.copytree(pictures, os.path.join(out, 'app'))
    return source


def load_run(args, source, out, events):
    """run.json (CONTRACT §5.2) with its defaults filled in, or one made for a clip that has only checkpoints.json
    ({name: epoch ms}, Android's WireframeSessionTest and WireframeSampleClipTest)."""
    metas = [e for e in events if e['type'] == 4]
    meta = metas[0]['data'] if metas else {'width': 0, 'height': 0}
    if os.path.exists(os.path.join(out, 'run.json')):
        with open(os.path.join(out, 'run.json')) as f:
            run = json.load(f)
        run['gallery'] = True
    else:
        with open(os.path.join(out, 'checkpoints.json')) as f:
            moments = json.load(f)
        name = os.path.basename(source.rstrip('/'))
        # A clip's checkpoint names the moment after its frames: a frame stamped with that very time already
        # belongs to what follows, and rrweb leaves it out when it seeks there.
        run = {'platform': 'android', 'device': f'clip {name}', 'os': '', 'gallery': False, 'clip': name,
               'sampleMs': 0, 'checkpoints': [{'name': k, 'scenario': name, 'at': v} for k, v in moments.items()]}
    run['family'] = platform_family(run.get('platform'))
    run.setdefault('width', meta.get('width'))
    run.setdefault('height', meta.get('height'))
    run.setdefault('density', 1)
    run.setdefault('secrets', [])
    run.setdefault('sampleMs', 150)
    for cp in run['checkpoints']:
        for key in ('shows', 'hides', 'ignore', 'boxes'):
            cp.setdefault(key, [])
        cp.setdefault('minMutations', 0)
        cp.setdefault('scenario', cp['name'])
        if args.boxes_from_tree and not cp['boxes']:
            cp['boxes'] = boxes_from_tree(os.path.join(source, 'tree', f"{cp['name']}.txt"), run['width'], run['height'],
                                          run['secrets'])
    run['checkpoints'].sort(key=lambda cp: cp['at'])
    key = f"{run['family']}-{int(run['width'])}x{int(run['height'])}"
    run['baselineKey'] = key if run['gallery'] else f"{key}-{run['clip']}"
    return run


def selected(run, only):
    if not only:
        return run['checkpoints']
    patterns = [p for p in only.split(',') if p]
    return [cp for cp in run['checkpoints']
            if any(fnmatch.fnmatch(cp['name'], p) or fnmatch.fnmatch(cp['scenario'], p) for p in patterns)]


# ---- checks -----------------------------------------------------------------------

def check_recording(check, events, run):
    cps = run['checkpoints']
    size = len(json.dumps(events))
    kinds = {}
    for e in events:
        key = e['type'] if e['type'] != 3 else f"3.{e['data'].get('source')}"
        kinds[key] = kinds.get(key, 0) + 1
    print(f'  ...  {len(events)} events, {size // 1024} KB as JSON: {kinds}', flush=True)
    check(bool(events) and events[0]['type'] == 4 and len(events) > 1 and events[1]['type'] == 2,
          'the recording starts with a meta event and a full snapshot')
    check(all(e['type'] in (2, 3, 4, 5) for e in events), 'every event is an rrweb event',
          str({e['type'] for e in events}))
    stamps = [e['timestamp'] for e in events]
    check(stamps == sorted(stamps), 'events are in time order')
    if cps:
        check(bool(events) and events[0]['timestamp'] <= cps[0]['at'] and
              events[-1]['timestamp'] >= cps[-1]['at'], 'the recording covers every checkpoint')
    if run['gallery']:
        check(kinds.get('3.0', 0) > 8, 'the screen changes are mutations', f"{kinds.get('3.0', 0)} mutations")
        check(kinds.get('3.2', 0) > 4, 'touches are recorded', f"{kinds.get('3.2', 0)} touch interactions")
        check(kinds.get('3.6', 0) > 4, 'drags are recorded as touch moves', f"{kinds.get('3.6', 0)} moves")
    else:
        check(kinds.get('3.0', 0) > 0, 'the screen changes are mutations', f"{kinds.get('3.0', 0)} mutations")
    return {'events': len(events), 'bytes': size, 'kinds': kinds}


def shows_and_hides(cp, texts):
    joined = '\n'.join(texts)
    problems = []
    missing = [w for w in cp['shows'] if w not in joined]
    leaked = [h for h in cp['hides'] if h in joined]
    if missing:
        problems.append(f'missing {missing}')
    if leaked:
        problems.append(f'shows {leaked}')
    return problems


def check_checkpoints(check, events, run, cps, sample):
    mirror, shown = play(events, {cp['name']: cp['at'] + sample for cp in run['checkpoints']})
    check(not mirror.errors, 'every mutation applies as rrweb replays it', '; '.join(mirror.errors[:5]))
    mutations = sorted(e['timestamp'] for e in events if e['type'] == 3 and e['data'].get('source') == 0)
    results = {}
    previous = 0
    wanted = {cp['name'] for cp in cps}
    for cp in run['checkpoints']:
        name = cp['name']
        problems = shows_and_hides(cp, shown.get(name, []))
        count = sum(1 for t in mutations if previous < t <= cp['at'])
        if cp.get('minMutations') and count < cp['minMutations']:
            problems.append(f'{count} mutations since the last checkpoint, wanted {cp["minMutations"]}')
        previous = cp['at']
        if name not in wanted:
            continue
        check(not problems, f'replay at {name} shows that screen', '; '.join(problems))
        results[name] = {'problems': problems, 'mutations': count, 'texts': shown.get(name, [])}
    text = json.dumps(events, ensure_ascii=False)
    leaked = [s for s in run['secrets'] if s in text]
    check(not leaked, 'no secret appears anywhere in the recording', f'leaked {leaked}' if leaked else
          f'{len(run["secrets"])} secrets')
    return results


def scenario_spans(run):
    """[(scenario, start, end)]: from run.json `scenarios`, or else from where each scenario's checkpoints begin."""
    starts = sorted(run.get('scenarios') or [], key=lambda s: s['at'])
    if starts:
        spans = [(s['id'], s['at']) for s in starts]
    else:
        spans, previous_end = [], None
        for cp in run['checkpoints']:
            if not spans or spans[-1][0] != cp['scenario']:
                spans.append((cp['scenario'], previous_end if previous_end is not None else float('-inf')))
            previous_end = cp['at'] + 1
    return [(sid, at, spans[i + 1][1] if i + 1 < len(spans) else float('inf')) for i, (sid, at) in enumerate(spans)]


def scenario_bytes(events, run):
    out = {}
    for sid, start, end in scenario_spans(run):
        chosen = [e for e in events if start <= e['timestamp'] < end]
        out[sid] = {'bytes': len(json.dumps(chosen)), 'events': len(chosen),
                    'mutations': sum(1 for e in chosen if e['type'] == 3 and e['data'].get('source') == 0)}
    return out


def geometry_problems(boxes):
    problems = []
    for b in boxes:
        if b.get('got') is None:
            problems.append(f'no element with the text {b["text"]!r}')
        elif not b['ok']:
            problems.append(f'{b["text"]!r} off by x {b["dx"]:+.1f} y {b["dy"]:+.1f} w {b["dw"]:+.1f} h {b["dh"]:+.1f}')
    return problems


def evaluate_visual(check, visual, baseline, run, cps, args):
    known = baseline.get('checkpoints', {})
    floors = {'ssim': args.floor_ssim, 'edges': args.floor_edges, 'color': args.floor_color}
    verdicts = {}
    by_name = {cp['name']: cp for cp in cps}
    for name, v in visual['checkpoints'].items():
        if name not in by_name:
            continue
        problems = []
        fid = v.get('fidelity')
        base = (known.get(name) or {}).get('fidelity') or {}
        if fid is None:
            problems_note = 'no app picture'
        else:
            problems_note = ''
            for metric, floor in floors.items():
                value = fid.get(metric)
                if value is None:
                    problems.append(f'no {metric}')
                    continue
                if value < floor:
                    problems.append(f'{metric} {value:.3f} below the floor {floor}')
                if metric in base and value < base[metric] - args.tolerance:
                    problems.append(f'{metric} {value:.3f} fell from {base[metric]:.3f}')
        golden = v.get('golden')
        if golden is not None and golden['changed'] > args.max_change:
            problems.append(f'{golden["changed"] * 100:.2f}% of pixels changed from the baseline')
        if v.get('screens', 1) != 1:
            problems.append(f'{v.get("screens")} screen nodes in the replay')
        rendered = shows_and_hides(by_name[name], v.get('texts') or [])
        problems += [f'rrweb-player: {p}' for p in rendered]
        verdicts[name] = problems
        f = fid or {}
        check(not problems, f'visual {name}', '; '.join(problems) if problems else (problems_note or
              f'ssim {f.get("ssim", 0):.3f} edges {f.get("edges", 0):.3f} color {f.get("color", 0):.3f}')
              + (f', {golden["changed"] * 100:.2f}% changed' if golden else ''))
        boxes = v.get('geometry') or []
        if boxes:
            gp = geometry_problems(boxes)
            verdicts[name] = verdicts[name] + gp
            worst = max((max(abs(b['dx']), abs(b['dy'])) for b in boxes if b.get('got')), default=0)
            check(not gp, f'geometry {name}', '; '.join(gp[:4]) + (f' (+{len(gp) - 4})' if len(gp) > 4 else '')
                  if gp else f'{len(boxes)} boxes, worst position {worst:.1f} px')
    for problem in visual.get('problems', []):
        check(False, 'replay', problem)
    return verdicts


def check_sizes(check, sizes, baseline, cps, args):
    known = baseline.get('scenarios', {})
    wanted = {cp['scenario'] for cp in cps}
    for sid, size in sizes.items():
        base = (known.get(sid) or {}).get('bytes')
        if base is None or args.update_baseline or sid not in wanted:
            continue
        check(size['bytes'] <= base * 1.5 + 20000, f'scenario {sid} records about as much as before',
              f'{size["bytes"] // 1024} KB, baseline {base // 1024} KB')


def layout_differences(standalone, dashboard, slack=0.5):
    """Where the dashboard's replay iframe lays the screen out differently from rrweb-player's (same elements, same
    classes, boxes within [slack] px, same own text)."""
    if len(standalone) != len(dashboard):
        return [f'{len(dashboard)} elements in the dashboard, {len(standalone)} in rrweb-player']
    differ = [(a, b) for a, b in zip(standalone, dashboard)
              if a[0] != b[0] or a[1] != b[1] or a[6] != b[6] or any(abs(a[i] - b[i]) > slack for i in range(2, 6))]
    if not differ:
        return []
    a, b = differ[0]
    return [f'{len(differ)} element(s) laid out differently from rrweb-player, first {a[0]}.{a[1]} '
            f'{a[2:6]} vs {b[2:6]}']


def evaluate_dashboard(check, dash, visual, run, cps, args):
    """The dashboard's session page against the checkpoints and the standalone rendering."""
    verdicts = {}
    for problem in dash.get('problems', []):
        check(False, 'dashboard', problem)
    if dash.get('session'):
        s = dash['session']
        print(f"  ...  dashboard session {s.get('url')}", flush=True)
    errors = dash.get('rrwebErrors') or []
    check(not errors, 'the dashboard player reports no missing nodes or replay errors',
          ' | '.join(errors[:3]) if errors else f"{len(dash.get('console') or [])} other console messages")
    for cp in cps:
        name = cp['name']
        d = (dash.get('checkpoints') or {}).get(name)
        if d is None:
            if dash.get('session'):
                check(False, f'dashboard {name}', 'not shown')
            continue
        problems = list(d.get('problems') or [])
        problems += shows_and_hides(cp, d.get('texts') or [])
        standalone = (visual.get('checkpoints') or {}).get(name) or {}
        if standalone.get('texts') is not None and sorted(standalone['texts']) != sorted(d.get('texts') or []):
            only_dash = sorted(set(d.get('texts') or []) - set(standalone['texts']))[:4]
            only_alone = sorted(set(standalone['texts']) - set(d.get('texts') or []))[:4]
            problems.append(f'texts differ from rrweb-player: dashboard only {only_dash}, standalone only {only_alone}')
        problems += geometry_problems(d.get('geometry') or [])
        if standalone.get('layout') is not None and d.get('layout') is not None:
            problems += layout_differences(standalone['layout'], d['layout'])
        agree = d.get('agreement')
        if agree:
            f = agree['fidelity']
            if f['ssim'] < args.dashboard_floor:
                problems.append(f"ssim {f['ssim']:.3f} against rrweb-player, below {args.dashboard_floor}")
            if agree.get('changed') is not None and agree['changed'] > args.dashboard_max_change:
                problems.append(f"{agree['changed'] * 100:.2f}% of pixels differ from rrweb-player")
        elif standalone:
            problems.append('no comparison with rrweb-player')
        verdicts[name] = problems
        detail = '; '.join(problems) if problems else (
            f"ssim {agree['fidelity']['ssim']:.3f} edges {agree['fidelity']['edges']:.3f}, "
            f"{agree['changed'] * 100:.2f}% differ" if agree else 'shown')
        check(not problems, f'dashboard {name}', detail)
    return verdicts


# ---- report -------------------------------------------------------------------------

def write_report(out, run, cps, stats, sizes, text_results, visual, verdicts, baseline, check, dash, dash_verdicts):
    e = html.escape
    known = baseline.get('checkpoints', {})
    title = f"{PLATFORM_NAMES.get(run['family'], run['family'])} wireframe {'gallery' if run['gallery'] else 'clip ' + run['clip']}"
    rows = []
    for cp in cps:
        name = cp['name']
        file = urllib.parse.quote(name)
        v = visual.get('checkpoints', {}).get(name, {})
        fid = v.get('fidelity') or {}
        base = (known.get(name) or {}).get('fidelity') or {}
        golden = v.get('golden')
        d = (dash or {}).get('checkpoints', {}).get(name)
        problems = (text_results.get(name) or {}).get('problems', []) + verdicts.get(name, [])
        dproblems = (dash_verdicts or {}).get(name, [])

        def metric(k):
            value = fid.get(k)
            if value is None:
                return '–'
            delta = '' if k not in base else f' <small>({value - base[k]:+.3f})</small>'
            return f'{value:.3f}{delta}'

        def img(p):
            return (f'<a href="{p}"><img src="{p}" loading="lazy"></a>'
                    if os.path.exists(os.path.join(out, urllib.parse.unquote(p))) else '<span class="none">–</span>')

        boxes = v.get('geometry') or []
        geo = '–'
        if boxes:
            ok = sum(1 for b in boxes if b.get('ok'))
            found = [b for b in boxes if b.get('got')]
            pos = max((max(abs(b['dx']), abs(b['dy'])) for b in found), default=0)
            size = max((max(abs(b['dw']), abs(b['dh'])) for b in found), default=0)
            geo = f'{ok}/{len(boxes)} <small>(pos ≤{pos:.1f}, size ≤{size:.1f})</small>'
        geo_table = ''
        if boxes:
            dboxes = {b['text']: b for b in (d or {}).get('geometry') or []}
            body = []
            for b in boxes:
                db = dboxes.get(b['text'])

                def cell(x):
                    if not x or x.get('got') is None:
                        return '<td colspan="4" class="bad">not found</td>'
                    cls = lambda v, lim: ' class="bad"' if abs(v) > lim else ''
                    return (f'<td{cls(x["dx"], 1.5)}>{x["dx"]:+.1f}</td><td{cls(x["dy"], 1.5)}>{x["dy"]:+.1f}</td>'
                            f'<td{cls(x["dw"], 2)}>{x["dw"]:+.1f}</td><td{cls(x["dh"], 2)}>{x["dh"]:+.1f}</td>')
                want = ' '.join(f'{x:.1f}' for x in b['want'])
                got = ' '.join(f'{x:.1f}' for x in b['got']) if b.get('got') else '–'
                body.append(f'<tr><td>{e(b["text"][:40])}</td><td>{want}</td><td>{got}</td>{cell(b)}'
                            + (cell(db) if dash else '') + '</tr>')
            dhead = '<th colspan="4">dashboard Δx Δy Δw Δh</th>' if dash else ''
            geo_table = (f'<details><summary>geometry ({len(boxes)} boxes)</summary><table class="g"><tr><th>text</th>'
                         f'<th>native x y w h</th><th>replay x y w h</th><th colspan="4">Δx Δy Δw Δh</th>{dhead}</tr>'
                         + ''.join(body) + '</table></details>')
        dash_cells = ''
        if dash:
            agree = (d or {}).get('agreement') or {}
            af = agree.get('fidelity') or {}
            dmetrics = (f"ssim {af['ssim']:.3f} · edges {af['edges']:.3f}<br>{agree['changed'] * 100:.2f}% differ"
                        if af else '–')
            dash_cells = (f'<td>{img(f"dashboard/{file}.png")}<div class="dm"><span class="badge {"bad" if dproblems else ""}">'
                          f'{"FAIL" if dproblems else "PASS"}</span> {dmetrics}</div>'
                          + ''.join(f'<div class="p">{e(p)}</div>' for p in dproblems) + '</td>'
                          f'<td>{img(f"dashboard-diff/{file}.png")}</td>')
        failed = bool(problems or dproblems)
        rows.append(f'''<tr class="{'fail' if failed else 'pass'}" id="{e(name)}">
<th><b>{e(name)}</b><br><span class="badge {'bad' if problems else ''}">{'FAIL' if problems else 'PASS'}</span>
<table class="m"><tr><td>SSIM</td><td>{metric('ssim')}</td></tr><tr><td>edges</td><td>{metric('edges')}</td></tr>
<tr><td>color</td><td>{metric('color')}</td></tr>
<tr><td>changed</td><td>{'–' if golden is None else f"{golden['changed'] * 100:.2f}%"}</td></tr>
<tr><td>mutations</td><td>{(text_results.get(name) or {}).get('mutations', '–')}</td></tr>
<tr><td>geometry</td><td>{geo}</td></tr></table>
{''.join(f'<div class="p">{e(p)}</div>' for p in problems)}{geo_table}</th>
<td>{img(f'app/{file}.png')}</td><td>{img(f'replay/{file}.png')}</td>
<td>{img(f'delta/{file}.png')}</td><td>{img(f'baseline/{file}.png')}</td><td>{img(f'diff/{file}.png')}</td>{dash_cells}</tr>''')
    size_rows = ''.join(
        f'<tr><td>{e(sid)}</td><td>{s["bytes"] // 1024} KB</td><td>{s["events"]}</td><td>{s["mutations"]}</td>'
        f'<td>{(baseline.get("scenarios", {}).get(sid) or {}).get("bytes", 0) // 1024} KB</td></tr>'
        for sid, s in sizes.items())
    failures = ''.join(f'<li>{e(f)}</li>' for f in check.failures)
    passed = not check.failures
    dash_head = '<th>Dashboard</th><th>Dashboard vs replay</th>' if dash else ''
    dash_summary = ''
    if dash and dash.get('session'):
        s = dash['session']
        console = ''.join(f'<li>{e(m)}</li>' for m in (dash.get('console') or [])[:20])
        dash_summary = (f'<h2>Dashboard</h2><p>Session <a href="{e(s["url"])}">{e(s["id"])}</a> in “{e(s["project"])}” '
                        f'({s["segments"]} segment(s), {s["bytes"] // 1024} KB gzipped, scope {e(s["sdk"])}); '
                        f'player scale {s.get("scale", 0):.3f}, screenshots at device scale {s.get("deviceScale", 1):.3f}. '
                        f'Seeks by clicking the checkpoint rows of the session page’s Actions tab.</p>'
                        + (f'<details><summary>other console messages ({len(dash.get("console") or [])})</summary><ul>{console}</ul></details>' if console else '')
                        + ('<img src="dashboard/session-page.png" style="width:100%;max-width:1440px">'
                           if os.path.exists(os.path.join(out, 'dashboard', 'session-page.png')) else ''))
    with open(os.path.join(out, 'report.html'), 'w') as f:
        f.write(f'''<!doctype html><meta charset="utf-8"><title>{e(title)}</title>
<style>
body{{font:14px system-ui;margin:16px;color:#222}} h1{{margin:0 0 4px}}
.sum{{padding:10px 14px;border-radius:8px;margin:12px 0;background:{'#e8f5e9' if passed else '#ffebee'}}}
table.r{{border-collapse:collapse}} table.r>tbody>tr>td,table.r>tbody>tr>th{{vertical-align:top;padding:6px;border-bottom:1px solid #ddd}}
table.r th{{text-align:left;width:230px;font-weight:normal}} img{{width:200px;border:1px solid #ccc;display:block}}
.badge{{background:#2e7d32;color:#fff;border-radius:4px;padding:1px 6px;font-size:11px}} .badge.bad{{background:#c62828}}
table.m td{{padding:0 8px 0 0;font-size:12px}} .p{{color:#c62828;font-size:12px;margin-top:4px;max-width:230px;overflow-wrap:anywhere}} .none{{color:#aaa}}
.dm{{font-size:12px;margin-top:4px}} table.g{{font-size:11px;border-collapse:collapse}} table.g td,table.g th{{padding:0 4px;white-space:nowrap}}
table.g .bad{{color:#c62828;font-weight:600}} details{{margin-top:6px}}
label{{margin-right:12px}} table.s td{{padding:2px 10px}}
</style>
<h1>{e(title)}</h1>
<div>{e(str(run.get('device', '')))} {e(PLATFORM_NAMES.get(run['family'], ''))} {e(str(run.get('os', '')))} · {run['width']}×{run['height']} ·
{len(cps)} checkpoints · {stats.get('events', 0)} events · {stats.get('bytes', 0) // 1024} KB · baseline {e(run['baselineKey'])}</div>
<div class="sum"><b>{'PASSED' if passed else f'FAILED: {len(check.failures)} check(s)'}</b><ul>{failures}</ul></div>
<label><input type="checkbox" id="onlyfail"> failures only</label>
<p>Columns: what the app drew · the replay (rrweb-player at 1:1) · where they differ (brighter is more) ·
the accepted baseline rendering · the replay's changes from it (red; yellow is anti-aliasing){' · the dashboard session page’s player at the same moment · its changes from the replay' if dash else ''}.
SSIM, edges and color compare the replay with the app (1 is identical; in brackets the change from the baseline).
Geometry: boxes within 1.5 px (position) and 2 px (size) of the native frames, and the largest deltas.</p>
<table class="r"><tr><th></th><th>App</th><th>Replay</th><th>App vs replay</th><th>Baseline</th><th>Changes</th>{dash_head}</tr>
{''.join(rows)}</table>
<h2>Size per scenario</h2><table class="s"><tr><th>scenario</th><th>bytes</th><th>events</th><th>mutations</th><th>baseline</th></tr>{size_rows}</table>
{dash_summary}
<script>document.getElementById('onlyfail').onchange=e=>document.querySelectorAll('tr.pass').forEach(r=>r.style.display=e.target.checked?'none':'')</script>
''')


# ---- main -------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    where = ap.add_mutually_exclusive_group(required=True)
    where.add_argument('--dir', help='a gallery directory (events.json, run.json, app/) or a clip (events.json, checkpoints.json, views/)')
    where.add_argument('--gh-run', help='a CI run whose gallery artifact to check')
    ap.add_argument('--repo', default=f'tracewayapp/traceway-{HOME_PLATFORM}', help='the GitHub repository of --gh-run')
    ap.add_argument('--artifact', default='wireframe-gallery', help='the artifact of --gh-run')
    ap.add_argument('--out', default=os.path.expanduser(f'~/.cache/tw-wireframe/{HOME_PLATFORM}'))
    ap.add_argument('--baseline', default=os.path.join(HERE, 'baseline'))
    ap.add_argument('--update-baseline', action='store_true')
    ap.add_argument('--only', help='comma-separated checkpoint or scenario names (shell patterns) to check and render')
    ap.add_argument('--tolerance', type=float, default=0.03, help='how far a fidelity metric may fall')
    ap.add_argument('--max-change', type=float, default=0.002, help='share of pixels that may change from the baseline')
    ap.add_argument('--floor-ssim', type=float, default=0.5)
    ap.add_argument('--floor-edges', type=float, default=0.3)
    ap.add_argument('--floor-color', type=float, default=0.6)
    ap.add_argument('--geometry-position', type=float, default=1.5, help='px a box may be off')
    ap.add_argument('--geometry-size', type=float, default=2.0, help='px a box may be wider, narrower, taller or shorter')
    ap.add_argument('--boxes-from-tree', action='store_true',
                    help='checkpoints without boxes take the visible, unique UILabel frames of tree/<checkpoint>.txt (iOS)')
    ap.add_argument('--playwright', default=os.environ.get('PLAYWRIGHT_DIR', os.path.expanduser('~/Work/workspace/js-client/node_modules/playwright')))
    ap.add_argument('--rrweb-player', default=os.path.join(REPO, '..', 'traceway', 'frontend', 'node_modules', 'rrweb-player'))
    ap.add_argument('--chromium', default=shutil.which('chromium') or '/usr/bin/chromium')
    ap.add_argument('--no-visual', action='store_true', help='skip the rendering in rrweb-player')
    dash = ap.add_argument_group('dashboard (a running Traceway, e.g. examples/devtesting-embedded)')
    dash.add_argument('--dashboard', help='the dashboard origin, e.g. http://127.0.0.1:15183 (vite dev) or the backend itself')
    dash.add_argument('--traceway', help='the backend origin for ingest and the API (default: --dashboard)')
    dash.add_argument('--email', default='admin@localhost.com')
    dash.add_argument('--password', default='admin')
    dash.add_argument('--project', help='the project to upload into (default: the seeded "<Platform> App")')
    dash.add_argument('--token', help="the project's ingest token (default: the seeded <platform>-dev-token)")
    dash.add_argument('--sdk-version', default='2.0.0-beta.1', help='the scope version the session reports')
    dash.add_argument('--dashboard-floor', type=float, default=0.85,
                      help="SSIM the dashboard's rendering keeps against rrweb-player's; the dashboard scales a phone replay down (about 0.8), which softens dense text to about 0.9, while a wrong frame or lost styles fall far below 0.85")
    dash.add_argument('--dashboard-max-change', type=float, default=0.05,
                      help="share of pixels that may differ between the dashboard's (scaled) rendering and rrweb-player's")
    args = ap.parse_args()
    if args.dashboard and args.no_visual:
        ap.error('--dashboard compares with the standalone rendering; drop --no-visual')

    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    source = fetch(args, out)
    with open(os.path.join(out, 'events.json')) as f:
        events = json.load(f)
    run = load_run(args, source, out, events)
    cps = selected(run, args.only)
    if not cps:
        sys.exit(f'--only {args.only} matches no checkpoint')
    run['selected'] = [cp['name'] for cp in cps]
    with open(os.path.join(out, 'check.json'), 'w') as f:
        json.dump(run, f, indent=1, ensure_ascii=False)
    baseline_dir = os.path.join(args.baseline, run['baselineKey'])
    baseline = {}
    if os.path.exists(os.path.join(baseline_dir, 'baseline.json')):
        with open(os.path.join(baseline_dir, 'baseline.json')) as f:
            baseline = json.load(f)

    check = Check()
    sample = run['sampleMs']
    stats = check_recording(check, events, run)
    text_results = check_checkpoints(check, events, run, cps, sample)
    sizes = scenario_bytes(events, run)
    check_sizes(check, sizes, baseline, cps, args)

    visual, verdicts = {'checkpoints': {}}, {}
    if not args.no_visual:
        subprocess.run(['node', os.path.join(HERE, 'replay.mjs'), '--out', out, '--playwright', args.playwright,
                        '--rrweb-player', os.path.abspath(args.rrweb_player), '--chromium', args.chromium,
                        '--baseline', baseline_dir, '--geometry-position', str(args.geometry_position),
                        '--geometry-size', str(args.geometry_size)], check=True)
        with open(os.path.join(out, 'visual.json')) as f:
            visual = json.load(f)
        verdicts = evaluate_visual(check, visual, baseline, run, cps, args)

    dash_result, dash_verdicts = None, {}
    if args.dashboard:
        project, token = SEEDED.get(run['family'], (None, None))
        dash_json = os.path.join(out, 'dashboard.json')
        if os.path.exists(dash_json):
            os.remove(dash_json)
        subprocess.run(['node', os.path.join(HERE, 'dashboard.mjs'), '--out', out, '--playwright', args.playwright,
                        '--chromium', args.chromium, '--dashboard', args.dashboard.rstrip('/'),
                        '--traceway', (args.traceway or args.dashboard).rstrip('/'), '--email', args.email,
                        '--password', args.password, '--project', args.project or project,
                        '--token', args.token or token, '--sdk-name', f"traceway-{run['family']}",
                        '--sdk-version', args.sdk_version, '--geometry-position', str(args.geometry_position),
                        '--geometry-size', str(args.geometry_size)], check=False)
        if os.path.exists(dash_json):
            with open(dash_json) as f:
                dash_result = json.load(f)
        else:
            dash_result = {'problems': ['dashboard.mjs wrote no result'], 'checkpoints': {}}
        dash_verdicts = evaluate_dashboard(check, dash_result, visual, run, cps, args)

    if args.update_baseline:
        os.makedirs(baseline_dir, exist_ok=True)
        for cp in cps:
            replay_png = os.path.join(out, 'replay', f"{cp['name']}.png")
            if os.path.exists(replay_png):
                shutil.copy(replay_png, os.path.join(baseline_dir, f"{cp['name']}.png"))
        kept = baseline if args.only else {}
        fresh = {name: {k: v[k] for k in ('fidelity', 'golden', 'size') if k in v}
                 for name, v in visual.get('checkpoints', {}).items()}
        wanted = {cp['scenario'] for cp in cps}
        with open(os.path.join(baseline_dir, 'baseline.json'), 'w') as f:
            json.dump({'checkpoints': {**kept.get('checkpoints', {}), **fresh},
                       'scenarios': {**kept.get('scenarios', {}), **{k: v for k, v in sizes.items() if k in wanted}}},
                      f, indent=1, sort_keys=True)
        print(f'  ...  baseline written to {baseline_dir}')

    write_report(out, run, cps, stats, sizes, text_results, visual, verdicts, baseline, check, dash_result, dash_verdicts)
    print(f'\nreport: {os.path.join(out, "report.html")}')
    if check.failures:
        print(f'{len(check.failures)} check(s) failed')
        sys.exit(1)
    print('all checks passed')


if __name__ == '__main__':
    main()
