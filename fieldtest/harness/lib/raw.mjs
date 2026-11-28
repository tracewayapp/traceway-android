// Parsers for what the driver writes on the device: the sampler's raw /proc text, `dumpsys gfxinfo`, `dumpsys
// meminfo`, the SDK's debug lines in logcat. Pure functions, tested on hand-made input (harness/test/).

const CLK_TCK = 100; // Android's USER_HZ
const PAGE_KB = 4; // 4 KiB pages on the x86_64 emulator and phones

/** One `/proc/<pid>[/task/<tid>]/stat` line: pid, comm, state, utime and stime in clock ticks. */
export function parseStat(line) {
  const open = line.indexOf('(');
  const close = line.lastIndexOf(')');
  if (open < 0 || close < open) return null;
  const pid = Number(line.slice(0, open).trim());
  const rest = line.slice(close + 2).split(' ');
  // rest[0] is field 3 (state); utime is field 14, stime field 15, num_threads 20, starttime 22.
  return {
    pid,
    comm: line.slice(open + 1, close),
    state: rest[0],
    utime: Number(rest[11]),
    stime: Number(rest[12]),
    threads: Number(rest[17]),
    starttime: Number(rest[19]),
  };
}

/** `/proc/stat`'s first line: total and idle jiffies of the device. */
function parseProcStat(line) {
  const f = line.trim().split(/\s+/).slice(1).map(Number);
  const idle = (f[3] || 0) + (f[4] || 0);
  const total = f.reduce((a, b) => a + (Number.isFinite(b) ? b : 0), 0);
  return { total, idle };
}

/** The sampler's blocks: `@sample <epochMs> <pid> <tag>`, the cat output, `@end`. */
export function parseSampleBlocks(text) {
  const blocks = [];
  const re = /@sample (\d+) (\d+) (\S+)\n([\s\S]*?)\n@end/g;
  let m;
  while ((m = re.exec(text))) {
    const epochMs = Number(m[1]);
    const pid = Number(m[2]);
    const lines = m[4].split('\n').filter((l) => l.length);
    const b = { epochMs, pid, tag: m[3], proc: null, statm: null, cpu: null, tasks: [] };
    for (const l of lines) {
      if (/^cpu /.test(l)) b.cpu = parseProcStat(l);
      else if (/^cpu\d|^(intr|ctxt|btime|processes|procs_|softirq)/.test(l)) continue;
      else if (/^\d+ \(/.test(l)) {
        const s = parseStat(l);
        if (!s) continue;
        if (!b.proc) b.proc = s;
        else b.tasks.push(s);
      } else if (/^\d+( \d+){5,}$/.test(l.trim()) && !b.statm) {
        const f = l.trim().split(/\s+/).map(Number);
        b.statm = { sizeKb: f[0] * PAGE_KB, rssKb: f[1] * PAGE_KB, sharedKb: f[2] * PAGE_KB };
      }
    }
    blocks.push(b);
  }
  return blocks;
}

/** A thread's group for M-CPU-3/-4: the main thread, Android's RenderThread, the SDK's Traceway* threads, the rest. */
export function threadGroup(task, pid) {
  if (task.pid === pid) return 'main';
  if (task.comm === 'RenderThread') return 'RenderThread';
  if (task.comm.startsWith('Traceway')) return 'Traceway';
  return 'other';
}

/**
 * samples.jsonl lines (05-RESULTS-FORMAT.md §2) and the CPU metrics between [fromMs] and [toMs] (epoch), from the
 * parsed blocks of one process. Thread CPU is cumulative per thread id; a thread seen first after [fromMs] started
 * from zero. Returns { samples, cpu } with cpu in ms.
 */
export function cpuFromBlocks(blocks, { t0, fromMs = t0, toMs }) {
  const samples = [];
  const firstTask = new Map();
  const lastTask = new Map();
  let prev = null;
  let start = null;
  let end = null;
  for (const b of blocks) {
    if (!b.proc) continue;
    const pid = b.proc.pid;
    const threads = {};
    for (const t of b.tasks) {
      const ms = ((t.utime + t.stime) * 1000) / CLK_TCK;
      const key = `${t.pid}`;
      if (b.epochMs >= fromMs && !firstTask.has(key)) firstTask.set(key, { ms: start ? 0 : ms, comm: t.comm, group: threadGroup(t, pid) });
      if (b.epochMs <= toMs + 1500) lastTask.set(key, { ms, comm: t.comm, group: threadGroup(t, pid) });
      threads[t.comm] = (threads[t.comm] || 0) + ms;
    }
    const s = {
      t: b.epochMs - t0,
      cpuUserMs: (b.proc.utime * 1000) / CLK_TCK,
      cpuSysMs: (b.proc.stime * 1000) / CLK_TCK,
      threads,
    };
    if (b.statm) s.rssKb = b.statm.rssKb;
    if (prev && b.cpu && prev.cpu) {
      const dTotal = b.cpu.total - prev.cpu.total;
      const dBusy = dTotal - (b.cpu.idle - prev.cpu.idle);
      const dApp = b.proc.utime + b.proc.stime - (prev.proc.utime + prev.proc.stime);
      const elapsed = b.epochMs - prev.epochMs;
      // Other processes' CPU on the device (all cores), in % of one core.
      if (elapsed > 0 && dTotal > 0) s.hostOtherPct = Math.max(0, (((dBusy - dApp) * 1000) / CLK_TCK / elapsed) * 100);
    }
    samples.push(s);
    if (b.epochMs >= fromMs && !start) start = b;
    if (b.epochMs <= toMs + 1500) end = b;
    prev = b;
  }
  const cpu = {};
  if (start && end) {
    cpu.totalMs = ((end.proc.utime + end.proc.stime - start.proc.utime - start.proc.stime) * 1000) / CLK_TCK;
    cpu.userMs = ((end.proc.utime - start.proc.utime) * 1000) / CLK_TCK;
    cpu.sysMs = ((end.proc.stime - start.proc.stime) * 1000) / CLK_TCK;
    cpu.windowMs = end.epochMs - start.epochMs;
    const byGroup = { main: 0, RenderThread: 0, Traceway: 0, other: 0 };
    const byThread = {};
    for (const [key, last] of lastTask) {
      const first = firstTask.get(key);
      const d = last.ms - (first ? first.ms : 0);
      byGroup[last.group] += d;
      byThread[last.comm] = (byThread[last.comm] || 0) + d;
    }
    cpu.byGroup = byGroup;
    cpu.byThread = byThread;
  }
  return { samples, cpu };
}

export function median(xs) {
  const a = xs.filter(Number.isFinite).sort((x, y) => x - y);
  if (!a.length) return null;
  const m = a.length >> 1;
  return a.length % 2 ? a[m] : (a[m - 1] + a[m]) / 2;
}

/** `dumpsys gfxinfo <pkg>`: the process's frame statistics (the first block, which covers every window). */
export function parseGfxinfo(text) {
  const num = (re) => {
    const m = text.match(re);
    return m ? Number(m[1]) : null;
  };
  const out = {
    totalFrames: num(/Total frames rendered:\s*(\d+)/),
    jankyFrames: num(/Janky frames:\s*(\d+)/),
    jankyLegacy: num(/Janky frames \(legacy\):\s*(\d+)/),
    p50: num(/50th percentile:\s*([\d.]+)ms/),
    p90: num(/90th percentile:\s*([\d.]+)ms/),
    p95: num(/95th percentile:\s*([\d.]+)ms/),
    p99: num(/99th percentile:\s*([\d.]+)ms/),
    gpuP50: num(/50th gpu percentile:\s*([\d.]+)ms/),
    missedVsync: num(/Number Missed Vsync:\s*(\d+)/),
    slowUiThread: num(/Number Slow UI thread:\s*(\d+)/),
    slowBitmapUploads: num(/Number Slow bitmap uploads:\s*(\d+)/),
    slowDraw: num(/Number Slow issue draw commands:\s*(\d+)/),
    deadlineMissed: num(/Number Frame deadline missed:\s*(\d+)/),
    histogram: [],
  };
  const h = text.match(/HISTOGRAM:\s*([^\n]+)/);
  if (h) {
    for (const part of h[1].trim().split(/\s+/)) {
      const m = part.match(/^(\d+)ms=(\d+)$/);
      if (m) out.histogram.push([Number(m[1]), Number(m[2])]);
    }
  }
  return out;
}

/** M-FR-* from gfxinfo: percentiles, slow frames over 1.5 × the frame interval, frozen frames of 700 ms or more. */
export function frameMetrics(g, refreshHz = 60) {
  if (!g || !g.totalFrames) return null;
  const limit = 1.5 * (1000 / refreshHz);
  let slow = 0;
  let frozen = 0;
  let worst = 0;
  let counted = 0;
  for (const [ms, n] of g.histogram) {
    counted += n;
    if (ms > limit) slow += n;
    if (ms >= 700) frozen += n;
    if (n > 0) worst = Math.max(worst, ms);
  }
  return {
    'M-FR-1': { p50: g.p50, p90: g.p90, p95: g.p95, p99: g.p99 },
    'M-FR-2': counted ? (100 * slow) / counted : null,
    'M-FR-3': frozen,
    'M-FR-4': worst,
    frames: g.totalFrames,
    jankyPct: g.totalFrames ? (100 * g.jankyFrames) / g.totalFrames : null,
  };
}

/** `dumpsys meminfo <pkg>`: the App Summary and the total, in KB. */
export function parseMeminfo(text) {
  const kb = (re) => {
    const m = text.match(re);
    return m ? Number(m[1]) : null;
  };
  return {
    totalPssKb: kb(/TOTAL PSS:\s*(\d+)/) ?? kb(/^\s*TOTAL\s+(\d+)/m),
    totalRssKb: kb(/TOTAL RSS:\s*(\d+)/),
    javaHeapKb: kb(/Java Heap:\s*(\d+)/),
    nativeHeapKb: kb(/Native Heap:\s*(\d+)/),
    graphicsKb: kb(/Graphics:\s*(\d+)/),
    codeKb: kb(/Code:\s*(\d+)/),
    stackKb: kb(/Stack:\s*(\d+)/),
  };
}

/** M-SDK-4: the SDK's own capture time from its debug log ("wireframe capture takes X ms on average (N views)"). */
export function captureTimes(appLog) {
  const out = [];
  const re = /wireframe capture takes ([\d.]+) ms on average(?: \((\d+) views\))?/g;
  let m;
  while ((m = re.exec(appLog))) out.push({ ms: Number(m[1]), views: m[2] ? Number(m[2]) : null });
  return out;
}

/** `FM_*` marker lines from logcat (`-v epoch`): [{ name, epochMs, rest }]. */
export function markers(appLog) {
  const out = [];
  for (const l of appLog.split('\n')) {
    const m = l.match(/\bFM_(READY|FULLY|SCREEN|SESSION|CONFIG|INFO|CP|DONE)\b\s*(.*)$/);
    if (!m) continue;
    const rest = m[2].trim();
    const epoch = rest.match(/(\d{12,})\s*$/);
    out.push({ name: `FM_${m[1]}`, epochMs: epoch ? Number(epoch[1]) : null, rest });
  }
  return out;
}
