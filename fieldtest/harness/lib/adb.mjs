// adb for the harness: one device, chosen by FM_SERIAL (or adb's default), never `adb kill-server`.
import { spawnSync, spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';

export function adbPath() {
  if (process.env.ADB) return process.env.ADB;
  for (const home of [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, join(process.env.HOME || '', 'Android/Sdk')]) {
    if (home && existsSync(join(home, 'platform-tools/adb'))) return join(home, 'platform-tools/adb');
  }
  return 'adb';
}

function base() {
  const serial = process.env.FM_SERIAL;
  return serial ? ['-s', serial] : [];
}

/** Runs adb and returns { code, stdout, stderr }; throws unless allowFail. */
export function adb(args, { allowFail = false, timeoutMs = 120000, input } = {}) {
  const r = spawnSync(adbPath(), [...base(), ...args], { encoding: 'utf8', timeout: timeoutMs, input, maxBuffer: 256 << 20 });
  const out = { code: r.status ?? -1, stdout: r.stdout || '', stderr: (r.stderr || '') + (r.error ? String(r.error) : '') };
  if (out.code !== 0 && !allowFail) throw new Error(`adb ${args.join(' ')} failed (${out.code}): ${out.stderr || out.stdout}`);
  return out;
}

export const shell = (cmd, opts) => adb(['shell', cmd], opts).stdout;

/** `adb shell am instrument -w …`, streamed to [log] as it runs; resolves with the exit code and the output. */
export function instrument(args, { onLine, timeoutMs = 60 * 60 * 1000 } = {}) {
  return new Promise((resolve) => {
    const child = spawn(adbPath(), [...base(), 'shell', 'am', 'instrument', '-w', '-r', ...args], { stdio: ['ignore', 'pipe', 'pipe'] });
    let text = '';
    const timer = setTimeout(() => child.kill('SIGTERM'), timeoutMs);
    const feed = (b) => {
      const s = b.toString();
      text += s;
      if (onLine) for (const l of s.split('\n')) if (l.trim()) onLine(l);
    };
    child.stdout.on('data', feed);
    child.stderr.on('data', feed);
    child.on('close', (code) => {
      clearTimeout(timer);
      const failed = /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=/.test(text) || !/OK \(\d+ test/.test(text);
      resolve({ code, text, ok: code === 0 && !failed });
    });
  });
}

export function connectIfNetwork() {
  const s = process.env.FM_SERIAL;
  if (s && /:\d+$/.test(s)) spawnSync(adbPath(), ['connect', s], { encoding: 'utf8', timeout: 20000 });
}
