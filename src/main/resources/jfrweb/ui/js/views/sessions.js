// @ts-check
/**
 * The sessions: start one, stop the one recording, open a report.
 */
import * as api from '../api.js';
import {h, replace, errorBox} from '../dom.js';
import * as fmt from '../format.js';
import {autoPanel} from '../components/auto.js';
import {renameButton} from '../components/sessionName.js';

/** How often the list refreshes while a session records or auto mode is armed, in ms. */
const POLL = 1000;

/**
 * @param {HTMLElement} root
 * @returns {() => void}
 */
export function renderSessions(root) {
  const auto = h('div');
  const content = h('div');
  root.append(h('h1', null, 'Sessions'), auto, content);

  /** @type {number | undefined} */
  let timer;
  let stopped = false;
  /** The auto mode shown, redrawn only when it changes: its form may be in use. */
  let autoShown = '';

  async function refresh() {
    clearTimeout(timer);
    try {
      const [sessions, autoState, snapshots] = await Promise.all([api.listSessions(), api.getAuto(), api.getSnapshots()]);
      if (stopped)
        return;
      const autoJson = JSON.stringify(autoState);
      // a static export only shows what was recorded
      if (autoJson !== autoShown && !api.isStatic()) {
        autoShown = autoJson;
        replace(auto, autoPanel(autoState, refresh));
      }
      replace(content, api.isStatic() ? null : toolbar(sessions, snapshots, refresh), table(sessions, refresh));
      // snapshots may be taken by themselves at any time
      if (autoState.armed || snapshots.available || sessions.some(s => s.state === 'RECORDING'))
        timer = window.setTimeout(refresh, autoState.armed || !snapshots.available ? POLL : 5 * POLL);
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
 * @param {api.Session[]} sessions
 * @param {api.SnapshotState} snapshots
 * @param {() => void} refresh
 */
function toolbar(sessions, snapshots, refresh) {
  const recording = sessions.some(s => s.state === 'RECORDING');
  const minutes = snapshots.window ? Math.round(snapshots.window / 60) : 0;
  return h('div', {class: 'toolbar'},
    h('button', {
      class: 'primary',
      disabled: recording,
      title: recording ? 'A session is already recording' : null,
      onclick: () => act(() => api.startSession(), refresh),
    }, 'Start a session'),
    snapshots.available
      ? [' ', h('button', {
          title: 'A rolling recording keeps the last minutes: save them, e.g. right after a problem. ' +
                 'Snapshots are also saved by themselves when an operation is much slower than usual, or errors burst.',
          onclick: () => act(() => api.saveSnapshot(), refresh),
        }, `Save the last ${minutes} minutes`)]
      : null);
}

/**
 * @param {api.Session[]} sessions
 * @param {() => void} refresh
 */
function table(sessions, refresh) {
  if (sessions.length === 0)
    return h('p', {class: 'empty'}, 'No session yet. Start one, use the application, then stop it.');

  return h('table', {class: 'grid'},
    h('thead', null, h('tr', null,
      h('th', {class: 'num'}, '#'), h('th', null, 'Name'), h('th', null, 'State'), h('th', null, 'Started'),
      h('th', {class: 'num'}, 'Duration'), h('th', {class: 'num'}, 'Size'),
      h('th', null, 'Trigger'), h('th', null, ''))),
    h('tbody', null, sessions.map(s => h('tr', null,
      h('td', {class: 'num'}, s.id),
      h('td', null, s.name ?? h('span', {class: 'hint'}, '—')),
      h('td', null, h('span', {class: `state ${s.state.toLowerCase()}`}, s.state.toLowerCase())),
      h('td', null, fmt.time(s.start)),
      h('td', {class: 'num'}, fmt.duration(s.duration)),
      h('td', {class: 'num'}, s.state === 'STOPPED' ? fmt.bytes(s.size) : ''),
      h('td', null, s.trigger),
      h('td', {class: 'actions'}, s.state === 'RECORDING'
        ? h('button', {onclick: () => act(() => api.stopSession(s.id), refresh)}, 'Stop')
        : [h('a', {href: `#/sessions/${s.id}`}, 'Report'), ' ',
           h('a', {href: api.downloadUrl(s.id), download: true}, 'Download .jfr'),
           api.isStatic() ? null : renameButton(s, refresh)])))));
}

/**
 * Runs an action, then refreshes, or reports the failure.
 *
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
