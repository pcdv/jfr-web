// @ts-check
/**
 * A flame graph of a session, with its filters: operation, thread, time
 * interval, and the samples taken outside operations.
 *
 * The session's samples are loaded once, and the flame graph of each filter
 * built and drawn here. A static export still shows the flame graphs the
 * server drew, one filter at a time.
 */
import * as api from '../api.js';
import * as fmt from '../format.js';
import {h, replace} from '../lib/dom.js';
import {errorBox} from './errorBox.js';
import {label} from './slowest.js';
import {tree} from '../profile/profile.js';
import {flameGraph} from '../flame/render.js';

/** @typedef {import('../profile/profile.js').Profile} Profile */

/**
 * @typedef {object} FlamePanel
 * @property {HTMLElement} element
 * @property {(name: string) => void} showName selects the operations of a name
 * @property {(from: number | undefined, to: number | undefined, name?: string | null) => void}
 *           showInterval selects an interval, in seconds since the start, or the whole
 *           session; and the operations of a name too when given, null for all
 * @property {(op: api.InstanceRow) => void} showInstance shows one operation, until a
 *           filter changes, and brings the flame graph into view
 */

/**
 * @param {api.Report} report
 * @param {Promise<Profile> | null} profile the session's, null in a static export
 * @param {AbortSignal} signal the view's
 * @param {(op: api.InstanceRow | null) => void} [onInstance] told of the operation shown
 *        alone, and with null once the flame graph is back to the filters
 * @returns {FlamePanel}
 */
export function flamePanel(report, profile, signal, onInstance = () => {}) {
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
  const merged = h('input', {type: 'checkbox', name: 'merged'});
  const reversed = h('input', {type: 'checkbox', name: 'reversed'});
  const java = h('input', {type: 'checkbox', name: 'java'});

  /** @type {number | undefined} one operation shown, instead of the filters */
  let instance;
  /** @type {string} what it is, for the caption */
  let instanceLabel = '';
  const instanceChip = h('span', {class: 'chip', hidden: true});

  // a static export: the pages the server drew
  const frame = profile ? null : h('iframe', {class: 'flame', title: 'Flame graph'});
  const graph = profile ? flameGraph(signal) : null;
  const caption = h('div', {class: 'flame-caption'}, 'Loading the samples…');
  const serverPage = h('a', {target: '_blank', rel: 'noopener',
                             title: "The same flame graph, drawn by the server with async-profiler's converter"},
                       'Drawn by the server');
  const body = h('div', null, frame ?? [h('div', {class: 'flame-caption-bar'}, caption, serverPage), graph?.element]);

  /** @returns {api.FlameFilter} */
  function filter() {
    return instance !== undefined ? {instance} : {
      name: name.value || undefined,
      thread: thread.value || undefined,
      from: from.value === '' ? undefined : Number(from.value),
      to: to.value === '' ? undefined : Number(to.value),
      all: all.checked,
      merged: merged.checked,
      reversed: reversed.checked,
      java: java.checked,
    };
  }

  function update() {
    instanceChip.hidden = instance === undefined;
    const f = filter();
    if (frame) {
      frame.src = api.flameUrl(id, f);
      return;
    }
    serverPage.href = api.flameUrl(id, f);
    profile?.then(p => {
      if (signal.aborted || !graph)
        return;
      const {root, samples} = tree(p, f);
      const what = [f.java ? 'Java code' : 'Wall clock'];
      if (f.instance !== undefined)
        what.push(instanceLabel);
      else {
        if (f.name)
          what.push(f.name);
        if (f.thread)
          what.push(`thread ${f.thread}`);
        if (f.from !== undefined || f.to !== undefined)
          what.push(fmt.interval(f));
      }
      replace(caption, `${what.join(' · ')}: ${fmt.duration(root.total)} in ${samples} samples`,
              samples ? null : ', none for these filters');
      graph.show(root, !!f.reversed && f.instance === undefined);
    }).catch(e => {
      if (!signal.aborted)
        replace(body, errorBox(e));
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
    for (const c of [all, merged, reversed, java])
      if (keep !== c)
        c.checked = false;
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
      all, ' samples outside operations'),
    h('label', {title: 'One tree for all the operations kept, instead of one per name: what costs in all of them'},
      merged, ' merged'),
    h('label', {title: 'The methods that were running at the root, their callers above: the costliest, whatever calls them'},
      reversed, ' hot methods'),
    h('label', {title: 'Only the samples of threads running Java code, without native methods: closer to CPU time'},
      java, ' Java only'));

  function clearInstance() {
    if (instance === undefined)
      return;
    instance = undefined;
    onInstance(null);
  }

  /** Asked for: shown even if its section was folded. */
  function reveal() {
    const folded = body.closest('details');
    if (folded)
      folded.open = true;
    body.scrollIntoView({behavior: 'smooth'});
  }

  update();
  return {
    element: h('div', null, form, body),
    showName(n) {
      clearInstance();
      name.value = n;
      onlyFilter(name);
      update();
      reveal();
    },
    showInterval(f, t, n) {
      clearInstance();
      from.value = f === undefined ? '' : String(f);
      to.value = t === undefined ? '' : String(t);
      if (n !== undefined)
        name.value = n ?? '';
      update();
    },
    showInstance(r) {
      instance = r.id;
      instanceLabel = `${label(r)} at ${r.start.toFixed(3)} s`;
      replace(instanceChip,
        `${label(r)} at ${r.start.toFixed(3)} s `,
        h('button', {type: 'button', title: 'Back to the filters', onclick: () => onFilterChange()}, '×'));
      update();
      onInstance(r);
      reveal();
    },
  };
}
