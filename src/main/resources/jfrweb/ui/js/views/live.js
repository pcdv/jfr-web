// @ts-check
/**
 * The endpoints of the apps observed, over the last minutes, kept by counters
 * whether a session records or not.
 */
import * as api from '../api.js';
import {h, replace, errorBox} from '../dom.js';
import {endpointsTable} from '../components/endpoints.js';
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
  const content = h('div');
  root.append(h('h1', null, 'Live endpoints'), toolbar, content);

  /** @type {number | undefined} */
  let timer;
  let stopped = false;

  async function refresh() {
    clearTimeout(timer);
    try {
      const live = await api.getLive(window_);
      if (stopped)
        return;
      replace(toolbar, windowPicker(live, w => {
        window_ = w;
        writeSetting(WINDOW_KEY, String(w));
        refresh();
      }));
      replace(content, live.observing
        ? endpointsTable(live.endpoints, undefined, `No request in the last ${plural(live.window, 'minute')}.`)
        : h('p', {class: 'empty'},
            'No app is observed. Pass the flak apps to observe to the profiler: ',
            h('code', null, 'Profiler.builder().observe(app)'), '.'));
      timer = window.setTimeout(refresh, POLL);
    }
    catch (e) {
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
