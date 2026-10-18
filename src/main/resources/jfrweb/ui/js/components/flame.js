// @ts-check
/**
 * A flame graph of a session, with its filters: operation, thread, time
 * interval, and the samples taken outside operations.
 *
 * Built from the session's profile, loaded once: each filter's flame graph
 * is summed and drawn here, with no request. It needs nothing else, so the
 * page `jfr-web flame` writes shows it too.
 */
import * as fmt from '../format.js';
import {h, replace} from '../lib/dom.js';
import {errorBox} from './errorBox.js';
import {tree} from '../profile/profile.js';
import {operationRows} from '../profile/operations.js';
import {flameGraph} from '../flame/render.js';

/** @typedef {import('../profile/profile.js').Profile} Profile */
/** @typedef {import('../profile/profile.js').Filter} FlameFilter */
/** @typedef {import('../api.js').InstanceRow} InstanceRow */

/**
 * @typedef {object} FlamePanel
 * @property {HTMLElement} element
 * @property {(name: string) => void} showName selects the operations of a name
 * @property {(from: number | undefined, to: number | undefined, name?: string | null) => void}
 *           showInterval selects an interval, in seconds since the start, or the whole
 *           session; and the operations of a name too when given, null for all
 * @property {(op: InstanceRow) => void} showInstance shows one operation, until a
 *           filter changes, and brings the flame graph into view
 */

/**
 * @param {Promise<Profile>} profile the session's
 * @param {AbortSignal} signal the view's
 * @param {object} [options]
 * @param {(op: InstanceRow | null) => void} [options.onInstance] told of the operation shown
 *        alone, and with null once the flame graph is back to the filters
 * @param {FlameFilter} [options.filter] the filters it opens with
 * @returns {FlamePanel}
 */
export function flamePanel(profile, signal, {onInstance = () => {}, filter: initial = {}} = {}) {
  const name = h('select', {name: 'name'}, h('option', {value: ''}, 'All operations'));
  const thread = h('select', {name: 'thread'}, h('option', {value: ''}, 'Any thread'));
  const from = h('input', {type: 'number', name: 'from', min: 0, step: 'any', placeholder: '0',
                           value: initial.from ?? ''});
  const to = h('input', {type: 'number', name: 'to', min: 0, step: 'any', placeholder: 'end',
                         value: initial.to ?? ''});
  const all = h('input', {type: 'checkbox', name: 'all', checked: !!initial.all});
  const merged = h('input', {type: 'checkbox', name: 'merged', checked: !!initial.merged});
  const reversed = h('input', {type: 'checkbox', name: 'reversed', checked: !!initial.reversed});
  const java = h('input', {type: 'checkbox', name: 'java', checked: !!initial.java});

  /** @type {number | undefined} one operation shown, instead of the filters */
  let instance;
  /** @type {string} what it is, for the caption */
  let instanceLabel = '';
  const instanceChip = h('span', {class: 'chip', hidden: true});

  const graph = flameGraph(signal);
  const caption = h('div', {class: 'flame-caption'}, 'Loading the samples…');
  const body = h('div', null, h('div', {class: 'flame-caption-bar'}, caption), graph.element);

  // the operations by total time, the busiest threads first, as the profile
  // has them; a name asked for before, selected once there
  let listed = false;
  let wanted = initial.name ?? '';
  profile.then(p => {
    // those with samples: a page of a few operations has theirs only
    const sampled = new Set();
    for (const op of p.operation)
      if (op >= 0)
        sampled.add(p.strings[p.operations.name[op]]);
    for (const o of operationRows(p, {}))
      if (sampled.has(o.name))
        name.append(h('option', {value: o.name}, o.name));
    /** @type {Map<string, number>} */
    const samples = new Map();
    for (const t of p.thread)
      samples.set(p.threads[t].name, (samples.get(p.threads[t].name) ?? 0) + 1);
    for (const [n, count] of [...samples].sort((a, b) => b[1] - a[1]))
      thread.append(h('option', {value: n}, `${n} (${count})`));
    name.value = wanted;
    thread.value = initial.thread ?? '';
    listed = true;
  }, () => {});

  /** @param {string} n */
  function selectName(n) {
    if (listed)
      name.value = n;
    else
      wanted = n;
  }

  /** @returns {FlameFilter} */
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
    profile.then(p => {
      if (signal.aborted)
        return;
      // read once the lists are in
      const f = filter();
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

  /** Back to the filters, as soon as one of them changes. */
  function onFilterChange() {
    clearInstance();
    update();
  }

  const form = h('form', {class: 'filters', onchange: onFilterChange, onsubmit: (/** @type {Event} */ e) => e.preventDefault()},
    instanceChip,
    h('label', null, 'Operation ', name),
    h('label', null, 'Thread ', thread),
    h('label', null, 'From ', from, ' s'),
    h('label', null, 'to ', to, ' s'),
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
      selectName(n);
      update();
      reveal();
    },
    showInterval(f, t, n) {
      clearInstance();
      from.value = f === undefined ? '' : String(f);
      to.value = t === undefined ? '' : String(t);
      if (n !== undefined)
        selectName(n ?? '');
      update();
    },
    showInstance(r) {
      instance = r.id;
      instanceLabel = `${fmt.label(r)} at ${r.start.toFixed(3)} s`;
      replace(instanceChip,
        `${instanceLabel} `,
        h('button', {type: 'button', title: 'Back to the filters', onclick: () => onFilterChange()}, '×'));
      update();
      onInstance(r);
      reveal();
    },
  };
}
