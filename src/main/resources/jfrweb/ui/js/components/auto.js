// @ts-check
/**
 * The auto mode: sessions that start by themselves when matching operations
 * come, and stop once they are over.
 */
import * as api from '../api.js';
import {h, replace} from '../dom.js';
import {readSetting, writeSetting} from '../settings.js';

/** Last arming, kept per browser to arm again in one click. */
const ARMING_KEY = 'jfrweb.auto.arming';

/** Whether the panel is unfolded, kept per browser. */
const OPEN_KEY = 'jfrweb.auto.open';

/**
 * A foldable panel, whose title tells the state when folded.
 *
 * @param {api.AutoState} state
 * @param {() => void} refresh called once the state changed
 */
export function autoPanel(state, refresh) {
  const panel = h('details', {class: 'card auto', open: readSetting(OPEN_KEY) !== 'false'},
    h('summary', null, 'Auto mode ', status(state)),
    !state.observing
      ? h('p', {class: 'empty'}, 'No operation yet, so none can start a session. Begin them with ',
          h('code', null, 'Operations.begin(name)'), ', or pass the flak apps to the profiler: ',
          h('code', null, 'Profiler.builder().observe(app)'), '.')
      : state.armed ? armed(state, refresh) : form(state, refresh));
  panel.addEventListener('toggle', () => writeSetting(OPEN_KEY, String(panel.open)));
  return panel;
}

/** @param {api.AutoState} s */
function status(s) {
  if (!s.observing || !s.armed)
    return h('span', {class: 'hint'}, '· off');
  return h('span', {class: 'state recording'}, s.recording !== null ? `· recording session ${s.recording}` : '· armed');
}

/**
 * @param {api.AutoState} s
 * @param {() => void} refresh
 */
function armed(s, refresh) {
  const what = s.patterns && s.patterns.length
    ? [h('span', {class: 'mono'}, s.patterns.join(', '))]
    : ['any operation'];
  const who = s.browser ? (s.thisBrowser ? ' from this browser' : ' from another browser') : '';
  return h('div', {class: 'form-grid'},
    h('span', {class: 'label'}, 'Starts a session on'),
    h('span', null, ...what, who, s.repeat ? ', every time' : ', once'),
    h('span', {class: 'label'}, 'Stops after'),
    h('span', null, `${s.idle} s without one, or ${s.max} s at most`),
    h('span'),
    h('span', null, h('button', {onclick: () => act(api.disarmAuto, refresh)}, 'Disarm')));
}

/**
 * @param {api.AutoState} state
 * @param {() => void} refresh
 */
function form(state, refresh) {
  /** @type {api.AutoArming} */
  const last = {patterns: '', browser: true, repeat: false, idle: 3, max: 120, ...readArming()};

  const patterns = h('input', {type: 'text', id: 'auto-patterns', class: 'wide', value: last.patterns,
                               placeholder: 'any operation, or e.g. GET /api/repository*, order*'});
  // only the requests of flak can come from a browser
  const browser = h('input', {type: 'checkbox', checked: last.browser && state.flakApps});
  const repeat = h('input', {type: 'checkbox', checked: last.repeat});
  const idle = h('input', {type: 'number', class: 'seconds', min: 0.5, step: 'any', value: last.idle});
  const max = h('input', {type: 'number', class: 'seconds', min: 1, step: 'any', value: last.max});

  function arm() {
    /** @type {api.AutoArming} */
    const a = {
      patterns: patterns.value, browser: browser.checked, repeat: repeat.checked,
      idle: Number(idle.value), max: Number(max.value),
    };
    writeArming(a);
    act(() => api.armAuto(a), refresh);
  }

  return h('form', {class: 'form-grid', onsubmit: (/** @type {Event} */ e) => { e.preventDefault(); arm(); }},
    h('label', {class: 'label', for: 'auto-patterns'}, 'Operations'),
    h('div', null, patterns,
      h('div', {class: 'hint'}, 'Separated by commas; * matches anything; empty for any operation.')),
    h('span', {class: 'label'}, 'Sessions'),
    h('div', {class: 'inline'},
      state.flakApps
        ? h('label', {title: 'A cookie marks this browser: the requests of other clients do not count'},
            browser, ' only requests from this browser')
        : null,
      h('label', null, repeat, ' a session every time, not once')),
    h('span', {class: 'label'}, 'Stops after'),
    h('div', {class: 'inline'},
      h('label', {title: 'Once no matching operation has run for that long, which leaves time for asynchronous work'},
        idle, ' s without one'),
      h('label', null, max, ' s at most')),
    h('span'),
    h('div', null, h('button', {class: 'primary', type: 'submit'}, 'Arm')));
}

/**
 * @param {() => Promise<unknown>} action
 * @param {() => void} refresh
 */
async function act(action, refresh) {
  try {
    await action();
  }
  catch (e) {
    alert(e instanceof Error ? e.message : String(e));
  }
  refresh();
}

/** @returns {Partial<api.AutoArming>} */
function readArming() {
  try {
    return JSON.parse(readSetting(ARMING_KEY) || '{}');
  }
  catch (e) {
    return {};
  }
}

/** @param {api.AutoArming} a */
function writeArming(a) {
  writeSetting(ARMING_KEY, JSON.stringify(a));
}
