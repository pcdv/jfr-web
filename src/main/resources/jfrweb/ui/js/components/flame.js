// @ts-check
/**
 * A flame graph of a session, with its filters: endpoint, thread, time
 * interval, and the samples taken outside requests.
 */
import * as api from '../api.js';
import {h, replace} from '../dom.js';

/**
 * @typedef {object} FlamePanel
 * @property {HTMLElement} element
 * @property {(endpoint: string) => void} showEndpoint selects an endpoint
 * @property {(from: number | undefined, to: number | undefined) => void} showInterval
 *           selects an interval, in seconds since the start, or the whole session
 * @property {(request: api.RequestRow) => void} showRequest shows one request, until a
 *           filter changes
 */

/**
 * @param {api.Report} report
 * @returns {FlamePanel}
 */
export function flamePanel(report) {
  const id = report.session.id;
  const seconds = Math.ceil(report.session.duration / 1000);

  const endpoint = h('select', {name: 'endpoint'},
    h('option', {value: ''}, 'All requests'),
    report.endpoints.map(e => h('option', {value: e.endpoint}, e.endpoint)));

  // the busiest threads first
  const threads = [...report.threads].sort((a, b) => b.samples - a.samples);
  const thread = h('select', {name: 'thread'},
    h('option', {value: ''}, 'Any thread'),
    threads.map(t => h('option', {value: t.name}, `${t.name} (${t.samples})`)));

  const from = h('input', {type: 'number', name: 'from', min: 0, max: seconds, step: 'any', placeholder: '0'});
  const to = h('input', {type: 'number', name: 'to', min: 0, max: seconds, step: 'any', placeholder: String(seconds)});
  const all = h('input', {type: 'checkbox', name: 'all'});
  const frame = h('iframe', {class: 'flame', title: 'Flame graph'});

  /** @type {number | undefined} one request shown, instead of the filters */
  let request;
  const requestChip = h('span', {class: 'chip', hidden: true});

  function update() {
    requestChip.hidden = request === undefined;
    frame.src = api.flameUrl(id, request !== undefined ? {request} : {
      endpoint: endpoint.value || undefined,
      thread: thread.value || undefined,
      from: from.value === '' ? undefined : Number(from.value),
      to: to.value === '' ? undefined : Number(to.value),
      all: all.checked,
    });
  }

  /** Back to the filters, as soon as one of them changes. */
  function onFilterChange() {
    request = undefined;
    update();
  }

  const form = h('form', {class: 'filters', onchange: onFilterChange, onsubmit: (/** @type {Event} */ e) => e.preventDefault()},
    requestChip,
    h('label', null, 'Endpoint ', endpoint),
    h('label', null, 'Thread ', thread),
    h('label', null, 'From ', from, ' s'),
    h('label', null, 'to ', to, ' s'),
    h('label', {title: 'Mostly idle threads blocked in native code: selectors, acceptors'},
      all, ' samples outside requests'));

  update();
  return {
    element: h('div', null, form, frame),
    showEndpoint(e) {
      request = undefined;
      endpoint.value = e;
      update();
      // asked for: shown even if its section was folded
      const folded = frame.closest('details');
      if (folded)
        folded.open = true;
      frame.scrollIntoView({behavior: 'smooth'});
    },
    showInterval(f, t) {
      request = undefined;
      from.value = f === undefined ? '' : String(f);
      to.value = t === undefined ? '' : String(t);
      update();
    },
    showRequest(r) {
      request = r.id;
      replace(requestChip,
        `Request ${r.method} ${r.path} at ${r.start.toFixed(3)} s `,
        h('button', {type: 'button', title: 'Back to the filters', onclick: onFilterChange}, '×'));
      update();
    },
  };
}
