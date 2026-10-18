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
import {$} from './lib/dom.js';
import {startRouter} from './lib/router.js';

/** @type {(import('./lib/router.js').Route & {title: (params: string[]) => string})[]} */
const routes = [
  {hash: /^#\/sessions\/(\d+)$/, view: renderSession, title: p => `Session ${p[0]}`},
  {hash: /^#\/live$/, view: renderLive, title: () => 'Live operations'},
  {hash: /^#\/history$/, view: renderHistory, title: () => 'History of operations'},
  {hash: /^(?:#\/?)?$/, view: renderSessions, title: () => 'Profiling'},
];

/** The application profiled, in the title of the page: tabs tell them apart. */
let appName = 'jfr-web';

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
  startRouter($('app', HTMLElement), routes, {
    fallback: '#/',
    onRoute: (r, params) => {
      document.title = `${r.title(params)} · ${appName}`;
      markActive();
    },
  });
});
