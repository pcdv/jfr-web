// From web-lib (https://github.com/pcdv/web-lib), src/dom.ts: changed there, then synced here.
/**
 * A few helpers to build the DOM, in place of a framework.
 */
export type Child = Node | string | number | null | undefined | false;
type Children = (Child | Child[])[];
type Tag = keyof HTMLElementTagNameMap;
/**
 * The element of the page with this id, which must be of a type.
 *
 * @param type e.g. HTMLInputElement
 */
export declare function $<T extends HTMLElement>(id: string, type: {
    new (): T;
    prototype: T;
}): T;
/**
 * Creates an element. Attributes named "on..." take a listener; true and
 * false add or omit a boolean attribute.
 */
export declare function h<K extends Tag>(tag: K, attrs?: Record<string, any> | null, ...children: Children): HTMLElementTagNameMap[K];
/** Replaces the content of an element. */
export declare function replace(el: Element, ...children: Children): void;
export {};
