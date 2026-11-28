#!/usr/bin/env node
// The Android field-test harness (sdk-field-test AGENT-4 step 3). Same commands on the GitHub runner (emulator,
// gateway in capture mode) and on this machine (local processing of what the runner captured).
//
//   node fieldtest/harness/fm.mjs build [--ci] [--out DIR]          APKs + sizes (M-SIZE-1)
//   node fieldtest/harness/fm.mjs device-setup [--size 720x1560 --density 280]
//   node fieldtest/harness/fm.mjs install --apks DIR                adb reverse, install, cmd package compile -m speed
//   node fieldtest/harness/fm.mjs warmup                            the app once until UiAutomator sees it (not a run)
//   node fieldtest/harness/fm.mjs run --scenario S6 --variant stream-img [--evidence] --out DIR [--ledger DIR]
//   node fieldtest/harness/fm.mjs plan --scenarios S1,S6 --variants off,off2,core,stream-img --reps 5 --seed N --out DIR
//   node fieldtest/harness/fm.mjs startup --variants off,core --repeat 10 --out DIR
//   node fieldtest/harness/fm.mjs collect --raw DIR --shell DIR --out DIR [--ledger DIR]
//   node fieldtest/harness/fm.mjs process --gh-run ID | --artifact DIR      download → forward → check → render (here)
//
// DIR for run/plan/startup: the output root; raw device files go to DIR/raw/<run>/, run folders to
// DIR/results/<platform>/{runs,evidence}/<run>/.
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync, readdirSync, copyFileSync, statSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { adb, shell, instrument, connectIfNetwork } from './lib/adb.mjs';
import { collectRun } from './lib/collect.mjs';
import { makePlan } from './lib/plan.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const FIELDTEST = resolve(HERE, '..');
const REPO = resolve(FIELDTEST, '..');
const PLATFORM = process.env.FM_PLATFORM || 'android@emulator';
const DRIVER = 'com.tracewayapp.fieldmart.driver';
const RUNNER = `${DRIVER}/androidx.test.runner.AndroidJUnitRunner`;
const MEDIA = `/sdcard/Android/media/${DRIVER}/fieldtest`;
const SHELL_DIR = '/data/local/tmp/fieldtest';
const PACKAGES = { off: 'com.tracewayapp.fieldmart.off', sdk: 'com.tracewayapp.fieldmart' };
const APKS = { off: 'app-off-bench.apk', sdk: 'app-sdk-bench.apk', driver: 'driver-bench.apk' };
// Local processing needs the campaign's shared tools (FM_TOOLS) and results folder (FM_RESULTS); a local build may
// go through a memory-capped Gradle wrapper (FM_GRADLE, run under FM_HEAVY). None of these exist on a runner.
const TOOLS = process.env.FM_TOOLS;

function args(argv) {
  const o = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const k = a.slice(2);
      const v = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true;
      o[k] = v;
    } else o._.push(a);
  }
  return o;
}

const log = (...a) => console.log(new Date().toISOString().slice(11, 23), ...a);
const list = (s, d = []) => (s ? String(s).split(',').map((x) => x.trim()).filter(Boolean) : d);
const pkgFor = (variant) => (variant.startsWith('off') ? PACKAGES.off : PACKAGES.sdk);
const readJson = (f, d = null) => (existsSync(f) ? JSON.parse(readFileSync(f, 'utf8')) : d);

function sh(cmd, argv, opts = {}) {
  log('$', cmd, argv.join(' '));
  const r = spawnSync(cmd, argv, { stdio: 'inherit', ...opts });
  if (r.status !== 0) throw new Error(`${cmd} exited ${r.status}`);
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

function git(...a) {
  const r = spawnSync('git', ['-C', REPO, ...a], { encoding: 'utf8' });
  return r.status === 0 ? r.stdout.trim() : null;
}

/** The journey and fixture hashes of run.json, as the shared tools define them (tools/lib/hashes.mjs). */
function hashes() {
  const j = join(FIELDTEST, 'shared/journey.json');
  const lock = join(FIELDTEST, 'shared/fixtures.lock.json');
  return {
    journeyHash: existsSync(j) ? `sha256:${sha256(j)}` : null,
    fixturesHash: existsSync(lock) ? `sha256:${JSON.parse(readFileSync(lock, 'utf8')).hash}` : null,
  };
}

// ---------------------------------------------------------------------------------------------------------- build

function build(o) {
  const tasks = [':core:test', ':app-off:assembleBench', ':app-sdk:assembleBench', ':driver:assembleBench'];
  if (o['no-cleartext']) tasks.push(':app-sdk:assembleBenchNoCleartext');
  if (o.ci || !process.env.FM_GRADLE) {
    sh('./gradlew', ['-p', 'fieldtest', '--no-daemon', '--stacktrace', ...tasks], { cwd: REPO });
  } else {
    const wrapped = process.env.FM_HEAVY ? [process.env.FM_HEAVY, ['-m', '3600M', process.env.FM_GRADLE]] : [process.env.FM_GRADLE, []];
    sh(wrapped[0], [...wrapped[1], '-p', 'fieldtest', ...tasks], { cwd: REPO });
  }
  const out = resolve(o.out || join(FIELDTEST, 'build/apks'));
  mkdirSync(out, { recursive: true });
  const found = {
    off: join(FIELDTEST, 'app-off/build/outputs/apk/bench', APKS.off),
    sdk: join(FIELDTEST, 'app-sdk/build/outputs/apk/bench', APKS.sdk),
    driver: join(FIELDTEST, 'driver/build/outputs/apk/bench', APKS.driver),
    sdkNoCleartext: join(FIELDTEST, 'app-sdk/build/outputs/apk/benchNoCleartext', 'app-sdk-benchNoCleartext.apk'),
  };
  const info = { commit: git('rev-parse', '--short=8', 'HEAD'), dirty: !!git('status', '--porcelain', '--', 'traceway', 'gradle-plugin'), apks: {} };
  for (const [k, f] of Object.entries(found)) {
    if (!existsSync(f)) continue;
    copyFileSync(f, join(out, basename(f)));
    info.apks[k] = { file: basename(f), bytes: statSync(f).size, sha256: sha256(f) };
  }
  for (const k of ['off', 'sdk']) {
    const m = join(FIELDTEST, `app-${k}/build/outputs/mapping/bench/mapping.txt`);
    if (existsSync(m)) copyFileSync(m, join(out, `mapping-${k}.txt`));
  }
  info.size = sizes(out, info.apks);
  writeFileSync(join(out, 'build.json'), JSON.stringify(info, null, 2));
  log('apks in', out, JSON.stringify(info.size));
}

/** M-SIZE-1 with apkanalyzer: file size and download size of each app, and the SDK build's difference. */
function sizes(dir, apks) {
  const home = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || join(process.env.HOME, 'Android/Sdk');
  const tool = [join(home, 'cmdline-tools/latest/bin/apkanalyzer'), ...readdirSafe(join(home, 'cmdline-tools')).map((d) => join(home, 'cmdline-tools', d, 'bin/apkanalyzer'))].find(existsSync);
  const out = {};
  for (const k of ['off', 'sdk']) {
    if (!apks[k]) continue;
    const f = join(dir, apks[k].file);
    const one = (what) => {
      if (!tool) return null;
      const r = spawnSync(tool, ['apk', what, f], { encoding: 'utf8' });
      return r.status === 0 ? Number(r.stdout.trim()) : null;
    };
    out[k] = { fileBytes: one('file-size') ?? apks[k].bytes, downloadBytes: one('download-size') };
  }
  if (out.off && out.sdk) {
    out.addedKb = { file: +((out.sdk.fileBytes - out.off.fileBytes) / 1024).toFixed(1), download: out.sdk.downloadBytes && out.off.downloadBytes ? +((out.sdk.downloadBytes - out.off.downloadBytes) / 1024).toFixed(1) : null };
  }
  out.tool = tool ? 'apkanalyzer' : 'file size only';
  return out;
}

function readdirSafe(d) {
  try {
    return readdirSync(d);
  } catch {
    return [];
  }
}

// --------------------------------------------------------------------------------------------------------- device

function deviceSetup(o) {
  connectIfNetwork();
  adb(['wait-for-device']);
  const props = {};
  for (const p of ['ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk', 'ro.product.cpu.abi', 'ro.kernel.qemu', 'ro.hardware', 'ro.build.fingerprint']) {
    props[p] = shell(`getprop ${p}`, { allowFail: true }).trim();
  }
  if (o.size) shell(`wm size ${o.size}`);
  if (o.density) shell(`wm density ${o.density}`);
  // Awake, unlocked, the soft keyboard shown even with a hardware keyboard, animations as the device has them.
  for (const c of ['svc power stayon true', 'settings put system screen_off_timeout 1800000', 'input keyevent 82', 'wm dismiss-keyguard',
    'settings put secure show_ime_with_hard_keyboard 1', 'settings put global package_verifier_enable 0']) shell(c, { allowFail: true });
  const info = {
    props,
    wmSize: shell('wm size', { allowFail: true }).trim(),
    wmDensity: shell('wm density', { allowFail: true }).trim(),
    animations: ['window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'].map((k) => `${k}=${shell(`settings get global ${k}`, { allowFail: true }).trim()}`),
    cpus: shell('nproc', { allowFail: true }).trim(),
    memInfo: shell('head -3 /proc/meminfo', { allowFail: true }).trim(),
    refresh: (shell('dumpsys display', { allowFail: true }).match(/renderFrameRate ([\d.]+)|fps=([\d.]+)/) || [])[0] || null,
  };
  if (o.out) {
    mkdirSync(o.out, { recursive: true });
    writeFileSync(join(o.out, 'device.json'), JSON.stringify(info, null, 2));
  }
  log('device', JSON.stringify(info));
  return info;
}

function install(o) {
  connectIfNetwork();
  const dir = resolve(o.apks || join(FIELDTEST, 'build/apks'));
  adb(['reverse', 'tcp:18190', 'tcp:18190']);
  for (const k of ['off', 'sdk', 'driver']) {
    const f = join(dir, APKS[k]);
    if (!existsSync(f)) throw new Error(`missing ${f}`);
    log('install', f);
    adb(['install', '-r', '-g', '-t', f], { timeoutMs: 300000 });
  }
  // The same compiled code in every run: no JIT warm-up difference (03-MEASUREMENT.md §3.3).
  for (const p of Object.values(PACKAGES)) log('compile', p, shell(`cmd package compile -m speed -f ${p}`, { timeoutMs: 600000 }).trim());
  log('reverse', adb(['reverse', '--list']).stdout.trim());
}

// ------------------------------------------------------------------------------------------------------------ run

function context(o) {
  const build = readJson(join(resolve(o.apks || join(FIELDTEST, 'build/apks')), 'build.json'), {});
  return {
    campaign: o.campaign || process.env.FM_CAMPAIGN || 'FT-20261128-1',
    platform: PLATFORM,
    target: o.target || process.env.FM_TARGET || 'emulator-api34',
    captureMode: o.capture !== 'false',
    build: { sdkCommit: build.commit || git('rev-parse', '--short=8', 'HEAD'), sdkDirty: build.dirty ?? null, appBuildId: build.apks ? { off: build.apks.off?.sha256?.slice(0, 12), sdk: build.apks.sdk?.sha256?.slice(0, 12) } : null },
    ...hashes(),
  };
}

/** One run: the driver plays it on the device, the raw files come back, the run folder is written. */
async function runOne(r, o, ctx) {
  const out = resolve(o.out);
  const extra = [];
  const put = (k, v) => v != null && v !== '' && extra.push('-e', k, String(v));
  put('fmRun', r.runId);
  put('fmScenario', r.scenario);
  put('fmVariant', r.variant);
  put('fmEvidence', r.evidence ? '1' : '0');
  put('fmPackage', pkgFor(r.variant));
  put('fmTarget', ctx.target);
  put('fmCampaign', ctx.campaign);
  put('fmToolkit', r.toolkit || o.toolkit);
  put('fmFps', r.fps || o.fps);
  put('fmRemote', o.remote);
  put('fmFlingDir', o['fling-dir']);
  if (r.mode) put('fmMode', r.mode);
  if (r.repeat) put('fmRepeat', r.repeat);
  if (r.clear != null) put('fmClear', r.clear ? '1' : '0');
  log('run', r.runId, r.evidence ? '(evidence)' : '');
  const started = Date.now();
  const res = await instrument([...extra, RUNNER], { onLine: (l) => /INSTRUMENTATION_STATUS: (stack|Error)|FAILURES|Exception/.test(l) && log('  ', l) });
  const raw = join(out, 'raw', r.runId);
  mkdirSync(raw, { recursive: true });
  writeFileSync(join(raw, 'instrument.txt'), res.text);
  adb(['pull', `${MEDIA}/${r.runId}`, join(raw, 'media')], { allowFail: true, timeoutMs: 600000 });
  if (r.evidence) adb(['pull', `${SHELL_DIR}/${r.runId}`, join(raw, 'shell')], { allowFail: true, timeoutMs: 600000 });
  shell(`rm -r ${MEDIA}/${r.runId} ${SHELL_DIR}/${r.runId}`, { allowFail: true });
  const media = existsSync(join(raw, 'media', r.runId)) ? join(raw, 'media', r.runId) : join(raw, 'media');
  const shellDir = existsSync(join(raw, 'shell', r.runId)) ? join(raw, 'shell', r.runId) : join(raw, 'shell');
  if (r.mode === 'startup') {
    const dst = join(out, 'results', PLATFORM, 'startup', r.runId);
    mkdirSync(dst, { recursive: true });
    if (existsSync(join(media, 'startup.json'))) copyFileSync(join(media, 'startup.json'), join(dst, 'startup.json'));
    return { runId: r.runId, ok: res.ok };
  }
  if (!existsSync(join(media, 'run-meta.json'))) {
    log('  no run-meta.json: the driver failed', res.text.slice(-1500));
    return { runId: r.runId, ok: false, error: 'no-output' };
  }
  const dst = join(out, 'results', PLATFORM, r.evidence ? 'evidence' : 'runs', r.runId);
  const run = collectRun(media, shellDir, dst, { ...ctx, block: r.block, orderInBlock: r.orderInBlock, ledgerDir: o.ledger && resolve(o.ledger) });
  if (r.warmup) {
    run.flags.push('warmup');
    writeFileSync(join(dst, 'run.json'), JSON.stringify(run, null, 2));
  }
  // The run folder has everything now; drop the raw copies that only duplicate it (screen pieces, screenshots),
  // so an artifact stays under 500 MB. FM_KEEP_RAW=1 keeps them.
  if (process.env.FM_KEEP_RAW !== '1') {
    if (existsSync(join(dst, 'screen.mp4'))) rmSync(shellDir, { recursive: true, force: true });
    rmSync(join(media, 'app'), { recursive: true, force: true });
    // A measured run's folder has everything of its raw files (the sampler text is in samples.jsonl).
    if (!r.evidence) rmSync(media, { recursive: true, force: true });
  }
  log('  done in', Math.round((Date.now() - started) / 1000), 's valid', run.valid, run.flags.join(','), `CPU ${run.metrics['M-CPU-1']} s`);
  return { runId: r.runId, ok: res.ok && run.valid, flags: run.flags };
}

/** The vendored journey's scenario [id]: its variant (S7 parts), platforms and tiers. */
function scenarioInfo(id) {
  const j = readJson(join(FIELDTEST, 'shared/journey.json'), { scenarios: {} });
  const s = j.scenarios[id];
  if (!s) throw new Error(`no scenario ${id} in shared/journey.json`);
  if (s.platforms && !s.platforms.includes('android')) throw new Error(`${id} is not an Android scenario (${s.platforms.join(', ')})`);
  if (s.tiers && s.tiers.includes('live') && !s.tiers.includes('capture')) {
    log(`${id} needs a live Traceway (${s.notes || 'remote settings'}); in capture mode it is n/a`);
  }
  return s;
}

async function run(o) {
  const ctx = context(o);
  const info = scenarioInfo(o.scenario);
  o.variant = o.variant || info.variant;
  const r = {
    scenario: o.scenario,
    variant: o.variant || 'core',
    evidence: !!o.evidence,
    runId: o.run || makePlan({ scenarios: [o.scenario], variants: [o.variant || 'core'], reps: 1, seed: Date.now() & 0xffffff, warmup: 0, evidence: o.evidence ? [{ scenario: o.scenario, variant: o.variant || 'core' }] : [] }).at(-1).runId,
    block: o.block != null ? Number(o.block) : null,
    orderInBlock: o.order != null ? Number(o.order) : null,
  };
  const res = await runOne(r, o, ctx);
  if (!res.ok) process.exitCode = 1;
}

async function plan(o) {
  const ctx = context(o);
  const seed = Number(o.seed || 1112970949);
  const scenarios = list(o.scenarios || o.scenario);
  for (const sc of scenarios) scenarioInfo(sc);
  const evidence = [];
  for (const s of scenarios) for (const v of list(o['evidence-variants'])) evidence.push({ scenario: s, variant: v });
  // --extra stream-noimg:S1+S2 adds a variant to the blocks of those scenarios only (plan.json extraVariants).
  const extraVariants = {};
  for (const e of list(o.extra)) {
    const [v, ss] = e.split(':');
    if (v && ss) extraVariants[v] = ss.split('+');
  }
  // One job per scenario: the order is drawn for the whole plan (--plan-scenarios, every scenario of the wave in
  // the campaign's order) and this job keeps its own scenario's runs, so the jobs together play the plan of one seed.
  const all = list(o['plan-scenarios'] || o.scenarios || o.scenario);
  for (const sc of scenarios) if (!all.includes(sc)) throw new Error(`scenario ${sc} is not in --plan-scenarios ${all.join(',')}`);
  const runs = makePlan({ scenarios: all, variants: list(o.variants, ['off', 'off2', 'core', 'stream-img']), reps: Number(o.reps || 5), seed, warmup: o.warmup === '0' ? 0 : 1, evidence, extraVariants })
    .filter((r) => scenarios.includes(r.scenario));
  const out = resolve(o.out);
  mkdirSync(join(out, 'results', PLATFORM), { recursive: true });
  writeFileSync(join(out, 'results', PLATFORM, `plan-${scenarios.join('+')}-${seed}.json`), JSON.stringify({ seed, planScenarios: all, extraVariants, scenarios, runs }, null, 2));
  const results = [];
  for (const r of runs) {
    // A run that fails is kept and repeated once (03-MEASUREMENT.md §5.3).
    let res = await runOne(r, o, ctx);
    if (!res.ok && !r.warmup) {
      log('  repeating once', r.runId);
      res = await runOne({ ...r, runId: `${r.runId}r` }, o, ctx);
      res.retry = true;
    }
    results.push(res);
  }
  writeFileSync(join(out, 'results', PLATFORM, `plan-${scenarios.join('+')}-${seed}.results.json`), JSON.stringify(results, null, 2));
  log('plan done', results.filter((r) => r.ok).length, 'of', results.length, 'ok');
}

/**
 * Cold starts (03-MEASUREMENT.md §4) in blocks: in each of [repeat] blocks every variant starts once, in an order drawn
 * from the seed, from cleared data (or kept with --keep). Each launch is a run of scenario `S0-startup` with
 * M-ST-1 (am start -W TotalTime: process start to the first frame drawn) and M-ST-2 (to FM_FULLY), paired by block.
 */
async function startup(o) {
  const ctx = context(o);
  const variants = list(o.variants, ['off', 'off2', 'core', 'stream-img']);
  const seed = Number(o.seed || 1112970949);
  const plan = makePlan({ scenarios: ['S0'], variants, reps: Number(o.repeat || 10), seed, warmup: 0 });
  const out = resolve(o.out);
  // 03-MEASUREMENT.md §4.3: from clean state (pm clear) every time; the second pass (--keep) keeps the state.
  const scenario = o.keep ? 'S0-startup-kept' : 'S0-startup';
  for (const r of plan) {
    const runId = r.runId.replace('-S0-', o.keep ? '-S0startupkept-' : '-S0startup-');
    const res = await runOne({ ...r, runId, mode: 'startup', repeat: 1, clear: !o.keep }, o, ctx);
    const src = join(out, 'results', PLATFORM, 'startup', runId, 'startup.json');
    const st = readJson(src);
    const l = st?.launches?.[0];
    const dst = join(out, 'results', PLATFORM, 'runs', runId);
    mkdirSync(dst, { recursive: true });
    const metrics = {};
    if (l?.totalTimeMs != null) metrics['M-ST-1'] = l.totalTimeMs;
    if (l?.fullyAfterStartMs != null) metrics['M-ST-2'] = l.fullyAfterStartMs;
    if (l?.readyAfterStartMs != null) metrics.readyAfterAmStartMs = l.readyAfterStartMs;
    const valid = !!(res.ok && l?.totalTimeMs != null);
    writeFileSync(join(dst, 'run.json'), JSON.stringify({
      runId, campaign: ctx.campaign, platform: PLATFORM, target: ctx.target, scenario, variant: r.variant,
      block: r.block, orderInBlock: r.orderInBlock, evidence: false, t0EpochMs: l?.amStartEpochMs ?? null, endEpochMs: null,
      valid, flags: ['capture-mode', ...(o.keep ? ['kept-state'] : []), ...(valid ? [] : ['invalid'])], sessionId: null, projectId: null,
      metrics, units: 'ms', build: ctx.build, journeyHash: ctx.journeyHash, fixturesHash: ctx.fixturesHash,
      android: { launch: l || null, clearedData: !o.keep },
    }, null, 2));
    log('  startup', runId, JSON.stringify(metrics));
  }
}

/** The app once before a job's first run, until UiAutomator sees it (see the driver's warm-up); not recorded. */
async function warmup(o) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    const res = await instrument(['-e', 'fmMode', 'warmup', '-e', 'fmScenario', 'S0', '-e', 'fmPackage', PACKAGES.off, RUNNER]);
    log('warm-up', attempt, res.ok ? 'ok' : 'failed');
    if (res.ok) return;
  }
  throw new Error('warm-up failed three times');
}

function collect(o) {
  const run = collectRun(resolve(o.raw), o.shell ? resolve(o.shell) : null, resolve(o.out), { ...context(o), ledgerDir: o.ledger && resolve(o.ledger) });
  log('collected', run.runId, 'valid', run.valid, JSON.stringify(run.metrics));
}

// ------------------------------------------------------------------------------------------- local processing (here)

/**
 * What the runner captured, processed here: download the artifact (gh run download), forward each run's captured
 * ledger into the local Traceway (within two hours of capture), copy the run folders into the campaign, then the
 * shared checker and renderer on every evidence run. The shared tools are Agent 1's (FM_TOOLS).
 */
async function processRuns(o) {
  const campaign = o.campaign || 'FT-20261128-1';
  if (!TOOLS) throw new Error('set FM_TOOLS to the shared tools folder');
  const results = resolve(o.results || join(process.env.FM_RESULTS || '', campaign, PLATFORM));
  if (!o.results && !process.env.FM_RESULTS) throw new Error('set FM_RESULTS (the campaigns folder) or pass --results');
  let art = o.artifact && resolve(o.artifact);
  if (o['gh-run']) {
    art = resolve(o.into || join(process.env.HOME, '.cache/sdk-field-test/android/downloads', String(o['gh-run'])));
    if (existsSync(art)) throw new Error(`${art} exists: pass --into a new folder`);
    mkdirSync(art, { recursive: true });
    sh('gh', ['run', 'download', String(o['gh-run']), '-R', o.repo || 'tracewayapp/traceway-android', '-D', art]);
  }
  if (!art) throw new Error('--gh-run ID or --artifact DIR');
  const roots = findOutputRoots(art);
  log('outputs', roots);
  const added = { runs: [], evidence: [], startup: [] };
  for (const root of roots) {
    const ledger = join(root, 'ledger');
    if (existsSync(ledger) && !o['no-forward']) {
      // --speed 1 keeps the capture's spacing, so the backend's per-30 s limits see what a live run would send.
      sh(process.execPath, [join(TOOLS, 'gateway/gateway.mjs'), 'forward', '--ledger', ledger, '--speed', String(o.speed ?? 1), '--upstream', o.upstream || 'http://127.0.0.1:18182']);
    }
    const src = join(root, 'results', PLATFORM);
    for (const kind of ['runs', 'evidence', 'startup']) {
      const d = join(src, kind);
      if (!existsSync(d)) continue;
      for (const r of readdirSync(d)) {
        const dst = join(results, kind, r);
        if (existsSync(dst)) {
          log('exists, kept', dst);
          continue;
        }
        copyTree(join(d, r), dst);
        added[kind].push(r);
        if (existsSync(join(ledger, `${r}.forward.jsonl`))) copyFileSync(join(ledger, `${r}.forward.jsonl`), join(dst, 'ledger.forward.jsonl'));
      }
    }
    for (const f of readdirSafe(src).filter((f) => f.endsWith('.json'))) copyFileSync(join(src, f), join(results, f));
  }
  log('added', Object.entries(added).map(([k, v]) => `${v.length} ${k}`).join(', '));
  if (o['no-check']) return;
  // Measured runs are not forwarded: the checks that need only the captured ledger (E-25 for off and off2, E-20 for
  // core; the session half of E-20 cannot be seen without a forward). No browser, so no lock (check.mjs).
  for (const r of added.runs) {
    const dir = join(results, 'runs', r);
    const run = readJson(join(dir, 'run.json'));
    // Warm-ups are not results; a startup launch is not a journey (no run marks, nothing to check in the ledger).
    if (!run || (run.flags || []).includes('warmup') || /startup/i.test(run.scenario)) continue;
    const only = run.variant.startsWith('off') ? 'E-25' : run.variant === 'core' ? 'E-20' : null;
    if (!only) continue;
    try {
      sh(process.execPath, [join(TOOLS, 'tw/check.mjs'), '--run-dir', dir, '--platform', PLATFORM, '--only', only, '--no-dom', '--no-dashboard']);
    } catch (e) {
      log('check failed', r, e.message);
    }
  }
  // Traceway stores forwarded recordings a few seconds after it answers; checking at once finds no events.
  const settle = Number(o['settle-s'] ?? 30);
  if (settle > 0 && !o['no-forward']) {
    log(`waiting ${settle} s for the forwarded data to be stored`);
    await new Promise((r) => setTimeout(r, settle * 1000));
  }
  // The checker and the renderer take the measurement lock themselves (tools/README.md).
  // Only the evidence runs this call added (--recheck: all), so checks.json gets each run's results once.
  for (const r of o.recheck ? readdirSafe(join(results, 'evidence')) : added.evidence) {
    const dir = join(results, 'evidence', r);
    if (!existsSync(join(dir, 'run.json'))) continue;
    try {
      sh(process.execPath, [join(TOOLS, 'tw/check.mjs'), '--run-dir', dir, '--platform', PLATFORM]);
    } catch (e) {
      log('check failed', r, e.message);
    }
    if (!o['no-render']) {
      try {
        sh(process.execPath, [join(TOOLS, 'replay-render/render.mjs'), '--run-dir', dir, ...(o['no-dashboard'] ? ['--no-dashboard'] : [])]);
      } catch (e) {
        log('render failed', r, e.message);
      }
    }
  }
}

function findOutputRoots(dir) {
  const out = [];
  const walk = (d, depth) => {
    if (existsSync(join(d, 'results', PLATFORM))) out.push(d);
    else if (depth < 3) for (const e of readdirSafe(d)) if (statSync(join(d, e)).isDirectory()) walk(join(d, e), depth + 1);
  };
  walk(dir, 0);
  return out;
}

function copyTree(src, dst) {
  mkdirSync(dst, { recursive: true });
  for (const e of readdirSync(src)) {
    const s = join(src, e);
    const d = join(dst, e);
    if (statSync(s).isDirectory()) copyTree(s, d);
    else copyFileSync(s, d);
  }
}

// ----------------------------------------------------------------------------------------------------------- main

const o = args(process.argv.slice(2));
const cmd = o._[0];
const commands = { build, 'device-setup': deviceSetup, install, warmup, run, plan, startup, collect, process: processRuns };
if (!commands[cmd]) {
  console.log(readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(1, 16).map((l) => l.replace(/^\/\/ ?/, '')).join('\n'));
  process.exit(cmd ? 2 : 0);
}
Promise.resolve(commands[cmd](o)).catch((e) => {
  console.error(e.stack || e);
  process.exit(1);
});
