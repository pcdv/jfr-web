// @ts-check
/**
 * How long a session records at most, set as it starts: "Stop after 5 minutes".
 * Empty for a session that records until it is stopped.
 */
import {h} from '../lib/dom.js';
import {readSetting, writeSetting} from '../lib/settings.js';

/** What was chosen last, kept per browser: the unit, and the number. */
const KEY = 'jfrweb.stopAfter';

/** The units, in seconds. */
const UNITS = /** @type {const} */ ({seconds: 1, minutes: 60});

/**
 * @param {string} value what was typed, e.g. "5"
 * @param {string} unit 'seconds' or 'minutes'
 * @returns {number | null} null if nothing was typed
 * @throws {Error} if it is not a positive number
 */
export function toSeconds(value, unit) {
  const v = value.trim();
  if (v === '')
    return null;
  const n = Number(v);
  const factor = UNITS[/** @type {keyof typeof UNITS} */ (unit)];
  if (!Number.isFinite(n) || n <= 0 || !factor)
    throw new Error(`Stop after: not a positive number of ${unit}: ${v}`);
  return n * factor;
}

/**
 * A field that tells after how long to stop a session, drawn once: the page
 * puts it back in its toolbar each time that is drawn again.
 *
 * @returns {{element: HTMLElement, seconds: () => number | null}} seconds: throws if what was typed is not
 *          a positive number
 */
export function stopAfterField() {
  let last = {value: '', unit: 'minutes'};
  try {
    last = {...last, ...JSON.parse(readSetting(KEY) || '{}')};
  }
  catch (e) {
    // only a convenience
  }
  const value = h('input', {type: 'number', min: 0, step: 'any', value: last.value,
                            placeholder: 'No limit', 'aria-label': 'Stop the session after'});
  const unit = h('select', {'aria-label': 'Unit of the time to stop after'},
    Object.keys(UNITS).map(u => h('option', {value: u, selected: u === last.unit}, u)));
  const remember = () => writeSetting(KEY, JSON.stringify({value: value.value, unit: unit.value}));
  value.addEventListener('change', remember);
  unit.addEventListener('change', remember);
  return {
    element: h('span', {class: 'stop-after', title: 'The session stops by itself after this long'},
      'Stop after ', value, ' ', unit),
    seconds: () => toSeconds(value.value, unit.value),
  };
}
