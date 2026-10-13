// @ts-check
/**
 * A few helpers to build the DOM, in place of a framework.
 */

/** @typedef {Node | string | number | null | undefined | false} Child */

/**
 * Creates an element. Attributes named "on..." take a listener; "class" a
 * class name; true and false add or omit a boolean attribute.
 *
 * @template {keyof HTMLElementTagNameMap} K
 * @param {K} tag
 * @param {Record<string, any> | null} [attrs]
 * @param {...(Child | Child[])} children
 * @returns {HTMLElementTagNameMap[K]}
 */
export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [name, value] of Object.entries(attrs || {})) {
    if (name.startsWith('on') && typeof value === 'function')
      el.addEventListener(name.substring(2), value);
    else if (value === true)
      el.setAttribute(name, '');
    else if (value !== false && value != null)
      el.setAttribute(name, String(value));
  }
  append(el, children);
  return el;
}

/**
 * @param {Node} parent
 * @param {(Child | Child[])[]} children
 */
function append(parent, children) {
  for (const c of children) {
    if (Array.isArray(c))
      append(parent, c);
    else if (c instanceof Node)
      parent.appendChild(c);
    else if (c !== null && c !== undefined && c !== false)
      parent.appendChild(document.createTextNode(String(c)));
  }
}

/**
 * Replaces the content of an element.
 *
 * @param {Element} el
 * @param {...(Child | Child[])} children
 */
export function replace(el, ...children) {
  el.replaceChildren();
  append(el, children);
}

/**
 * An error message, in place of a view that could not load.
 *
 * @param {unknown} e
 */
export function errorBox(e) {
  return h('div', {class: 'error'}, e instanceof Error ? e.message : String(e));
}
