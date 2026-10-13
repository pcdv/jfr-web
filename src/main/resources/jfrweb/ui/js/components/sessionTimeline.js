// @ts-check
/**
 * The timeline of a session, zoomable: drag to select an interval, which the
 * sections below follow; zoom in on it to redraw the timeline over it, in
 * finer buckets, and select more precisely.
 */
import * as api from '../api.js';
import {h, replace, errorBox} from '../dom.js';
import * as fmt from '../format.js';
import {timelineChart} from './timeline.js';

/**
 * @param {number} sessionId
 * @param {api.TimelineData} whole the timeline of the whole session
 * @param {(interval: api.Interval) => void} onInterval called with what the sections
 *        below should show: the selection, else the range zoomed in on, else all
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

  /** @param {api.TimelineData} data */
  function draw(data) {
    const offset = range.from ?? 0;
    replace(chart, timelineChart(data, (from, to) => {
      selection = from === undefined || to === undefined
        ? null
        : {from: round(offset + from), to: round(offset + to)};
      drawToolbar();
      onInterval(selection ?? range);
    }, {timeLabel: t => `${round(offset + t)} s`}));
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
  return h('div', null, toolbar, chart);
}

/** @param {number} v */
function round(v) {
  return Math.round(v * 1000) / 1000;
}
