// @ts-check
/**
 * The name the user gives a session, to find it again among the others.
 */
import * as api from '../api.js';
import {h} from '../dom.js';

/**
 * Asks for a name.
 *
 * @param {string} question
 * @param {string} [current]
 * @returns {string | null} the name, empty for none; null if cancelled
 */
export function askName(question, current = '') {
  const name = prompt(question, current);
  return name === null ? null : name.trim();
}

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
      const name = askName(`Name of session ${s.id}, empty for none:`, s.name ?? '');
      if (name === null)
        return;
      try {
        onRenamed(await api.renameSession(s.id, name));
      }
      catch (e) {
        alert(e instanceof Error ? e.message : String(e));
      }
    },
  }, s.name ? 'Rename' : 'Name');
}
