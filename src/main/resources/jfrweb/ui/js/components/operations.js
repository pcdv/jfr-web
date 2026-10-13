// @ts-check
/**
 * The table of the operations of a session by name, sorted by total time.
 */
import * as api from '../api.js';
import {h, replace, errorBox} from '../dom.js';
import * as fmt from '../format.js';
import {section} from './section.js';

/**
 * @typedef {object} OperationsPanel
 * @property {HTMLElement} element
 * @property {(interval: api.Interval) => void} showInterval shows the operations in
 *           flight during an interval, or all of them
 */

/**
 * The operations of a session, as a foldable section, over the whole session
 * or an interval.
 *
 * @param {number} sessionId
 * @param {api.OperationRow[]} rows those of the whole session
 * @param {(name: string) => void} onSelect called when a row is clicked
 * @returns {OperationsPanel}
 */
export function operationsPanel(sessionId, rows, onSelect) {
  const title = h('span', null, 'Operations');
  const body = h('div', null, operationsTable(rows, onSelect));

  /** @param {api.Interval} interval */
  async function showInterval(interval) {
    const label = fmt.interval(interval);
    replace(title, 'Operations', label ? h('span', {class: 'interval'}, ` · ${label}`) : null);
    try {
      replace(body, label
        ? operationsTable(await api.listOperations(sessionId, interval), onSelect, 'No operation in flight in this interval.')
        : operationsTable(rows, onSelect));
    }
    catch (e) {
      replace(body, errorBox(e));
    }
  }

  return {element: section('operations', title, body), showInterval};
}

/**
 * @param {import('../api.js').OperationRow[]} rows
 * @param {(name: string) => void} [onSelect] called when a row is clicked
 * @param {string} [empty] the message shown when there is no row
 */
export function operationsTable(rows, onSelect, empty = 'No operation recorded.') {
  if (rows.length === 0)
    return h('p', {class: 'empty'}, empty);

  return h('table', {class: 'grid'},
    h('thead', null, h('tr', null,
      h('th', null, 'Operation'), h('th', {class: 'num'}, 'Count'), h('th', {class: 'num'}, 'Total'),
      h('th', {class: 'num'}, 'p50'), h('th', {class: 'num'}, 'p99'), h('th', {class: 'num'}, 'Max'),
      h('th', {class: 'num', title: 'Operations that failed: 5xx responses for requests'}, 'Errors'),
      h('th', {class: 'num'}, 'In'), h('th', {class: 'num'}, 'Out'))),
    h('tbody', null, rows.map(r => h('tr', onSelect ? {
        class: 'clickable',
        title: 'Show the flame graph of these operations',
        onclick: () => onSelect(r.name),
      } : null,
      h('td', {class: 'mono'}, r.name),
      h('td', {class: 'num'}, r.count),
      h('td', {class: 'num'}, fmt.duration(r.total)),
      h('td', {class: 'num'}, fmt.duration(r.p50)),
      h('td', {class: 'num'}, fmt.duration(r.p99)),
      h('td', {class: 'num'}, fmt.duration(r.max)),
      h('td', {class: r.errors ? 'num bad' : 'num'}, r.errors),
      h('td', {class: 'num'}, fmt.bytes(r.bytesIn)),
      h('td', {class: 'num'}, fmt.bytes(r.bytesOut))))));
}

/**
 * The outcome of an operation in a table cell: the status of a request, or
 * whether another operation failed.
 *
 * @param {{status: number, failed: boolean}} r
 */
export function outcomeCell(r) {
  return h('td', {class: r.failed ? 'num bad' : 'num'},
    r.status !== 0 ? r.status : r.failed ? 'failed' : 'ok');
}
