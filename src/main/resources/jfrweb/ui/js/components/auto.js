// @ts-check
/**
 * The auto mode: sessions that start by themselves when matching requests
 * come, and stop once they are over.
 */
import * as api from '../api.js';
import {h, replace} from '../dom.js';

/** Last arming, kept per browser to arm again in one click. */
const ARMING_KEY = 'jfrweb.auto.arming';

/**
 * @param {api.AutoState} state
 * @param {() => void} refresh called once the state changed
 */
export function autoPanel(state, refresh) {
  return h('div', {class: 'card auto'},
    h('h3', null, 'Auto mode'),
    !state.observing
      ? h('p', {class: 'empty'}, 'No app is observed, so no request can start a session. Pass the flak apps to the profiler: ',
          h('code', null, 'Profiler.builder().observe(app)'), '.')
      : state.armed ? armed(state, refresh) : form(refresh));
}

/**
 * @param {api.AutoState} s
 * @param {() => void} refresh
 */
function armed(s, refresh) {
  const what = s.patterns && s.patterns.length
    ? [h('span', {class: 'mono'}, s.patterns.join(', '))]
    : ['any request'];
  const who = s.browser ? (s.thisBrowser ? ' from this browser' : ' from another browser') : '';
  return h('div', null,
    h('p', null,
      h('span', {class: 'state recording'}, s.recording !== null ? `recording session ${s.recording}` : 'armed'),
      ' · starts a session on ', ...what, who, s.repeat ? ', every time' : ', once',
      `; stops after ${s.idle} s without one, or after ${s.max} s.`),
    h('button', {onclick: () => act(api.disarmAuto, refresh)}, 'Disarm'));
}

/** @param {() => void} refresh */
function form(refresh) {
  /** @type {api.AutoArming} */
  const last = {patterns: '', browser: true, repeat: false, idle: 3, max: 120, ...readArming()};

  const patterns = h('input', {type: 'text', size: 40, value: last.patterns,
                               placeholder: 'any endpoint, or e.g. GET /api/repository*, * /api/env'});
  const browser = h('input', {type: 'checkbox', checked: last.browser});
  const repeat = h('input', {type: 'checkbox', checked: last.repeat});
  const idle = h('input', {type: 'number', min: 0.5, step: 'any', value: last.idle});
  const max = h('input', {type: 'number', min: 1, step: 'any', value: last.max});

  function arm() {
    /** @type {api.AutoArming} */
    const a = {
      patterns: patterns.value, browser: browser.checked, repeat: repeat.checked,
      idle: Number(idle.value), max: Number(max.value),
    };
    writeArming(a);
    act(() => api.armAuto(a), refresh);
  }

  return h('form', {class: 'filters', onsubmit: (/** @type {Event} */ e) => { e.preventDefault(); arm(); }},
    h('label', {title: 'Separated by commas; * matches anything; empty for any endpoint'}, 'Endpoints ', patterns),
    h('label', {title: 'A cookie marks this browser: the requests of other clients do not count'},
      browser, ' only this browser'),
    h('label', null, repeat, ' every time'),
    h('label', {title: 'A session stops once no matching request has run for that long'}, 'Idle ', idle, ' s'),
    h('label', null, 'Max ', max, ' s'),
    h('button', {class: 'primary', type: 'submit'}, 'Arm'));
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
    return JSON.parse(localStorage.getItem(ARMING_KEY) || '{}');
  }
  catch (e) {
    return {};
  }
}

/** @param {api.AutoArming} a */
function writeArming(a) {
  try {
    localStorage.setItem(ARMING_KEY, JSON.stringify(a));
  }
  catch (e) {
    // only a convenience
  }
}
