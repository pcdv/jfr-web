// @ts-check
/**
 * The name the user gives a session, to find it again among the others.
 */
import * as api from '../api.js';
import {ask, tell} from '../lib/dialog.js';
import {h} from '../lib/dom.js';

/**
 * A button that renames a session.
 *
 * @param {api.Session} s
 * @param {(s: api.Session) => void} onRenamed
 */
export function renameButton(s, onRenamed) {
  return h('button', {
    class: 'link',
    title: 'Name this session, to find it again',
    onclick: async () => {
      const name = await ask(`Name of session ${s.id}, empty for none:`, s.name ?? '');
      if (name === null)
        return;
      try {
        onRenamed(await api.renameSession(s.id, name.trim()));
      }
      catch (e) {
        tell(e instanceof Error ? e.message : String(e));
      }
    },
  }, s.name ? 'Rename' : 'Name');
}
