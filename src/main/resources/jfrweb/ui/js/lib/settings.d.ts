// From web-lib (https://github.com/pcdv/web-lib), src/settings.ts: changed there, then synced here.
/**
 * What the page remembers in the browser: a tab chosen, a section folded…
 * Browser storage can be unavailable (a private window, site data blocked):
 * it is only a convenience, and the page works without it.
 *
 * Keys are global to the origin: each project starts its own with its name,
 * e.g. "jfrweb.live.window".
 */
export declare function readSetting(key: string): string | null;
/** @param value stored as a string */
export declare function writeSetting(key: string, value: string | number | boolean): void;
