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

/**
 * @param {number} sessionId
 * @param {api.TimelineData} whole the timeline of the whole session
 * @param {(interval: api.Interval, name?: string) => void} onInterval called with what the
 *        sections below should show: the selection, else the range zoomed in on, else all;
 *        and the operation clicked, when a click picked one in a bucket
 * @returns {{element: HTMLElement, mark: (op: api.InstanceRow | null) => void}} the timeline,
 *          and what outlines the span of one operation on it, or removes the outline
 */
export function sessionTimeline(sessionId, whole, onInterval) {
  const toolbar = h('div', {class: 'toolbar timeline-toolbar'});
  const chart = h('div');

  /** @type {api.Interval} the range shown, all of it when empty */
  let range = {};
  /** @type {api.Interval[]} ranges zoomed out of, the latest last */
  const zooms = [];
  /** @type {api.Interval | null} the interval dragged, in seconds since the start of the session */
  let selection = null;
  /** @type {api.InstanceRow | null} the operation outlined */
  let marked = null;
  /** @type {ReturnType<typeof timelineChart> | null} */
  let drawn = null;

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
    const offset = range.from ?? 0;
    drawn = timelineChart(data, (from, to, name) => {
      selection = from === undefined || to === undefined
        ? null
        : {from: round(offset + from), to: round(offset + to)};
      drawToolbar();
      onInterval(selection ?? range, name);
    }, {timeLabel: t => `${round(offset + t)} s`});
    replace(chart, drawn);
    applyMark();
    drawToolbar();
  }

  function drawToolbar() {
    const shown = fmt.interval(range);
    replace(toolbar,
      h('button', {
        disabled: !selection,
        title: selection ? 'Redraw the timeline over the selection, in finer buckets'
                         : 'Drag on the timeline first to select an interval',
        onclick: () => {
          if (!selection)
            return;
          zooms.push(range);
          zoomTo(selection);
        },
      }, 'Zoom in'),
      ' ',
      h('button', {
        disabled: zooms.length === 0,
        onclick: () => zoomTo(zooms.pop() ?? {}),
      }, 'Zoom out'),
      shown ? h('span', {class: 'hint'}, ` · showing ${shown}`) : null);
  }

  /** @param {api.Interval} r */
  async function zoomTo(r) {
    range = r;
    selection = null;
    onInterval(range);
    try {
      draw(range.from === undefined && range.to === undefined ? whole : await api.getTimeline(sessionId, range));
    }
    catch (e) {
      replace(chart, errorBox(e));
    }
  }

  draw(whole);
  return {
    // a static export has the buckets of the whole session only: no zoom,
    // but a selection, which the sections below follow from the profile
    element: h('div', null, api.isStatic() ? null : toolbar, chart),
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
