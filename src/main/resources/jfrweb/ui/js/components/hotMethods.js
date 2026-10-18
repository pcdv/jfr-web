// @ts-check
/**
 * The methods where the time went, whatever calls them: by self time, the
 * samples taken while they ran, or by total time, the samples they were in.
 * It follows the filters of the flame graph; a click finds the method in it.
 */
import {h, replace} from '../lib/dom.js';
import * as fmt from '../format.js';
import {shareCell} from './share.js';

/** @typedef {import('../profile/profile.js').HotMethod} HotMethod */

/** Rows shown: past them, methods of little time. */
const SHOWN = 200;

/**
 * @param {(name: string) => void} onPick called with the method clicked
 * @param {(name: string) => void} onMethod called to show the callers and callees of one
 * @returns {{element: HTMLElement, show: (hot: {methods: HotMethod[], total: number}) => void}}
 *          the table, and what fills it with the methods of the filters
 */
export function hotMethodsPanel(onPick, onMethod) {
  const body = h('div', {class: 'table-scroll'});
  /** @type {'self' | 'total'} */
  let by = 'self';
  /** @type {{methods: HotMethod[], total: number}} */
  let hot = {methods: [], total: 0};

  /** @param {'self' | 'total'} column */
  function header(column) {
    const label = column === 'self' ? 'Self' : 'Total';
    return h('th', {
      class: by === column ? 'num sortable sorted' : 'num sortable',
      title: column === 'self'
        ? 'The samples taken while it ran: at the top of the stack'
        : 'The samples it was in, anywhere in the stack, each once however deep its recursion',
      onclick: () => {
        by = column;
        draw();
      },
    }, label);
  }

  function draw() {
    if (hot.methods.length === 0) {
      replace(body, h('p', {class: 'empty'}, 'No sample for these filters.'));
      return;
    }
    const sorted = [...hot.methods].sort((a, b) => b[by] - a[by]).slice(0, SHOWN);
    const max = sorted[0][by];
    replace(body, h('table', {class: 'grid'},
      h('thead', null, h('tr', null,
        h('th', null, 'Method'),
        h('th', {title: `Share of the samples kept, by ${by} time`}, 'Time'),
        header('self'),
        header('total'),
        h('th'))),
      h('tbody', null, sorted.map(m => h('tr', {
          class: 'clickable',
          title: 'Find it in the flame graph',
          onclick: () => onPick(m.name),
        },
        h('td', {class: 'mono'}, m.name),
        shareCell(m[by], hot.total, max, `the samples kept, by ${by} time`),
        h('td', {class: 'num'}, fmt.duration(m.self)),
        h('td', {class: 'num'}, fmt.duration(m.total)),
        h('td', {class: 'action'}, h('button', {
          type: 'button', class: 'small', title: 'What calls it, and what it calls, wherever it is called from',
          onclick: (/** @type {Event} */ e) => {
            e.stopPropagation();
            onMethod(m.name);
          },
        }, 'Callers & callees')))))));
  }

  return {
    element: body,
    show(methods) {
      hot = methods;
      draw();
    },
  };
}
