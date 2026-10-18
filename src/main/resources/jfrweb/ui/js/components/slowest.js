// @ts-check
/**
 * The slowest operations of a session, of one name or of all, and the detail
 * of the one picked: the threads that worked for it, its exceptions, and its
 * slow file and socket accesses.
 */
import * as api from '../api.js';
import {h, replace} from '../lib/dom.js';
import {errorBox} from './errorBox.js';
import * as fmt from '../format.js';
import {outcomeCell} from './operations.js';
import {section} from './section.js';
import {instanceRows} from '../profile/operations.js';

const LIMIT = 20;

/** Under this number of samples, a flame graph of one operation says little. */
const FEW_SAMPLES = 10;

/**
 * @typedef {object} SlowestPanel
 * @property {HTMLElement} element
 * @property {(name: string | undefined) => void} showName lists the slowest
 *           operations of a name, or of all
 * @property {(interval: api.Interval, name?: string | null) => void} showInterval keeps
 *           those in flight during an interval, or all of them; and those of a name too
 *           when given, null for all
 */

/**
 * @param {number} sessionId
 * @param {Promise<import('../profile/profile.js').Profile> | null} profile the session's,
 *        which gives the slowest of a name or an interval with no request; null in a
 *        static export
 * @param {(op: api.InstanceRow) => void} onPick called when an operation is picked
 * @returns {SlowestPanel}
 */
export function slowestPanel(sessionId, profile, onPick) {
  const title = h('span');
  const list = h('div');
  const detail = h('div');
  /** @type {string | undefined} */
  let name;
  /** @type {api.Interval} */
  let interval = {};

  async function refresh() {
    const label = fmt.interval(interval);
    replace(title, 'Slowest operations',
      name ? h('span', {class: 'mono'}, ` · ${name}`) : null,
      label ? h('span', {class: 'interval'}, ` · ${label}`) : null);
    replace(detail);
    try {
      const rows = profile
        ? instanceRows(await profile, name, interval, LIMIT)
        : await api.listInstances(sessionId, name, interval, LIMIT);
      replace(list, table(rows, pick, label ? 'No operation in flight in this interval.' : 'No operation recorded.'));
    }
    catch (e) {
      replace(list, errorBox(e));
    }
  }

  /** @param {api.InstanceRow} row */
  async function pick(row) {
    onPick(row);
    for (const tr of list.querySelectorAll('tr.selected'))
      tr.classList.remove('selected');
    list.querySelector(`tr[data-id="${row.id}"]`)?.classList.add('selected');
    replace(detail, h('p', {class: 'empty'}, 'Reading the events of the operation…'));
    try {
      replace(detail, details(await api.getInstance(sessionId, row.id)));
    }
    catch (e) {
      replace(detail, errorBox(e));
    }
  }

  refresh();
  return {
    element: section('slowest', title, list, detail),
    showName(n) {
      name = n;
      refresh();
    },
    showInterval(i, n) {
      interval = i;
      if (n !== undefined)
        name = n ?? undefined;
      refresh();
    },
  };
}

/**
 * What an operation worked on, or its name.
 *
 * @param {{name: string, detail: string | null}} r
 */
export function label(r) {
  return r.detail ?? r.name;
}

/**
 * @param {api.InstanceRow[]} rows
 * @param {(row: api.InstanceRow) => void} onPick
 * @param {string} empty the message shown when there is no row
 */
function table(rows, onPick, empty) {
  if (rows.length === 0)
    return h('p', {class: 'empty'}, empty);
  return h('table', {class: 'grid'},
    h('thead', null, h('tr', null,
      h('th', {class: 'num'}, 'At'), h('th', {class: 'num'}, 'Duration'), h('th', {class: 'num'}, 'Outcome'),
      h('th', null, 'Operation'), h('th', null, 'Thread'))),
    h('tbody', null, rows.map(r => h('tr', {
        class: 'clickable',
        'data-id': r.id,
        title: 'Show the flame graph, exceptions and slow accesses of this operation',
        onclick: () => onPick(r),
      },
      h('td', {class: 'num'}, `${r.start.toFixed(3)} s`),
      h('td', {class: 'num'}, fmt.duration(r.duration)),
      outcomeCell(r),
      h('td', {class: 'mono'}, label(r)),
      h('td', null, r.async ? 'several' : r.thread)))));
}

/**
 * @param {api.InstanceRow & api.InstanceDetail} d
 */
function details(d) {
  const samples = d.javaSamples + d.nativeSamples;
  const thresholds = Object.entries(d.ioThresholds)
    .map(([event, t]) => `${event.replace('jdk.', '')} ${t ?? 'not recorded'}`).join(', ');
  const outcome = d.status !== 0 ? `status ${d.status}` : d.failed ? 'failed' : 'ok';

  return h('div', {class: 'card'},
    h('h3', null, h('span', {class: 'mono'}, label(d)),
      ` · ${fmt.duration(d.duration)}, ${outcome}`, d.async ? ', asynchronous' : `, thread ${d.thread}`),
    d.error ? h('p', {class: 'hint warn mono'}, d.error) : null,
    h('p', {class: samples < FEW_SAMPLES ? 'hint warn' : 'hint'},
      `${samples} samples (${d.javaSamples} Java, ${d.nativeSamples} native) in the flame graph below`,
      samples < FEW_SAMPLES ? `: too few to tell much, look at all the ${d.name} operations instead` : '.'),

    d.async ? segments(d) : null,

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

/**
 * The threads that worked for an asynchronous operation: the rest of its
 * time, it waited.
 *
 * @param {api.InstanceRow & api.InstanceDetail} d
 */
function segments(d) {
  const busy = d.segments.reduce((sum, s) => sum + s.duration, 0);
  return h('div', null,
    h('h3', null, `Threads that worked for it (${d.segments.length})`),
    d.segments.length === 0
      ? h('p', {class: 'empty'}, 'None declared: wrap the work in a segment of the operation to see its samples.')
      : h('p', {class: 'hint'}, `${fmt.duration(busy)} of work in ${fmt.duration(d.duration)}: it waited the rest of the time.`),
    d.segments.length === 0 ? null : h('table', {class: 'grid'},
      h('thead', null, h('tr', null,
        h('th', {class: 'num'}, 'At'), h('th', {class: 'num'}, 'Duration'), h('th', null, 'Thread'))),
      h('tbody', null, d.segments.map(s => h('tr', null,
        h('td', {class: 'num'}, fmt.duration(s.offset)),
        h('td', {class: 'num'}, fmt.duration(s.duration)),
        h('td', null, s.thread))))));
}
