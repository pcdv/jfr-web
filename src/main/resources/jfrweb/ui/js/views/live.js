// @ts-check
/**
 * The operations of the application, over the last minutes, kept by counters
 * whether a session records or not; a session can be armed on any of them.
 */
import * as api from '../api.js';
import {h, replace, errorBox} from '../dom.js';
import {readArming} from '../components/auto.js';
import {operationsTable} from '../components/operations.js';
import {readSetting, writeSetting} from '../settings.js';

/** How often the statistics refresh, in ms. */
const POLL = 2000;

/** The window shown first, in minutes; the choice is kept per browser. */
const WINDOW_KEY = 'jfrweb.live.window';

/**
 * @param {HTMLElement} root
 * @returns {() => void}
 */
export function renderLive(root) {
  let window_ = Number(readSetting(WINDOW_KEY) || 5);
  const toolbar = h('div', {class: 'toolbar'});
  const armedLine = h('div');
  const content = h('div');
  root.append(h('h1', null, 'Live operations'), toolbar, armedLine, content);

  /** @type {number | undefined} */
  let timer;
  let stopped = false;
  /** The latest refresh: an earlier one answering after it is ignored. */
  let latest = 0;
  /** @type {number | null} the session the auto mode records, last seen */
  let recording = null;
  /** @type {number | null} the session it recorded since, to open */
  let recorded = null;

  /** @param {api.AutoState} auto */
  function showAuto(auto) {
    if (recording !== null && auto.recording === null)
      recorded = recording;
    recording = auto.recording;
    replace(armedLine, autoLine(auto, recorded, () => act(api.disarmAuto)));
  }

  /**
   * Arms a session on the next operation of a name, from any client, as the
   * auto mode was last armed otherwise; named after the operation, to be
   * renamed if need be. It replaces any other arming.
   *
   * @param {api.OperationRow} r
   */
  function arm(r) {
    const last = readArming();
    recorded = null;
    act(() => api.armAuto({patterns: r.name, browser: false, repeat: false,
                           idle: last.idle ?? 3, max: last.max ?? 120, name: r.name}));
  }

  /**
   * @param {api.OperationRow} r
   * @param {api.AutoState} auto
   */
  function armButton(r, auto) {
    const mine = auto.armed && auto.patterns?.length === 1 && auto.patterns[0] === r.name;
    return mine
      ? h('button', {class: 'small', onclick: () => act(api.disarmAuto)}, 'Disarm')
      : h('button', {class: 'small', title: `Record a session on the next ${r.name}`, onclick: () => arm(r)}, 'Arm');
  }

  /** @param {() => Promise<unknown>} action */
  async function act(action) {
    try {
      await action();
    }
    catch (e) {
      alert(e instanceof Error ? e.message : String(e));
    }
    refresh();
  }

  async function refresh() {
    clearTimeout(timer);
    const mine = ++latest;
    try {
      const [live, auto] = await Promise.all([api.getLive(window_), api.getAuto()]);
      // e.g. a poll for the window shown before another one was picked
      if (stopped || mine !== latest)
        return;
      showAuto(auto);
      replace(toolbar, windowPicker(live, w => {
        window_ = w;
        writeSetting(WINDOW_KEY, String(w));
        refresh();
      }));
      replace(content, live.observing
        ? operationsTable(live.operations, undefined, `No operation in the last ${plural(live.window, 'minute')}.`,
                          r => armButton(r, auto))
        : h('p', {class: 'empty'}, live.fed
            ? ['No operation from this JVM yet. They come from the events of its flak requests ',
               '(flak 3.4.0 or later) and of the ', h('code', null, 'jfrweb.ops'), ' API.']
            : ['No operation yet. Begin them with ', h('code', null, 'Operations.begin(name)'),
               ', or pass the flak apps to observe to the profiler: ',
               h('code', null, 'Profiler.builder().observe(app)'), '.']));
      timer = window.setTimeout(refresh, POLL);
    }
    catch (e) {
      if (mine === latest)
        replace(content, errorBox(e));
    }
  }

  refresh();
  return () => {
    stopped = true;
    clearTimeout(timer);
  };
}

/**
 * What the auto mode waits for, or records, and the session it recorded.
 *
 * @param {api.AutoState} auto
 * @param {number | null} recorded
 * @param {() => void} onDisarm
 */
function autoLine(auto, recorded, onDisarm) {
  const named = auto.name ? [' named ', h('b', null, auto.name)] : [];
  if (auto.recording !== null && auto.waiting)
    return h('p', {class: 'armed-line'}, h('span', {class: 'state recording'}, 'Recording '),
      h('a', {href: `#/sessions/${auto.recording}`}, `session ${auto.recording}`), named, ', waiting for ', what(auto),
      `: it stops once one has ended, after ${auto.idle} s without another. `,
      h('button', {class: 'small', onclick: onDisarm}, 'Disarm'));
  if (auto.recording !== null)
    return h('p', {class: 'armed-line'}, h('span', {class: 'state recording'}, 'Recording '),
      h('a', {href: `#/sessions/${auto.recording}`}, `session ${auto.recording}`), named, ', started by ', what(auto), '.');
  if (auto.armed)
    return h('p', {class: 'armed-line'}, h('span', {class: 'state recording'}, 'Armed: '),
      'the next ', what(auto), auto.browser ? ' from the armed browser' : '', ' records a session', named,
      auto.repeat ? ', every time' : '', `, until ${auto.idle} s without one or ${auto.max} s at most. `,
      h('button', {class: 'small', onclick: onDisarm}, 'Disarm'));
  if (recorded !== null)
    return h('p', {class: 'armed-line'}, 'Recorded ',
      h('a', {href: `#/sessions/${recorded}`}, `session ${recorded}`), ': open its report.');
  return null;
}

/**
 * The operations a trigger waits for.
 *
 * @param {api.AutoState} auto
 */
function what(auto) {
  return auto.patterns?.length ? auto.patterns.join(', ') : 'any operation';
}

/**
 * @param {api.Live} live
 * @param {(window: number) => void} onPick
 */
function windowPicker(live, onPick) {
  return h('span', {class: 'segmented'},
    'Last ',
    live.windows.map(w => h('button', {
      class: w === live.window ? 'selected' : null,
      onclick: () => onPick(w),
    }, plural(w, 'minute'))),
    live.apps.length ? h('span', {class: 'hint'}, ` · apps: ${live.apps.map(a => a || '/').join(', ')}`) : null);
}

/**
 * @param {number} n
 * @param {string} unit
 */
function plural(n, unit) {
  return `${n} ${unit}${n > 1 ? 's' : ''}`;
}
