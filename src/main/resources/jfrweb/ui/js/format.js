// @ts-check
/**
 * Formatting of durations, sizes and times.
 */

/**
 * @param {number} ms
 * @returns {string} e.g. "0.4 ms", "350 ms", "12.3 s"
 */
export function duration(ms) {
  if (ms >= 10_000) return `${(ms / 1000).toFixed(1)} s`;
  if (ms >= 100) return `${Math.round(ms)} ms`;
  return `${ms.toFixed(1)} ms`;
}

/**
 * @param {number} n
 * @returns {string} e.g. "512 B", "1.5 KiB"
 */
export function bytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KiB`;
  return `${(n / 1024 / 1024).toFixed(1)} MiB`;
}

/**
 * @param {string} iso
 * @returns {string} in the local time of the browser
 */
export function time(iso) {
  return new Date(iso).toLocaleString();
}

/**
 * @param {{from?: number, to?: number}} i in seconds
 * @returns {string} e.g. "2–4.2 s", or "" for the whole session
 */
export function interval(i) {
  if (i.from === undefined && i.to === undefined)
    return '';
  return `${i.from ?? 0}–${i.to ?? 'end'}${i.to !== undefined ? ' s' : ''}`;
}

/**
 * What an operation worked on, or its name.
 *
 * @param {{name: string, detail: string | null}} r
 */
export function label(r) {
  return r.detail ?? r.name;
}

/**
 * @param {number} ratio between 0 and 1
 * @returns {string}
 */
export function percent(ratio) {
  return `${Math.round(ratio * 100)}%`;
}
