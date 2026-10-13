// @ts-check
/**
 * Entry point: routes the hash of the URL to a view.
 *
 *   #/              the sessions
 *   #/sessions/3    the report of session 3
 *   #/live          the endpoints of the apps observed, live
 */
import {renderSessions} from './views/sessions.js';
import {renderSession} from './views/session.js';
import {renderLive} from './views/live.js';

/**
 * A view renders itself into the root, and may return a function that stops
 * whatever it keeps running (e.g. polling) when another view replaces it.
 *
 * @typedef {(root: HTMLElement, params: string[]) => (void | (() => void))} View
 */

/** @type {[RegExp, View][]} */
const routes = [
  [/^#\/sessions\/(\d+)$/, renderSession],
  [/^#\/live$/, renderLive],
  [/^(#\/?)?$/, renderSessions],
];

/** @type {void | (() => void)} */
let stopCurrent;

function route() {
  if (stopCurrent)
    stopCurrent();

  const root = /** @type {HTMLElement} */ (document.getElementById('app'));
  root.replaceChildren();
  for (const [pattern, view] of routes) {
    const m = location.hash.match(pattern);
    if (m) {
      stopCurrent = view(root, m.slice(1));
      return;
    }
  }
  location.hash = '#/';
}

window.addEventListener('hashchange', route);
route();
