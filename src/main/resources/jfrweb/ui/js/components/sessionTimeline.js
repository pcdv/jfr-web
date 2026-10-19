// @ts-check
/**
 * The timeline of a session, zoomable: drag to select an interval, which the
 * sections below follow; zoom in on it to redraw the timeline over it, in
 * finer buckets, and select more precisely.
 */
import * as api from '../api.js';
import {h, replace} from '../lib/dom.js';
import {errorBox} from './errorBox.js';
import * as fmt from '../format.js';
import {timelineChart} from './timeline.js';
import {timeline} from '../profile/timeline.js';

/** @typedef {import('./flame.js').ShownFrame} ShownFrame */

/**
 * @param {api.TimelineData} whole the timeline of the whole session
 * @param {Promise<import('../profile/profile.js').Profile>} profile the session's, from which a
 *        part of the timeline is drawn in finer buckets, with no request
 * @param {(interval: api.Interval, name?: string) => void} onInterval called with what the
 *        sections below should show: the selection, else the range zoomed in on, else all;
 *        and the operation clicked, when a click picked one in a bucket
 * @returns {{element: HTMLElement, mark: (op: api.InstanceRow | null) => void,
 *            showFrame: (frame: ShownFrame | null) => void}} the timeline, what outlines the
 *          span of one operation on it, or removes the outline, and what draws when the samples
 *          of a frame of the flame graph were taken, or removes them
 */
export function sessionTimeline(whole, profile, onInterval) {
  // the zoom, over the chart
  const toolbar = h('span', {class: 'timeline-zoom'});
  const chart = h('div');

  /** @type {api.Interval} the range shown, all of it when empty */
  let range = {};
  /** @type {api.Interval[]} ranges zoomed out of, the latest last */
  const zooms = [];
  /** @type {api.Interval | null} the interval dragged, in seconds since the start of the session */
  let selection = null;
  /** @type {api.InstanceRow | null} the operation outlined */
  let marked = null;
  /** @type {ShownFrame | null} the frame whose samples are drawn */
  let frame = null;
  /** @type {ReturnType<typeof timelineChart> | null} */
  let drawn = null;

  /** @param {api.TimelineData} data the buckets drawn */
  function applyFrame(data) {
    if (frame)
      drawn?.samples(frame.name, frame.times((range.from ?? 0) * 1000, data.bucket, data.buckets), frame.unit);
    else
      drawn?.samples();
  }

  /** @type {api.TimelineData} the buckets drawn */
  let buckets = whole;

  function applyMark() {
    if (!drawn)
      return;
    const offset = range.from ?? 0;
    if (marked)
      drawn.mark(marked.start - offset, marked.start + marked.duration / 1000 - offset);
    else
      drawn.mark();
  }

  /** @param {api.TimelineData} data */
  function draw(data) {
    buckets = data;
    const offset = range.from ?? 0;
    drawn = timelineChart(data, (from, to, name) => {
      selection = from === undefined || to === undefined
        ? null
        : {from: round(offset + from), to: round(offset + to)};
      drawToolbar();
      onInterval(selection ?? range, name);
    }, {timeLabel: t => `${round(offset + t)} s`, controls: toolbar, frames: true});
    replace(chart, drawn);
    applyMark();
    applyFrame(data);
    drawToolbar();
  }

  function drawToolbar() {
    const shown = fmt.interval(range);
    replace(toolbar,
      shown ? h('span', {class: 'timeline-range'}, shown) : null,
      h('button', {
        type: 'button', class: 'small',
        disabled: !selection,
        title: selection ? 'Zoom in: redraw the timeline over the selection, in finer buckets'
                         : 'Zoom in: drag on the timeline first to select an interval',
        onclick: () => {
          if (!selection)
            return;
          zooms.push(range);
          zoomTo(selection);
        },
      }, '+'),
      h('button', {
        type: 'button', class: 'small',
        disabled: zooms.length === 0,
        title: 'Zoom out: back to the range shown before',
        onclick: () => zoomTo(zooms.pop() ?? {}),
      }, '−'));
  }

  /** @param {api.Interval} r */
  async function zoomTo(r) {
    range = r;
    selection = null;
    onInterval(range);
    try {
      draw(range.from === undefined && range.to === undefined ? whole : timeline(await profile, range));
    }
    catch (e) {
      replace(chart, errorBox(e));
    }
  }

  draw(whole);
  return {
    element: chart,
    showFrame(f) {
      frame = f;
      applyFrame(buckets);
    },
    mark(op) {
      marked = op;
      applyMark();
    },
  };
}

/** @param {number} v */
function round(v) {
  return Math.round(v * 1000) / 1000;
}
