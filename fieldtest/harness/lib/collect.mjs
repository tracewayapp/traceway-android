// Turns what the driver left on the device (pulled into a raw folder) into a run folder of 05-RESULTS-FORMAT.md §2.
import { existsSync, readFileSync, writeFileSync, mkdirSync, readdirSync, copyFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { parseSampleBlocks, cpuFromBlocks, parseGfxinfo, frameMetrics, parseMeminfo, captureTimes, markers, median } from './raw.mjs';
import { buildScreen } from './screen.mjs';

const readJson = (f, d = null) => (existsSync(f) ? JSON.parse(readFileSync(f, 'utf8')) : d);
const readText = (f) => (existsSync(f) ? readFileSync(f, 'utf8') : '');

/** Scenarios in which the app is meant to die (02-APP-SPEC.md §5.4, S7b crash and S7e kill). */
const EXPECTS_DEATH = /^S7[be]/;

/** The gateway's ledger lines for [runId] from a ledger folder ({runId}.jsonl), or []. */
export function ledgerLines(ledgerDir, runId, file = `${runId}.jsonl`) {
  if (!ledgerDir) return [];
  const f = join(ledgerDir, file);
  if (!existsSync(f)) return [];
  return readFileSync(f, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
}

export function netMetrics(lines, durationMs) {
  const byRoute = {};
  const requests = {};
  let total = 0;
  for (const l of lines) {
    if (!l.path || l.marker || l.mark) continue;
    const kb = (l.wireBytes || 0) / 1024;
    byRoute[l.path] = (byRoute[l.path] || 0) + kb;
    requests[l.path] = (requests[l.path] || 0) + 1;
    total += kb;
  }
  const minutes = durationMs / 60000;
  return {
    'M-NET-1': { totalKb: +total.toFixed(1), perMinKb: minutes > 0 ? +(total / minutes).toFixed(1) : null, byRoute: Object.fromEntries(Object.entries(byRoute).map(([k, v]) => [k, +v.toFixed(1)])) },
    'M-NET-2': requests,
  };
}

/**
 * [rawDir]: the driver's media folder for the run; [shellDir]: the shell folder (screen pieces); [outDir]: the run
 * folder to write. [ctx]: campaign, platform, target, block, orderInBlock, ledgerDir, build, journeyHash,
 * fixturesHash, captureMode.
 */
export function collectRun(rawDir, shellDir, outDir, ctx = {}) {
  mkdirSync(outDir, { recursive: true });
  const meta = readJson(join(rawDir, 'run-meta.json'));
  if (!meta) throw new Error(`no run-meta.json in ${rawDir}`);
  const t0 = meta.t0EpochMs;
  const end = meta.endEpochMs;
  const flags = new Set(meta.flags || []);
  if (ctx.captureMode) flags.add('capture-mode');

  // Samples and CPU.
  const blocks = parseSampleBlocks(readText(join(rawDir, 'samples.raw')));
  const { samples, cpu } = cpuFromBlocks(blocks, { t0, toMs: end });
  writeFileSync(join(outDir, 'samples.jsonl'), samples.map((s) => JSON.stringify(s)).join('\n') + (samples.length ? '\n' : ''));
  const durS = (end - t0) / 1000;
  const metrics = {};
  if (cpu.totalMs != null) {
    metrics['M-CPU-1'] = +(cpu.totalMs / 1000).toFixed(3);
    metrics['M-CPU-2'] = +((100 * cpu.totalMs) / 1000 / durS).toFixed(2);
    metrics['M-CPU-3'] = +(cpu.byGroup.main / 1000).toFixed(3);
    metrics['M-CPU-4'] = +(cpu.byGroup.Traceway / 1000).toFixed(3);
    metrics.cpuByGroupS = Object.fromEntries(Object.entries(cpu.byGroup).map(([k, v]) => [k, +(v / 1000).toFixed(3)]));
    metrics.cpuByThreadS = Object.fromEntries(Object.entries(cpu.byThread).filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]).map(([k, v]) => [k, +(v / 1000).toFixed(3)]));
    metrics.cpuWindowS = +(cpu.windowMs / 1000).toFixed(1);
  }
  // M-CPU-5 (S0 only): average CPU from 10 s to 70 s.
  if (/^S0/.test(meta.scenario)) {
    const idle = cpuFromBlocks(blocks, { t0, fromMs: t0 + 10000, toMs: t0 + 70000 }).cpu;
    if (idle.totalMs != null && idle.windowMs > 0) metrics['M-CPU-5'] = +((100 * idle.totalMs) / idle.windowMs).toFixed(2);
  }
  const inRun = samples.filter((s) => s.t >= 0 && s.t <= end - t0 + 1500 && s.rssKb);
  const last20 = inRun.filter((s) => s.t >= end - t0 - 20000);
  if (inRun.length) {
    metrics['M-MEM-1'] = +(median(last20.map((s) => s.rssKb)) / 1024).toFixed(1);
    metrics['M-MEM-2'] = +(Math.max(...inRun.map((s) => s.rssKb)) / 1024).toFixed(1);
    metrics.memSource = 'RSS from /proc/<pid>/statm';
  }
  const noisy = samples.filter((s) => s.t >= 0 && s.t <= end - t0 && s.hostOtherPct != null).map((s) => s.hostOtherPct);
  if (noisy.length) metrics.hostOtherPctAvg = +(noisy.reduce((a, b) => a + b, 0) / noisy.length).toFixed(1);

  // Frames and memory dumps.
  for (const f of readdirSync(rawDir)) if (/^(gfxinfo\.txt|meminfo-.*\.txt)$/.test(f)) copyFileSync(join(rawDir, f), join(outDir, f));
  const g = parseGfxinfo(readText(join(rawDir, 'gfxinfo.txt')));
  const fr = frameMetrics(g);
  if (fr) Object.assign(metrics, { 'M-FR-1': fr['M-FR-1'], 'M-FR-2': +fr['M-FR-2']?.toFixed(2), 'M-FR-3': fr['M-FR-3'], 'M-FR-4': fr['M-FR-4'], framesTotal: fr.frames, jankyPct: fr.jankyPct != null ? +fr.jankyPct.toFixed(2) : null });
  const mem = parseMeminfo(readText(join(rawDir, 'meminfo-end.txt')));
  if (mem.javaHeapKb != null) metrics['M-MEM-3'] = +(mem.javaHeapKb / 1024).toFixed(1);
  if (mem.totalPssKb != null) metrics.pssEndMb = +(mem.totalPssKb / 1024).toFixed(1);
  if (mem.nativeHeapKb != null) metrics.nativeHeapEndMb = +(mem.nativeHeapKb / 1024).toFixed(1);
  if (mem.graphicsKb != null) metrics.graphicsEndMb = +(mem.graphicsKb / 1024).toFixed(1);

  // Startup of the cold launch of this run (the dedicated startup procedure is `harness startup`).
  const cold = (meta.launches || [])[0];
  if (cold) {
    if (cold.totalTimeMs != null) metrics.amStartTotalTimeMs = cold.totalTimeMs;
    if (cold.readyEpochMs) metrics.readyAfterAmStartMs = cold.readyEpochMs - cold.amStartEpochMs;
  }

  // Logs, markers, the SDK's self-reported capture time.
  const appLog = readText(join(rawDir, 'app.log'));
  writeFileSync(join(outDir, 'app.log'), appLog);
  copyIf(join(rawDir, 'driver.log'), join(outDir, 'driver.log'));
  const caps = captureTimes(appLog);
  if (caps.length) metrics['M-SDK-4'] = { avgMs: +(caps.reduce((a, c) => a + c.ms, 0) / caps.length).toFixed(2), reports: caps.length, views: median(caps.map((c) => c.views)) };
  const marks = markers(appLog);

  // The probe: frame intervals as µs.
  const probe = readJson(join(rawDir, 'probe.json'));
  writeFileSync(join(outDir, 'probe.json'), JSON.stringify({
    source: probe?.source || 'none',
    frames: probe?.frames || [],
    vsyncMs: probe?.vsyncMs || [],
    droppedReports: probe?.droppedReports || 0,
    markers: (probe?.markers || []).concat(marks.filter((m) => m.epochMs).map((m) => ({ name: `${m.name} ${m.rest}`.trim(), epochMs: m.epochMs, from: 'logcat' }))),
  }));

  // Checkpoints, steps, actions.
  const cps = readJson(join(rawDir, 'checkpoints.json'), []);
  writeFileSync(join(outDir, 'checkpoints.json'), JSON.stringify(cps, null, 1));
  for (const f of ['steps.json', 'actions.json', 'run-meta.json']) copyIf(join(rawDir, f), join(outDir, f));

  // The ledger slice, with the fixture requests (fxlog.jsonl), by the shared ledger tool; bodies are kept for evidence
  // runs only (05-RESULTS-FORMAT.md §1).
  if (ctx.ledgerDir) {
    const tool = join(dirname(dirname(dirname(fileURLToPath(import.meta.url)))), 'shared/gateway/ledger.mjs');
    const r = spawnSync(process.execPath, [tool, 'export', '--ledger', ctx.ledgerDir, '--run', meta.runId, '--to', outDir, ...(meta.evidence ? [] : ['--no-bodies'])], { encoding: 'utf8' });
    if (r.status !== 0) flags.add('ledger-export-failed');
    Object.assign(metrics, netMetrics(ledgerLines(outDir, null, 'ledger.jsonl'), end - t0));
  }

  // Evidence: screenshots, text boxes, the real screen.
  let screen = null;
  if (meta.evidence) {
    const appDir = join(rawDir, 'app');
    if (existsSync(appDir)) {
      mkdirSync(join(outDir, 'app'), { recursive: true });
      for (const f of readdirSync(appDir)) copyFileSync(join(appDir, f), join(outDir, 'app', f));
    }
    copyIf(join(rawDir, 'boxes.json'), join(outDir, 'boxes.json'));
    const pieces = readJson(join(rawDir, 'screen-pieces.json'), []);
    if (shellDir && pieces.length) {
      try {
        // Taps with no other gesture in the 1.5 s before them: a reveal or a fling moves the picture there first.
        const actions = readJson(join(rawDir, 'actions.json'), []) || [];
        const taps = actions.filter((a) => (a.do || a.kind) === 'tap' && a.x != null).filter((t) =>
          !actions.some((o) => o !== t && (o.do || o.kind) !== 'checkpoint' && (o.endEpochMs ?? o.epochMs) <= t.epochMs && (o.endEpochMs ?? o.epochMs) > t.epochMs - 1500));
        const logical = meta.device ? { width: meta.device.widthDp, height: meta.device.heightDp } : null;
        screen = buildScreen({ rawDir: shellDir, outDir, pieces, checkpoints: cps, taps, logical });
      } catch (e) {
        flags.add('screen-failed');
        writeFileSync(join(outDir, 'screen-error.txt'), String(e.stack || e));
      }
    }
  }

  const expectedDeath = EXPECTS_DEATH.test(meta.scenario);
  if (expectedDeath) flags.delete('crashed');
  const valid = !flags.has('late') && !flags.has('crashed') && !flags.has('driver-error') && !flags.has('no-ready') && !flags.has('step-failed');
  const run = {
    runId: meta.runId,
    campaign: ctx.campaign || null,
    platform: ctx.platform || 'android@emulator',
    target: ctx.target || `${meta.device?.model} API ${meta.device?.sdk}`,
    scenario: meta.scenario,
    variant: meta.variant,
    block: ctx.block ?? null,
    orderInBlock: ctx.orderInBlock ?? null,
    evidence: !!meta.evidence,
    device: meta.device ? { width: +Number(meta.device.widthDp).toFixed(1), height: +Number(meta.device.heightDp).toFixed(1), scale: meta.device.density } : null,
    t0EpochMs: t0,
    endEpochMs: end,
    valid,
    // run.schema.json's flag words; the harness's own reasons (step-failed, driver-error, …) go to android.reasons.
    flags: schemaFlags(flags, valid),
    sessionId: null,
    projectId: null,
    metrics,
    units: 'see 03-MEASUREMENT.md §2; memory MB, CPU seconds or % of one core',
    build: ctx.build || {},
    journeyHash: ctx.journeyHash || null,
    fixturesHash: ctx.fixturesHash || null,
    android: {
      package: meta.package,
      reasons: [...flags].filter((f) => !RUN_FLAGS.has(f)),
      device: meta.device,
      maxLateMs: meta.maxLateMs,
      launches: (meta.launches || []).map(({ amStart, ...rest }) => rest),
      marks: meta.marks,
      processGoneEpochMs: meta.processGoneEpochMs || null,
      screen: screen ? { startEpochMs: screen.startEpochMs, pieces: screen.pieces.map((p) => ({ latencyMs: p.latencyMs, method: p.method, matchesUsed: p.matchesUsed, spreadMs: p.spreadMs })) } : null,
    },
  };
  writeFileSync(join(outDir, 'run.json'), JSON.stringify(run, null, 2));
  return run;
}

/** The flag words of run.schema.json. */
export const RUN_FLAGS = new Set(['late', 'noisy', 'crashed', 'retry', 'budget-exhausted', 'capture-mode', 'warmup', 'invalid']);

/** Schema flags only; `invalid` when the run is not valid and no schema flag says why (a step failed, …). */
export function schemaFlags(flags, valid) {
  const out = [...flags].filter((f) => RUN_FLAGS.has(f));
  if (!valid && !out.some((f) => ['late', 'crashed', 'invalid'].includes(f))) out.push('invalid');
  return out;
}

function copyIf(src, dst) {
  if (existsSync(src)) copyFileSync(src, dst);
}
