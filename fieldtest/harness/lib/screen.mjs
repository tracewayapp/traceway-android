// The real-screen video of an evidence run: screenrecord's pieces (170 s each) joined into one screen.mp4 whose
// time 0 is a known device-clock instant, so every checkpoint (device clock, the same clock as the SDK's replay
// events) has an exact offset in it.
//
// screenrecord starts recording some time after it is executed. That latency is measured, per piece, by finding in
// the video the frame that best matches each checkpoint screenshot (taken at a known device time): the frame's time
// in the piece and the screenshot's epoch give the piece's start. Checkpoints on a still screen match many frames
// equally and are not used; the median of the distinct matches is taken.
import { spawnSync } from 'node:child_process';
import { existsSync, writeFileSync, mkdirSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';

const FPS = 30;
const W = 72;
const H = 156;

export function ffmpegPath() {
  return process.env.FFMPEG || (existsSync('/usr/bin/ffmpeg') ? '/usr/bin/ffmpeg' : 'ffmpeg');
}

function ffprobePath() {
  return process.env.FFPROBE || (existsSync('/usr/bin/ffprobe') ? '/usr/bin/ffprobe' : 'ffprobe');
}

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { maxBuffer: 1 << 30, ...opts });
  if (r.status !== 0) throw new Error(`${cmd} ${args.join(' ')}: ${(r.stderr || '').toString().slice(-800)}`);
  return r.stdout;
}

export function probe(file) {
  const out = run(ffprobePath(), ['-v', 'error', '-select_streams', 'v:0', '-count_frames', '-show_entries',
    'stream=width,height,nb_read_frames,start_time,duration:format=duration', '-of', 'json', file]).toString();
  const j = JSON.parse(out);
  const s = j.streams?.[0] || {};
  return {
    width: s.width,
    height: s.height,
    frames: Number(s.nb_read_frames || 0),
    startS: Number(s.start_time || 0),
    durationMs: Math.round(1000 * Number(s.duration || j.format?.duration || 0)),
  };
}

/** Gray thumbnails of every frame at 30 fps from the first frame on: a Buffer of n × W × H bytes. */
function grayFrames(file) {
  return run(ffmpegPath(), ['-v', 'error', '-i', file, '-vf', `fps=${FPS}:start_time=0,scale=${W}:${H},format=gray`,
    '-f', 'rawvideo', '-'], { stdio: ['ignore', 'pipe', 'pipe'] });
}

function grayImage(file) {
  return run(ffmpegPath(), ['-v', 'error', '-i', file, '-vf', `scale=${W}:${H},format=gray`, '-f', 'rawvideo', '-'],
    { stdio: ['ignore', 'pipe', 'pipe'] });
}

function mae(frames, index, img) {
  const n = W * H;
  const off = index * n;
  let s = 0;
  for (let i = 0; i < n; i++) s += Math.abs(frames[off + i] - img[i]);
  return s / n;
}

/**
 * The latency of one piece: for each screenshot taken while it recorded, the best-matching frame within
 * [searchBeforeMs] before and [searchAfterMs] after the frame its exec time predicts.
 */
export function calibratePiece({ file, execEpochMs, shots, searchBeforeMs = 2500, searchAfterMs = 400, frames = grayFrames(file) }) {
  const count = Math.floor(frames.length / (W * H));
  const matches = [];
  for (const shot of shots) {
    const img = grayImage(shot.png);
    if (img.length !== W * H) continue;
    const predicted = shot.epochMs - execEpochMs;
    const lo = Math.max(0, Math.floor(((predicted - searchBeforeMs) * FPS) / 1000));
    const hi = Math.min(count - 1, Math.ceil(((predicted + searchAfterMs) * FPS) / 1000));
    if (hi - lo < 4) continue;
    let best = Infinity;
    let at = -1;
    const all = [];
    for (let i = lo; i <= hi; i++) {
      const e = mae(frames, i, img);
      all.push(e);
      if (e < best) {
        best = e;
        at = i;
      }
    }
    const sorted = [...all].sort((a, b) => a - b);
    const typical = sorted[sorted.length >> 1];
    // A match counts only if it stands out (a still screen matches everything) and is not at the window's edge.
    const distinct = typical > 0 && best < 0.6 * typical && at > lo && at < hi;
    const frameMs = (at * 1000) / FPS;
    matches.push({ name: shot.name, predictedMs: predicted, frameMs, latencyMs: Math.round(predicted - frameMs), mae: +best.toFixed(2), typical: +typical.toFixed(2), distinct });
  }
  const good = matches.filter((m) => m.distinct).map((m) => m.latencyMs).sort((a, b) => a - b);
  const latency = good.length ? good[good.length >> 1] : null;
  const spread = good.length >= 2 ? good[Math.floor(good.length * 0.75)] - good[Math.floor(good.length * 0.25)] : null;
  return { latencyMs: latency, spreadMs: spread, used: good.length, matches, frames: count };
}

export { grayFrames };

/**
 * The latency of one piece from the taps (evidence runs show touches): every tap changes the picture right around
 * its point at its device time (the touch spot, the ripple). Each change that starts in a small area around a tap's
 * point, after a still moment, is a candidate latency; the latency most taps agree on (within 50 ms) is the piece's.
 * [taps]: { epochMs, x, y } with x and y in logical px; [logical]: { width, height } of the screen.
 */
export function calibrateFromTaps({ frames, count, execEpochMs, taps, logical, maxLatencyMs = 3000 }) {
  if (!frames || !taps.length || !logical?.width) return { latencyMs: null, used: 0, taps: taps.length };
  const n = W * H;
  const r = 3;
  const cands = [];
  for (const tap of taps) {
    const cx = Math.round((tap.x / logical.width) * W);
    const cy = Math.round((tap.y / logical.height) * H);
    const predicted = tap.epochMs - execEpochMs;
    const lo = Math.max(1, Math.floor(((predicted - maxLatencyMs - 300) * FPS) / 1000));
    const hi = Math.min(count - 1, Math.ceil(((predicted + 300) * FPS) / 1000));
    if (hi - lo < 6) continue;
    const d = [];
    for (let i = lo; i <= hi; i++) {
      let sum = 0;
      let px = 0;
      for (let y = Math.max(0, cy - r); y <= Math.min(H - 1, cy + r); y++) {
        for (let x = Math.max(0, cx - r); x <= Math.min(W - 1, cx + r); x++) {
          const k = y * W + x;
          sum += Math.abs(frames[i * n + k] - frames[(i - 1) * n + k]);
          px++;
        }
      }
      d.push(px ? sum / px : 0);
    }
    const sorted = [...d].sort((a, b) => a - b);
    const quiet = sorted[sorted.length >> 1];
    const threshold = Math.max(6, 4 * quiet);
    const mine = [];
    for (let j = 2; j < d.length; j++) {
      if (d[j] >= threshold && d[j - 1] < threshold && d[j - 2] < threshold) {
        const frameMs = ((lo + j) * 1000) / FPS;
        const lat = predicted - frameMs;
        if (lat >= -100 && lat <= maxLatencyMs) mine.push(Math.round(lat));
      }
    }
    if (mine.length) cands.push(mine);
  }
  if (!cands.length) return { latencyMs: null, used: 0, taps: taps.length };
  let best = null;
  let bestScore = 0;
  for (let L = -100; L <= maxLatencyMs; L += 10) {
    let score = 0;
    for (const c of cands) if (c.some((x) => Math.abs(x - L) <= 50)) score++;
    // On a tie the earlier onset wins (a larger latency): a press starts with the touch, its end comes later.
    if (score >= bestScore && score > 0) {
      bestScore = score;
      best = L;
    }
  }
  const near = cands.map((c) => c.filter((x) => Math.abs(x - best) <= 50)).filter((c) => c.length).map((c) => c.reduce((a, b) => (Math.abs(a - best) < Math.abs(b - best) ? a : b)));
  near.sort((a, b) => a - b);
  const latency = near[near.length >> 1];
  const spread = near.length >= 2 ? near[Math.floor(near.length * 0.75)] - near[Math.floor(near.length * 0.25)] : null;
  return { latencyMs: latency, used: near.length, taps: taps.length, withCandidates: cands.length, spreadMs: spread };
}

/**
 * Builds `<outDir>/screen.mp4` and `screen.json` from the pieces (`screen-pieces.json` from the driver) in [rawDir].
 * [checkpoints] are the run's checkpoints with `shot` and `shotStartEpochMs`/`shotEndEpochMs`.
 */
export function buildScreen({ rawDir, outDir, pieces, checkpoints, taps = [], logical = null, bitrate = '2M', defaultLatencyMs = 0 }) {
  mkdirSync(outDir, { recursive: true });
  const parts = [];
  for (const p of pieces) {
    const file = join(rawDir, p.file);
    if (!existsSync(file)) continue;
    const info = probe(file);
    if (!info.frames) continue;
    const shots = checkpoints
      .filter((c) => c.shot && c.shotStartEpochMs >= p.execEpochMs && c.shotEndEpochMs <= p.endEpochMs)
      .map((c) => ({ name: c.name, png: join(outDir, c.shot), epochMs: (c.shotStartEpochMs + c.shotEndEpochMs) / 2 }))
      .filter((s) => existsSync(s.png));
    const frames = grayFrames(file);
    const count = Math.floor(frames.length / (W * H));
    const shot = shots.length ? calibratePiece({ file, execEpochMs: p.execEpochMs, shots, frames }) : { latencyMs: null, used: 0, matches: [] };
    const inPiece = taps.filter((t) => t.epochMs >= p.execEpochMs + 300 && t.epochMs <= p.endEpochMs - 300);
    const tap = calibrateFromTaps({ frames, count, execEpochMs: p.execEpochMs, taps: inPiece, logical });
    // Taps first (a change at a known instant); screenshots of still screens are the fallback.
    const useTaps = tap.latencyMs != null && tap.used >= 3 && tap.used >= 0.4 * tap.withCandidates;
    const cal = useTaps
      ? { latencyMs: tap.latencyMs, spreadMs: tap.spreadMs, used: tap.used, source: 'taps', matches: shot.matches, taps: tap, shots: { latencyMs: shot.latencyMs, used: shot.used, spreadMs: shot.spreadMs } }
      : { ...shot, source: shot.latencyMs != null ? 'screenshots' : null, taps: tap };
    parts.push({ ...p, file, info, cal });
  }
  if (!parts.length) return null;
  const known = parts.map((p) => p.cal.latencyMs).filter((x) => x != null).sort((a, b) => a - b);
  const fallback = known.length ? known[known.length >> 1] : defaultLatencyMs;
  for (const p of parts) {
    p.latencyMs = p.cal.latencyMs ?? fallback;
    p.method = p.cal.latencyMs != null ? `calibrated (${p.cal.source})` : known.length ? 'other-pieces' : 'assumed';
    p.startEpochMs = p.execEpochMs + p.latencyMs;
  }
  const start = parts[0].startEpochMs;
  // Each piece is converted to 30 fps (screenrecord writes only changed frames) and held on its last frame until the
  // next piece starts, so screen.mp4's clock runs with the device's.
  const listFile = join(outDir, '.screen-concat.txt');
  const lines = [];
  parts.forEach((p, i) => {
    const next = parts[i + 1];
    const gapMs = next ? Math.max(0, next.startEpochMs - (p.startEpochMs + p.info.durationMs)) : 0;
    p.gapAfterMs = next ? next.startEpochMs - (p.startEpochMs + p.info.durationMs) : 0;
    const seg = join(outDir, `.screen-part-${i}.mp4`);
    run(ffmpegPath(), ['-v', 'error', '-y', '-i', p.file, '-vf',
      `fps=${FPS}:start_time=0${gapMs > 0 ? `,tpad=stop_mode=clone:stop_duration=${(gapMs / 1000).toFixed(3)}` : ''}`,
      // The campaign's encoding (tools/README.md): at most about 2 Mbit/s, far less for a still screen.
      '-an', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '23', '-maxrate', bitrate, '-bufsize', '4M', '-pix_fmt', 'yuv420p', seg]);
    lines.push(`file '${seg.replace(/'/g, "'\\''")}'`);
  });
  writeFileSync(listFile, lines.join('\n') + '\n');
  const out = join(outDir, 'screen.mp4');
  run(ffmpegPath(), ['-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', listFile, '-c', 'copy', '-movflags', '+faststart', out]);
  for (let i = 0; i < parts.length; i++) unlinkSync(join(outDir, `.screen-part-${i}.mp4`));
  unlinkSync(listFile);
  const meta = {
    file: 'screen.mp4',
    startEpochMs: Math.round(start),
    source: 'screenrecord (Android), joined and calibrated against the checkpoint screenshots',
    width: parts[0].info.width,
    height: parts[0].info.height,
    fps: FPS,
    note: 'time 0 of screen.mp4 is startEpochMs on the device clock (the clock of the SDK events and the checkpoints)',
    pieces: parts.map((p) => ({
      file: p.file.split('/').pop(),
      execEpochMs: p.execEpochMs,
      endEpochMs: p.endEpochMs,
      latencyMs: p.latencyMs,
      method: p.method,
      spreadMs: p.cal.spreadMs ?? null,
      matchesUsed: p.cal.used,
      taps: p.cal.taps ? { used: p.cal.taps.used, of: p.cal.taps.taps, latencyMs: p.cal.taps.latencyMs, spreadMs: p.cal.taps.spreadMs } : null,
      screenshots: p.cal.shots || null,
      startEpochMs: Math.round(p.startEpochMs),
      durationMs: p.info.durationMs,
      gapAfterMs: Math.round(p.gapAfterMs),
      size: `${p.info.width}x${p.info.height}`,
      matches: p.cal.matches,
    })),
    checkpoints: checkpoints.map((c) => ({ name: c.name, epochMs: c.epochMs, offsetMs: c.epochMs - Math.round(start) })),
  };
  writeFileSync(join(outDir, 'screen.json'), JSON.stringify(meta, null, 2));
  return meta;
}
