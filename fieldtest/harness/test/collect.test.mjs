// Hand-made input for the harness: a raw run as the driver writes it, with a synthetic screen piece whose
// recording latency is known. Run: node --test fieldtest/harness/test/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { collectRun, netMetrics } from '../lib/collect.mjs';
import { parseStat, parseGfxinfo, frameMetrics, parseMeminfo, parseSampleBlocks } from '../lib/raw.mjs';
import { makePlan } from '../lib/plan.mjs';
import { ffmpegPath } from '../lib/screen.mjs';

const scratch = process.env.FM_TEST_TMP || join(process.env.HOME, '.cache/sdk-field-test/android/harness-test');
mkdirSync(scratch, { recursive: true });

const PID = 4321;
/** A /proc stat line with utime/stime in ticks (fields 14 and 15). */
const stat = (pid, comm, u, s) => `${pid} (${comm}) S 1 1 0 0 -1 4194624 100 0 0 0 ${u} ${s} 0 0 10 -10 42 0 1234 0 0 0`;
const statm = (rssPages) => `300000 ${rssPages} 20000 5 0 40000 0`;
const procStat = (busy, idle) => `cpu  ${busy} 0 0 ${idle} 0 0 0 0 0 0\ncpu0 1 1 1 1 0 0 0 0 0 0\nintr 1 2 3\nctxt 99`;
function block(epoch, tag, p, tasks, rss, cpu) {
  return `@sample ${epoch} ${PID} ${tag}\n${stat(PID, 'com.tracewayapp', p[0], p[1])}\n${statm(rss)}\n${procStat(cpu[0], cpu[1])}\n` +
    tasks.map(([tid, comm, u, s]) => stat(tid, comm, u, s)).join('\n') + '\n@end\n';
}

const GFX = `Applications Graphics Acceleration Info:
** Graphics info for pid ${PID} [com.tracewayapp.fieldmart] **

Stats since: 123456789ns
Total frames rendered: 1000
Janky frames: 50 (5.00%)
Janky frames (legacy): 80 (8.00%)
50th percentile: 9ms
90th percentile: 16ms
95th percentile: 21ms
99th percentile: 48ms
Number Missed Vsync: 3
Number High input latency: 10
Number Slow UI thread: 20
Number Slow bitmap uploads: 1
Number Slow issue draw commands: 5
Number Frame deadline missed: 40
HISTOGRAM: 5ms=300 6ms=200 10ms=300 16ms=150 24ms=0 26ms=30 32ms=15 50ms=4 700ms=1 750ms=0
50th gpu percentile: 4ms
`;
const MEM = `Applications Memory Usage (in Kilobytes):
** MEMINFO in pid ${PID} [com.tracewayapp.fieldmart] **
 App Summary
                       Pss(KB)                        Rss(KB)
                        ------                         ------
           Java Heap:    12000                          30000
         Native Heap:    25000                          26000
                Code:     8000                          40000
               Stack:      900                            900
            Graphics:     6000                           6000
       Private Other:     3000
              System:     5000
             Unknown:                                    1000

           TOTAL PSS:    60000            TOTAL RSS:   104000       TOTAL SWAP PSS:       10
`;

test('stat, gfxinfo and meminfo parsers', () => {
  const s = parseStat('77 (TracewayIO-1) S 1 1 0 0 -1 4194624 100 0 0 0 250 50 0 0 10 -10 42 0 1234 0 0 0');
  assert.equal(s.comm, 'TracewayIO-1');
  assert.equal(s.utime, 250);
  assert.equal(s.stime, 50);
  // A thread name with a space and a parenthesis.
  assert.equal(parseStat('78 (Binder:4321 (2)) S 1 1 0 0 -1 4194624 100 0 0 0 3 4 0').comm, 'Binder:4321 (2)');
  const g = parseGfxinfo(GFX);
  assert.equal(g.totalFrames, 1000);
  assert.equal(g.p95, 21);
  const f = frameMetrics(g);
  assert.equal(f['M-FR-3'], 1);
  assert.equal(f['M-FR-4'], 700);
  // Slow: buckets over 25 ms: 30 + 15 + 4 + 1 of 1000.
  assert.equal(f['M-FR-2'], 5);
  const m = parseMeminfo(MEM);
  assert.equal(m.totalPssKb, 60000);
  assert.equal(m.javaHeapKb, 12000);
});

test('the plan: blocks of every variant in a seeded order, warm-up first, evidence after', () => {
  const a = makePlan({ scenarios: ['S1'], variants: ['off', 'off2', 'core', 'stream-img'], reps: 2, seed: 7, evidence: [{ scenario: 'S1', variant: 'stream-img' }] });
  const b = makePlan({ scenarios: ['S1'], variants: ['off', 'off2', 'core', 'stream-img'], reps: 2, seed: 7, evidence: [{ scenario: 'S1', variant: 'stream-img' }] });
  assert.deepEqual(a, b);
  assert.equal(a.length, 3 * 4 + 1);
  assert.ok(a.slice(0, 4).every((r) => r.warmup && r.block === 0));
  for (const blk of [0, 1, 2]) assert.deepEqual(a.filter((r) => r.block === blk).map((r) => r.variant).sort(), ['core', 'off', 'off2', 'stream-img']);
  assert.match(a.at(-1).runId, /^androidemu-S1-stream-img-ev-[0-9a-f]{4}$/);
  assert.match(a[5].runId, /^androidemu-S1-[a-z0-9-]+-b01-[0-9a-f]{4}$/);
});

test('collect: metrics, ledger, and the screen video calibrated against the screenshots', () => {
  const root = mkdtempSync(join(scratch, 'run-'));
  const raw = join(root, 'media');
  const shellDir = join(root, 'shell');
  const ledger = join(root, 'ledger');
  const out = join(root, 'out');
  for (const d of [raw, shellDir, join(ledger, 'bodies'), join(raw, 'app')]) mkdirSync(d, { recursive: true });
  const runId = 'androidemu-S6-stream-img-ev-abcd';
  const t0 = 1795000000000;
  const end = t0 + 10000;

  // CPU: the process uses 300 ticks (3 s) between t0 and the end; main 150, RenderThread 50, Traceway threads 60.
  let samples = '';
  samples += block(t0, 't0', [1000, 200], [[PID, 'com.tracewayapp', 500, 100], [PID + 1, 'RenderThread', 100, 10], [PID + 2, 'TracewayIO-1', 20, 0]], 40000, [100000, 300000]);
  samples += block(t0 + 5000, 'tick', [1100, 230], [[PID, 'com.tracewayapp', 560, 110], [PID + 1, 'RenderThread', 120, 15], [PID + 2, 'TracewayIO-1', 40, 0], [PID + 3, 'TracewayImages', 10, 0]], 45000, [100400, 301600]);
  samples += block(end, 'end', [1250, 250], [[PID, 'com.tracewayapp', 620, 130], [PID + 1, 'RenderThread', 140, 20], [PID + 2, 'TracewayIO-1', 60, 0], [PID + 3, 'TracewayImages', 20, 0]], 50000, [100900, 303100]);
  writeFileSync(join(raw, 'samples.raw'), samples);
  writeFileSync(join(raw, 'gfxinfo.txt'), GFX);
  writeFileSync(join(raw, 'meminfo-end.txt'), MEM);
  writeFileSync(join(raw, 'app.log'), `${t0 - 900}.000  1000  1000 I FM      : FM_READY ${t0}\n${t0 + 100}.000 1000 1000 D Traceway: wireframe capture takes 3.20 ms on average (812 views)\n`);
  writeFileSync(join(raw, 'driver.log'), 'x\n');

  // A synthetic screen piece: testsrc2 changes every frame. True latency 420 ms after the exec.
  const exec = t0 - 3000;
  const latency = 420;
  const piece = join(shellDir, 'screen-0.mp4');
  const ff = (a) => {
    const r = spawnSync(ffmpegPath(), ['-v', 'error', '-y', ...a]);
    assert.equal(r.status, 0, String(r.stderr));
  };
  ff(['-f', 'lavfi', '-i', 'testsrc2=size=360x780:rate=30', '-t', '14', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', piece]);
  const cps = [];
  for (const [name, at] of [['cp-a', 2.0], ['cp-b', 5.5], ['cp-c', 9.0]]) {
    ff(['-ss', String(at), '-i', piece, '-frames:v', '1', join(raw, 'app', `${name}.png`)]);
    const shotEpoch = exec + latency + at * 1000;
    cps.push({ name, epochMs: shotEpoch - 20, tMs: shotEpoch - 20 - t0, shot: `app/${name}.png`, shotStartEpochMs: shotEpoch - 30, shotEndEpochMs: shotEpoch + 30, shows: [], hides: [], masked: [] });
  }
  writeFileSync(join(raw, 'checkpoints.json'), JSON.stringify(cps));
  writeFileSync(join(raw, 'screen-pieces.json'), JSON.stringify([{ index: 0, file: 'screen-0.mp4', execEpochMs: exec, endEpochMs: exec + 14800 }]));
  writeFileSync(join(raw, 'boxes.json'), '{}');
  writeFileSync(join(raw, 'run-meta.json'), JSON.stringify({
    runId, scenario: 'S6', variant: 'stream-img', package: 'com.tracewayapp.fieldmart', evidence: true, t0EpochMs: t0, endEpochMs: end,
    flags: [], maxLateMs: 40, launches: [{ kind: 'cold', amStartEpochMs: t0 - 900, totalTimeMs: 820, readyEpochMs: t0, pid: PID }],
    device: { model: 'sdk_gphone64_x86_64', sdk: 34, density: 1.75, widthDp: 411.43, heightDp: 891.43 },
  }));

  // The gateway's capture ledger for the run.
  mkdirSync(join(ledger, 'bodies', runId), { recursive: true });
  writeFileSync(join(ledger, 'bodies', runId, '000001.bin'), 'x'.repeat(2048));
  writeFileSync(join(ledger, `${runId}.jsonl`), [
    { seq: 1, run: runId, recvEpochMs: t0 + 1000, method: 'POST', path: '/api/recordings', status: 200, wireBytes: 2048, decodedBytes: 9000, body: `bodies/${runId}/000001.bin` },
    { seq: 2, run: runId, recvEpochMs: t0 + 2000, method: 'POST', path: '/api/otel/v1/logs', status: 200, wireBytes: 1024, decodedBytes: 4000, body: `bodies/${runId}/000002.bin` },
    { run: runId, recvEpochMs: t0 + 2500, mark: 'checkpoint x' },
  ].map((l) => JSON.stringify(l)).join('\n') + '\n');
  writeFileSync(join(ledger, `${runId}.fx.jsonl`), JSON.stringify({ run: runId, path: '/fx/api/products', route: '/fx/api/products', status: 200 }) + '\n');

  const run = collectRun(raw, shellDir, out, { campaign: 'FT-test', ledgerDir: ledger, captureMode: true });
  const m = run.metrics;
  assert.equal(m['M-CPU-1'], 3);
  assert.equal(m['M-CPU-2'], 30);
  assert.equal(m['M-CPU-3'], 1.5);
  // TracewayIO-1 40 ticks + TracewayImages 20 ticks (new thread, counted from zero).
  assert.equal(m['M-CPU-4'], 0.6);
  assert.equal(m.cpuByGroupS.RenderThread, 0.5);
  assert.equal(m['M-MEM-2'], +((50000 * 4) / 1024).toFixed(1));
  assert.equal(m['M-MEM-3'], +(12000 / 1024).toFixed(1));
  assert.equal(m['M-FR-1'].p95, 21);
  assert.deepEqual(m['M-SDK-4'], { avgMs: 3.2, reports: 1, views: 812 });
  assert.equal(m['M-NET-1'].byRoute['/api/recordings'], 2);
  assert.equal(m['M-NET-2']['/api/otel/v1/logs'], 1);
  assert.equal(run.valid, true);
  assert.deepEqual(run.flags, ['capture-mode']);
  // Host noise: the device was busy 500 ticks per 5 s besides... (busy delta - app delta).
  const s = readFileSync(join(out, 'samples.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l));
  assert.equal(s.length, 3);
  assert.equal(s[1].hostOtherPct, ((400 - 130) * 10) / 5000 * 100);
  // Evidence: the ledger body is kept, the screen is joined and calibrated.
  assert.ok(existsSync(join(out, 'ledger-bodies', '000001.bin')));
  assert.ok(existsSync(join(out, 'fxlog.jsonl')));
  assert.deepEqual(run.device, { width: 411.4, height: 891.4, scale: 1.75 });
  assert.ok(existsSync(join(out, 'screen.mp4')));
  const screen = JSON.parse(readFileSync(join(out, 'screen.json'), 'utf8'));
  const p = screen.pieces[0];
  assert.equal(p.method, 'calibrated (screenshots)');
  assert.ok(Math.abs(p.latencyMs - latency) <= 40, `latency ${p.latencyMs} vs ${latency}`);
  assert.equal(p.matchesUsed, 3);
  assert.ok(Math.abs(screen.startEpochMs - (exec + latency)) <= 40);
  for (const c of screen.checkpoints) assert.equal(c.offsetMs, c.epochMs - screen.startEpochMs);
});

test('net metrics ignore marker lines', () => {
  const n = netMetrics([{ path: '/api/recordings', wireBytes: 1024 }, { marker: 'x' }], 60000);
  assert.equal(n['M-NET-1'].perMinKb, 1);
});

test('sample blocks keep the process line apart from the main thread line', () => {
  const b = parseSampleBlocks(block(1, 't0', [10, 2], [[PID, 'com.tracewayapp', 5, 1]], 100, [1, 2]));
  assert.equal(b[0].proc.utime, 10);
  assert.equal(b[0].tasks[0].utime, 5);
});

test('two screen pieces: screen.mp4 runs on the device clock across the restart of screenrecord', async () => {
  const { buildScreen } = await import('../lib/screen.mjs');
  const root = mkdtempSync(join(scratch, 'pieces-'));
  const shellDir = join(root, 'shell');
  const out = join(root, 'out');
  mkdirSync(shellDir, { recursive: true });
  mkdirSync(join(out, 'app'), { recursive: true });
  const ff = (a) => {
    const r = spawnSync(ffmpegPath(), ['-v', 'error', '-y', ...a]);
    assert.equal(r.status, 0, String(r.stderr));
    return r.stdout;
  };
  const E0 = 1795000000000;
  const pieces = [
    { index: 0, file: 'screen-0.mp4', execEpochMs: E0, latency: 420, seconds: 12, pattern: 'testsrc2=size=360x780:rate=30' },
    { index: 1, file: 'screen-1.mp4', execEpochMs: E0 + 12420 + 650, latency: 380, seconds: 10, pattern: 'testsrc=size=360x780:rate=30' },
  ];
  const cps = [];
  for (const p of pieces) {
    ff(['-f', 'lavfi', '-i', p.pattern, '-t', String(p.seconds), '-c:v', 'libx264', '-pix_fmt', 'yuv420p', join(shellDir, p.file)]);
    p.endEpochMs = p.execEpochMs + p.latency + p.seconds * 1000 + 100;
    for (const at of [1.5, 4.0, 7.5]) {
      const name = `p${p.index}-${at}`;
      ff(['-ss', String(at), '-i', join(shellDir, p.file), '-frames:v', '1', join(out, 'app', `${name}.png`)]);
      const e = p.execEpochMs + p.latency + at * 1000;
      cps.push({ name, epochMs: e, shot: `app/${name}.png`, shotStartEpochMs: e - 25, shotEndEpochMs: e + 25 });
    }
  }
  const meta = buildScreen({ rawDir: shellDir, outDir: out, pieces, checkpoints: cps });
  assert.deepEqual(meta.pieces.map((p) => p.latencyMs), [420, 380]);
  assert.equal(meta.pieces[0].gapAfterMs, pieces[1].execEpochMs + 380 - (E0 + 420 + 12000));
  assert.ok(!existsSync(join(out, '.screen-part-0.mp4')));
  // The frame of screen.mp4 at each checkpoint's offset is the checkpoint's screenshot.
  const gray = (args) => ff([...args, '-vf', 'scale=72:156,format=gray', '-f', 'rawvideo', '-']);
  for (const c of meta.checkpoints) {
    const frame = gray(['-ss', (c.offsetMs / 1000).toFixed(3), '-i', join(out, 'screen.mp4'), '-frames:v', '1']);
    const shot = gray(['-i', join(out, `app/${c.name}.png`)]);
    let d = 0;
    for (let i = 0; i < shot.length; i++) d += Math.abs(frame[i] - shot[i]);
    assert.ok(d / shot.length < 2, `${c.name}: frame at ${c.offsetMs} ms differs by ${(d / shot.length).toFixed(2)}`);
  }
});

test('taps: the recording latency from the moments the picture changes at tap points', async () => {
  const { calibrateFromTaps, grayFrames } = await import('../lib/screen.mjs');
  const root = mkdtempSync(join(scratch, 'taps-'));
  const file = join(root, 'piece.mp4');
  // 412 x 892 logical, 360 x 780 video. A white box (a touch and its ripple) shows at each tap's point, starting
  // 400 ms (the latency) plus one frame after the tap's device time; a grey box elsewhere moves the rest of the time.
  const latency = 400;
  const taps = [[2.0, 100, 200], [4.5, 300, 600], [7.0, 200, 400], [9.5, 60, 800], [11.0, 350, 120]].map(([t, x, y]) => ({ epochMs: 1_000_000 + t * 1000, x, y }));
  const sx = 360 / 412;
  const sy = 780 / 892;
  const boxes = taps.map((t) => {
    const at = (t.epochMs - 1_000_000 - latency + 33) / 1000;
    return `drawbox=x=${Math.round(t.x * sx) - 20}:y=${Math.round(t.y * sy) - 20}:w=40:h=40:color=white:t=fill:enable='between(t,${at.toFixed(3)},${(at + 0.8).toFixed(3)})'`;
  });
  const r = spawnSync(ffmpegPath(), ['-v', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=0x334455:s=360x780:r=30', '-t', '13',
    '-vf', [...boxes, "drawbox=x='mod(t*40,300)':y=700:w=30:h=30:color=gray:t=fill"].join(','), '-c:v', 'libx264', '-pix_fmt', 'yuv420p', file]);
  assert.equal(r.status, 0, String(r.stderr));
  const frames = grayFrames(file);
  const cal = calibrateFromTaps({ frames, count: Math.floor(frames.length / (72 * 156)), execEpochMs: 1_000_000, taps, logical: { width: 412, height: 892 } });
  assert.ok(cal.used >= 4, JSON.stringify(cal));
  assert.ok(Math.abs(cal.latencyMs - latency) <= 40, JSON.stringify(cal));
});
