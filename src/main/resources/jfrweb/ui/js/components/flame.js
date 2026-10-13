// @ts-check
/**
 * A flame graph of a session, with its filters: operation, thread, time
 * interval, and the samples taken outside operations.
 */
import * as api from '../api.js';
import {h, replace} from '../dom.js';
import {label} from './slowest.js';

/**
 * @typedef {object} FlamePanel
 * @property {HTMLElement} element
 * @property {(name: string) => void} showName selects the operations of a name
 * @property {(from: number | undefined, to: number | undefined) => void} showInterval
 *           selects an interval, in seconds since the start, or the whole session
 * @property {(op: api.InstanceRow) => void} showInstance shows one operation, until a
 *           filter changes
 */

/**
 * @param {api.Report} report
 * @returns {FlamePanel}
 */
export function flamePanel(report) {
  const id = report.session.id;
  const seconds = Math.ceil(report.session.duration / 1000);

  const name = h('select', {name: 'name'},
    h('option', {value: ''}, 'All operations'),
    report.operations.map(o => h('option', {value: o.name}, o.name)));

  // the busiest threads first
  const threads = [...report.threads].sort((a, b) => b.samples - a.samples);
  const thread = h('select', {name: 'thread'},
    h('option', {value: ''}, 'Any thread'),
    threads.map(t => h('option', {value: t.name}, `${t.name} (${t.samples})`)));

  const from = h('input', {type: 'number', name: 'from', min: 0, max: seconds, step: 'any', placeholder: '0'});
  const to = h('input', {type: 'number', name: 'to', min: 0, max: seconds, step: 'any', placeholder: String(seconds)});
  const all = h('input', {type: 'checkbox', name: 'all'});
  const frame = h('iframe', {class: 'flame', title: 'Flame graph'});

  /** @type {number | undefined} one operation shown, instead of the filters */
  let instance;
  const instanceChip = h('span', {class: 'chip', hidden: true});

  function update() {
    instanceChip.hidden = instance === undefined;
    frame.src = api.flameUrl(id, instance !== undefined ? {instance} : {
      name: name.value || undefined,
      thread: thread.value || undefined,
      from: from.value === '' ? undefined : Number(from.value),
      to: to.value === '' ? undefined : Number(to.value),
      all: all.checked,
    });
  }

  /** Back to the filters, as soon as one of them changes. */
  function onFilterChange() {
    instance = undefined;
    update();
  }

  const form = h('form', {class: 'filters', onchange: onFilterChange, onsubmit: (/** @type {Event} */ e) => e.preventDefault()},
    instanceChip,
    h('label', null, 'Operation ', name),
    h('label', null, 'Thread ', thread),
    h('label', null, 'From ', from, ' s'),
    h('label', null, 'to ', to, ' s'),
    h('label', {title: 'Mostly idle threads blocked in native code: selectors, acceptors'},
      all, ' samples outside operations'));

  update();
  return {
    element: h('div', null, form, frame),
    showName(n) {
      instance = undefined;
      name.value = n;
      update();
      // asked for: shown even if its section was folded
      const folded = frame.closest('details');
      if (folded)
        folded.open = true;
      frame.scrollIntoView({behavior: 'smooth'});
    },
    showInterval(f, t) {
      instance = undefined;
      from.value = f === undefined ? '' : String(f);
      to.value = t === undefined ? '' : String(t);
      update();
    },
    showInstance(r) {
      instance = r.id;
      replace(instanceChip,
        `${label(r)} at ${r.start.toFixed(3)} s `,
        h('button', {type: 'button', title: 'Back to the filters', onclick: onFilterChange}, '×'));
      update();
    },
  };
}
