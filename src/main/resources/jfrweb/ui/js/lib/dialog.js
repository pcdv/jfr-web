// From web-lib (https://github.com/pcdv/web-lib), src/dialog.ts: changed there, then synced here.
// @ts-nocheck: its types are in dialog.d.ts
/**
 * Questions and messages in a dialog of the page, in place of prompt() and
 * alert(): a sandboxed frame blocks those, e.g. the browser inside VS Code,
 * where prompt() returns null at once as if cancelled.
 *
 * The dialog goes at the end of the body, out of the views: a view redrawn
 * meanwhile does not take it away.
 */
import {h} from './dom.js';

/**
 * Asks for a line of text.
 *
 * @param value the text proposed
 * @returns the text entered; null if cancelled
 */
export async function ask(question, value = '') {
  const input = h('input', {type: 'text', value});
  const dialog = h('dialog', {class: 'dialog'});
  // OK first in the form: Enter submits with the first submit button
  dialog.append(h('form', {method: 'dialog'},
    h('label', null, question, input),
    h('div', {class: 'dialog-buttons'},
      h('button', {class: 'primary', value: 'ok'}, 'OK'),
      h('button', {type: 'button', onclick: () => dialog.close()}, 'Cancel'))));
  const closed = show(dialog);
  // all selected, as prompt() does: typing replaces it
  input.select();
  return await closed === 'ok' ? input.value : null;
}

/** Shows a message until dismissed. */
export async function tell(message) {
  const dialog = h('dialog', {class: 'dialog'},
    h('form', {method: 'dialog'},
      h('p', null, message),
      h('div', {class: 'dialog-buttons'}, h('button', {class: 'primary', value: 'ok'}, 'OK'))));
  await show(dialog);
}

/**
 * @returns its return value: the value of the button that closed it, empty
 * for Escape or Cancel
 */
function show(dialog) {
  // most browsers close a modal dialog on Escape by themselves, not all
  dialog.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
      e.preventDefault();
      dialog.close();
    }
  });
  document.body.append(dialog);
  dialog.showModal();
  return new Promise(resolve => dialog.addEventListener('close', () => {
    dialog.remove();
    resolve(dialog.returnValue);
  }));
}
