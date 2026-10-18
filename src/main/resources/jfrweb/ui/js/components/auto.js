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
      ? h('p', {class: 'empty'}, state.fed
          ? ['No operation from this JVM yet, so none can start a session. They come from the events of its ',
             'flak requests (flak 3.4.0 or later) and of the ', h('code', null, 'jfrweb.ops'), ' API.']
          : ['No operation yet, so none can start a session. Begin them with ',
             h('code', null, 'Operations.begin(name)'), ', or pass the flak apps to the profiler: ',
             h('code', null, 'Profiler.builder().observe(app)'), '.'])
      : state.armed ? armed(state, refresh) : form(state, refresh));
  panel.addEventListener('toggle', () => writeSetting(OPEN_KEY, String(panel.open)));
  return panel;
}

/** @param {api.AutoState} s */
function status(s) {
  if (!s.observing || !s.armed)
    return h('span', {class: 'hint'}, '· off');
  return h('span', {class: 'state recording'}, s.recording === null ? '· armed'
    : `· recording session ${s.recording}${s.waiting ? ', waiting for an operation' : ''}`);
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
    h('span', null, ...what, who),
    h('span', {class: 'label'}, 'Repeat'),
    h('span', null, s.repeat ? 'yes, a new session each time' : 'no, only the first session'),
    h('span', {class: 'label'}, 'Stops when'),
    h('span', null, `no matching operation for ${s.idle} seconds, or after ${s.max} s in all`),
    s.name ? [h('span', {class: 'label'}, 'Named'), h('span', null, s.name)] : null,
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
  // not kept: a name is for one arming
  const name = h('input', {type: 'text', id: 'auto-name', class: 'wide', placeholder: 'optional, e.g. slow login of user X'});

  function arm() {
    /** @type {api.AutoArming} */
    const a = {
      patterns: patterns.value, browser: browser.checked, repeat: repeat.checked,
      idle: Number(idle.value), max: Number(max.value),
    };
    writeArming(a);
    act(() => api.armAuto({...a, name: name.value}), refresh);
  }

  return h('form', {class: 'form-grid', onsubmit: (/** @type {Event} */ e) => { e.preventDefault(); arm(); }},
    h('label', {class: 'label', for: 'auto-patterns'}, 'Operations'),
    h('div', null, patterns,
      h('div', {class: 'hint'}, 'Separated by commas; * matches anything; empty for any operation.',
        state.fed ? ' The JVM tells of an operation only once it ends: the session records from the moment it is ' +
                    'armed, until one has ended.' : null)),
    state.flakApps
      ? [h('span', {class: 'label'}, 'From'),
         h('div', null, h('label', {title: 'A cookie marks this browser'},
           browser, ' this browser only: requests from other clients are ignored'))]
      : null,
    h('span', {class: 'label'}, 'Repeat'),
    h('div', null, h('label', null, repeat, ' start a new session every time the operation starts')),
    h('span', {class: 'label'}, 'Stops when'),
    h('div', {class: 'inline'},
      h('label', {title: 'The wait leaves time for asynchronous work to end'},
        'no matching operation for ', idle, ' seconds'),
      h('label', null, 'or after ', max, ' seconds')),
    h('label', {class: 'label', for: 'auto-name'}, 'Name'),
    h('div', null, name),
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

/** @returns {Partial<api.AutoArming>} the last arming of this browser, to arm alike */
export function readArming() {
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
