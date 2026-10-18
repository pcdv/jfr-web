// @ts-check
/**
 * Routes the hash of the URL to a view, which renders itself into the root.
 *
 * When another view replaces it, the root is emptied: its elements go, and
 * the listeners on them with them. Its signal is aborted then: the view stops
 * what would outlive its elements (a timer, a listener on window…) and drops
 * the answers still coming.
 *
 * Part of a small lib kept as identical copies in jfr-web and jfr-soundscape:
 * change both.
 */

/**
 * @typedef {(root: HTMLElement, params: string[], signal: AbortSignal) => void} View
 * the params are the groups of the hash matched
 */

/**
 * @typedef {object} Route
 * @property {RegExp} hash matched against location.hash, e.g. /^#\/sessions\/(\d+)$/
 * @property {View} view
 */

/**
 * Shows the view of the hash now, then of each hash it changes to.
 *
 * @template {Route} R a route, with what the application tells of it, e.g. a title
 * @param {HTMLElement} root
 * @param {R[]} routes the first one matching wins
 * @param {object} options
 * @param {string} options.fallback the hash gone to when none matches, e.g. "#/"
 * @param {(route: R, params: string[]) => void} [options.onRoute] called before a view renders
 */
export function startRouter(root, routes, {fallback, onRoute}) {
  /** @type {AbortController | undefined} */
  let current;

  function route() {
    current?.abort();
    current = undefined;
    root.replaceChildren();
    for (const r of routes) {
      const m = location.hash.match(r.hash);
      if (m) {
        const params = m.slice(1);
        onRoute?.(r, params);
        current = new AbortController();
        r.view(root, params, current.signal);
        return;
      }
    }
    location.hash = fallback;
  }

  window.addEventListener('hashchange', route);
  route();
}
