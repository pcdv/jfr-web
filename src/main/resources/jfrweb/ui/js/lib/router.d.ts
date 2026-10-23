// From web-lib (https://github.com/pcdv/web-lib), src/router.ts: changed there, then synced here.
/**
 * Routes the hash of the URL to a view, which renders itself into the root.
 *
 * When another view replaces it, the root is emptied: its elements go, and
 * the listeners on them with them. Its signal is aborted then: the view stops
 * what would outlive its elements (a timer, a listener on window…) and drops
 * the answers still coming.
 */
/** The params are the groups of the hash matched. */
export type View = (root: HTMLElement, params: string[], signal: AbortSignal) => void;
export interface Route {
    /** matched against location.hash, e.g. /^#\/sessions\/(\d+)$/ */
    hash: RegExp;
    view: View;
}
export interface RouterOptions<R extends Route> {
    /** the hash gone to when none matches, e.g. "#/" */
    fallback: string;
    /** called before a view renders */
    onRoute?: (route: R, params: string[]) => void;
}
/**
 * Shows the view of the hash now, then of each hash it changes to.
 *
 * @param routes the first one matching wins; a route can carry what the
 * application tells of it, e.g. a title
 */
export declare function startRouter<R extends Route>(root: HTMLElement, routes: R[], { fallback, onRoute }: RouterOptions<R>): void;
