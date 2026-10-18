// @ts-check
/**
 * What the page remembers in the browser: a tab chosen, a section folded…
 * Browser storage can be unavailable (a private window, site data blocked):
 * it is only a convenience, and the page works without it.
 *
 * Keys are global to the origin: each project starts its own with its name,
 * e.g. "jfrweb.live.window".
 *
 * Part of a small lib kept as identical copies in jfr-web and jfr-soundscape:
 * change both.
 */

/**
 * @param {string} key
 * @returns {string | null}
 */
export function readSetting(key) {
  try {
    return localStorage.getItem(key);
  }
  catch (e) {
    return null;
  }
}

/**
 * @param {string} key
 * @param {string | number | boolean} value stored as a string
 */
export function writeSetting(key, value) {
  try {
    localStorage.setItem(key, String(value));
  }
  catch (e) {
    // only a convenience
  }
}
