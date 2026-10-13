// @ts-check
/**
 * The table of endpoints of a session, sorted by total time.
 */
import * as api from '../api.js';
import {h, replace, errorBox} from '../dom.js';
import * as fmt from '../format.js';
import {section} from './section.js';

/**
 * @typedef {object} EndpointsPanel
 * @property {HTMLElement} element
 * @property {(interval: api.Interval) => void} showInterval shows the requests in
 *           flight during an interval, or all of them
 */

/**
 * The endpoints of a session, as a foldable section, over the whole session
 * or an interval.
 *
 * @param {number} sessionId
 * @param {api.EndpointRow[]} rows those of the whole session
 * @param {(endpoint: string) => void} onSelect called when a row is clicked
 * @returns {EndpointsPanel}
 */
export function endpointsPanel(sessionId, rows, onSelect) {
  const title = h('span', null, 'Endpoints');
  const body = h('div', null, endpointsTable(rows, onSelect));

  /** @param {api.Interval} interval */
  async function showInterval(interval) {
    const label = fmt.interval(interval);
    replace(title, 'Endpoints', label ? h('span', {class: 'interval'}, ` · ${label}`) : null);
    try {
      replace(body, label
        ? endpointsTable(await api.listEndpoints(sessionId, interval), onSelect, 'No request in flight in this interval.')
        : endpointsTable(rows, onSelect));
    }
    catch (e) {
      replace(body, errorBox(e));
    }
  }

  return {element: section('endpoints', title, body), showInterval};
}

/**
 * @param {import('../api.js').EndpointRow[]} rows
 * @param {(endpoint: string) => void} [onSelect] called when a row is clicked
 * @param {string} [empty] the message shown when there is no row
 */
export function endpointsTable(rows, onSelect, empty = 'No request recorded.') {
  if (rows.length === 0)
    return h('p', {class: 'empty'}, empty);

  return h('table', {class: 'grid'},
    h('thead', null, h('tr', null,
      h('th', null, 'Endpoint'), h('th', {class: 'num'}, 'Count'), h('th', {class: 'num'}, 'Total'),
      h('th', {class: 'num'}, 'p50'), h('th', {class: 'num'}, 'p99'), h('th', {class: 'num'}, 'Max'),
      h('th', {class: 'num'}, '5xx'), h('th', {class: 'num'}, 'In'), h('th', {class: 'num'}, 'Out'))),
    h('tbody', null, rows.map(r => h('tr', onSelect ? {
        class: 'clickable',
        title: 'Show the flame graph of this endpoint',
        onclick: () => onSelect(r.endpoint),
      } : null,
      h('td', {class: 'mono'}, r.endpoint),
      h('td', {class: 'num'}, r.count),
      h('td', {class: 'num'}, fmt.duration(r.total)),
      h('td', {class: 'num'}, fmt.duration(r.p50)),
      h('td', {class: 'num'}, fmt.duration(r.p99)),
      h('td', {class: 'num'}, fmt.duration(r.max)),
      h('td', {class: r.errors ? 'num bad' : 'num'}, r.errors),
      h('td', {class: 'num'}, fmt.bytes(r.bytesIn)),
      h('td', {class: 'num'}, fmt.bytes(r.bytesOut))))));
}
