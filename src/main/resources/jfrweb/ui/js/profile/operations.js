// @ts-check
/**
 * The operations of a profile, summed as the server sums them
 * (/api/sessions/:id/operations and /instances): the table of the operations
 * by name and the slowest ones, over the whole session or the operations in
 * flight during an interval, with no request. The same rows as the server's,
 * to the hundredth of a millisecond.
 *
 * No DOM: tested with Node.
 */

/** @typedef {import('./profile.js').Profile} Profile */
/** @typedef {import('../api.js').Interval} Interval */

/**
 * The operations by name, sorted by total time, the biggest first.
 *
 * @param {Profile} p
 * @param {Interval} interval keeps those in flight during it, each with its whole duration
 * @returns {import('../api.js').OperationRow[]}
 */
export function operationRows(p, interval) {
  const o = p.operations;
  const keep = during(p, interval);
  /** @type {Map<number, number[]>} the operations of each name, the names in the order met */
  const byName = new Map();
  for (let i = 0; i < o.count; i++)
    if (keep(i)) {
      let list = byName.get(o.name[i]);
      if (!list)
        byName.set(o.name[i], list = []);
      list.push(i);
    }

  const stats = [...byName].map(([name, list]) => {
    const durations = list.map(i => o.duration[i]).sort((a, b) => a - b);
    let total = 0, errors = 0, bytesIn = 0, bytesOut = 0;
    for (const i of list) {
      total += o.duration[i];
      errors += o.failed[i];
      bytesIn += o.bytesIn[i];
      bytesOut += o.bytesOut[i];
    }
    return {name: p.strings[name], total, durations, errors, bytesIn, bytesOut};
  });
  // stable, as the server's: equal totals keep the order met
  stats.sort((a, b) => b.total - a.total);
  return stats.map(s => ({
    name: s.name,
    count: s.durations.length,
    total: ms(s.total),
    p50: ms(percentile(s.durations, 50)),
    p99: ms(percentile(s.durations, 99)),
    max: ms(s.durations[s.durations.length - 1]),
    errors: s.errors,
    bytesIn: s.bytesIn,
    bytesOut: s.bytesOut,
  }));
}

/**
 * The slowest operations, the slowest first.
 *
 * @param {Profile} p
 * @param {string | undefined} name keeps the operations of a name, all if undefined or empty
 * @param {Interval} interval keeps those in flight during it
 * @param {number} limit
 * @returns {import('../api.js').InstanceRow[]}
 */
export function instanceRows(p, name, interval, limit) {
  const o = p.operations;
  const keep = during(p, interval);
  const indexes = [];
  for (let i = 0; i < o.count; i++)
    if ((!name || p.strings[o.name[i]] === name) && keep(i))
      indexes.push(i);
  // stable, as the server's: equal durations keep the order of the recording
  indexes.sort((i, j) => o.duration[j] - o.duration[i]);
  /** @param {number} s */
  const string = s => s < 0 ? null : p.strings[s];
  return indexes.slice(0, limit).map(i => ({
    id: i,
    name: p.strings[o.name[i]],
    detail: string(o.detail[i]),
    status: o.status[i],
    failed: o.failed[i] === 1,
    error: string(o.error[i]),
    async: o.async[i] === 1,
    start: Math.round(o.start[i] / 1e6) / 1000,
    duration: ms(o.duration[i]),
    thread: p.strings[o.thread[i]],
    bytesIn: o.bytesIn[i],
    bytesOut: o.bytesOut[i],
  }));
}

/**
 * @param {Profile} p
 * @param {Interval} interval in seconds since the start of the recording
 * @returns {(i: number) => boolean} whether an operation was in flight during it
 */
function during(p, interval) {
  const o = p.operations;
  // in whole ns, as the server reads seconds
  const from = interval.from === undefined ? -Infinity : Math.trunc(interval.from * 1e9);
  const to = interval.to === undefined ? Infinity : Math.trunc(interval.to * 1e9);
  return i => o.start[i] < to && o.start[i] + o.duration[i] > from;
}

/**
 * Nearest-rank percentile of sorted values.
 *
 * @param {number[]} sorted
 * @param {number} percent
 */
function percentile(sorted, percent) {
  const rank = Math.ceil(percent / 100 * sorted.length);
  return sorted[Math.max(0, rank - 1)];
}

/**
 * @param {number} ns
 * @returns {number} in ms, to the hundredth, as the server rounds them
 */
function ms(ns) {
  return Math.round(ns / 1e4) / 100;
}
