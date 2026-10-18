// @ts-check
/**
 * A few helpers to build the DOM, in place of a framework.
 *
 * Part of a small lib kept as identical copies in jfr-web and jfr-soundscape:
 * change both.
 */

/** @typedef {Node | string | number | null | undefined | false} Child */

/**
 * The element of the page with this id, which must be of a type.
 *
 * @template {HTMLElement} T
 * @param {string} id
 * @param {{new (): T, prototype: T}} type e.g. HTMLInputElement
 * @returns {T}
 */
export function $(id, type) {
  const el = document.getElementById(id);
  if (!(el instanceof type))
    throw new Error(`#${id} is not a ${type.name}`);
  return el;
}

/**
 * Creates an element. Attributes named "on..." take a listener; true and
 * false add or omit a boolean attribute.
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
