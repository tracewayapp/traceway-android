// The block scheduler of 03-MEASUREMENT.md §5.2: a block is one repetition of one scenario in every variant, in an
// order drawn from a seed; one warm-up block per scenario first (thrown away).

/** The seeded generator of 02-APP-SPEC.md §3.4, here only for shuffling. */
export function prng(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function shuffle(list, next) {
  const a = [...list];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(next() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

export const PLATFORM_TOKEN = 'androidemu';

/** `androidemu-S6-stream-img-b03-9f2c` (letters, digits and dashes; 05-RESULTS-FORMAT.md §1). */
export function runId({ scenario, variant, block, evidence }, next) {
  const hex = Math.floor(next() * 0x10000).toString(16).padStart(4, '0');
  const b = evidence ? 'ev' : `b${String(block).padStart(2, '0')}`;
  return `${PLATFORM_TOKEN}-${scenario}-${variant}-${b}-${hex}`.replace(/[^A-Za-z0-9-]/g, '-');
}

/**
 * [scenarios] × [variants] × [reps] in blocks; [evidence]: list of { scenario, variant } evidence runs, run after the
 * measured blocks of their scenario. Returns the runs in execution order.
 */
export function makePlan({ scenarios, variants, reps, seed, warmup = 1, evidence = [], extraVariants = {} }) {
  const next = prng(seed);
  const runs = [];
  for (const scenario of scenarios) {
    const vs = [...variants, ...Object.entries(extraVariants).filter(([, ss]) => ss.includes(scenario)).map(([v]) => v)];
    for (let b = warmup ? 0 : 1; b <= reps; b++) {
      const order = shuffle(vs, next);
      order.forEach((variant, i) => {
        runs.push({ scenario, variant, block: b, orderInBlock: i + 1, warmup: b === 0, evidence: false, runId: runId({ scenario, variant, block: b }, next) });
      });
    }
    for (const e of evidence.filter((e) => e.scenario === scenario)) {
      runs.push({ scenario, variant: e.variant, block: null, orderInBlock: null, warmup: false, evidence: true, runId: runId({ scenario, variant: e.variant, evidence: true }, next) });
    }
  }
  return runs;
}
