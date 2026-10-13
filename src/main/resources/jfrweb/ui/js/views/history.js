// @ts-check
/**
 * The operation history: what ran during a period, from the files the
 * history writes, even when nothing was recorded with JFR.
 */
import * as api from '../api.js';
import {h, replace, errorBox} from '../dom.js';
import * as fmt from '../format.js';
import {operationsTable, outcomeCell} from '../components/operations.js';
import {timelineChart} from '../components/timeline.js';
import {section} from '../components/section.js';
import {readSetting, writeSetting} from '../settings.js';

/** The periods offered, in hours. */
const PRESETS = [1, 6, 24, 24 * 7];

/** What the timeline measures, kept per browser. */
const MEASURE_KEY = 'jfrweb.history.measure';

/** @typedef {'count' | 'time'} Measure */

/**
 * @typedef {{from: Date, to: Date}} Period
 */

/**
 * @param {HTMLElement} root
 */
export function renderHistory(root) {
  const toolbar = h('div', {class: 'toolbar history-toolbar'});
  const content = h('div');
  root.append(h('h1', null, 'History'), toolbar, content);

  /** @type {Period} */
  let period = last(1);
  /** @type {number | null} the preset shown, null for a period picked otherwise */
  let preset = 1;
  /** @type {Period[]} periods zoomed out of, the latest last */
  const zooms = [];
  /** @type {string | undefined} */
  let name;

  async function load() {
    replace(toolbar, periodPicker());
    try {
      const history = await api.getHistory(period.from, period.to, name);
      replace(content, render(history));
    }
    catch (e) {
      replace(content, errorBox(e));
    }
  }

  function periodPicker() {
    const from = h('input', {type: 'datetime-local', value: local(period.from)});
    const to = h('input', {type: 'datetime-local', value: local(period.to)});
    return h('div', {class: 'form-grid'},
      h('span', {class: 'label'}, 'Last'),
      h('span', {class: 'segmented'}, PRESETS.map(hours => h('button', {
        class: preset === hours ? 'selected' : null,
        onclick: () => pick(last(hours), hours, true),
      }, hours < 24 ? `${hours} h` : `${hours / 24} d`))),
      h('span', {class: 'label'}, 'From'),
      h('form', {class: 'inline', onsubmit: (/** @type {Event} */ e) => {
          e.preventDefault();
          if (from.value && to.value)
            pick({from: new Date(from.value), to: new Date(to.value)}, null, true);
        }},
        from, h('span', null, 'to'), to,
        h('button', {type: 'submit'}, preset ? 'Show' : 'Refresh'),
        zooms.length ? h('button', {type: 'button', onclick: zoomOut}, '← Zoom out') : null));
  }

  /**
   * @param {Period} p
   * @param {number | null} hours the preset, if any
   * @param {boolean} reset whether it starts afresh, forgetting the zooms
   */
  function pick(p, hours, reset) {
    if (reset)
      zooms.length = 0;
    period = p;
    preset = hours;
    name = undefined;
    load();
  }

  function zoomOut() {
    const p = zooms.pop();
    if (p)
      pick(p, null, false);
  }

  /** @param {api.History} history */
  function render(history) {
    if (!history.available)
      return h('p', {class: 'empty'}, 'No history: pass its directory to the profiler, ',
        h('code', null, 'Profiler.builder().history(dir)'), ', where an ', h('code', null, 'OperationHistory'),
        ' writes it.');
    if (!history.intervals)
      return h('p', {class: 'empty'}, `Nothing in the history from ${period.from.toLocaleString()} to ${period.to.toLocaleString()}: it was not written then.`);

    const timeline = /** @type {NonNullable<api.History['timeline']>} */ (history.timeline);
    const start = new Date(timeline.start).getTime() / 1000;
    const zoom = (/** @type {number | undefined} */ f, /** @type {number | undefined} */ t) => {
      if (f === undefined || t === undefined)
        return;
      zooms.push(period);
      pick({from: new Date((start + f) * 1000), to: new Date((start + t) * 1000)}, null, false);
    };

    // the number of operations, or the time they took: what runs most is not
    // always what costs most
    const chart = h('div');
    const measures = h('span', {class: 'segmented'});
    const showMeasure = (/** @type {Measure} */ m) => {
      writeSetting(MEASURE_KEY, m);
      replace(measures, 'Show ', ['count', 'time'].map(x => h('button', {
        class: x === m ? 'selected' : null,
        onclick: () => showMeasure(/** @type {Measure} */ (x)),
      }, x === 'count' ? 'Count' : 'Time')));
      const time = m === 'time';
      replace(chart, timelineChart(time ? {...timeline, series: timeline.seriesSeconds, other: timeline.otherSeconds} : timeline, zoom, {
        hint: time
          ? `Seconds spent in operations per ${fmt.duration(timeline.bucket)}, by name. Drag to zoom in.`
          : `Operations per ${fmt.duration(timeline.bucket)}, by name. Drag to zoom in.`,
        unit: time ? ' s' : '',
        timeLabel: t => clock(new Date((start + t) * 1000), period.to.getTime() - period.from.getTime()),
        startEpoch: start,
      }));
    };
    showMeasure(readSetting(MEASURE_KEY) === 'time' ? 'time' : 'count');

    return h('div', null,
      h('p', {class: 'summary'},
        `${history.intervals} intervals from ${period.from.toLocaleString()} to ${period.to.toLocaleString()}, in `,
        h('code', null, history.directory ?? '')),
      section('history-timeline', 'Operations over time', h('div', {class: 'toolbar'}, measures), chart),
      section('history-operations', 'Operations', operationsTable(/** @type {api.OperationRow[]} */ (history.operations),
        n => {
          name = n;
          load();
        }, 'No operation in this period.')),
      section('history-slowest', name ? h('span', null, 'Slowest operations', h('span', {class: 'mono'}, ` · ${name}`)) : 'Slowest operations',
        slowestTable(/** @type {api.HistorySlow[]} */ (history.slowest)),
        name ? h('button', {onclick: () => { name = undefined; load(); }}, 'All operations') : null));
  }

  load();
}

/**
 * @param {api.HistorySlow[]} rows
 */
function slowestTable(rows) {
  if (rows.length === 0)
    return h('p', {class: 'empty'}, 'No operation in this period.');
  return h('table', {class: 'grid'},
    h('thead', null, h('tr', null,
      h('th', null, 'Ended'), h('th', {class: 'num'}, 'Duration'), h('th', {class: 'num'}, 'Outcome'),
      h('th', null, 'Operation'), h('th', null, 'Detail'), h('th', null, 'Thread'))),
    h('tbody', null, rows.map(r => h('tr', null,
      h('td', null, new Date(r.end).toLocaleString()),
      h('td', {class: 'num'}, fmt.duration(r.duration)),
      outcomeCell(r),
      h('td', {class: 'mono'}, r.name),
      h('td', {class: 'mono wrap'}, r.detail ?? ''),
      h('td', null, r.thread)))));
}

/**
 * @param {number} hours
 * @returns {Period} the last hours, up to now
 */
function last(hours) {
  const to = new Date();
  return {from: new Date(to.getTime() - hours * 3600_000), to};
}

/**
 * @param {Date} d
 * @returns {string} for a datetime-local input, in local time
 */
function local(d) {
  const pad = (/** @type {number} */ n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * @param {Date} d
 * @param {number} spanMs the period shown: seconds under a quarter of an
 *        hour, the day beyond a day
 */
function clock(d, spanMs) {
  const time = d.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit', second: spanMs <= 900_000 ? '2-digit' : undefined});
  return spanMs > 86_400_000 ? `${d.toLocaleDateString([], {month: 'short', day: 'numeric'})} ${time}` : time;
}
