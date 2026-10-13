// @ts-check
/**
 * Preferences kept per browser: a tab chosen, a section folded. Browser
 * storage can be unavailable (private mode, blocked site data): they are only
 * a convenience, and the page works without them.
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
 * @param {string} value
 */
export function writeSetting(key, value) {
  try {
    localStorage.setItem(key, value);
  }
  catch (e) {
    // only a convenience
  }
}
