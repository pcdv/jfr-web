// @ts-check
/**
 * The table of the operations of a session by name, sorted by total time.
 */
import * as api from '../api.js';
import {h, replace} from '../lib/dom.js';
import {errorBox} from './errorBox.js';
import * as fmt from '../format.js';
import {section} from './section.js';
import {operationRows} from '../profile/operations.js';

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
 * @param {Promise<import('../profile/profile.js').Profile> | null} profile the session's,
 *        which gives the rows of an interval with no request; null in a static export
 * @param {(name: string) => void} onSelect called when a row is clicked
 * @returns {OperationsPanel}
 */
export function operationsPanel(sessionId, rows, profile, onSelect) {
  const title = h('span', null, 'Operations');
  const body = h('div', null, operationsTable(rows, onSelect));

  /** @param {api.Interval} interval */
  async function showInterval(interval) {
    const label = fmt.interval(interval);
    replace(title, 'Operations', label ? h('span', {class: 'interval'}, ` · ${label}`) : null);
    try {
      replace(body, label
        ? operationsTable(profile ? operationRows(await profile, interval) : await api.listOperations(sessionId, interval),
                          onSelect, 'No operation in flight in this interval.')
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
 * @param {(row: api.OperationRow) => Node} [action] what a last cell offers for a row
 */
export function operationsTable(rows, onSelect, empty = 'No operation recorded.', action) {
  if (rows.length === 0)
    return h('p', {class: 'empty'}, empty);

  const sum = rows.reduce((s, r) => s + r.total, 0);
  const max = Math.max(...rows.map(r => r.total));

  return h('table', {class: 'grid'},
    h('thead', null, h('tr', null,
      h('th', null, 'Operation'),
      h('th', {title: 'Share of the time spent in all operations'}, 'Time'),
      h('th', {class: 'num'}, 'Count'), h('th', {class: 'num'}, 'Total'),
      h('th', {class: 'num'}, 'p50'), h('th', {class: 'num'}, 'p99'), h('th', {class: 'num'}, 'Max'),
      h('th', {class: 'num', title: 'Operations that failed: 5xx responses for requests'}, 'Errors'),
      h('th', {class: 'num'}, 'In'), h('th', {class: 'num'}, 'Out'),
      action ? h('th') : null)),
    h('tbody', null, rows.map(r => h('tr', onSelect ? {
        class: 'clickable',
        title: 'Show the flame graph of these operations',
        onclick: () => onSelect(r.name),
      } : null,
      h('td', {class: 'mono'}, r.name),
      shareCell(r.total, sum, max),
      h('td', {class: 'num'}, r.count),
      h('td', {class: 'num'}, fmt.duration(r.total)),
      h('td', {class: 'num'}, fmt.duration(r.p50)),
      h('td', {class: 'num'}, fmt.duration(r.p99)),
      h('td', {class: 'num'}, fmt.duration(r.max)),
      h('td', {class: r.errors ? 'num bad' : 'num'}, r.errors),
      h('td', {class: 'num'}, fmt.bytes(r.bytesIn)),
      h('td', {class: 'num'}, fmt.bytes(r.bytesOut)),
      action ? h('td', {class: 'action'}, action(r)) : null))));
}

/**
 * The share of the time of all operations taken by those of one name: a bar
 * scaled to the busiest name, so that the longest bar spans the cell, and the
 * percentage of the sum.
 *
 * @param {number} total
 * @param {number} sum the total of all names
 * @param {number} max the total of the busiest name
 */
function shareCell(total, sum, max) {
  const share = sum > 0 ? fmt.percent(total / sum) : '';
  return h('td', {class: 'share', title: sum > 0 ? `${share} of the time spent in all operations` : null},
    h('span', {class: 'share-track'},
      h('span', {class: 'share-bar', style: `width: ${max > 0 ? total / max * 100 : 0}%`})),
    h('span', {class: 'share-label'}, share));
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
