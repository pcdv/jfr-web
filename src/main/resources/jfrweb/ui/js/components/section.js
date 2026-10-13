// @ts-check
/**
 * A foldable section of a page, whose title stays visible when folded. Each
 * section is left open or folded as the browser last left it.
 */
import {h} from '../dom.js';

/**
 * @param {string} id names the section, to remember its state
 * @param {import('../dom.js').Child} title
 * @param {...(import('../dom.js').Child | import('../dom.js').Child[])} content
 */
export function section(id, title, ...content) {
  const key = `jfrweb.section.${id}`;
  const el = h('details', {class: 'section', open: read(key) !== 'false'},
    h('summary', null, h('h2', null, title)),
    ...content);
  el.addEventListener('toggle', () => write(key, String(el.open)));
  return el;
}

/** @param {string} key */
function read(key) {
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
function write(key, value) {
  try {
    localStorage.setItem(key, value);
  }
  catch (e) {
    // only a convenience
  }
}
