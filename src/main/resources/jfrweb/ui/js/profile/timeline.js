// @ts-check
/**
 * The timeline of a session from its profile, as the server computes it
 * (jfrweb.analysis.Timeline): the operations in flight per bucket, by name,
 * and the samples of Java code, over the whole recording or a part of it in
 * finer buckets. Zooming in makes no request, in a static export too.
 *
 * No DOM: tested with Node.
 */

/** @typedef {import('./profile.js').Profile} Profile */

/** Operation names shown on their own, the others are added up. */
const MAX_SERIES = 8;

/** At most this many buckets. */
const MAX_BUCKETS = 300;

/** The bucket durations to choose from, in ms. */
const STEPS = [10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000,
               60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000];

/**
 * @param {Profile} p
 * @param {import('../api.js').Interval} range in seconds since the start of the recording, all
 *        of it when empty
 * @returns {import('../api.js').TimelineData}
 */
export function timeline(p, range) {
  // in ns since the start of the recording, as the server reads seconds
  const from = range.from !== undefined && range.from > 0 ? Math.trunc(range.from * 1e9) : 0;
  const to = range.to !== undefined && Math.trunc(range.to * 1e9) < p.end ? Math.trunc(range.to * 1e9) : p.end;
  const span = to < from ? 0 : to - from;
  const bucket = bucketFor(span);
  const buckets = Math.max(1, Math.ceil(span / bucket));
  const o = p.operations;

  // the operations with the most time in the whole recording get a series
  // of their own: each keeps its color when zooming
  /** @type {Map<number, number>} total ns by name */
  const totals = new Map();
  for (let i = 0; i < o.count; i++)
    totals.set(o.name[i], (totals.get(o.name[i]) ?? 0) + o.duration[i]);
  const top = [...totals].sort((a, b) => b[1] - a[1]).slice(0, MAX_SERIES).map(([name]) => name);
  /** @type {Map<number, Float64Array>} */
  const series = new Map(top.map(name => [name, new Float64Array(buckets)]));
  const other = totals.size > MAX_SERIES ? new Float64Array(buckets) : null;

  for (let i = 0; i < o.count; i++) {
    const values = series.get(o.name[i]) ?? other;
    if (!values)
      continue;
    const begin = Math.max(o.start[i] - from, 0);
    const finish = Math.min(o.start[i] + o.duration[i] - from, buckets * bucket);
    for (let b = Math.floor(begin / bucket); b * bucket < finish && b < buckets; b++) {
      const overlap = Math.min(finish, (b + 1) * bucket) - Math.max(begin, b * bucket);
      // an operation in flight for the whole bucket counts 1
      values[b] += overlap / bucket;
    }
  }

  const javaSamples = new Array(buckets).fill(0);
  for (let i = 0; i < p.time.length; i++)
    if (p.kind[i] === 0) {
      // sample times are whole ms: one within a ms of a bucket's edge may fall in the next
      const b = Math.floor((p.time[i] * 1e6 - from) / bucket);
      if (b >= 0 && b < buckets)
        javaSamples[b]++;
    }

  return {
    start: new Date(Date.parse(p.start) + from / 1e6).toISOString(),
    bucket: bucket / 1e6,
    buckets,
    series: [...series].map(([name, values]) => ({name: p.strings[name], values: rounded(values)})),
    other: other && rounded(other),
    javaSamples,
  };
}

/**
 * @param {number} span in ns
 * @returns {number} the shortest round bucket, in ns, that keeps at most MAX_BUCKETS
 */
function bucketFor(span) {
  for (const ms of STEPS)
    if (span <= ms * 1e6 * MAX_BUCKETS)
      return ms * 1e6;
  return STEPS[STEPS.length - 1] * 1e6;
}

/**
 * @param {Float64Array} values
 * @returns {number[]} to the thousandth, as the server sends them
 */
function rounded(values) {
  return Array.from(values, v => Math.round(v * 1000) / 1000);
}
