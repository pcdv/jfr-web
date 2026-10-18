// @ts-check
/**
 * The page of jfr-web on its own: the JVMs of this machine, to attach to one
 * and profile it, and the applications whose sessions are kept.
 */
import * as api from '../api.js';
import * as fmt from '../format.js';
import {h, replace} from '../lib/dom.js';
import {errorBox} from '../components/errorBox.js';

/** JVMs come and go: listed again that often, in ms. */
const POLL = 3000;

/** Lists the JVMs again, e.g. once one is disconnected from. */
let refresh = () => {};

/**
 * @param {HTMLElement} root
 */
function render(root) {
  const content = h('div');
  root.append(h('h1', null, 'JVMs of this machine'), content);
  document.title = 'JVMs · jfr-web';

  /** @type {number | undefined} */
  let timer;

  refresh = async () => {
    clearTimeout(timer);
    try {
      const list = await api.listJvms();
      replace(content, jvmTable(list), appTable(list),
        h('p', {class: 'hint'}, 'Sessions are kept in ', h('code', null, list.directory),
          ', a directory per application.'),
        list.mappedEvents.length
          ? h('p', {class: 'hint'}, 'Operations also come from these events of the applications: ',
              ...list.mappedEvents.flatMap((m, i) => [i ? ', ' : '', h('code', null, m.event), ' named ',
                                                      h('code', null, m.name)]), '.')
          : null);
    }
    catch (e) {
      replace(content, errorBox(e));
    }
    timer = window.setTimeout(refresh, POLL);
  };

  refresh();
}

/**
 * The JVMs running, those of an application with sessions first.
 *
 * @param {api.Jvms} list
 */
function jvmTable(list) {
  if (list.jvms.length === 0)
    return h('p', {class: 'empty'}, 'No other JVM of this user runs on this machine.');
  const known = new Set(list.apps.filter(a => a.sessions > 0).map(a => a.app));
  const jvms = [...list.jvms].sort((a, b) =>
    Number(known.has(b.app)) - Number(known.has(a.app)) || a.app.localeCompare(b.app) || a.pid - b.pid);
  return h('table', {class: 'grid'},
    h('thead', null, h('tr', null,
      h('th', {class: 'num'}, 'PID'), h('th', null, 'Application'), h('th', null, 'Command'), h('th', null, ''))),
    h('tbody', null, jvms.map(j => h('tr', null,
      h('td', {class: 'num'}, j.pid),
      h('td', null, j.app),
      h('td', {class: 'mono command', title: j.name}, j.name || h('span', {class: 'hint'}, 'not told')),
      h('td', {class: 'action'}, j.attached
        ? [h('a', {href: `app/${encodeURIComponent(j.app)}/`}, 'Open'), ' ',
           h('button', {class: 'small', onclick: detach}, 'Disconnect')]
        : h('button', {class: 'small', onclick: (/** @type {Event} */ e) => attach(j, e)}, 'Connect'))))));
}

/**
 * Disconnects from the JVM attached, then lists the JVMs again.
 */
async function detach() {
  try {
    await api.detachJvm();
  }
  catch (err) {
    alert(err instanceof Error ? err.message : String(err));
  }
  refresh();
}

/**
 * Attaches to a JVM, then opens the interface of its application.
 *
 * @param {api.Jvm} j
 * @param {Event} e
 */
async function attach(j, e) {
  const button = /** @type {HTMLButtonElement} */ (e.target);
  button.disabled = true;
  button.textContent = 'Connecting…';
  try {
    const r = await api.attachJvm(j.pid);
    location.href = r.path;
  }
  catch (err) {
    button.disabled = false;
    button.textContent = 'Connect';
    alert(err instanceof Error ? err.message : String(err));
  }
}

/**
 * The applications whose sessions are kept, the latest first.
 *
 * @param {api.Jvms} list
 */
function appTable(list) {
  const apps = list.apps.filter(a => a.sessions > 0 || a.pid !== null);
  if (apps.length === 0)
    return null;
  return [
    h('h2', null, 'Sessions by application'),
    h('table', {class: 'grid'},
      h('thead', null, h('tr', null,
        h('th', null, 'Application'), h('th', {class: 'num'}, 'Sessions'), h('th', null, 'Latest'),
        h('th', null, 'Connected'), h('th', null, ''))),
      h('tbody', null, apps.map(a => h('tr', null,
        h('td', null, a.app),
        h('td', {class: 'num'}, a.sessions),
        h('td', null, a.last ? fmt.time(a.last) : ''),
        h('td', null, a.pid !== null ? `JVM ${a.pid}` : h('span', {class: 'hint'}, '—')),
        h('td', {class: 'action'}, h('a', {href: a.path}, 'Open'))))))];
}

render(/** @type {HTMLElement} */ (document.getElementById('app')));
