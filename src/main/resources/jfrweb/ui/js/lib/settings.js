// From web-lib (https://github.com/pcdv/web-lib), src/settings.ts: changed there, then synced here.
// @ts-nocheck: its types are in settings.d.ts
/**
 * What the page remembers in the browser: a tab chosen, a section folded…
 * Browser storage can be unavailable (a private window, site data blocked):
 * it is only a convenience, and the page works without it.
 *
 * Keys are global to the origin: each project starts its own with its name,
 * e.g. "jfrweb.live.window".
 */

export function readSetting(key) {
  try {
    return localStorage.getItem(key);
  }
  catch (e) {
    return null;
  }
}

/** @param value stored as a string */
export function writeSetting(key, value) {
  try {
    localStorage.setItem(key, String(value));
  }
  catch (e) {
    // only a convenience
  }
}
