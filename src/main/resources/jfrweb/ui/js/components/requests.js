// @ts-check
/**
 * The slowest requests of a session, of one endpoint or of all, and the
 * detail of the one picked: its exceptions, and its slow file and socket
 * accesses.
 */
import * as api from '../api.js';
import {h, replace, errorBox} from '../dom.js';
import * as fmt from '../format.js';
import {section} from './section.js';

const LIMIT = 20;

/** Under this number of samples, a flame graph of one request says little. */
const FEW_SAMPLES = 10;

/**
 * @typedef {object} RequestsPanel
 * @property {HTMLElement} element
 * @property {(endpoint: string | undefined) => void} showEndpoint lists the slowest
 *           requests of an endpoint, or of all
 * @property {(interval: api.Interval) => void} showInterval keeps those in flight
 *           during an interval, or all of them
 */

/**
 * @param {number} sessionId
 * @param {(request: api.RequestRow) => void} onPick called when a request is picked
 * @returns {RequestsPanel}
 */
export function requestsPanel(sessionId, onPick) {
  const title = h('span');
  const list = h('div');
  const detail = h('div');
  /** @type {string | undefined} */
  let endpoint;
  /** @type {api.Interval} */
  let interval = {};

  async function refresh() {
    const label = fmt.interval(interval);
    replace(title, 'Slowest requests',
      endpoint ? h('span', {class: 'mono'}, ` · ${endpoint}`) : null,
      label ? h('span', {class: 'interval'}, ` · ${label}`) : null);
    replace(detail);
    try {
      const rows = await api.listRequests(sessionId, endpoint, interval, LIMIT);
      replace(list, table(rows, pick, label ? 'No request in flight in this interval.' : 'No request recorded.'));
    }
    catch (e) {
      replace(list, errorBox(e));
    }
  }

  /** @param {api.RequestRow} row */
  async function pick(row) {
    onPick(row);
    for (const tr of list.querySelectorAll('tr.selected'))
      tr.classList.remove('selected');
    list.querySelector(`tr[data-id="${row.id}"]`)?.classList.add('selected');
    replace(detail, h('p', {class: 'empty'}, 'Reading the events of the request…'));
    try {
      replace(detail, details(await api.getRequest(sessionId, row.id)));
    }
    catch (e) {
      replace(detail, errorBox(e));
    }
  }

  refresh();
  return {
    element: section('requests', title, list, detail),
    showEndpoint(e) {
      endpoint = e;
      refresh();
    },
    showInterval(i) {
      interval = i;
      refresh();
    },
  };
}

/**
 * @param {api.RequestRow[]} rows
 * @param {(row: api.RequestRow) => void} onPick
 * @param {string} empty the message shown when there is no row
 */
function table(rows, onPick, empty) {
  if (rows.length === 0)
    return h('p', {class: 'empty'}, empty);
  return h('table', {class: 'grid'},
    h('thead', null, h('tr', null,
      h('th', {class: 'num'}, 'At'), h('th', {class: 'num'}, 'Duration'), h('th', {class: 'num'}, 'Status'),
      h('th', null, 'Request'), h('th', null, 'Thread'))),
    h('tbody', null, rows.map(r => h('tr', {
        class: 'clickable',
        'data-id': r.id,
        title: 'Show the flame graph, exceptions and slow accesses of this request',
        onclick: () => onPick(r),
      },
      h('td', {class: 'num'}, `${r.start.toFixed(3)} s`),
      h('td', {class: 'num'}, fmt.duration(r.duration)),
      h('td', {class: r.status >= 500 ? 'num bad' : 'num'}, r.status),
      h('td', {class: 'mono'}, `${r.method} ${r.path}`),
      h('td', null, r.thread)))));
}

/**
 * @param {api.RequestRow & api.RequestDetail} d
 */
function details(d) {
  const samples = d.javaSamples + d.nativeSamples;
  const thresholds = Object.entries(d.ioThresholds)
    .map(([event, t]) => `${event.replace('jdk.', '')} ${t ?? 'not recorded'}`).join(', ');

  return h('div', {class: 'card'},
    h('h3', null, h('span', {class: 'mono'}, `${d.method} ${d.path}`), ` · ${fmt.duration(d.duration)}, status ${d.status}, thread ${d.thread}`),
    h('p', {class: samples < FEW_SAMPLES ? 'hint warn' : 'hint'},
      `${samples} samples (${d.javaSamples} Java, ${d.nativeSamples} native) in the flame graph below`,
      samples < FEW_SAMPLES ? ': too few to tell much, look at the endpoint instead' : '.'),

    h('h3', null, `Exceptions created (${d.exceptions.length})`),
    !d.exceptionsRecorded
      ? h('p', {class: 'empty'}, 'Not recorded: jdk.JavaExceptionThrow was off.')
      : d.exceptions.length === 0
        ? h('p', {class: 'empty'}, 'None.')
        : h('table', {class: 'grid'},
            h('thead', null, h('tr', null,
              h('th', {class: 'num'}, 'At'), h('th', null, 'Exception'), h('th', null, 'Message'), h('th', null, 'Created in'))),
            h('tbody', null, d.exceptions.map(e => h('tr', null,
              h('td', {class: 'num'}, fmt.duration(e.offset)),
              h('td', {class: 'mono'}, e.type),
              h('td', {class: 'wrap'}, e.message ?? ''),
              h('td', {class: 'mono'}, e.frame ?? ''))))),

    h('h3', null, `Slow file and socket accesses (${d.io.length})`),
    h('p', {class: 'hint'}, `Only those over the thresholds of the recording: ${thresholds}.`),
    d.io.length === 0
      ? h('p', {class: 'empty'}, 'None.')
      : h('table', {class: 'grid'},
          h('thead', null, h('tr', null,
            h('th', {class: 'num'}, 'At'), h('th', null, 'Access'), h('th', null, 'File or host'),
            h('th', {class: 'num'}, 'Bytes'), h('th', {class: 'num'}, 'Duration'))),
          h('tbody', null, d.io.map(i => h('tr', null,
            h('td', {class: 'num'}, fmt.duration(i.offset)),
            h('td', null, i.kind),
            h('td', {class: 'mono wrap'}, i.target),
            h('td', {class: 'num'}, fmt.bytes(i.bytes)),
            h('td', {class: 'num'}, fmt.duration(i.duration)))))));
}
