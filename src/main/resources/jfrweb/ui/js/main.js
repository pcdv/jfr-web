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
      markActive();
      stopCurrent = view(root, m.slice(1));
      return;
    }
  }
  location.hash = '#/';
}

/**
 * Outlines the link of the page shown in the top bar: a session's report is
 * under Sessions.
 */
function markActive() {
  const hash = location.hash.startsWith('#/sessions/') || location.hash === '' ? '#/' : location.hash;
  for (const a of document.querySelectorAll('header nav a'))
    a.classList.toggle('active', a.getAttribute('href') === hash);
}

/**
 * Recordings with no JVM behind them, a static export or files opened, leave
 * out the pages they have nothing for; a static export says so.
 */
function staticMode() {
  if (!api.recordingsOnly())
    return;
  const history = api.isStatic() ? api.getStaticHistory() : api.getAbout()?.history;
  for (const a of document.querySelectorAll('header nav a')) {
    const href = a.getAttribute('href');
    if (href === '#/live' || (href === '#/history' && !history))
      a.remove();
  }
  if (!api.isStatic())
    return;
  const note = document.createElement('p');
  note.className = 'static-note';
  note.textContent = 'Static export, limited compared to a live run: no interval to select or zoom in on, ' +
                     'one flame graph filter at a time, no live statistics, auto mode or snapshots. ' +
                     'Run jfr-web on the recordings for everything.';
  document.querySelector('header')?.after(note);
}

api.ready.then(() => {
  staticMode();
  appName = api.getAbout()?.app ?? appName;
  const header = document.querySelector('header');
  if (header)
    showTarget(header);
  window.addEventListener('hashchange', route);
  route();
});
