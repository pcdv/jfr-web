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
import {mainType, COMPILED, INLINED, INTERPRETED, NATIVE, SYNTHETIC, ALLOCATED} from '../profile/profile.js';
import {heatOf} from './heat.js';
import {readSetting, writeSetting} from '../lib/settings.js';

/** @typedef {import('../profile/profile.js').Frame} Frame */
/** @typedef {import('./heat.js').Heat} Heat */

/** How far the hottest frame goes toward the hot color: not all the way, its name stays readable. */
const MAX_HEAT = 0.85;

/** What the colors of the frames tell, as last chosen. */
const COLORS = 'jfrweb.flame.colors';

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
  {type: ALLOCATED, name: 'allocated', color: '--flame-allocated'},
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
 * @property {HTMLElement} toolbar its search field and zoom reset, in the element unless moved,
 *           e.g. onto the row of a caption
 * @property {(root: Frame, inverted: boolean, unit?: 'ms' | 'bytes') => void} show draws a tree,
 *           staying zoomed on the same frame if the tree still has it; its totals in ms, or in
 *           bytes for allocations
 * @property {(name: string) => void} find searches the frames of a method, by its exact name
 */

/**
 * A frame shown elsewhere, e.g. where its samples fall on a timeline.
 *
 * @typedef {object} FramePath
 * @property {string} name
 * @property {string[]} path the names of the frame and of those under it, from the root's
 *           children up; empty for the root
 */

/**
 * @param {AbortSignal} signal stops following the width of the page
 * @param {object} [options]
 * @param {(frame: FramePath | null) => void} [options.onFrame] told of the frame hovered,
 *        else of the one zoomed on, else of none
 * @param {(name: string) => void} [options.onMethod] called with the method of a frame
 *        right-clicked: to show its callers and callees
 * @param {boolean} [options.search] whether it has a search field, true by default
 * @returns {FlameGraph}
 */
export function flameGraph(signal, {onFrame = () => {}, onMethod, search: searchable = true} = {}) {
  const canvas = h('canvas', {class: 'flame-canvas', tabindex: 0});
  const tooltip = h('div', {class: 'tooltip', hidden: true});
  // as tall as the deepest stack, which scrolls if it is taller than the window
  const scroller = h('div', {class: 'flame-scroll'}, canvas);
  const wrapper = h('div', {class: 'flame-wrapper'}, scroller, tooltip);
  const search = h('input', {type: 'search', placeholder: 'Search, e.g. HashMap|parse', 'aria-label': 'Search frames',
                             hidden: !searchable});
  const matched = h('span', {class: 'flame-matched'});
  const reset = h('button', {type: 'button', class: 'small', hidden: true, onclick: () => zoomOn(null)}, 'Reset zoom');
  const heatBox = h('input', {type: 'checkbox', name: 'heat', checked: readSetting(COLORS) === 'heat'});
  heatBox.addEventListener('change', () => {
    writeSetting(COLORS, heatBox.checked ? 'heat' : 'type');
    draw();
  });
  const heatLabel = h('label', {
    hidden: !searchable,
    title: 'Darkens the methods that run a lot in total, but split in many frames, each too narrow to '
           + 'notice; a frame is the cooler the more of its method it shows itself, so the wide ones '
           + 'are not dark as well',
  }, heatBox, ' Heat');
  const toolbar = h('div', {class: 'flame-toolbar'}, search, matched, heatLabel, reset);

  /** @type {Box[][]} the boxes of each level, from the left */
  let levels = [];
  /** @type {Box | null} */
  let root = null;
  /** @type {Box | null} the frame filling the width, null for the root */
  let zoomed = null;
  /** @type {Box | null} */
  let hovered = null;
  let inverted = false;
  /** @type {'ms' | 'bytes'} what the totals count */
  let unit = 'ms';
  /** @param {number} v */
  const amount = v => fmt.amount(v, unit);
  /** @type {RegExp | null} */
  let pattern = null;
  /** @type {Heat | null} of the tree drawn */
  let heat = null;

  /** @returns {boolean} whether the frames show their heat, not their type */
  function byHeat() {
    return heatBox.checked && searchable;
  }

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

  /**
   * @param {Frame} f
   * @returns {number} in a comparison, its share of the time minus its share in the baseline
   */
  function change(f) {
    const now = root ? f.total / (root.frame.total || 1) : 0;
    const before = root?.frame.base ? (f.base ?? 0) / root.frame.base : 0;
    return now - before;
  }

  /** @param {number} level */
  function top(level) {
    return inverted ? level * ROW : (levels.length - 1 - level) * ROW;
  }

  function draw() {
    const width = scroller.clientWidth;
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
    // compared with a baseline: colored by the change of their share of the time
    const diff = root.frame.base !== undefined;
    const more = hex(style.getPropertyValue('--flame-more'));
    const less = hex(style.getPropertyValue('--flame-less'));
    const same = hex(style.getPropertyValue('--flame-synthetic'));
    // by heat: the color of the type, drawn toward this one the hotter the frame
    const hottest = hex(style.getPropertyValue('--flame-hot'));
    const temperature = byHeat() && !diff ? heat : null;
    let widest = 0.02;
    if (diff)
      for (const level of levels)
        for (const b of level)
          widest = Math.max(widest, Math.abs(change(b.frame)));

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
        const matches = pattern && pattern.test(b.frame.name);
        const base = shade(colors[mainType(b.frame)], b.frame.name);
        const heated = temperature && !matches
          ? mixRgb(base, hottest, temperature.of(b.frame) * MAX_HEAT) : null;
        ctx.fillStyle = matches ? match
          : diff ? mix(same, change(b.frame) > 0 ? more : less, Math.abs(change(b.frame)) / widest)
          : heated ? css(heated)
          : css(base);
        ctx.fillRect(left, y, Math.max(right - left - 1, 0.5), ROW - 1);
        const chars = Math.floor((right - left - 6) / charWidth);
        if (chars >= 3) {
          // the name stays readable on a frame heated dark
          ctx.fillStyle = heated && luminance(heated) < 0.4 ? '#fff' : text;
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
    tell();
  }

  /** @type {Box | null | undefined} the frame told of last, undefined before any */
  let told;

  /** Tells of the frame hovered, else of the one zoomed on, when it changes. */
  function tell() {
    const b = hovered ?? zoomed;
    if (b === told)
      return;
    told = b;
    if (!b) {
      onFrame(null);
      return;
    }
    const path = [];
    for (let x = b; x.parent; x = x.parent)
      path.unshift(x.frame.name);
    onFrame({name: b.frame.name, path});
  }

  /**
   * @param {Box} b
   * @param {MouseEvent} e
   */
  function showTooltip(b, e) {
    const f = b.frame;
    const all = root ? root.frame.total : f.total;
    const share = (/** @type {number} */ v) => `${amount(v)} · ${percent(v / all)}`;
    const types = TYPES.filter(t => t.name && f.types[t.type] > 0);
    const style = getComputedStyle(canvas);
    replace(tooltip,
      h('div', {class: 'tooltip-title flame-tooltip-name'}, f.name),
      row(null, 'Total', share(f.total)),
      row(null, 'Self', share(f.self)),
      f.base !== undefined && root?.frame.base
        ? [row(null, 'In the baseline', `${amount(f.base)} · ${percent(f.base / root.frame.base)}`),
           row(null, 'Change', `${change(f) >= 0 ? '+' : '−'}${(Math.abs(change(f)) * 100).toFixed(1)} points`)]
        : null,
      zoomed && b.level >= zoomed.level && zoomed !== b
        ? row(null, 'Of the zoomed frame', percent(f.total / zoomed.frame.total))
        : null,
      heat && byHeat() && f.base === undefined
        ? [row(null, 'Self, all its frames', share(heat.method(f.name))),
           row(null, 'Self, other frames', percent(heat.elsewhere(f)))]
        : null,
      types.length > 1 || (types.length === 1 && types[0].type !== COMPILED)
        ? types.map(t => row(style.getPropertyValue(t.color), t.name, percent(f.types[t.type] / f.total)))
        : null,
      h('div', {class: 'flame-tooltip-hint'},
        zoomed && b.level < zoomed.level ? 'Click to zoom out' : b === zoomed || b === root ? '' : 'Click to zoom in'),
      onMethod && mainType(f) !== SYNTHETIC
        ? h('div', {class: 'flame-tooltip-hint'}, 'Right-click for its callers and callees')
        : null);
    tooltip.hidden = false;
    const r = wrapper.getBoundingClientRect();
    const left = e.clientX - r.left;
    tooltip.style.left = left < r.width / 2 ? `${left + 16}px` : '';
    tooltip.style.right = left < r.width / 2 ? '' : `${r.width - left + 16}px`;
    // above the pointer near the bottom: the graph's frame would cut it
    const top = e.clientY - r.top;
    const height = tooltip.offsetHeight;
    tooltip.style.top = `${top + 16 + height <= r.height ? top + 16 : Math.max(0, top - 8 - height)}px`;
  }

  canvas.addEventListener('mousemove', e => {
    const b = boxAt(e);
    if (b !== hovered) {
      hovered = b;
      draw();
      tell();
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
    tell();
  });
  canvas.addEventListener('click', e => {
    const b = boxAt(e);
    if (b)
      zoomOn(b);
  });
  canvas.addEventListener('contextmenu', e => {
    const b = boxAt(e);
    if (!onMethod || !b || mainType(b.frame) === SYNTHETIC)
      return;
    e.preventDefault();
    onMethod(b.frame.name);
  });
  canvas.addEventListener('keydown', e => {
    if (e.key === 'Escape')
      zoomOn(null);
  });

  search.addEventListener('input', searchChanged);

  function searchChanged() {
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
  }

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
    matched.textContent = `Matched: ${percent(ms / (root.frame.total || 1))}, ${amount(ms)}`;
  }

  /** Whether the root is to be scrolled to once the graph has a size. */
  let toRoot = false;

  /** On the root: at the bottom of a graph growing upward. */
  function scrollToRoot() {
    if (!toRoot || scroller.clientWidth === 0)
      return;
    toRoot = false;
    scroller.scrollTop = inverted ? 0 : scroller.scrollHeight;
  }

  const resized = new ResizeObserver(() => {
    draw();
    scrollToRoot();
  });
  resized.observe(scroller);
  signal.addEventListener('abort', () => resized.disconnect());

  return {
    element: h('div', {class: 'flame-graph'}, toolbar, wrapper),
    toolbar,
    find(name) {
      search.value = `^${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`;
      searchChanged();
      wrapper.scrollIntoView({behavior: 'smooth', block: 'nearest'});
    },
    show(frame, inv, u = 'ms') {
      unit = u;
      // the path of the frame zoomed on, by name, to zoom on it again
      const path = [];
      for (let b = zoomed; b; b = b.parent)
        path.unshift(b.frame.name);
      inverted = inv;
      const r = root = layout(frame);
      heat = heatOf(frame);
      hovered = null;
      // a new tree: told of anew, even of none
      told = undefined;
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
      toRoot = true;
      scrollToRoot();
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
 * @param {number[]} from
 * @param {number[]} to
 * @param {number} k between 0 for from and 1 for to
 */
function mix(from, to, k) {
  const t = Math.min(1, k);
  return `rgb(${from.map((c, i) => Math.round(c + (to[i] - c) * t)).join(', ')})`;
}

/** @param {number[]} rgb */
function css(rgb) {
  return `rgb(${rgb.map(Math.round).join(', ')})`;
}

/**
 * @param {number[]} from
 * @param {number[]} to
 * @param {number} k between 0 for from and 1 for to
 * @returns {number[]} the color between them
 */
function mixRgb(from, to, k) {
  return from.map((c, i) => c + (to[i] - c) * k);
}

/**
 * @param {number[]} rgb
 * @returns {number} how light it looks, between 0 and 1
 */
function luminance([r, g, b]) {
  return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
}

/**
 * A color a little lighter, by how much the name says: neighbours of one type
 * stay apart, and a method keeps its shade from one graph to the next.
 *
 * @param {number[]} rgb
 * @param {string} name
 * @returns {number[]}
 */
function shade([r, g, b], name) {
  let hash = 0;
  for (let i = 0; i < name.length; i++)
    hash = (hash * 31 + name.charCodeAt(i)) | 0;
  const k = ((hash >>> 0) % 100) / 100 * 0.25;
  return [r + (255 - r) * k, g + (255 - g) * k, b + (255 - b) * k];
}
