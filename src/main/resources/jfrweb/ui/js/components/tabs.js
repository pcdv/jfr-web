// @ts-check
/**
 * A foldable section of a page holding several panels, one shown at a time:
 * their titles are tabs in its header. It remembers, as the browser last left
 * them, whether it is folded and which tab is shown.
 */
import {h} from '../lib/dom.js';
import {readSetting, writeSetting} from '../lib/settings.js';

/**
 * @typedef {object} Tab
 * @property {string} key names it, to remember the tab shown
 * @property {import('../lib/dom.js').Child} title
 * @property {import('../lib/dom.js').Child[]} content
 */

/**
 * @param {string} id names the section
 * @param {Tab[]} tabs the first one shown unless another was
 * @returns {HTMLElement & {show: (key: string) => void}} the section, and what shows a tab
 */
export function tabbedSection(id, tabs) {
  const openKey = `jfrweb.section.${id}`;
  const tabKey = `jfrweb.section.${id}.tab`;
  const buttons = tabs.map(t => h('button', {type: 'button', class: 'tab', role: 'tab'}, t.title));
  const panels = tabs.map(t => h('div', {role: 'tabpanel'}, ...t.content));
  const el = h('details', {class: 'section tabbed', open: readSetting(openKey) !== 'false'},
    h('summary', null, h('h2', {role: 'tablist'}, buttons)),
    panels);
  el.addEventListener('toggle', () => writeSetting(openKey, String(el.open)));

  /** @param {string} key */
  function show(key) {
    const shown = Math.max(0, tabs.findIndex(t => t.key === key));
    buttons.forEach((b, i) => {
      b.classList.toggle('selected', i === shown);
      b.setAttribute('aria-selected', String(i === shown));
    });
    panels.forEach((p, i) => p.hidden = i !== shown);
  }

  buttons.forEach((b, i) => b.addEventListener('click', e => {
    // a tab of an open section shows its panel; of a folded one, opens it too
    e.preventDefault();
    el.open = true;
    writeSetting(tabKey, tabs[i].key);
    show(tabs[i].key);
  }));
  show(readSetting(tabKey) ?? tabs[0].key);
  return Object.assign(el, {show});
}
