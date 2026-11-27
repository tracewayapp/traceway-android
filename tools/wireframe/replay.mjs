// Renders a wireframe run's recording in rrweb-player (the dashboard's copy)
// at 1:1 in Playwright, for run.py: at every checkpoint the replay is
// screenshotted, its texts and the boxes of the checkpoint's `boxes` texts
// are read from the replay iframe (compare.js twProbe), and the picture is
// compared (compare.js twCompare) with the one the app drew of itself at that
// moment and with the baseline rendering.
//
// Reads <out>/events.json and <out>/check.json (run.json as run.py checks it);
// writes <out>/replay, delta, diff and baseline PNGs and <out>/visual.json.
import fs from 'node:fs';
import path from 'node:path';
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
const sample = run.sampleMs;
const chosen = new Set(run.selected);
const checkpoints = run.checkpoints.filter((cp) => chosen.has(cp.name));
const tolerance = { position: Number(args['geometry-position'] || 1.5), size: Number(args['geometry-size'] || 2) };
const first = events[0].timestamp;
const problems = [];
const visual = { checkpoints: {}, problems, sampleMs: sample };

const metas = events.filter((e) => e.type === 4);
const meta = (metas.filter((e) => e.timestamp <= checkpoints[0].at).pop() || metas[0]).data;

const writePng = (file, dataUrl) =>
  fs.writeFileSync(file, Buffer.from(dataUrl.slice(dataUrl.indexOf(',') + 1), 'base64'));

for (const dir of ['replay', 'delta', 'diff', 'baseline']) fs.mkdirSync(path.join(out, dir), { recursive: true });

const playerDir = path.join(out, 'player');
fs.mkdirSync(playerDir, { recursive: true });
fs.copyFileSync(path.join(args['rrweb-player'], 'dist', 'rrweb-player.umd.cjs'), path.join(playerDir, 'rrweb-player.js'));
fs.copyFileSync(path.join(args['rrweb-player'], 'dist', 'style.css'), path.join(playerDir, 'style.css'));
fs.copyFileSync(path.join(here, 'compare.js'), path.join(playerDir, 'compare.js'));
fs.writeFileSync(path.join(playerDir, 'events.js'), `window.EVENTS = ${JSON.stringify(events)};`);
fs.writeFileSync(path.join(playerDir, 'compare.html'), '<!doctype html><meta charset="utf-8"><script src="compare.js"></script>');
fs.writeFileSync(
  path.join(playerDir, 'index.html'),
  `<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="style.css">
<style>body{margin:0;background:#fff} .replayer-mouse,.replayer-mouse-tail{display:none!important}
.rr-player .replayer-wrapper{left:0!important;top:0!important;transform:none!important}</style>
<script src="events.js"></script><script src="rrweb-player.js"></script><script src="compare.js"></script></head>
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
  executablePath: args.chromium,
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
await page.waitForFunction(() => window.player && window.twCompare);
await page.waitForTimeout(300);
const probes = {};
for (const cp of checkpoints) {
  await page.evaluate((t) => window.player.goto(t, false), cp.at + sample - first);
  await page.waitForTimeout(350);
  await page.locator('.replayer-wrapper').screenshot({ path: path.join(out, 'replay', `${cp.name}.png`) });
  probes[cp.name] = await page.evaluate(
    ({ boxes, tolerance }) => window.twProbe(document.querySelector('.replayer-wrapper iframe').contentDocument, boxes, tolerance),
    { boxes: cp.boxes || [], tolerance },
  );
}

for (const cp of checkpoints) {
  const name = cp.name;
  const probe = probes[name];
  const geometry = probe.geometry;
  const entry = (visual.checkpoints[name] = { texts: probe.texts, screens: probe.screens, geometry, layout: probe.layout });
  const baselinePng = args.baseline ? path.join(args.baseline, `${name}.png`) : null;
  const hasBaseline = baselinePng && fs.existsSync(baselinePng);
  if (hasBaseline) fs.copyFileSync(baselinePng, path.join(out, 'baseline', `${name}.png`));
  const appPng = path.join(out, 'app', `${name}.png`);
  let result;
  try {
    result = await page.evaluate((input) => window.twCompare(input), {
      app: fs.existsSync(appPng) ? `file://${appPng}` : null,
      replay: `file://${out}/replay/${name}.png`,
      baseline: hasBaseline ? `file://${baselinePng}` : null,
      ignore: cp.ignore || [],
      appIgnore: cp.appIgnore || [],
    });
  } catch (e) {
    problems.push(`${name}: cannot compare (${e.message})`);
    continue;
  }
  if (result.delta) writePng(path.join(out, 'delta', `${name}.png`), result.delta);
  if (result.diff) writePng(path.join(out, 'diff', `${name}.png`), result.diff);
  const [aw, ah, rw, rh] = result.size;
  if (aw !== rw || ah !== rh) problems.push(`${name}: the app's picture is ${aw}x${ah}, the replay ${rw}x${rh}`);
  Object.assign(entry, { fidelity: result.fidelity, golden: result.golden, size: result.size });
  const f = result.fidelity;
  const off = geometry.filter((b) => !b.ok).length;
  console.log(
    `  ...  ${name}: ` +
      (f ? `ssim ${f.ssim.toFixed(3)} edges ${f.edges.toFixed(3)} color ${f.color.toFixed(3)}` : 'no app picture') +
      (result.golden ? `, ${(result.golden.changed * 100).toFixed(2)}% changed` : '') +
      (geometry.length ? `, geometry ${geometry.length - off}/${geometry.length}` : ''),
  );
}
const replayErrors = consoleErrors.filter((m) => /Node with id|node not found|Exception in mutation|Uncaught/i.test(m));
if (replayErrors.length) problems.push(`rrweb-player reported ${replayErrors.length} problems: ${replayErrors.slice(0, 3).join(' | ')}`);
else console.log('  PASS  rrweb-player played the whole recording without warnings about missing nodes');

await browser.close();
fs.writeFileSync(path.join(out, 'visual.json'), JSON.stringify(visual, null, 1));
process.exit(0);
