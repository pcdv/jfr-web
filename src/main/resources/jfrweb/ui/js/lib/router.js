// From web-lib (https://github.com/pcdv/web-lib), src/router.ts: changed there, then synced here.
// @ts-nocheck: its types are in router.d.ts
/**
 * Routes the hash of the URL to a view, which renders itself into the root.
 *
 * When another view replaces it, the root is emptied: its elements go, and
 * the listeners on them with them. Its signal is aborted then: the view stops
 * what would outlive its elements (a timer, a listener on window…) and drops
 * the answers still coming.
 */

/**
 * Shows the view of the hash now, then of each hash it changes to.
 *
 * @param routes the first one matching wins; a route can carry what the
 * application tells of it, e.g. a title
 */
export function startRouter(root, routes, {fallback, onRoute}) {
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
