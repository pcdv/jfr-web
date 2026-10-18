// @ts-check
/**
 * Draws a flame graph on a canvas: a frame is as wide as its time, its
 * callees on top of it; inverted for hot methods, the methods running at the
 * top and their callers below.
 *
 * A click zooms on a frame: it fills the width, the frames under it stay,
 * faded, and a click on one of them zooms back out. Hovering a frame tells
 * its times. A search colors the frames whose name matches, and tells their
 * share of the time.
 */
import {h, replace} from '../lib/dom.js';
import * as fmt from '../format.js';
import {mainType, COMPILED, INLINED, INTERPRETED, NATIVE, SYNTHETIC} from './profile.js';

/** @typedef {import('./profile.js').Frame} Frame */

/** Height of a level, in CSS pixels. */
const ROW = 18;
const FONT = '12px ui-monospace, "Cascadia Mono", Consolas, monospace';

/** Names of the frame types, by type, and the CSS colors they are drawn in. */
const TYPES = [
  {type: COMPILED, name: 'compiled', color: '--flame-compiled'},
  {type: INLINED, name: 'inlined', color: '--flame-inlined'},
  {type: INTERPRETED, name: 'interpreted', color: '--flame-interpreted'},
  {type: NATIVE, name: 'native', color: '--flame-native'},
  {type: SYNTHETIC, name: '', color: '--flame-synthetic'},
];

/**
 * A frame laid out.
 *
 * @typedef {object} Box
 * @property {Frame} frame
 * @property {Box | null} parent
 * @property {number} level 0 for the root
 * @property {number} left in ms from the left of the root
 */

/**
 * @typedef {object} FlameGraph
 * @property {HTMLElement} element
 * @property {(root: Frame, inverted: boolean) => void} show draws a tree, staying zoomed on
 *           the same frame if the tree still has it
 */

/**
 * @param {AbortSignal} signal stops following the width of the page
 * @returns {FlameGraph}
 */
export function flameGraph(signal) {
  const canvas = h('canvas', {class: 'flame-canvas', tabindex: 0});
  const tooltip = h('div', {class: 'tooltip', hidden: true});
  const wrapper = h('div', {class: 'flame-wrapper'}, canvas, tooltip);
  const search = h('input', {type: 'search', placeholder: 'Search, e.g. HashMap|parse', 'aria-label': 'Search frames'});
  const matched = h('span', {class: 'flame-matched'});
  const reset = h('button', {type: 'button', class: 'small', hidden: true, onclick: () => zoomOn(null)}, 'Reset zoom');
  const toolbar = h('div', {class: 'flame-toolbar'}, search, matched, reset);

  /** @type {Box[][]} the boxes of each level, from the left */
  let levels = [];
  /** @type {Box | null} */
  let root = null;
  /** @type {Box | null} the frame filling the width, null for the root */
  let zoomed = null;
  /** @type {Box | null} */
  let hovered = null;
  let inverted = false;
  /** @type {RegExp | null} */
  let pattern = null;

  /** @param {Frame} frame */
  function layout(frame) {
    levels = [];
    /**
     * @param {Frame} f
     * @param {Box | null} parent
     * @param {number} level
     * @param {number} left
     * @returns {Box}
     */
    function place(f, parent, level, left) {
      const box = {frame: f, parent, level, left};
      (levels[level] ??= []).push(box);
      let x = left;
      for (const c of f.children) {
        place(c, box, level + 1, x);
        x += c.total;
      }
      return box;
    }
    return place(frame, null, 0, 0);
  }

  /** The visible range, in ms from the left of the root. */
  function range() {
    const z = zoomed ?? root;
    return z ? {x0: z.left, span: z.frame.total || 1} : {x0: 0, span: 1};
  }

  /** @param {number} level */
  function top(level) {
    return inverted ? level * ROW : (levels.length - 1 - level) * ROW;
  }

  function draw() {
    const width = wrapper.clientWidth;
    const height = levels.length * ROW;
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.round(width * dpr);
    canvas.height = Math.round(height * dpr);
    canvas.style.height = `${height}px`;
    const ctx = canvas.getContext('2d');
    if (!ctx || !root)
      return;
    ctx.scale(dpr, dpr);
    ctx.font = FONT;
    ctx.textBaseline = 'middle';
    const charWidth = ctx.measureText('m').width;

    const style = getComputedStyle(canvas);
    const colors = TYPES.map(t => hex(style.getPropertyValue(t.color)));
    const match = style.getPropertyValue('--flame-match').trim();
    const text = style.getPropertyValue('--flame-text').trim();
    const outline = style.getPropertyValue('--fg').trim();

    const {x0, span} = range();
    const scale = width / span;
    const zoomLevel = zoomed ? zoomed.level : 0;
    for (const level of levels)
      for (const b of level) {
        const x = (b.left - x0) * scale;
        const w = b.frame.total * scale;
        if (w < 0.5 || x + w <= 0 || x >= width)
          continue;
        const left = Math.max(x, 0);
        const right = Math.min(x + w, width);
        const y = top(b.level);
        // the frames under the one zoomed on, which zoom back out
        ctx.globalAlpha = b.level < zoomLevel ? 0.5 : 1;
        ctx.fillStyle = pattern && pattern.test(b.frame.name) ? match : shade(colors[mainType(b.frame)], b.frame.name);
        ctx.fillRect(left, y, Math.max(right - left - 1, 0.5), ROW - 1);
        const chars = Math.floor((right - left - 6) / charWidth);
        if (chars >= 3) {
          ctx.fillStyle = text;
          const name = b.frame.name;
          ctx.fillText(name.length <= chars ? name : name.substring(0, chars - 1) + '…', left + 3, y + ROW / 2);
        }
      }
    ctx.globalAlpha = 1;
    if (hovered) {
      const x = Math.max((hovered.left - x0) * scale, 0);
      const w = Math.min(hovered.frame.total * scale, width - x);
      ctx.strokeStyle = outline;
      ctx.lineWidth = 1;
      ctx.strokeRect(x + 0.5, top(hovered.level) + 0.5, Math.max(w - 2, 1), ROW - 2);
    }
  }

  /**
   * @param {MouseEvent} e
   * @returns {Box | null} the box under the pointer
   */
  function boxAt(e) {
    const r = canvas.getBoundingClientRect();
    const y = e.clientY - r.top;
    const row = Math.floor(y / ROW);
    const level = inverted ? row : levels.length - 1 - row;
    if (level < 0 || level >= levels.length)
      return null;
    const {x0, span} = range();
    const ms = x0 + (e.clientX - r.left) / r.width * span;
    for (const b of levels[level])
      if (b.left <= ms && ms < b.left + b.frame.total)
        return b;
    return null;
  }

  /** @param {Box | null} box null for the root */
  function zoomOn(box) {
    zoomed = box && box !== root ? box : null;
    reset.hidden = !zoomed;
    draw();
  }

  /**
   * @param {Box} b
   * @param {MouseEvent} e
   */
  function showTooltip(b, e) {
    const f = b.frame;
    const all = root ? root.frame.total : f.total;
    const share = (/** @type {number} */ ms) => `${fmt.duration(ms)} · ${percent(ms / all)}`;
    const types = TYPES.filter(t => t.name && f.types[t.type] > 0);
    const style = getComputedStyle(canvas);
    replace(tooltip,
      h('div', {class: 'tooltip-title flame-tooltip-name'}, f.name),
      row(null, 'Total', share(f.total)),
      row(null, 'Self', share(f.self)),
      zoomed && b.level >= zoomed.level && zoomed !== b
        ? row(null, 'Of the zoomed frame', percent(f.total / zoomed.frame.total))
        : null,
      types.length > 1 || (types.length === 1 && types[0].type !== COMPILED)
        ? types.map(t => row(style.getPropertyValue(t.color), t.name, percent(f.types[t.type] / f.total)))
        : null,
      h('div', {class: 'flame-tooltip-hint'},
        zoomed && b.level < zoomed.level ? 'Click to zoom out' : b === zoomed || b === root ? '' : 'Click to zoom in'));
    tooltip.hidden = false;
    const r = wrapper.getBoundingClientRect();
    const left = e.clientX - r.left;
    tooltip.style.left = left < r.width / 2 ? `${left + 16}px` : '';
    tooltip.style.right = left < r.width / 2 ? '' : `${r.width - left + 16}px`;
    tooltip.style.top = `${e.clientY - r.top + 16}px`;
  }

  canvas.addEventListener('mousemove', e => {
    const b = boxAt(e);
    if (b !== hovered) {
      hovered = b;
      draw();
    }
    if (b)
      showTooltip(b, e);
    else
      tooltip.hidden = true;
  });
  canvas.addEventListener('mouseleave', () => {
    hovered = null;
    tooltip.hidden = true;
    draw();
  });
  canvas.addEventListener('click', e => {
    const b = boxAt(e);
    if (b)
      zoomOn(b);
  });
  canvas.addEventListener('keydown', e => {
    if (e.key === 'Escape')
      zoomOn(null);
  });

  search.addEventListener('input', () => {
    try {
      pattern = search.value ? new RegExp(search.value) : null;
      search.classList.remove('invalid');
    }
    catch (e) {
      pattern = null;
      search.classList.add('invalid');
    }
    showMatched();
    draw();
  });

  /** The share of the time in frames matching, each counted once: not again under a match. */
  function showMatched() {
    if (!pattern || !root) {
      matched.textContent = '';
      return;
    }
    const p = pattern;
    /** @type {(f: Frame) => number} */
    const sum = f => p.test(f.name) ? f.total : f.children.reduce((s, c) => s + sum(c), 0);
    const ms = sum(root.frame);
    matched.textContent = `Matched: ${percent(ms / (root.frame.total || 1))}, ${fmt.duration(ms)}`;
  }

  const resized = new ResizeObserver(() => draw());
  resized.observe(wrapper);
  signal.addEventListener('abort', () => resized.disconnect());

  return {
    element: h('div', {class: 'flame-graph'}, toolbar, wrapper),
    show(frame, inv) {
      // the path of the frame zoomed on, by name, to zoom on it again
      const path = [];
      for (let b = zoomed; b; b = b.parent)
        path.unshift(b.frame.name);
      inverted = inv;
      const r = root = layout(frame);
      hovered = null;
      tooltip.hidden = true;
      /** @type {Box} */
      let z = r;
      for (const name of path.slice(1)) {
        const parent = z;
        /** @type {Box | undefined} */
        const next = levels[z.level + 1]?.find(b => b.parent === parent && b.frame.name === name);
        if (!next)
          break;
        z = next;
      }
      zoomOn(path.length > 1 && z.level === path.length - 1 ? z : null);
      showMatched();
    },
  };
}

/**
 * @param {string | null} color of a swatch, none if null
 * @param {string} name
 * @param {string} value
 */
function row(color, name, value) {
  return h('div', {class: 'tooltip-row'},
    color ? h('span', {class: 'swatch', style: `background: ${color}`}) : h('span'),
    h('span', null, name),
    h('span', {class: 'tooltip-value'}, value));
}

/** @param {number} ratio */
function percent(ratio) {
  return `${(ratio * 100).toFixed(ratio < 0.1 ? 2 : 1)}%`;
}

/**
 * @param {string} color e.g. "#7cc47f"
 * @returns {number[]} its red, green and blue
 */
function hex(color) {
  const c = color.trim();
  return [1, 3, 5].map(i => parseInt(c.substring(i, i + 2), 16) || 0);
}

/**
 * A color a little lighter, by how much the name says: neighbours of one type
 * stay apart, and a method keeps its shade from one graph to the next.
 *
 * @param {number[]} rgb
 * @param {string} name
 */
function shade([r, g, b], name) {
  let hash = 0;
  for (let i = 0; i < name.length; i++)
    hash = (hash * 31 + name.charCodeAt(i)) | 0;
  const k = ((hash >>> 0) % 100) / 100 * 0.25;
  return `rgb(${Math.round(r + (255 - r) * k)}, ${Math.round(g + (255 - g) * k)}, ${Math.round(b + (255 - b) * k)})`;
}
