// @ts-check
/**
 * The table of endpoints of a session, sorted by total time.
 */
import {h} from '../dom.js';
import * as fmt from '../format.js';

/**
 * @param {import('../api.js').EndpointRow[]} rows
 * @param {(endpoint: string) => void} onSelect called when a row is clicked
 */
export function endpointsTable(rows, onSelect) {
  if (rows.length === 0)
    return h('p', {class: 'empty'}, 'No request recorded.');

  return h('table', {class: 'grid'},
    h('thead', null, h('tr', null,
      h('th', null, 'Endpoint'), h('th', {class: 'num'}, 'Count'), h('th', {class: 'num'}, 'Total'),
      h('th', {class: 'num'}, 'p50'), h('th', {class: 'num'}, 'p99'), h('th', {class: 'num'}, 'Max'),
      h('th', {class: 'num'}, '5xx'), h('th', {class: 'num'}, 'In'), h('th', {class: 'num'}, 'Out'))),
    h('tbody', null, rows.map(r => h('tr', {
        class: 'clickable',
        title: 'Show the flame graph of this endpoint',
        onclick: () => onSelect(r.endpoint),
      },
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
