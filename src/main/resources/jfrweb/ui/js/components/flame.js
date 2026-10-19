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
import {tree, timeOf, hotMethods, methodTrees, compare} from '../profile/profile.js';
import {hotMethodsPanel} from './hotMethods.js';
import {operationRows} from '../profile/operations.js';
import {flameGraph} from '../flame/render.js';
import {readSetting, writeSetting} from '../lib/settings.js';

/** @typedef {import('../profile/profile.js').Profile} Profile */
/** @typedef {import('../profile/profile.js').Filter} FlameFilter */
/** @typedef {import('../api.js').InstanceRow} InstanceRow */

/**
 * A frame of the flame graph, and when its samples were taken.
 *
 * @typedef {object} ShownFrame
 * @property {string} name
 * @property {(start: number, bucket: number, count: number) => Float64Array} times its ms of
 *           samples per bucket: from start, in ms since the start of the recording, count
 *           buckets of bucket ms; bytes for allocations
 * @property {'ms' | 'bytes'} unit what times() counts
 */

/**
 * @typedef {object} FlamePanel
 * @property {HTMLElement} element
 * @property {{title: HTMLElement, content: HTMLElement[]}} hot the hot methods of the same
 *           samples, for the page to place: under the graph, or in a tab
 * @property {(name: string) => void} showName selects the operations of a name
 * @property {(from: number | undefined, to: number | undefined, name?: string | null) => void}
 *           showInterval selects an interval, in seconds since the start, or the whole
 *           session; and the operations of a name too when given, null for all
 * @property {() => 'wall' | 'alloc'} samples those it shows
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
 * @param {(frame: ShownFrame | null) => void} [options.onFrame] told of the frame hovered, else
 *        of the one zoomed on, else of none: to show when its samples were taken
 * @param {(() => Promise<Profile>) | null} [options.allocations] loads the profile of the
 *        session's allocation samples, which a choice of samples then shows: null when it has
 *        none, the choice disabled; none for no choice, e.g. on a page holding one profile
 * @param {boolean} [options.showAllocations] whether it opens on the allocations
 * @returns {FlamePanel}
 */
export function flamePanel(profile, signal, {onInstance = () => {}, filter: initial = {}, onFrame = () => {},
                                             allocations, showAllocations = false} = {}) {
  // the samples drawn: the wall clock's, or the allocations', loaded once asked for
  const samplesChoice = h('select', {name: 'samples'},
    h('option', {value: 'wall'}, 'Wall clock'),
    h('option', {value: 'alloc', disabled: !allocations}, 'Allocations'));
  samplesChoice.value = allocations && showAllocations ? 'alloc' : 'wall';
  /** @type {Promise<Profile> | null} */
  let allocationProfile = null;
  /** @returns {Promise<Profile>} the profile of the samples chosen */
  function source() {
    if (samplesChoice.value !== 'alloc' || !allocations)
      return profile;
    return allocationProfile ??= allocations();
  }

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
  const hide = h('input', {type: 'text', name: 'hide', class: 'mono', placeholder: 'e.g. reflect|\\$Proxy',
                           value: initial.hide ?? ''});

  /** @type {number | undefined} one operation shown, instead of the filters */
  let instance;
  /** @type {string} what it is, for the caption */
  let instanceLabel = '';
  const instanceChip = h('span', {class: 'chip', hidden: true});

  /** @type {{profile: Profile, filter: FlameFilter} | null} what the graph was drawn from */
  let drawn = null;
  const graph = flameGraph(signal, {
    onFrame: f => {
      const d = drawn;
      onFrame(f && d ? {name: f.name, unit: d.profile.unit,
                        times: (start, bucket, count) => timeOf(d.profile, d.filter, f.path, start, bucket, count)}
                     : null);
    },
    onMethod: name => showMethod(name),
  });
  const caption = h('div', {class: 'flame-caption'}, 'Loading the samples…');

  // a comparison: what was selected (operation, thread, interval, one
  // operation) kept as a baseline, the display options those of the graph
  /** @type {{selection: FlameFilter, label: string} | null} */
  let baseline = null;
  /** @type {{selection: FlameFilter, label: string}} what the graph shows */
  let current = {selection: {}, label: ''};
  const compareBar = h('span', {class: 'flame-compare'});

  function drawCompareBar() {
    replace(compareBar, baseline
      ? h('span', {class: 'chip'},
          'Compared with ', h('b', null, baseline.label), ' ',
          h('button', {type: 'button', title: 'Stop comparing', onclick: () => {
            baseline = null;
            drawCompareBar();
            update();
          }}, '×'))
      : h('button', {
          type: 'button', class: 'small',
          title: 'Keep this selection as a baseline, then select another interval, operation or thread: '
                 + 'the flame graph shows the change, red where more of the time went, blue where less',
          onclick: () => {
            baseline = current;
            drawCompareBar();
            update();
          },
        }, 'Compare…'));
  }
  drawCompareBar();
  // the methods where the time went, of the same samples: placed by the page
  const hot = hotMethodsPanel(name => graph.find(name), name => showMethod(name));
  const hotTitle = h('span', null, 'Hot methods');

  // one method, picked: its callers above it, its callees below, merged
  // wherever it is called from
  /** @type {string | null} */
  let method = null;
  const callers = flameGraph(signal, {onMethod: name => showMethod(name), search: false});
  const callees = flameGraph(signal, {onMethod: name => showMethod(name), search: false});
  const methodTitle = h('span', {class: 'mono'});
  const methodCaption = h('div', {class: 'flame-caption'});
  const methodSection = h('section', {class: 'method-view', hidden: true},
    h('div', {class: 'method-view-title'},
      h('h3', null, 'Callers and callees of ', methodTitle),
      h('button', {type: 'button', class: 'small', title: 'Close', onclick: () => showMethod(null)}, '×')),
    methodCaption,
    h('h4', null, 'What calls it, up to the operations'), callers.element,
    h('h4', null, 'What it calls'), callees.element);

  // the caption, the search and the comparison on one row: the graph right
  // under the timeline
  const captionTools = h('div', {class: 'flame-caption-tools'}, graph.toolbar, compareBar);
  const body = h('div', {class: 'flame-main'},
    h('div', {class: 'flame-caption-bar'}, caption, captionTools),
    graph.element, methodSection);

  function showHot() {
    if (drawn)
      hot.show(hotMethods(drawn.profile, drawn.filter), drawn.profile.unit);
  }

  /** @param {string | null} [name] the method to show, the one shown if undefined, none if null */
  function showMethod(name) {
    if (name !== undefined)
      method = name;
    methodSection.hidden = method === null;
    if (method === null || !drawn)
      return;
    const t = methodTrees(drawn.profile, drawn.filter, method);
    replace(methodTitle, method);
    const unit = drawn.profile.unit;
    replace(methodCaption, `${fmt.amount(t.callees.total, unit)} in ${t.samples} samples of these filters`,
            t.samples ? null : ': none');
    // the method at the bottom of its callers, and at the top of its callees
    callers.show(t.callers, false, unit);
    callees.show(t.callees, true, unit);
    if (name)
      methodSection.scrollIntoView({behavior: 'smooth', block: 'start'});
  }

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
    // an invalid pattern hides nothing, and says so
    let pattern = hide.value || undefined;
    try {
      new RegExp(pattern ?? '');
      hide.classList.remove('invalid');
    }
    catch (e) {
      hide.classList.add('invalid');
      pattern = undefined;
    }
    return instance !== undefined ? {instance, hide: pattern} : {
      hide: pattern,
      name: name.value || undefined,
      thread: thread.value || undefined,
      from: from.value === '' ? undefined : Number(from.value),
      to: to.value === '' ? undefined : Number(to.value),
      all: all.checked,
      merged: merged.checked,
      reversed: reversed.checked,
      java: java.checked && samplesChoice.value !== 'alloc',
    };
  }

  function update() {
    instanceChip.hidden = instance === undefined;
    // allocations have no native samples to leave out
    java.disabled = samplesChoice.value === 'alloc';
    source().then(p => {
      if (signal.aborted)
        return;
      // read once the lists are in
      const f = filter();
      const {root, samples} = tree(p, f);
      drawn = {profile: p, filter: f};
      // the baseline's selection, with the display options of now
      // different operations: each compared with the baseline's, by the paths under them
      const across = !!baseline && !f.merged && operation(baseline.selection) !== operation(selection(f));
      const base = baseline && compare(tree(p, {...display(f), ...baseline.selection}).root, root, across);
      const what = [p.unit === 'bytes' ? 'Allocations' : f.java ? 'Java code' : 'Wall clock'];
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
      if (f.hide)
        what.push(`frames matching ${f.hide} hidden`);
      current = {selection: selection(f), label: what.slice(1).join(' · ') || 'the whole session'};
      replace(caption, `${what.join(' · ')}: ${fmt.amount(root.total, p.unit)} in ${samples} samples`,
              samples ? null : ', none for these filters',
              base ? h('span', {class: 'flame-compare-legend'},
                ' · ', h('span', {class: 'more'}, 'more'), ' or ', h('span', {class: 'less'}, 'less'),
                p.unit === 'bytes' ? ' allocated than in the baseline' : ' of the time than in the baseline') : null);
      graph.show(base ?? root, !!f.reversed && f.instance === undefined, p.unit);
      showHot();
      showMethod();
    }).catch(e => {
      if (!signal.aborted)
        replace(body, errorBox(e));
    });
  }

  /**
   * @param {FlameFilter} f
   * @returns {FlameFilter} what it selects: a baseline keeps it
   */
  function selection(f) {
    return f.instance !== undefined ? {instance: f.instance}
      : {name: f.name, thread: f.thread, from: f.from, to: f.to};
  }

  /**
   * @param {FlameFilter} s a selection
   * @returns {string} the operations it selects, by name or one by its id
   */
  function operation(s) {
    return s.instance !== undefined ? `#${s.instance}` : s.name ?? '';
  }

  /**
   * @param {FlameFilter} f
   * @returns {FlameFilter} how it shows them: a baseline takes them
   */
  function display(f) {
    return {all: f.all, merged: f.merged, reversed: f.reversed, java: f.java, hide: f.hide};
  }

  /** Back to the filters, as soon as one of them changes. */
  function onFilterChange() {
    clearInstance();
    update();
  }

  const form = h('form', {class: 'filters', onchange: onFilterChange, onsubmit: (/** @type {Event} */ e) => e.preventDefault()},
    instanceChip,
    allocations === undefined ? null : h('label', {
      title: allocations
        ? 'Wall clock: where the time went, from the Java and native samples. Allocations: where memory '
          + 'was allocated, from the allocation samples, the class allocated on top of each stack'
        : 'The recording has no allocation sample: jdk.ObjectAllocationSample was off, or the JVM older than 16',
    }, 'Samples ', samplesChoice),
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
      java, ' Java only'),
    h('label', {title: 'A regular expression: the frames whose name it finds are left out, their time given to their '
                       + 'callers. Reflection, proxies, the plumbing of a framework'},
      'Hide frames ', hide));

  // the filters, in a pane beside the graph, folded as last left
  const PANE = 'jfrweb.flame.filters';
  const pane = h('aside', {class: 'flame-pane'});
  const paneToggle = h('button', {type: 'button', class: 'flame-pane-toggle',
                                  onclick: () => foldPane(!pane.classList.contains('folded'))});
  pane.append(form);

  /** @param {boolean} folded the button that shows them then goes by the caption: the graph takes the width */
  function foldPane(folded) {
    pane.classList.toggle('folded', folded);
    if (folded)
      captionTools.append(paneToggle);
    else
      pane.prepend(paneToggle);
    paneToggle.textContent = folded ? '‹ Filters' : 'Filters ›';
    paneToggle.title = folded ? 'Show the filters' : 'Fold the filters: the caption above the graph tells them';
    writeSetting(PANE, String(folded));
  }
  foldPane(readSetting(PANE) === 'true');

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
    element: h('div', {class: 'flame-layout'}, body, pane),
    hot: {title: hotTitle, content: [h('p', {class: 'hint'}, 'Where the time went, whatever calls them, '
                                                            + 'of the samples of the flame graph.'), hot.element]},
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
    samples: () => samplesChoice.value === 'alloc' ? 'alloc' : 'wall',
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
