// @ts-check
/**
 * In standalone mode, the application profiled, under the top bar: its JVM
 * attached, or those to attach to.
 */
import * as api from '../api.js';
import {h, replace} from '../dom.js';

/** How often the JVM attached is checked, in ms: it may end. */
const POLL = 5000;

/**
 * Shows the bar if the profiler profiles another JVM, and keeps it up to date.
 *
 * @param {Element} after where to put it
 */
export async function showTarget(after) {
  const target = await api.getTarget();
  if (!target)
    return;
  const bar = h('div', {class: 'target-bar'});
  after.after(bar);

  /** @param {api.Target} t */
  function draw(t) {
    replace(bar,
      h('a', {href: '../../'}, '← JVMs'),
      h('strong', null, t.app),
      t.pid !== null
        ? [h('span', {class: 'hint', title: t.name ?? ''}, `connected to JVM ${t.pid}`),
           h('button', {class: 'small', onclick: detach}, 'Disconnect')]
        : [h('span', {class: 'hint'}, t.running.length ? 'not connected:' : 'no JVM running, sessions only'),
           ...t.running.map(j => h('button', {class: 'small', title: j.name,
                                               onclick: () => attach(j.pid)}, `Connect to ${j.pid}`))]);
  }

  async function detach() {
    try {
      await api.detachJvm('../../');
      const t = await api.getTarget();
      if (t)
        draw(t);
    }
    catch (e) {
      alert(e instanceof Error ? e.message : String(e));
    }
  }

  /** @param {number} pid */
  async function attach(pid) {
    try {
      await api.attachJvm(pid, '../../');
      location.reload();
    }
    catch (e) {
      alert(e instanceof Error ? e.message : String(e));
    }
  }

  draw(target);
  window.setInterval(async () => {
    const t = await api.getTarget().catch(() => null);
    if (t)
      draw(t);
  }, POLL);
}
