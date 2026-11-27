// Plays an Android wireframe recording in rrweb-player (the dashboard's own
// copy) in headless Chromium, the way the Flutter SDK's
// tool/e2e/wireframe/replay.mjs does: every checkpoint is rendered at 1:1
// and saved as a PNG, and the run fails when the player reports nodes it
// could not find (a mutation that would not apply) or the page has no
// screen.
//
//   node tools/wireframe/replay.mjs --events traceway/build/wireframe-sample/events.json \
//       [--checkpoints traceway/build/wireframe-sample/checkpoints.json] [--out /tmp/tw-replay]
//
// events.json: the clip's rrweb events (a JVM test writes a sample; an
// instrumented run can pull one from the device). checkpoints.json:
// {"name": epochMs, ...}; without it the start, middle and end are shown.
// Needs Playwright (--playwright, default the mise install), Chromium
// (--chromium, default /usr/bin/chromium) and rrweb-player
// (--rrweb-player, default ../traceway/frontend/node_modules/rrweb-player).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  args[process.argv[i].replace(/^--/, '')] = process.argv[i + 1];
}
const home = os.homedir();
const playwrightPath =
  args.playwright || path.join(home, '.local/share/mise/installs/npm-playwright/latest/node_modules/playwright');
const rrwebPlayer =
  args['rrweb-player'] || path.join(home, 'Work/workspace/traceway/frontend/node_modules/rrweb-player');
const chromiumPath = args.chromium || '/usr/bin/chromium';
const out = path.resolve(args.out || path.join(os.tmpdir(), 'tw-wireframe-replay'));
const require = createRequire(import.meta.url);
const { chromium } = require(playwrightPath);

const events = JSON.parse(fs.readFileSync(args.events, 'utf8'));
const first = events[0].timestamp;
const last = events[events.length - 1].timestamp;
let checkpoints = args.checkpoints ? JSON.parse(fs.readFileSync(args.checkpoints, 'utf8')) : null;
if (!checkpoints) {
  checkpoints = { start: first, middle: Math.round((first + last) / 2), end: last };
}
const names = Object.keys(checkpoints);
const meta = events.find((e) => e.type === 4).data;
let failures = 0;
const fail = (message) => {
  failures++;
  console.log(`  FAIL  ${message}`);
};

// Structure: rrweb's player needs a meta event and a full snapshot first, in time order.
if (events[0].type !== 4 || events[1].type !== 2) fail('the clip does not start with a meta event and a full snapshot');
for (let i = 1; i < events.length; i++) {
  if (events[i].timestamp < events[i - 1].timestamp) fail(`event ${i} is out of order`);
}

fs.mkdirSync(path.join(out, 'player'), { recursive: true });
const playerDir = path.join(out, 'player');
fs.copyFileSync(path.join(rrwebPlayer, 'dist', 'rrweb-player.umd.cjs'), path.join(playerDir, 'rrweb-player.js'));
fs.copyFileSync(path.join(rrwebPlayer, 'dist', 'style.css'), path.join(playerDir, 'style.css'));
fs.writeFileSync(path.join(playerDir, 'events.js'), `window.EVENTS = ${JSON.stringify(events)};`);
fs.writeFileSync(
  path.join(playerDir, 'index.html'),
  `<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="style.css">
<style>body{margin:0;background:#fff}
.rr-player .replayer-wrapper{left:0!important;top:0!important;transform:none!important}</style>
<script src="events.js"></script><script src="rrweb-player.js"></script></head>
<body><div id="target"></div><script>
const Player = window.rrwebPlayer.default || window.rrwebPlayer.Player || window.rrwebPlayer;
window.player = new Player({
  target: document.getElementById('target'),
  props: { events: window.EVENTS, width: ${meta.width}, height: ${meta.height},
           autoPlay: false, showController: false, skipInactive: false, mouseTail: false },
});
</script></body></html>`,
);

const browser = await chromium.launch({
  executablePath: chromiumPath,
  headless: true,
  args: ['--allow-file-access-from-files', '--font-render-hinting=none'],
});
const page = await browser.newPage({ viewport: { width: meta.width + 40, height: meta.height + 40 }, deviceScaleFactor: 1 });
const consoleErrors = [];
page.on('console', (m) => {
  if (m.type() === 'error' || m.type() === 'warning') consoleErrors.push(m.text());
});
page.on('pageerror', (e) => consoleErrors.push(String(e)));
await page.goto('file://' + path.join(playerDir, 'index.html'), { waitUntil: 'domcontentloaded' });
await page.waitForFunction(() => window.player);
await page.waitForTimeout(300);
const report = {};
for (const name of names) {
  const at = Math.min(checkpoints[name] - first, last - first);
  await page.evaluate((t) => window.player.goto(t, false), at);
  await page.waitForTimeout(400);
  const file = path.join(out, `${name}.png`);
  await page.locator('.replayer-wrapper').screenshot({ path: file });
  const frame = page.frameLocator('.replayer-wrapper iframe');
  const screen = frame.locator('#tw');
  const count = await screen.count();
  if (count !== 1) {
    fail(`${name}: the replayed page has no #tw screen`);
    continue;
  }
  const text = (await screen.innerText()).replace(/\s+/g, ' ').trim();
  const box = await screen.boundingBox();
  report[name] = { file, text: text.slice(0, 300), size: box && [Math.round(box.width), Math.round(box.height)] };
  console.log(`  ...  ${name}: ${file} (${report[name].size}) "${text.slice(0, 80)}"`);
}
await browser.close();

const replayErrors = consoleErrors.filter((m) => /Node with id|node not found|Exception in mutation|Uncaught/i.test(m));
if (replayErrors.length) fail(`rrweb-player reported ${replayErrors.length} problems: ${replayErrors.slice(0, 3).join(' | ')}`);
else console.log(`  PASS  rrweb-player played ${events.length} events without warnings about missing nodes`);
fs.writeFileSync(path.join(out, 'report.json'), JSON.stringify({ events: events.length, checkpoints: report, consoleErrors }, null, 2));
process.exit(failures ? 1 : 0);
