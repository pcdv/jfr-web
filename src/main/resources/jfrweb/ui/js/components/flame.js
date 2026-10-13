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
 *           filter changes, and brings the flame graph into view
 */

/**
 * @param {api.Report} report
 * @param {(op: api.InstanceRow | null) => void} [onInstance] told of the operation shown
 *        alone, and with null once the flame graph is back to the filters
 * @returns {FlamePanel}
 */
export function flamePanel(report, onInstance = () => {}) {
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

  /**
   * A static export has the flame graphs of one filter at a time: the others
   * are cleared.
   *
   * @param {EventTarget | null} keep the filter just changed
   */
  function onlyFilter(keep) {
    if (!api.isStatic())
      return;
    if (keep !== name)
      name.value = '';
    if (keep !== thread)
      thread.value = '';
    if (keep !== all)
      all.checked = false;
  }

  /**
   * Back to the filters, as soon as one of them changes.
   *
   * @param {Event} [e]
   */
  function onFilterChange(e) {
    clearInstance();
    if (e)
      onlyFilter(e.target);
    update();
  }

  const form = h('form', {class: 'filters', onchange: onFilterChange, onsubmit: (/** @type {Event} */ e) => e.preventDefault()},
    instanceChip,
    h('label', null, 'Operation ', name),
    h('label', null, 'Thread ', thread),
    api.isStatic() ? null : h('label', null, 'From ', from, ' s'),
    api.isStatic() ? null : h('label', null, 'to ', to, ' s'),
    h('label', {title: 'Mostly idle threads blocked in native code: selectors, acceptors'},
      all, ' samples outside operations'));

  function clearInstance() {
    if (instance === undefined)
      return;
    instance = undefined;
    onInstance(null);
  }

  /** Asked for: shown even if its section was folded. */
  function reveal() {
    const folded = frame.closest('details');
    if (folded)
      folded.open = true;
    frame.scrollIntoView({behavior: 'smooth'});
  }

  update();
  return {
    element: h('div', null, form, frame),
    showName(n) {
      clearInstance();
      name.value = n;
      onlyFilter(name);
      update();
      reveal();
    },
    showInterval(f, t) {
      clearInstance();
      from.value = f === undefined ? '' : String(f);
      to.value = t === undefined ? '' : String(t);
      update();
    },
    showInstance(r) {
      instance = r.id;
      replace(instanceChip,
        `${label(r)} at ${r.start.toFixed(3)} s `,
        h('button', {type: 'button', title: 'Back to the filters', onclick: () => onFilterChange()}, '×'));
      update();
      onInstance(r);
      reveal();
    },
  };
}
