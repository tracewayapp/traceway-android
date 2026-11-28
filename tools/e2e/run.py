#!/usr/bin/env python3
"""End-to-end check of the Android SDK against a running Traceway server.

Builds the unit tests, runs the scripted app (traceway/src/test/java/com/tracewayapp/traceway/
E2EScriptTest.kt: the real client started with Traceway.init and a connection string, activities
of real views and a Compose screen under Robolectric, touches dispatched to their windows) in a
plain JVM, follows its E2E_PHASE lines and asserts through the dashboard API what the server holds
at each point, the same phases as the iOS SDK's check:

  1. before the exception: breadcrumbs, taps included, are NOT on the server (ring buffer); the
     session is
  2. after it: the issue, its breadcrumbs, the tap with the selector of the view, the HTTP span of
     the OkHttp interceptor (to a local echo server), the session and a wireframe replay clip
     linked to the exception
  3. a recording trigger from the app's local settings (navigation /users/{id}, a tap on
     #open-profile, a log line) starts a capture: the session lists the trigger as a recording
     reason, logs stream while it runs and not after, and replay segments are uploaded
  4. a capture created through the dashboard API: its lookback comes up, logs stream, and
     cancelling it stops them
  5. a Data Collection rule whose click step is the selector the server recorded for the pay tap:
     the server accepts it, the app matches the next tap, emits the rule's event and records
  6. a rule sets replay.images to "off", then "always": the session reports the policy
     (traceway.replay.images, after the app restarts) and the clip of a photo carries a
     data:image/jpeg picture only when always
  7. app.* metrics and the app's Traceway.metric / Traceway.count reach the server

Rules it creates target the run's instance only. It prints the session and issue ids to look at.

  python3 tools/e2e/run.py --base http://127.0.0.1:18182 --email admin@localhost.com --password admin \\
      [--project "Android App"] [--gradle ./gradlew] [--out build/e2e]
"""
import argparse, datetime, json, os, shlex, shutil, subprocess, sys, threading, time
import urllib.error, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
TEST_CLASS = 'com.tracewayapp.traceway.E2EScriptTest'
JOURNEY_CAPTURE_SECONDS = 20
RULE_PREFIX = 'e2e android '


class Api:
    def __init__(self, base, email, password, project_name):
        self.base = base.rstrip('/')
        self.jwt = None
        self.project = None
        status, body = self.call('POST', '/api/login', {'email': email, 'password': password}, project=False)
        if status != 200:
            sys.exit(f'login failed: {status} {body}')
        self.jwt = body['token']
        android = [p for p in body['projects'] if p['framework'] == 'android'
                   and (not project_name or p['name'] == project_name)]
        if not android:
            sys.exit(f'no Android project {project_name!r} in this account')
        self.project = android[0]
        status, projects = self.call('GET', '/api/projects', project=False)
        for p in projects if isinstance(projects, list) else (projects or {}).get('data') or []:
            if p.get('id') == self.project['id'] and p.get('token'):
                self.project['token'] = p['token']

    def call(self, method, path, body=None, project=True):
        url = self.base + path
        if project:
            url += ('&' if '?' in url else '?') + 'projectId=' + self.project['id']
        headers = {'Content-Type': 'application/json'}
        if self.jwt:
            headers['Authorization'] = 'Bearer ' + self.jwt
        req = urllib.request.Request(url, method=method, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(errors='replace')[:400]

    def page(self, path, since, extra=None, size=100, page=1):
        now = datetime.datetime.now(datetime.timezone.utc)
        body = {'fromDate': since.isoformat(), 'toDate': (now + datetime.timedelta(minutes=5)).isoformat(),
                'pagination': {'page': page, 'pageSize': size}}
        body.update(extra or {})
        status, data = self.call('POST', path, body)
        if status != 200:
            sys.exit(f'{path} -> {status} {data}')
        return data['data'] or [], (data.get('pagination') or {})

    def all_pages(self, path, since, extra=None, size=500):
        rows, page = [], 1
        while True:
            batch, pagination = self.page(path, since, extra, size, page)
            rows += batch
            if len(batch) < size or page >= (pagination.get('totalPages') or page):
                return rows
            page += 1


class Check:
    def __init__(self):
        self.failed = []

    def __call__(self, ok, what, detail=''):
        print(('  PASS  ' if ok else '  FAIL  ') + what + (f'   [{detail}]' if detail else ''), flush=True)
        if not ok:
            self.failed.append(what)
        return ok


def attrs(record):
    return record.get('logAttributes') or {}


class Snapshot:
    """What the server holds for the run's instance."""

    def __init__(self, api, since, instance, echo):
        logs = api.all_pages('/api/logs', since, {'attributeFilters': [
            {'scope': 'resource', 'key': 'service.instance.id', 'value': instance}]})
        self.names = {}
        for r in logs:
            self.names.setdefault(attrs(r).get('event.name') or '(console)', []).append(r)
        spans, _ = api.page('/api/spans/search', since, size=100)
        self.spans = [s for s in spans if s.get('scopeName') == 'traceway-android'
                      and (not s.get('attributes') or (s['attributes'].get('url.full') or '').startswith(echo))]
        sessions, _ = api.page('/api/sessions', since)
        self.sessions = [s for s in sessions if (s.get('attributes') or {}).get('service.instance.id') == instance]

    def named(self, name):
        return [attrs(r) for r in self.names.get(name, [])]

    def bodies(self):
        return [r.get('body') or '' for r in self.names.get('(console)', [])]

    def exceptions(self, contains=''):
        return [a for a in self.named('exception') if contains in (a.get('exception.message') or '')]


def recording(api, session):
    started = urllib.parse.quote(session['startedAt'])
    status, rec = api.call('GET', f"/api/sessions/{session['id']}/recording?startedAt={started}")
    return ((rec or {}).get('events') or []) if isinstance(rec, dict) else []


def issue(api, exception_id):
    status, detail = api.call('POST', f'/api/exception-stack-traces/by-id/{exception_id}', {})
    return status, detail if isinstance(detail, dict) else {}


def is_rrweb(events):
    return bool(events) and events[0].get('type') == 4 and any(e.get('type') == 2 for e in events)


def nodes(value):
    """Every rrweb node of a list of events (snapshots and added nodes)."""
    if isinstance(value, dict):
        if 'id' in value and 'type' in value and ('tagName' in value or 'textContent' in value or 'childNodes' in value):
            yield value
        for v in value.values():
            yield from nodes(v)
    elif isinstance(value, list):
        for v in value:
            yield from nodes(v)


def classes(events):
    """The class of every node, as it is added and as attribute mutations change it."""
    out = [(n.get('attributes') or {}).get('class') or '' for n in nodes(events)]
    for e in events:
        if e.get('type') == 3 and (e.get('data') or {}).get('source') == 0:
            out += [(a.get('attributes') or {}).get('class') or '' for a in e['data'].get('attributes') or []]
    return out


class Tee:
    """Writes what the harness prints to the run's log as well."""

    def __init__(self, path):
        self.file = open(path, 'w')
        self.stdout = sys.stdout

    def write(self, text):
        self.stdout.write(text)
        self.file.write(text)
        self.file.flush()

    def flush(self):
        self.stdout.flush()


def recording_reasons(api, session_id):
    status, detail = api.call('POST', f'/api/sessions/{session_id}', {})
    if not isinstance(detail, dict):
        return []
    return (detail.get('session') or {}).get('recordingReasons') or []


def eventually(fetch, ok, timeout=30, interval=2):
    """Fetches until ok(value) or the timeout: the server ingests asynchronously."""
    deadline = time.time() + timeout
    while True:
        value = fetch()
        if ok(value) or time.time() >= deadline:
            return value
        time.sleep(interval)


def build(args):
    """Builds the unit tests and returns the command that runs the scripted app in a plain JVM."""
    cp_dir = os.path.join(args.out, 'classpath')
    os.makedirs(cp_dir, exist_ok=True)
    if not args.skip_build:
        print('building the unit tests', flush=True)
        cmd = shlex.split(args.gradle) + ['-I', os.path.join(HERE, 'classpath.gradle'),
                                          f'-PtracewayE2eOut={cp_dir}', ':traceway:e2eClasspath']
        with open(os.path.join(args.out, 'build.log'), 'w') as log:
            if subprocess.run(cmd, cwd=REPO, stdout=log, stderr=subprocess.STDOUT).returncode != 0:
                sys.exit(f'the build failed, see {log.name}')
    read = lambda name: open(os.path.join(cp_dir, name)).read().strip()
    java_home = os.environ.get('JAVA_HOME')
    java = os.path.join(java_home, 'bin', 'java') if java_home else shutil.which('java')
    if not java:
        sys.exit('set JAVA_HOME (JDK 17 or 21)')
    jvm = [a for a in read('jvmargs.txt').splitlines() if a and not a.startswith(('-Xmx', '-Xms'))]
    return [java, f'-Xmx{args.heap}', '-XX:TieredStopAtLevel=1', *jvm, '-cp', read('classpath.txt'),
            'org.junit.runner.JUnitCore', TEST_CLASS], read('workdir.txt')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--base', required=True, help='the Traceway server, e.g. http://127.0.0.1:18182')
    ap.add_argument('--email', required=True)
    ap.add_argument('--password', required=True)
    ap.add_argument('--project', default='Android App', help='name of an Android project (default "Android App")')
    ap.add_argument('--gradle', default='./gradlew --no-daemon --console=plain',
                    help='the Gradle command, run in the repository root')
    ap.add_argument('--skip-build', action='store_true', help='reuse the classpath of the last build')
    ap.add_argument('--heap', default='1g', help='heap of the scripted app JVM')
    ap.add_argument('--out', default=os.path.join(REPO, 'build', 'e2e'))
    args = ap.parse_args()
    args.out = os.path.abspath(os.path.expanduser(args.out))

    api = Api(args.base, args.email, args.password, args.project)
    token = api.project.get('token')
    if not token:
        sys.exit('the project token is not readable through the API')
    dsn = f'{token}@{args.base}'
    check = Check()
    os.makedirs(args.out, exist_ok=True)
    run_id = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    sys.stdout = Tee(os.path.join(args.out, f'run-{run_id}.log'))
    control = os.path.join(args.out, 'control-' + run_id)
    os.makedirs(control)
    status, rules = api.call('GET', '/api/sdk-config/rules')
    for rule in ((rules or {}).get('data') or []) if isinstance(rules, dict) else []:
        if rule.get('name', '').startswith(RULE_PREFIX):
            api.call('DELETE', f"/api/sdk-config/rules/{rule['id']}")
        else:
            print(f"note: the project has a rule of its own, {rule.get('name')!r}; it may change what streams")

    command, workdir = build(args)
    since = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=5)
    phases, lines = {}, []
    app_log = os.path.join(args.out, f'app-{run_id}.log')
    env = dict(os.environ, TRACEWAY_E2E_DSN=dsn, E2E_CONTROL_DIR=control)
    launched = time.time()
    proc = subprocess.Popen(command, cwd=workdir, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, errors='replace', env=env)

    def pump():
        with open(app_log, 'w') as log:
            for line in proc.stdout:
                lines.append(line.rstrip())
                log.write(line)
                log.flush()
                for marker in ('E2E_PHASE ', 'E2E_NOTE '):
                    if marker in line:
                        rest = line.split(marker, 1)[1].strip()
                        print(f'app: {rest}   (+{time.time() - launched:.0f}s)', flush=True)
                        if marker == 'E2E_PHASE ':
                            name = rest.split(' ')[0]
                            phases[name] = dict(kv.split('=', 1) for kv in rest.split(' ')[1:] if '=' in kv)

    threading.Thread(target=pump, daemon=True).start()

    def wait_phase(name, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if name in phases:
                return phases[name]
            if proc.poll() is not None:
                time.sleep(1)
                if name in phases:
                    return phases[name]
                break
            time.sleep(0.2)
        print('\n'.join(lines[-80:]))
        sys.exit(f'app never reached phase {name} (log: {app_log})')

    def go(name):
        open(os.path.join(control, f'{name}.go'), 'w').close()

    created_rules = []
    result = {'run': run_id, 'log': app_log}
    try:
        start = wait_phase('start', 600)
        instance, session_id, echo = start['instance'], start['session'], start['echo'].rstrip('/')
        result.update(instance=instance, session=session_id, project=api.project['id'])
        print(f'\nsession {session_id}, instance {instance}', flush=True)

        def snap():
            return Snapshot(api, since, instance, echo)

        # ---- 1. quiet phase ------------------------------------------------
        wait_phase('breadcrumbs_done', 180)
        time.sleep(6)
        print('\n[1] before the exception: breadcrumbs must still be on the device')
        s = eventually(snap, lambda s: len(s.sessions) == 1)
        check('session.start' in s.names, 'session.start was sent (control records always stream)')
        for breadcrumb in ('(console)', 'browser.navigation', 'traceway.action', 'e2e_event', 'browser.user_action.click'):
            check(breadcrumb not in s.names, f'no {breadcrumb} record on the server yet',
                  f"{len(s.names.get(breadcrumb, []))}")
        check(not s.spans, 'no HTTP span on the server yet', f'{len(s.spans)} spans')
        check(not s.exceptions(), 'no exception yet')
        check(len(s.sessions) == 1 and s.sessions[0]['id'] == session_id, 'the session exists', f'{len(s.sessions)} sessions')
        go('breadcrumbs_done')

        # ---- 2. the exception ----------------------------------------------
        wait_phase('exception_sent', 120)
        print('\n[2] after the exception: issue, breadcrumbs, tap, span, session and clip')
        s = eventually(snap, lambda s: s.exceptions('1999') and s.named('browser.user_action.click') and s.spans)
        check(len(s.exceptions('1999')) == 1, 'one exception record', f"{len(s.exceptions('1999'))}")
        check('e2e breadcrumb log' in s.bodies(), 'the log line breadcrumb was flushed')
        stdout = [r.get('body') for r in s.names.get('(console)', []) if attrs(r).get('log.source') == 'stdout']
        check('Paying for 2 items' in stdout, 'the System.out line was flushed', f'{len(stdout)} stdout lines')
        nav = [(a.get('navigation.action'), a.get('url.full')) for a in s.named('browser.navigation')]
        check(('push', '/CheckoutActivity') in nav, 'the activity navigation was flushed', str(nav))
        clicks = s.named('browser.user_action.click')
        pay = [a for a in clicks if a.get('app.widget.id') == 'button1']
        check(len(pay) == 1, 'the pay tap was flushed with the breadcrumbs', f'{len(clicks)} taps')
        pay = pay[0] if pay else {}
        check(pay.get('browser.css_selector') == 'MaterialButton#button1' and pay.get('browser.tag_name') == 'MaterialButton',
              'the tap names the view', f"{pay.get('browser.css_selector')}")
        check(pay.get('app.widget.text') == 'Pay now' and pay.get('app.screen.name') == '/CheckoutActivity',
              'the tap carries the shown text and the screen', f"{pay.get('app.widget.text')} {pay.get('app.screen.name')}")
        field = [a for a in clicks if a.get('browser.css_selector') == 'EditText#edit']
        check(len(field) == 1 and field[0].get('app.widget.text') == 'Name on card',
              'the tap on the field names it by its hint, not what was typed', json.dumps(field)[:200])
        check(len(s.named('traceway.action')) == 1, 'the custom action was flushed', f"{len(s.named('traceway.action'))}")
        check(len(s.named('e2e_event')) == 1, 'the tracked event was flushed')
        span = s.spans[0] if s.spans else {}
        check(len(s.spans) == 1 and span.get('name') == 'GET', 'the HTTP client span of the OkHttp interceptor was flushed',
              f"{len(s.spans)} spans {json.dumps(span.get('attributes'))[:200]}")

        record = (s.exceptions('1999') or [{}])[0]
        exception_id = record.get('traceway.exception.id')
        result['exception'] = exception_id
        status, detail = eventually(lambda: issue(api, exception_id),
                                    lambda r: (r[1].get('sessionRecording') or {}).get('events'), timeout=30)
        check(status == 200, 'the issue is addressable by the SDK-generated exception id', f'{status}')
        exc = detail.get('exception') or {}
        stack = exc.get('stackTrace', '')
        check('CheckoutException' in stack and 'payment of 1999 declined' in stack and 'E2EScript.run' in stack,
              'the stack trace has the error line and the frames', stack[:160].replace('\n', ' | '))
        issue_attrs = exc.get('attributes') or {}
        check(issue_attrs.get('os.name') == 'android' and issue_attrs.get('user.id') == 'e2e-android-user'
              and issue_attrs.get('service.instance.id') == instance, 'the issue carries device, user and instance',
              json.dumps({k: issue_attrs.get(k) for k in ('os.name', 'user.id', 'service.instance.id')}))
        check(issue_attrs.get('app.screen.name') == '/CheckoutActivity', 'the issue carries its screen',
              f"{issue_attrs.get('app.screen.name')}")
        check(exc.get('appVersion') == 'e2e-1.0.0', 'the issue carries the app version', f"{exc.get('appVersion')}")
        check(detail.get('sessionId') == session_id, 'the issue is linked to the session', f"{detail.get('sessionId')}")
        check(issue_attrs.get('traceway.replay.status') == 'requested', 'the replay status is requested',
              f"{issue_attrs.get('traceway.replay.status')}")
        events = (detail.get('sessionRecording') or {}).get('events') or []
        check(is_rrweb(events), 'the issue has a wireframe clip: a Meta event, then a full snapshot', f'{len(events)} events')
        if events:
            length = (events[-1]['timestamp'] - events[0]['timestamp']) / 1000
            check(0 < length <= 31, 'the clip covers the 30 s before the exception at most', f'{length:.1f}s')
        text = json.dumps(events)
        check('Pay now' in text and 'Checkout' in text, 'the clip shows the checkout screen')
        check('Ada Lovelace' not in text, 'what was typed is masked in the clip')
        check(any(e.get('type') == 3 and (e.get('data') or {}).get('source') == 2 and (e.get('data') or {}).get('type') == 2
                  for e in events), 'the clip has the taps')

        until = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=5)).isoformat()
        status, timeline = api.call('GET', f"/api/sessions/{session_id}/timeline?from={urllib.parse.quote(since.isoformat())}"
                                           f"&to={urllib.parse.quote(until)}")
        text = json.dumps(timeline)
        check(status == 200 and 'e2e breadcrumb log' in text and '/CheckoutActivity' in text and '/v1/cart' in text,
              'the session timeline shows the log, navigation and network breadcrumbs', f'{status}')
        check('MaterialButton#button1' in text, 'the session timeline shows the tap with its selector')
        go('exception_sent')

        # ---- 3. a recording trigger from local settings --------------------
        wait_phase('journey_started', 90)
        time.sleep(10)
        print('\n[3] recording trigger: navigation /users/{id} -> tap #open-profile -> log')
        s = snap()
        fired = [a for a in s.named('traceway.trigger.fired') if a.get('traceway.trigger.id') == 'e2e-open-profile']
        check(len(fired) == 1, 'the trigger fired once', f'{len(fired)}')
        started = [a for a in s.named('traceway.capture.started') if a.get('traceway.capture.source') == 'trigger']
        check(len(started) == 1, 'a capture started on the device', f'{len(started)}')
        profile = [a for a in s.named('browser.user_action.click') if (a.get('browser.css_selector') or '').endswith('#open-profile')]
        check(len(profile) == 1 and profile[0].get('app.screen.name') == '/users/42',
              'the journey tap on the Compose button was uploaded with the lookback',
              json.dumps([(a.get('browser.css_selector'), a.get('app.screen.name')) for a in profile]))
        user_nav = [a for a in s.named('browser.navigation') if a.get('url.full') == '/users/42']
        check(len(user_nav) == 1 and user_nav[0].get('app.screen.route') == '/users/{id}',
              'the navigation carries its route template', json.dumps(user_nav)[:200])
        check(any('Profile loaded for 42' in b for b in s.bodies()), 'the journey log line was uploaded')
        ticks = [b for b in s.bodies() if b.startswith('e2e journey tick')]
        check(len(ticks) >= 5, 'logs stream while the capture runs', f'{len(ticks)} ticks')
        reasons = recording_reasons(api, session_id)
        journey = [r for r in reasons if r.get('triggerId') == 'e2e-open-profile']
        check(bool(journey) and journey[0].get('sequence') ==
              'navigation(/users/{id}) → click(#open-profile) → log(Profile loaded)',
              'the session lists the trigger as a recording reason, with its sequence',
              journey[0].get('sequence') if journey else json.dumps(reasons)[:300])

        wait_phase('journey_done', 120)
        time.sleep(6)
        s = snap()
        ticks = [b for b in s.bodies() if b.startswith('e2e journey tick')]
        last = max([int(t.rsplit(' ', 1)[1]) for t in ticks] or [-1])
        check(JOURNEY_CAPTURE_SECONDS - 6 <= last <= JOURNEY_CAPTURE_SECONDS + 1, 'streaming ended with the capture',
              f'last streamed tick {last}')
        check('e2e after journey' not in s.bodies(), 'logs are buffered again after the capture')
        segments = eventually(lambda: recording(api, s.sessions[0]) if s.sessions else [],
                              lambda e: sum(1 for x in e if x.get('type') == 2) >= 2)
        check(is_rrweb(segments), 'the session recording replays in rrweb', f'{len(segments)} events')
        snapshots = sum(1 for e in segments if e.get('type') == 2)
        check(snapshots >= 2, 'the session has the error clip and the streamed segments', f'{snapshots} full snapshots')
        check('Open profile' in json.dumps(segments), 'the streamed segments show the Compose screen')
        go('journey_done')

        # ---- 4. a capture from the dashboard -------------------------------
        wait_phase('await_capture', 60)
        print('\n[4] a capture started from the dashboard, then cancelled')
        status, capture = api.call('POST', '/api/sdk-config/captures', {
            'target': {'kind': 'instance', 'value': instance}, 'durationMinutes': 5,
            'lookbackSeconds': 60, 'replay': True, 'label': 'e2e android'})
        check(status in (200, 201), 'capture created through the dashboard API', f'{status} {capture if status >= 300 else ""}')
        capture_id = (capture or {}).get('id') if isinstance(capture, dict) else None
        result['capture'] = capture_id
        go('await_capture')
        running = wait_phase('capture_running', 120)
        check(running.get('capture') == capture_id, 'the app received the capture', f"{running.get('capture')}")
        s = eventually(snap, lambda s: len([b for b in s.bodies() if b.startswith('e2e remote tick')]) >= 12, timeout=20)
        remote = [a for a in s.named('traceway.capture.started') if a.get('traceway.capture.source') == 'remote']
        check(len(remote) == 1 and remote[0].get('traceway.capture.id') == capture_id, 'the app started the capture',
              f'{len(remote)}')
        check(all(f'e2e pre-capture tick {i}' in s.bodies() for i in range(3)) and 'e2e after journey' in s.bodies(),
              'the lookback brought up what was buffered')
        remote_ticks = [b for b in s.bodies() if b.startswith('e2e remote tick')]
        check(len(remote_ticks) == 12, 'logs stream during the capture', f'{len(remote_ticks)}')
        tagged = [r for r in s.names.get('(console)', []) if capture_id and capture_id in (attrs(r).get('traceway.capture.ids') or '')]
        check(len(tagged) >= 12, 'streamed records carry the capture id', f'{len(tagged)}')
        status, listing = api.call('GET', '/api/sdk-config/captures')
        mine = [c for c in ((listing or {}).get('data') or []) if c.get('id') == capture_id] if isinstance(listing, dict) else []
        check(bool(mine) and (mine[0].get('status') or '').lower() == 'collecting', 'the dashboard sees the capture collecting',
              mine[0].get('status') if mine else str(listing)[:200])
        status, _ = api.call('DELETE', f'/api/sdk-config/captures/{capture_id}')
        check(status in (200, 204), 'capture cancelled', f'{status}')
        go('capture_running')
        wait_phase('capture_cancelled', 120)
        time.sleep(5)
        s = snap()
        check('e2e after cancel' not in s.bodies(), 'logs are buffered again after the cancel')

        # ---- 5. a rule built from the selector the server recorded ---------
        print('\n[5] a Data Collection rule whose click step is the selector the server recorded')
        selector = pay.get('browser.css_selector') or 'MaterialButton#button1'
        conditions = {'all': [{'key': 'service.instance.id', 'op': 'eq', 'value': instance}]}
        clicks_before = len([a for a in s.named('browser.user_action.click') if a.get('app.widget.id') == 'button1'])
        status, body = api.call('POST', '/api/sdk-config/rules', {
            'name': RULE_PREFIX + 'remote click', 'enabled': True, 'conditions': conditions,
            'settings': {'signals': {'custom': {'mode': 'stream', 'sampleRate': 1}},
                         'triggers': [{'id': 'e2e-remote-pay', 'name': 'Pay tapped',
                                       'steps': [{'match': {'event': {'type': 'click', 'value': selector}}}],
                                       'actions': [{'type': 'emit', 'eventName': 'e2e_paid'},
                                                   {'type': 'flush', 'lookbackSeconds': 30, 'replay': True}]}]},
        })
        check(status in (200, 201), f'the server accepts the recorded selector {selector!r} in a rule',
              f'{status} {body if status >= 300 else ""}')
        if isinstance(body, dict) and body.get('id'):
            created_rules.append(body['id'])
        go('capture_cancelled')
        wait_phase('rule_tapped', 120)
        s = eventually(snap, lambda s: s.named('e2e_paid'), timeout=20)
        remote_fired = [a for a in s.named('traceway.trigger.fired') if a.get('traceway.trigger.id') == 'e2e-remote-pay']
        check(len(remote_fired) == 1, 'the app matched the next tap against it', f'{len(remote_fired)}')
        check(len(s.named('e2e_paid')) == 1, 'the rule streamed its event', f"{len(s.named('e2e_paid'))}")
        pays = [a for a in s.named('browser.user_action.click') if a.get('app.widget.id') == 'button1']
        check(len(pays) == clicks_before + 1, 'the rule flushed the tap', f'{len(pays)} pay taps')
        reasons = eventually(lambda: recording_reasons(api, session_id),
                             lambda r: any(x.get('triggerId') == 'e2e-remote-pay' for x in r), timeout=20)
        rule_reason = [r for r in reasons if r.get('triggerId') == 'e2e-remote-pay']
        check(bool(rule_reason) and selector in (rule_reason[0].get('sequence') or ''),
              'the session lists the rule as a recording reason', rule_reason[0].get('sequence') if rule_reason else '')
        check(len(s.exceptions()) == 1, 'still one exception', f'{len(s.exceptions())}')

        # ---- 6. replay images off, then always -----------------------------
        print('\n[6] replay images: a rule sets them off, then always')
        status, images_rule = api.call('POST', '/api/sdk-config/rules', {
            'name': RULE_PREFIX + 'replay images', 'enabled': True, 'conditions': conditions,
            'settings': {'replay': {'images': 'off'}}})
        check(status in (200, 201), 'the server accepts replay.images = "off"', f'{status} {images_rule if status >= 300 else ""}')
        if isinstance(images_rule, dict) and images_rule.get('id'):
            created_rules.append(images_rule['id'])
        go('rule_tapped')
        for policy in ('off', 'always'):
            reported = wait_phase(f'images_{policy}', 180)
            check(reported.get('images') == policy, f'the app applied replay.images = {policy}', f"{reported.get('images')}")
            check(reported.get('session') == session_id, 'the restarted app continued the session', f"{reported.get('session')}")
            s = eventually(snap, lambda s: s.exceptions(f'replay images {policy}') and s.sessions and
                           (s.sessions[0].get('attributes') or {}).get('traceway.replay.images') == policy)
            session_policy = (s.sessions[0].get('attributes') or {}).get('traceway.replay.images') if s.sessions else None
            check(session_policy == policy, f'the session reports traceway.replay.images = {policy}', f'{session_policy}')
            record = (s.exceptions(f'replay images {policy}') or [{}])[0]
            gallery_id = record.get('traceway.exception.id')
            result[f'exception_images_{policy}'] = gallery_id
            status, detail = eventually(lambda: issue(api, gallery_id),
                                        lambda r: (r[1].get('sessionRecording') or {}).get('events'), timeout=30)
            events = (detail.get('sessionRecording') or {}).get('events') or []
            text = json.dumps(events)
            image_nodes = [c for c in classes(events) if c == 'im' or c.startswith('im ')]
            check(is_rrweb(events) and 'Sunset over the bay' in text, 'the gallery clip shows the screen', f'{len(events)} events')
            check(bool(image_nodes), 'the photo is an image node', str(image_nodes[:3]))
            if policy == 'off':
                check('data:image/jpeg' not in text and all(c == 'im' for c in image_nodes),
                      'with images off the photo is a placeholder', str(sorted(set(image_nodes))))
                status, updated = api.call('PUT', f"/api/sdk-config/rules/{images_rule['id']}", {
                    'name': RULE_PREFIX + 'replay images', 'enabled': True, 'conditions': conditions,
                    'settings': {'replay': {'images': 'always'}}, 'expectedUpdatedAt': images_rule.get('updatedAt', '')})
                check(status == 200, 'the rule is changed to replay.images = "always"', f'{status} {updated if status >= 300 else ""}')
            else:
                pictured = sorted({c for c in image_nodes if c.startswith('im p')})
                styles = [(n.get('attributes') or {}).get('_cssText') or '' for n in nodes(events)]
                drawn = [c for c in pictured if any(st.startswith('.im.' + c[3:] + '{background:url(data:image/jpeg;base64,')
                                                    for st in styles)]
                check(bool(pictured) and drawn == pictured, 'with images always the photo node shows its JPEG picture',
                      f"classes {pictured}, {text.count('data:image/jpeg')} pictures")
            go(f'images_{policy}')

        # ---- 7. metrics ----------------------------------------------------
        wait_phase('metrics_sent', 90)
        print('\n[7] metrics: what the SDK measured and the app\'s own')
        now = datetime.datetime.now(datetime.timezone.utc)
        metric_from = (since - datetime.timedelta(minutes=2)).isoformat()

        def query(names):
            status, body = api.call('POST', '/api/metrics/query', {
                'queries': [{'name': n, 'aggregation': agg} for n, agg in names],
                'from': metric_from, 'to': (now + datetime.timedelta(minutes=2)).isoformat(), 'wholeRange': True})
            totals = {}
            for r in (body or {}).get('results') or [] if isinstance(body, dict) else []:
                totals[r['name']] = sum(p.get('value') or 0 for points in (r.get('series') or {}).values() for p in points)
            return status, totals

        wanted = [('e2e.checkout.amount', 'count'), ('e2e.checkout.completed', 'sum'), ('app.session', 'sum'),
                  ('app.request.duration', 'count'), ('app.screen.ttid', 'count')]
        status, totals = eventually(lambda: query(wanted), lambda r: all(r[1].get(n) for n, _ in wanted), timeout=30)
        check(status == 200, 'the metrics query answers', f'{status}')
        check(totals.get('e2e.checkout.amount', 0) >= 1, 'Traceway.metric reached the server', f"{totals.get('e2e.checkout.amount')}")
        check(totals.get('e2e.checkout.completed', 0) >= 2, 'Traceway.count reached the server', f"{totals.get('e2e.checkout.completed')}")
        for name, _ in wanted[2:]:
            check(totals.get(name, 0) > 0, f'{name} reached the server', f'{totals.get(name)}')
        status, discover = api.call('GET', '/api/metrics/discover')
        app_metrics = sorted({m.get('name') for m in (discover if isinstance(discover, list) else (discover or {}).get('metrics') or [])
                              if isinstance(m, dict) and (m.get('name') or '').startswith('app.')})
        print(f'  app.* metrics of the project: {", ".join(app_metrics) or json.dumps(discover)[:200]}')
        go('metrics_sent')
        done = wait_phase('done', 60)
        proc.wait(timeout=120)
        check(proc.returncode == 0, 'the scripted app finished', f'exit {proc.returncode}')
    finally:
        if proc.poll() is None:
            proc.kill()

    result['failed'] = check.failed
    with open(os.path.join(args.out, 'result.json'), 'w') as f:
        json.dump(result, f, indent=1)
    print(f"\nsession   {result.get('session')}  (breadcrumbs, the trigger's capture, the rule's recording, the gallery)")
    print(f"issue     {result.get('exception')}  (CheckoutException with its clip of the checkout screen)")
    print(f"images    off {result.get('exception_images_off')}  always {result.get('exception_images_always')}")
    print(f"project   {api.project['name']} {api.project['id']}")
    print(f"\n{'all checks passed' if not check.failed else f'{len(check.failed)} check(s) failed'}"
          f"  (logs {args.out}/run-{run_id}.log, {app_log})")
    sys.exit(1 if check.failed else 0)


if __name__ == '__main__':
    main()
