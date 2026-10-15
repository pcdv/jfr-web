// @ts-check
/**
 * Entry point: routes the hash of the URL to a view.
 *
 *   #/              the sessions
 *   #/sessions/3    the report of session 3
 *   #/live          the operations of the application, live
 *   #/history       the operation history written to disk
 */
import * as api from './api.js';
import {renderSessions} from './views/sessions.js';
import {renderSession} from './views/session.js';
import {renderLive} from './views/live.js';
import {renderHistory} from './views/history.js';
import {showTarget} from './components/target.js';

/**
 * A view renders itself into the root, and may return a function that stops
 * whatever it keeps running (e.g. polling) when another view replaces it.
 *
 * @typedef {(root: HTMLElement, params: string[]) => (void | (() => void))} View
 */

/** @type {[RegExp, View, (params: string[]) => string][]} */
const routes = [
  [/^#\/sessions\/(\d+)$/, renderSession, p => `Session ${p[0]}`],
  [/^#\/live$/, renderLive, () => 'Live operations'],
  [/^#\/history$/, renderHistory, () => 'History of operations'],
  [/^(#\/?)?$/, renderSessions, () => 'Sessions'],
];

/** The application profiled, in the title of the page: tabs tell them apart. */
let appName = 'jfr-web';

/** @type {void | (() => void)} */
let stopCurrent;

function route() {
  if (stopCurrent)
    stopCurrent();

  const root = /** @type {HTMLElement} */ (document.getElementById('app'));
  root.replaceChildren();
  for (const [pattern, view, title] of routes) {
    const m = location.hash.match(pattern);
    if (m) {
      document.title = `${title(m.slice(1))} · ${appName}`;
      stopCurrent = view(root, m.slice(1));
      return;
    }
  }
  location.hash = '#/';
}

/**
 * A static export says so, and leaves out the pages it has nothing for.
 */
function staticMode() {
  if (!api.isStatic())
    return;
  for (const a of document.querySelectorAll('header nav a')) {
    const href = a.getAttribute('href');
    if (href === '#/live' || (href === '#/history' && !api.getStaticHistory()))
      a.remove();
  }
  const note = document.createElement('p');
  note.className = 'static-note';
  note.textContent = 'Static export, limited compared to a live run: no interval to select or zoom in on, ' +
                     'one flame graph filter at a time, no live statistics, auto mode or snapshots. ' +
                     'Run jfr-web on the recordings for everything.';
  document.querySelector('header')?.after(note);
}

api.ready.then(async () => {
  staticMode();
  appName = (await api.getAbout())?.app ?? appName;
  const header = document.querySelector('header');
  if (header)
    showTarget(header);
  window.addEventListener('hashchange', route);
  route();
});
