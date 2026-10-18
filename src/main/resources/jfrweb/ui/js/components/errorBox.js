// @ts-check
import {h} from '../lib/dom.js';

/**
 * An error message, in place of a view or a panel that could not load.
 *
 * @param {unknown} e
 */
export function errorBox(e) {
  return h('div', {class: 'error'}, e instanceof Error ? e.message : String(e));
}
