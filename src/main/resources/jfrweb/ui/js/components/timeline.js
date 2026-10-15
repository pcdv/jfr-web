// @ts-check
/**
 * The timeline of a session: operations in flight per time bucket, stacked by
 * name, or the samples of Java code when no operation was recorded. Hovering
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
 * @typedef {object} TimelineOptions
 * @property {string} [hint] what the chart shows, instead of the text for a session
 * @property {(seconds: number) => string} [timeLabel] labels a time, in seconds since the
 *           start: clock times rather than "12 s"
 * @property {number} [startEpoch] the start in seconds since the epoch, so that ticks fall
 *           on round clock times
 * @property {string} [unit] after the values, e.g. " s"
 */

/**
 * @param {import('../api.js').TimelineData} data
 * @param {((from: number | undefined, to: number | undefined) => void) | null} onSelect called
 *        with the interval dragged, in seconds since the start, or undefined when cleared; null
 *        when nothing can be selected
 * @param {TimelineOptions} [options]
 * @returns {HTMLDivElement & {mark: (from?: number, to?: number) => void}} the chart, whose
 *          mark() outlines a span, in seconds since the start, or removes it when undefined
 */
export function timelineChart(data, onSelect, options = {}) {
  const label = options.timeLabel ?? (t => `${t} s`);
  const unit = options.unit ?? '';
  /** @type {Series[]} */
  const series = data.series.map((s, i) => ({name: s.name, values: s.values, color: `var(--series-${i + 1})`}));
  if (data.other)
    series.push({name: 'Other operations', values: data.other, color: 'var(--series-other)'});
  const operations = series.length > 0;
  if (!operations)
    series.push({name: 'Samples of Java code', values: data.javaSamples ?? new Array(data.buckets).fill(0),
                 color: 'var(--series-1)'});

  const bucketSec = data.bucket / 1000;
  const n = data.buckets;
  const totals = Array.from({length: n}, (_, i) => series.reduce((sum, s) => sum + s.values[i], 0));
  const yMax = axisCeiling(Math.max(...totals, operations ? 1 : 10));
  const bw = PLOT_W / n;
  const x = (/** @type {number} */ i) => LEFT + i * bw;
  const y = (/** @type {number} */ v) => TOP + PLOT_H - v / yMax * PLOT_H;

  const svg = svgEl('svg', {viewBox: `0 0 ${W} ${H}`, class: 'timeline', role: 'img',
                            'aria-label': operations ? 'Operations in flight over time, by name'
                                                   : 'Samples of Java code over time'});

  // recessive grid, one axis
  for (const v of [0, yMax / 2, yMax]) {
    svg.append(svgEl('line', {x1: LEFT, x2: W - RIGHT, y1: y(v), y2: y(v), class: 'grid-line'}));
    svg.append(svgText(LEFT - 6, y(v) + 4, formatCount(v) + unit, 'axis-label end'));
  }
  for (const t of timeTicks(n * bucketSec, options.startEpoch ?? 0))
    svg.append(svgText(LEFT + t / bucketSec * bw, H - 6, label(t), 'axis-label middle'));

  // stacked bars, with a surface gap between segments and between bars
  const gap = bw > 6 ? 2 : bw > 3 ? 1 : 0;
  /** @type {SVGElement[][]} the segments of each series */
  const bars = series.map(() => []);
  /** @type {{series: number, x: number, y: number, width: number, height: number}[][]} those of each bucket, bottom up */
  const stacks = [];
  for (let i = 0; i < n; i++) {
    stacks.push([]);
    let base = 0;
    series.forEach((s, si) => {
      const v = s.values[i];
      if (v <= 0)
        return;
      const top = y(base + v);
      const height = y(base) - top;
      base += v;
      const seg = {series: si, x: x(i) + gap / 2, width: Math.max(bw - gap, 0.5),
                   y: top, height: height > 2 + gap ? height - gap : height};
      stacks[i].push(seg);
      const rect = svgEl('rect', {x: seg.x, width: seg.width, y: seg.y, height: seg.height, fill: s.color, class: 'bar'});
      bars[si].push(rect);
      svg.append(rect);
    });
  }

  const selection = svgEl('rect', {y: TOP, height: PLOT_H, class: 'selection', visibility: 'hidden'});
  const hover = svgEl('rect', {y: TOP, height: PLOT_H, width: bw, class: 'hover', visibility: 'hidden'});
  const marked = svgEl('rect', {y: TOP, height: PLOT_H, class: 'mark', visibility: 'hidden'});
  // the segment under the pointer, whose series stands out
  const outline = svgEl('rect', {class: 'outline', visibility: 'hidden'});
  // catches the pointer over the whole plot, above the marks
  const capture = svgEl('rect', {x: LEFT, y: TOP, width: PLOT_W, height: PLOT_H, fill: 'transparent'});
  svg.append(hover, selection, marked, outline, capture);

  /** @type {number} the series in focus, -1 for none */
  let focused = -1;

  // hovering an entry of the legend focuses its series too
  const legendItems = series.map((s, si) => h('span', {
      class: 'legend-item',
      onpointerenter: () => series.length > 1 && focus(si),
      onpointerleave: () => focus(-1),
    }, h('span', {class: 'swatch', style: `background: ${s.color}`}), s.name));

  /**
   * Brings a series forward, fading the others, in the chart and the legend.
   *
   * @param {number} si -1 for none
   */
  function focus(si) {
    if (si === focused)
      return;
    if (focused >= 0) {
      bars[focused].forEach(r => r.classList.remove('focused'));
      legendItems[focused].classList.remove('focused');
    }
    focused = si;
    svg.classList.toggle('has-focus', si >= 0);
    if (si >= 0) {
      bars[si].forEach(r => r.classList.add('focused'));
      legendItems[si].classList.add('focused');
    }
  }

  /**
   * The segment of a bucket under the pointer, or the nearest one when a thin
   * one is hard to hit.
   *
   * @param {number} i the bucket
   * @param {number} vy the pointer, in the units of the viewBox
   */
  function segmentAt(i, vy) {
    let best = null;
    let distance = 4;
    for (const seg of stacks[i]) {
      const d = vy < seg.y ? seg.y - vy : vy > seg.y + seg.height ? vy - seg.y - seg.height : 0;
      if (d < distance) {
        best = seg;
        distance = d;
      }
    }
    return best;
  }

  /** @param {ReturnType<typeof segmentAt>} seg */
  function showOutline(seg) {
    if (!seg) {
      outline.setAttribute('visibility', 'hidden');
      return;
    }
    for (const k of /** @type {const} */ (['x', 'y', 'width', 'height']))
      outline.setAttribute(k, String(seg[k]));
    outline.setAttribute('visibility', 'visible');
  }

  /**
   * @param {number} [from] in seconds since the start
   * @param {number} [to]
   */
  function mark(from, to) {
    const span = n * bucketSec;
    if (from === undefined || to === undefined || to < 0 || from > span) {
      marked.setAttribute('visibility', 'hidden');
      return;
    }
    const a = LEFT + Math.max(0, from) / span * PLOT_W;
    const b = LEFT + Math.min(span, to) / span * PLOT_W;
    // a short operation stays visible
    marked.setAttribute('x', String(Math.min(a, LEFT + PLOT_W - 3)));
    marked.setAttribute('width', String(Math.max(b - a, 3)));
    marked.setAttribute('visibility', 'visible');
  }

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
    // no focus while dragging: the selection matters then
    const r = svg.getBoundingClientRect();
    const seg = dragFrom === null && series.length > 1 ? segmentAt(i, (e.clientY - r.top) / r.height * H) : null;
    showOutline(seg);
    focus(seg ? seg.series : -1);
    showTooltip(e, i);
    if (dragFrom !== null)
      showSelection(Math.min(dragFrom, i), Math.max(dragFrom, i));
  });
  capture.addEventListener('pointerleave', () => {
    hover.setAttribute('visibility', 'hidden');
    showOutline(null);
    focus(-1);
    tooltip.hidden = true;
  });
  capture.addEventListener('pointerdown', e => {
    if (!onSelect)
      return;
    dragFrom = bucketAt(e);
    capture.setPointerCapture(e.pointerId);
  });
  capture.addEventListener('pointerup', e => {
    if (dragFrom === null || !onSelect)
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
      h('div', {class: 'tooltip-title'}, options.timeLabel
        ? `${label(i * bucketSec)}–${label((i + 1) * bucketSec)}`
        : `${round(i * bucketSec)}–${round((i + 1) * bucketSec)} s`),
      rows.length === 0
        ? h('div', {class: 'hint'}, operations ? 'No operation in flight' : 'No sample')
        : rows.map(s => h('div', {class: s === series[focused] ? 'tooltip-row focused' : 'tooltip-row'},
            h('span', {class: 'swatch', style: `background: ${s.color}`}),
            h('span', {class: 'tooltip-name'}, s.name),
            h('span', {class: 'tooltip-value'}, formatCount(s.values[i]) + unit))),
      operations && rows.length > 1
        ? h('div', {class: 'tooltip-row total'}, h('span'), h('span', {class: 'tooltip-name'}, 'Total'),
            h('span', {class: 'tooltip-value'}, formatCount(totals[i]) + unit))
        : null);
    tooltip.hidden = false;
    const r = wrapper.getBoundingClientRect();
    const left = e.clientX - r.left;
    // on the side of the pointer with the most room
    tooltip.style.left = left < r.width / 2 ? `${left + 16}px` : '';
    tooltip.style.right = left < r.width / 2 ? '' : `${r.width - left + 16}px`;
  }

  const drag = onSelect ? ' Drag to select an interval: the sections below show only it; click to clear it.' : '';
  return Object.assign(h('div', null,
    h('p', {class: 'hint'}, options.hint ? options.hint : operations
      ? `Operations in flight, on average per ${fmt.duration(data.bucket)}, by name.${drag}`
      : `No operation recorded: samples of Java code per ${fmt.duration(data.bucket)}.${drag}`),
    h('div', {class: 'legend'}, legendItems),
    wrapper), {mark});
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

/** Round steps between clock times, in seconds: 1 min, 5 min, 1 h... */
const CLOCK_STEPS = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200, 86400];

/**
 * Round times for the x axis, about 6 of them: since the start, or on round
 * clock times when the start is given.
 *
 * @param {number} seconds the span
 * @param {number} startEpoch the start in seconds since the epoch, 0 if not on a clock
 * @returns {number[]} in seconds since the start
 */
function timeTicks(seconds, startEpoch) {
  const res = [];
  if (!startEpoch) {
    const step = niceCeiling(seconds / 6);
    for (let t = 0; t <= seconds + 1e-9; t += step)
      res.push(round(t));
    return res;
  }
  const step = CLOCK_STEPS.find(s => s >= seconds / 6) ?? CLOCK_STEPS[CLOCK_STEPS.length - 1];
  // in local time: an hour boundary where the browser is
  const offset = new Date(startEpoch * 1000).getTimezoneOffset() * 60;
  const first = Math.ceil((startEpoch - offset) / step) * step + offset - startEpoch;
  for (let t = first; t <= seconds + 1e-9; t += step)
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
