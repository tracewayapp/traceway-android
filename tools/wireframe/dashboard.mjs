// The dashboard mode of run.py: a wireframe run played by the real Traceway
// dashboard instead of a standalone rrweb-player.
//
// 1. The run becomes a live session of the platform's project: its events are
//    moved in time to end a few seconds ago, an OTLP/HTTP JSON `session.start`
//    log (scope traceway-<platform>, the session attributes the backend's
//    logs_converter.go reads) opens it, POST /api/recordings uploads the
//    events gzipped, one segment per clip (Meta + FullSnapshot) with
//    increasing segmentIndex, and `session.end` closes it. Each segment
//    carries a custom action per checkpoint in it ({category:
//    'wireframe-gallery', name}): the session page lists them under Actions,
//    and clicking one seeks the page's player to that exact moment.
// 2. When the session reports its recording, Playwright logs into the
//    dashboard, opens the session page and measures the player's scale. A
//    second browser context renders at the device scale that undoes it, so
//    the replay iframe's screenshot has the recording's logical pixels.
// 3. Per checkpoint: click its action row, screenshot the replay iframe, read
//    the iframe's texts and the checkpoint's boxes (compare.js twProbe), and
//    compare the picture with the standalone rendering replay.mjs made
//    (compare.js twCompare).
//
// Writes <out>/dashboard/*.png, <out>/dashboard-diff/*.png and
// <out>/dashboard.json; run.py turns them into checks and the report.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { createRequire } from 'node:module';

const args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  args[process.argv[i].replace(/^--/, '')] = process.argv[i + 1];
}
const here = path.dirname(new URL(import.meta.url).pathname);
const require = createRequire(import.meta.url);
const { chromium } = require(args.playwright);
const out = path.resolve(args.out);
const events = JSON.parse(fs.readFileSync(path.join(out, 'events.json'), 'utf8'));
const run = JSON.parse(fs.readFileSync(path.join(out, 'check.json'), 'utf8'));
const chosen = new Set(run.selected);
const checkpoints = run.checkpoints.filter((cp) => chosen.has(cp.name));
const sample = run.sampleMs;
const tolerance = { position: Number(args['geometry-position'] || 1.5), size: Number(args['geometry-size'] || 2) };
const dashboard = args.dashboard;
const traceway = args.traceway || dashboard;
const MARKER = 'wireframe-gallery';
const REPLAY_ERRORS = /Node with id|node not found|Exception in mutation|Uncaught/i;
const result = { session: null, rrwebErrors: [], pageErrors: [], console: [], checkpoints: {}, problems: [] };

for (const dir of ['dashboard', 'dashboard-diff']) fs.mkdirSync(path.join(out, dir), { recursive: true });
const writePng = (file, dataUrl) =>
  fs.writeFileSync(file, Buffer.from(dataUrl.slice(dataUrl.indexOf(',') + 1), 'base64'));
const save = () => fs.writeFileSync(path.join(out, 'dashboard.json'), JSON.stringify(result, null, 1));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function http(method, url, { body, token, gzip, headers = {} } = {}) {
  for (let attempt = 0; ; attempt++) {
    const init = { method, headers: { ...headers } };
    if (token) init.headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) {
      const json = Buffer.from(JSON.stringify(body));
      init.headers['Content-Type'] = 'application/json';
      if (gzip) init.headers['Content-Encoding'] = 'gzip';
      init.body = gzip ? zlib.gzipSync(json) : json;
    }
    const response = await fetch(url, init);
    if (response.status === 503 && attempt < 5) {
      await sleep(1000 * Number(response.headers.get('retry-after') || 1));
      continue;
    }
    const text = await response.text();
    if (!response.ok) throw new Error(`${method} ${new URL(url).pathname}: ${response.status} ${text.slice(0, 200)}`);
    return { json: text ? JSON.parse(text) : null, bytes: init.body ? init.body.length : 0 };
  }
}

const attr = (key, value) =>
  typeof value === 'number' ? { key, value: { intValue: String(value) } } : { key, value: { stringValue: String(value) } };
const nanos = (ms) => String(BigInt(Math.round(ms)) * 1000000n);

/** A session lifecycle log record the way the mobile SDKs send it (OTLP/HTTP JSON). */
function lifecycle(eventName, atMs, sessionId, startedMs, instanceId) {
  const os = { ios: 'iOS', android: 'Android', flutter: run.os ? 'iOS' : 'Android' }[run.family] || run.family;
  return {
    resourceLogs: [{
      resource: {
        attributes: [
          attr('service.name', `wireframe-gallery-${run.family}`),
          attr('service.version', '1.0.0'),
          attr('service.instance.id', instanceId),
          attr('os.name', os),
          attr('os.version', String(run.os || '')),
          attr('device.model.identifier', String(run.device || '')),
        ],
      },
      scopeLogs: [{
        scope: { name: args['sdk-name'], version: args['sdk-version'] },
        logRecords: [{
          timeUnixNano: nanos(atMs),
          observedTimeUnixNano: nanos(atMs),
          eventName,
          severityNumber: 9,
          severityText: 'INFO',
          body: { stringValue: eventName },
          attributes: [attr('event.name', eventName), attr('session.id', sessionId), attr('traceway.session.started_at', startedMs)],
        }],
      }],
    }],
  };
}

/** The SDK's clips: a new one at every Meta event followed by a full snapshot. */
function clips(list) {
  const segments = [];
  for (let i = 0; i < list.length; i++) {
    const boundary = list[i].type === 4 && list[i + 1] && list[i + 1].type === 2;
    if (!segments.length || (boundary && segments[segments.length - 1].length)) segments.push([]);
    segments[segments.length - 1].push(list[i]);
  }
  return segments;
}

async function upload() {
  const login = await http('POST', `${traceway}/api/login`, { body: { email: args.email, password: args.password } });
  const token = login.json.token;
  const projects = (await http('GET', `${traceway}/api/projects`, { token })).json;
  const project = projects.find((p) => p.name === args.project);
  if (!project) throw new Error(`no project "${args.project}" (have ${projects.map((p) => p.name).join(', ')})`);

  const first = events[0].timestamp;
  const last = events[events.length - 1].timestamp;
  const shift = Date.now() - 5000 - last;
  const shifted = events.map((e) => ({ ...e, timestamp: e.timestamp + shift }));
  const startedMs = first + shift - 500;
  const sessionId = crypto.randomUUID();
  const instanceId = crypto.randomUUID();
  await http('POST', `${traceway}/api/otel/v1/logs`, {
    token: args.token, body: lifecycle('session.start', startedMs, sessionId, startedMs, instanceId),
  });

  const lists = clips(shifted);
  const segments = lists.map((list, segmentIndex) => {
    const from = list[0].timestamp, to = list[list.length - 1].timestamp;
    const until = segmentIndex + 1 < lists.length ? lists[segmentIndex + 1][0].timestamp : Infinity;
    const actions = checkpoints
      .map((cp) => ({ cp, at: cp.at + sample + shift }))
      .filter(({ at }) => (segmentIndex === 0 || at >= from) && at < until)
      .map(({ cp, at }) => ({ type: 'custom', timestamp: new Date(at).toISOString(), category: MARKER, name: cp.name }));
    return {
      sessionId, segmentIndex, events: list, ...(actions.length ? { actions } : {}),
      startedAt: new Date(from).toISOString(), endedAt: new Date(to).toISOString(),
    };
  });
  let bytes = 0;
  for (let i = 0; i < segments.length;) {
    const batch = [];
    let size = 0;
    while (i < segments.length && batch.length < 100) {
      const length = JSON.stringify(segments[i]).length;
      if (batch.length && size + length > 12e6) break;
      batch.push(segments[i++]);
      size += length;
    }
    bytes += (await http('POST', `${traceway}/api/recordings`, { token: args.token, gzip: true, body: { segments: batch } })).bytes;
  }
  await http('POST', `${traceway}/api/otel/v1/logs`, {
    token: args.token, body: lifecycle('session.end', last + shift + 200, sessionId, startedMs, instanceId),
  });

  const scope = `projectId=${project.id}`;
  let detail = null, stored = 0;
  const deadline = Date.now() + 90000;
  while (Date.now() < deadline) {
    try {
      detail = (await http('POST', `${traceway}/api/sessions/${sessionId}?${scope}`, { token, body: {} })).json;
      if (detail.session && detail.session.hasRecording) {
        const recording = (await http('GET', `${traceway}/api/sessions/${sessionId}/recording?startedAt=${encodeURIComponent(detail.session.startedAt)}&${scope}`, { token })).json;
        stored = (recording.events || []).length;
        if (stored >= shifted.length) break;
      }
    } catch (e) {
      if (!/: 404 /.test(e.message)) throw e;
    }
    await sleep(1000);
  }
  if (!detail || !detail.session) throw new Error(`session ${sessionId} never appeared`);
  if (stored !== shifted.length) throw new Error(`the session's recording holds ${stored} of ${shifted.length} events after 90 s`);
  result.session = {
    id: sessionId, project: project.name, projectId: project.id, startedAt: detail.session.startedAt,
    url: `${dashboard}/sessions/${sessionId}?${scope}`, segments: segments.length, bytes, events: shifted.length,
    sdk: `${args['sdk-name']} ${args['sdk-version']}`, sdkLabel: detail.session.attributes?.['traceway.sdk.version'] || null,
  };
  console.log(`  ...  uploaded ${segments.length} segment(s), ${Math.round(bytes / 1024)} KB, session ${result.session.url}`);
}

async function waitForReplay(page) {
  await page.locator('.replayer-wrapper iframe').waitFor({ timeout: 60000 });
  await page.frameLocator('.replayer-wrapper iframe').locator('#tw').first().waitFor({ state: 'attached', timeout: 30000 });
  await page.waitForTimeout(1200);
}

async function play() {
  const browser = await chromium.launch({
    executablePath: args.chromium,
    headless: true,
    args: ['--allow-file-access-from-files', '--font-render-hinting=none'],
  });
  try {
    const viewport = { width: 1440, height: 1200 };
    const first = await browser.newContext({ viewport, deviceScaleFactor: 1, timezoneId: 'UTC' });
    const login = await first.newPage();
    await login.goto(`${dashboard}/login`);
    await login.fill('input[type=email]', args.email);
    await login.fill('input[type=password]', args.password);
    await login.click('button[type=submit]');
    await login.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 30000 });
    await login.goto(result.session.url);
    await waitForReplay(login);
    const scale = await login.evaluate(() =>
      new DOMMatrixReadOnly(getComputedStyle(document.querySelector('.replayer-wrapper')).transform).a);
    await login.screenshot({ path: path.join(out, 'dashboard', 'session-page.png'), fullPage: true });
    const state = await first.storageState();
    await first.close();
    result.session.scale = scale;
    result.session.deviceScale = 1 / scale;

    const context = await browser.newContext({ viewport, deviceScaleFactor: 1 / scale, timezoneId: 'UTC', storageState: state });
    const page = await context.newPage();
    page.on('console', (m) => {
      const text = `${m.type()}: ${m.text()}`;
      if (REPLAY_ERRORS.test(m.text())) result.rrwebErrors.push(text);
      else if (m.type() === 'error' || m.type() === 'warning') result.console.push(text);
    });
    page.on('pageerror', (e) => result.pageErrors.push(String(e)));
    await page.goto(result.session.url);
    await waitForReplay(page);
    await page.addStyleTag({ content: '.replayer-mouse,.replayer-mouse-tail{display:none!important}' });
    await page.addScriptTag({ content: fs.readFileSync(path.join(here, 'compare.js'), 'utf8') });
    const actionsTab = page.getByRole('tab', { name: /^Actions/ });
    if (await actionsTab.count()) await actionsTab.click();
    const iframe = page.locator('.replayer-wrapper iframe');
    for (const cp of checkpoints) {
      const entry = (result.checkpoints[cp.name] = { problems: [] });
      const row = page.locator(`td[title=${JSON.stringify(`${MARKER}/${cp.name}`)}]`);
      if (!(await row.count())) {
        entry.problems.push('no action row to seek with');
        continue;
      }
      await row.first().click();
      await page.waitForTimeout(600);
      await iframe.screenshot({ path: path.join(out, 'dashboard', `${cp.name}.png`) });
      Object.assign(entry, await page.evaluate(
        ({ boxes, tolerance }) => window.twProbe(document.querySelector('.replayer-wrapper iframe').contentDocument, boxes, tolerance),
        { boxes: cp.boxes || [], tolerance },
      ));
      if (entry.screens !== 1) entry.problems.push(`${entry.screens} screen nodes in the dashboard's replay`);
    }
    await context.close();

    const compare = await browser.newPage({ deviceScaleFactor: 1 });
    await compare.goto('file://' + path.join(out, 'player', 'compare.html'));
    await compare.waitForFunction(() => window.twCompare);
    for (const cp of checkpoints) {
      const entry = result.checkpoints[cp.name];
      const shot = path.join(out, 'dashboard', `${cp.name}.png`);
      const standalone = path.join(out, 'replay', `${cp.name}.png`);
      if (!entry || !fs.existsSync(shot) || !fs.existsSync(standalone)) continue;
      const c = await compare.evaluate((input) => window.twCompare(input), {
        app: `file://${standalone}`, replay: `file://${shot}`, baseline: `file://${standalone}`,
        ignore: cp.ignore || [], appIgnore: [], scaleBaseline: true,
      });
      entry.agreement = { fidelity: c.fidelity, changed: c.golden ? c.golden.changed : null, size: c.size };
      if (c.diff) writePng(path.join(out, 'dashboard-diff', `${cp.name}.png`), c.diff);
      const f = c.fidelity;
      console.log(`  ...  dashboard ${cp.name}: ssim ${f.ssim.toFixed(3)} edges ${f.edges.toFixed(3)}, ` +
        `${((c.golden?.changed || 0) * 100).toFixed(2)}% differ from rrweb-player`);
    }
  } finally {
    await browser.close();
  }
}

try {
  await upload();
  save();
  await play();
} catch (e) {
  result.problems.push(e.message.split('\n')[0]);
  console.log(`  FAIL  dashboard: ${e.message.split('\n')[0]}`);
}
for (const message of result.pageErrors) result.problems.push(`page error: ${message.slice(0, 200)}`);
save();
process.exit(0);
