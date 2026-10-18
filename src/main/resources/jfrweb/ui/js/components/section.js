// @ts-check
/**
 * A foldable section of a page, whose title stays visible when folded. Each
 * section is left open or folded as the browser last left it.
 */
import {h} from '../lib/dom.js';
import {readSetting, writeSetting} from '../lib/settings.js';

/**
 * @param {string} id names the section, to remember its state
 * @param {import('../lib/dom.js').Child} title
 * @param {...(import('../lib/dom.js').Child | import('../lib/dom.js').Child[])} content
 */
export function section(id, title, ...content) {
  const key = `jfrweb.section.${id}`;
  const el = h('details', {class: 'section', open: readSetting(key) !== 'false'},
    h('summary', null, h('h2', null, title)),
    ...content);
  el.addEventListener('toggle', () => writeSetting(key, String(el.open)));
  return el;
}
