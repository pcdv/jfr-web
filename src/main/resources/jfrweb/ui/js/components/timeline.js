// @ts-check
/**
 * The timeline of a session: requests in flight per time bucket, stacked by
 * endpoint, or the samples of Java code when no request was recorded. Hovering
 * a bucket details it; dragging selects an interval.
 */
import {h, replace} from '../dom.js';
import * as fmt from '../format.js';

const SVG = 'http://www.w3.org/2000/svg';

/** Drawing size, in the units of the viewBox: the chart scales to its container. */
const W = 1000;
const H = 200;
const LEFT = 44;
const RIGHT = 8;
const TOP = 8;
const BOTTOM = 22;
const PLOT_W = W - LEFT - RIGHT;
const PLOT_H = H - TOP - BOTTOM;

/**
 * @typedef {object} Series
 * @property {string} name
 * @property {number[]} values
 * @property {string} color a CSS color, e.g. var(--series-1)
 */

/**
 * @param {import('../api.js').TimelineData} data
 * @param {(from: number | undefined, to: number | undefined) => void} onSelect called with
 *        the interval dragged, in seconds since the start, or undefined when cleared
 */
export function timelineChart(data, onSelect) {
  /** @type {Series[]} */
  const series = data.series.map((s, i) => ({name: s.endpoint, values: s.values, color: `var(--series-${i + 1})`}));
  if (data.other)
    series.push({name: 'Other endpoints', values: data.other, color: 'var(--series-other)'});
  const requests = series.length > 0;
  if (!requests)
    series.push({name: 'Samples of Java code', values: data.javaSamples, color: 'var(--series-1)'});

  const bucketSec = data.bucket / 1000;
  const n = data.buckets;
  const totals = Array.from({length: n}, (_, i) => series.reduce((sum, s) => sum + s.values[i], 0));
  const yMax = axisCeiling(Math.max(...totals, requests ? 1 : 10));
  const bw = PLOT_W / n;
  const x = (/** @type {number} */ i) => LEFT + i * bw;
  const y = (/** @type {number} */ v) => TOP + PLOT_H - v / yMax * PLOT_H;

  const svg = svgEl('svg', {viewBox: `0 0 ${W} ${H}`, class: 'timeline', role: 'img',
                            'aria-label': requests ? 'Requests in flight over time, by endpoint'
                                                   : 'Samples of Java code over time'});

  // recessive grid, one axis
  for (const v of [0, yMax / 2, yMax]) {
    svg.append(svgEl('line', {x1: LEFT, x2: W - RIGHT, y1: y(v), y2: y(v), class: 'grid-line'}));
    svg.append(svgText(LEFT - 6, y(v) + 4, formatCount(v), 'axis-label end'));
  }
  for (const t of timeTicks(n * bucketSec))
    svg.append(svgText(LEFT + t / bucketSec * bw, H - 6, `${t} s`, 'axis-label middle'));

  // stacked bars, with a surface gap between segments and between bars
  const gap = bw > 6 ? 2 : bw > 3 ? 1 : 0;
  for (let i = 0; i < n; i++) {
    let base = 0;
    for (const s of series) {
      const v = s.values[i];
      if (v <= 0)
        continue;
      const top = y(base + v);
      const height = y(base) - top;
      base += v;
      svg.append(svgEl('rect', {
        x: x(i) + gap / 2, width: Math.max(bw - gap, 0.5),
        y: top, height: height > 2 + gap ? height - gap : height,
        fill: s.color,
      }));
    }
  }

  const selection = svgEl('rect', {y: TOP, height: PLOT_H, class: 'selection', visibility: 'hidden'});
  const hover = svgEl('rect', {y: TOP, height: PLOT_H, width: bw, class: 'hover', visibility: 'hidden'});
  // catches the pointer over the whole plot, above the marks
  const capture = svgEl('rect', {x: LEFT, y: TOP, width: PLOT_W, height: PLOT_H, fill: 'transparent'});
  svg.append(hover, selection, capture);

  const tooltip = h('div', {class: 'tooltip', hidden: true});
  const wrapper = h('div', {class: 'timeline-wrapper'}, svg, tooltip);

  /** @param {PointerEvent} e the bucket under the pointer */
  function bucketAt(e) {
    const r = svg.getBoundingClientRect();
    const vx = (e.clientX - r.left) / r.width * W;
    return Math.min(n - 1, Math.max(0, Math.floor((vx - LEFT) / bw)));
  }

  /** @type {number | null} */
  let dragFrom = null;

  capture.addEventListener('pointermove', e => {
    const i = bucketAt(e);
    hover.setAttribute('x', String(x(i)));
    hover.setAttribute('visibility', 'visible');
    showTooltip(e, i);
    if (dragFrom !== null)
      showSelection(Math.min(dragFrom, i), Math.max(dragFrom, i));
  });
  capture.addEventListener('pointerleave', () => {
    hover.setAttribute('visibility', 'hidden');
    tooltip.hidden = true;
  });
  capture.addEventListener('pointerdown', e => {
    dragFrom = bucketAt(e);
    capture.setPointerCapture(e.pointerId);
  });
  capture.addEventListener('pointerup', e => {
    if (dragFrom === null)
      return;
    const to = bucketAt(e);
    const [a, b] = [Math.min(dragFrom, to), Math.max(dragFrom, to)];
    dragFrom = null;
    if (a === b && selection.getAttribute('visibility') === 'visible') {
      // a click clears the selection
      selection.setAttribute('visibility', 'hidden');
      onSelect(undefined, undefined);
      return;
    }
    showSelection(a, b);
    onSelect(round(a * bucketSec), round((b + 1) * bucketSec));
  });

  /**
   * @param {number} a first bucket
   * @param {number} b last bucket
   */
  function showSelection(a, b) {
    selection.setAttribute('x', String(x(a)));
    selection.setAttribute('width', String((b - a + 1) * bw));
    selection.setAttribute('visibility', 'visible');
  }

  /**
   * @param {PointerEvent} e
   * @param {number} i
   */
  function showTooltip(e, i) {
    const rows = series.filter(s => s.values[i] > 0);
    replace(tooltip,
      h('div', {class: 'tooltip-title'}, `${round(i * bucketSec)}–${round((i + 1) * bucketSec)} s`),
      rows.length === 0
        ? h('div', {class: 'hint'}, requests ? 'No request in flight' : 'No sample')
        : rows.map(s => h('div', {class: 'tooltip-row'},
            h('span', {class: 'swatch', style: `background: ${s.color}`}),
            h('span', {class: 'tooltip-name'}, s.name),
            h('span', {class: 'tooltip-value'}, formatCount(s.values[i])))),
      requests && rows.length > 1
        ? h('div', {class: 'tooltip-row total'}, h('span'), h('span', {class: 'tooltip-name'}, 'Total'),
            h('span', {class: 'tooltip-value'}, formatCount(totals[i])))
        : null);
    tooltip.hidden = false;
    const r = wrapper.getBoundingClientRect();
    const left = e.clientX - r.left;
    // on the side of the pointer with the most room
    tooltip.style.left = left < r.width / 2 ? `${left + 16}px` : '';
    tooltip.style.right = left < r.width / 2 ? '' : `${r.width - left + 16}px`;
  }

  return h('div', null,
    h('p', {class: 'hint'}, requests
      ? `Requests in flight, on average per ${fmt.duration(data.bucket)}, by endpoint. Drag to select an interval: the sections below show only it; click to clear it.`
      : `No request recorded: samples of Java code per ${fmt.duration(data.bucket)}. Drag to select an interval: the sections below show only it; click to clear it.`),
    legend(series),
    wrapper);
}

/** @param {Series[]} series */
function legend(series) {
  return h('div', {class: 'legend'}, series.map(s =>
    h('span', {class: 'legend-item'}, h('span', {class: 'swatch', style: `background: ${s.color}`}), s.name)));
}

/**
 * @param {string} name
 * @param {Record<string, string | number>} attrs
 * @returns {SVGElement}
 */
function svgEl(name, attrs) {
  const el = document.createElementNS(SVG, name);
  for (const [k, v] of Object.entries(attrs))
    el.setAttribute(k, String(v));
  return /** @type {SVGElement} */ (el);
}

/**
 * @param {number} x
 * @param {number} y
 * @param {string} text
 * @param {string} cls
 */
function svgText(x, y, text, cls) {
  const t = svgEl('text', {x, y, class: cls});
  t.textContent = text;
  return t;
}

/**
 * A round value at least as big, to top the y axis without wasting half of
 * it: 1, 1.5, 2, 3, 4, 5, 6 or 8 times a power of ten.
 *
 * @param {number} v
 */
function axisCeiling(v) {
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  for (const m of [1, 1.5, 2, 3, 4, 5, 6, 8, 10])
    if (v <= m * p)
      return m * p;
  return 10 * p;
}

/**
 * A round value at least as big, for steps between ticks: 1, 2 or 5 times a
 * power of ten.
 *
 * @param {number} v
 */
function niceCeiling(v) {
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  for (const m of [1, 2, 5, 10])
    if (v <= m * p)
      return m * p;
  return 10 * p;
}

/**
 * Round times for the x axis, about 6 of them.
 *
 * @param {number} seconds the span
 * @returns {number[]}
 */
function timeTicks(seconds) {
  const step = niceCeiling(seconds / 6);
  const res = [];
  for (let t = 0; t <= seconds + 1e-9; t += step)
    res.push(round(t));
  return res;
}

/** @param {number} v */
function round(v) {
  return Math.round(v * 1000) / 1000;
}

/** @param {number} v */
function formatCount(v) {
  // at most 2 decimals, without trailing zeros: 2.5, not 2.50
  return String(v >= 10 ? Math.round(v * 10) / 10 : Math.round(v * 100) / 100);
}
