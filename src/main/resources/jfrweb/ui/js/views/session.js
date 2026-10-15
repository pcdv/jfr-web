// @ts-check
/**
 * The report of a session: how it was recorded, its operations, and its
 * flame graph.
 */
import * as api from '../api.js';
import {h, replace, errorBox} from '../dom.js';
import * as fmt from '../format.js';
import {flamePanel} from '../components/flame.js';
import {operationsPanel} from '../components/operations.js';
import {section} from '../components/section.js';
import {renameButton} from '../components/sessionName.js';
import {sessionTimeline} from '../components/sessionTimeline.js';
import {slowestPanel} from '../components/slowest.js';

/**
 * @param {HTMLElement} root
 * @param {string[]} params the id of the session
 */
export function renderSession(root, params) {
  const id = Number(params[0]);
  const content = h('div', null, h('p', {class: 'empty'}, 'Reading the recording…'));
  const title = h('h1', null, `Session ${id}`);
  root.append(h('p', null, h('a', {href: '#/'}, '← Sessions')),
              title,
              content);

  /** @param {api.Session} s */
  const showTitle = s => replace(title, `Session ${id}`, s.name ? ` · ${s.name}` : null, ' ',
    api.isStatic() ? null : h('span', {class: 'title-action'}, renameButton(s, showTitle)));

  Promise.all([api.getReport(id), api.getTimeline(id)]).then(
    ([report, timeline]) => {
      showTitle(report.session);
      /** @type {string | undefined} the operation clicked on the timeline, whose filters it set */
      let clicked;
      // the interval selected on the timeline, or zoomed in on, narrows
      // everything below it; an operation clicked there narrows the slowest
      // and the flame graph to it, until the next selection
      const showInterval = (/** @type {api.Interval} */ i, /** @type {string | undefined} */ name) => {
        operations.showInterval(i);
        // back to all of them after a click, else as they were
        const names = name ?? (clicked !== undefined ? null : undefined);
        slowest.showInterval(i, names);
        flame.showInterval(i.from, i.to, names);
        clicked = name;
      };
      const timelinePanel = sessionTimeline(id, timeline, showInterval);
      // the operation picked among the slowest is outlined on the timeline
      // while its flame graph shows
      const flame = flamePanel(report, op => timelinePanel.mark(op));
      const slowest = slowestPanel(id, flame.showInstance);
      const operations = operationsPanel(id, report.operations, n => {
        slowest.showName(n);
        flame.showName(n);
      });
      replace(content,
        summary(report),
        recording(report),
        section('timeline', 'Timeline', timelinePanel.element),
        operations.element,
        slowest.element,
        section('flame', 'Flame graph', flame.element));
    },
    e => replace(content, errorBox(e)));
}

/**
 * @param {api.Report} r
 */
function summary(r) {
  const s = r.session;
  return h('p', {class: 'summary'},
    `Started ${fmt.time(s.start)}, ${fmt.duration(s.duration)}, ${r.sampling.operations} operations, `,
    h('a', {href: api.downloadUrl(s.id), download: true}, `download the .jfr (${fmt.bytes(s.size)})`),
    ' to open it in JDK Mission Control.');
}

/**
 * How the session was recorded, which every report states: the sampling
 * actually achieved, and what it hides.
 *
 * @param {api.Report} r
 */
function recording(r) {
  const share = r.sampling.operationShareOfNative;
  const samples = r.sampling.java.samples + r.sampling.native.samples;
  const truncated = samples ? r.sampling.truncated / samples : 0;
  return h('details', {class: 'card'},
    h('summary', null, 'How it was recorded',
      truncated >= 0.01
        ? h('span', {class: 'hint warn'}, ` — ${fmt.percent(truncated)} of the stacks cut at ${r.sampling.stackDepth} frames`)
        : null,
      share !== null && share < 0.5
        ? h('span', {class: 'hint'}, ` — native times are proportions: operations got ${fmt.percent(share)} of the native samples`)
        : null),
    h('dl', null,
      h('dt', null, 'JVM'), h('dd', null, r.recording.jvm),
      h('dt', null, 'JVM arguments'), h('dd', {class: 'mono'}, r.recording.jvmArguments || '(none)'),
      h('dt', null, 'OS'), h('dd', null, r.recording.os),
      h('dt', null, 'Java code'), h('dd', null, sampling(r.sampling.java)),
      h('dt', null, 'Native calls'), h('dd', null, sampling(r.sampling.native),
        share !== null
          ? `. While operations ran, their threads got ${fmt.percent(share)} of the native samples: ` +
            'JFR samples one thread in native code per period, and idle threads take the rest.'
          : null),
      h('dt', null, 'Operations'), h('dd', null, r.sampling.operationEvents
        ? `${r.sampling.operations}, from the events of flak's requests and of the jfrweb.ops API`
        : 'neither flak.Request nor jfrweb.Operation enabled: no operation context'),
      h('dt', null, 'Stacks'), h('dd', null, r.sampling.truncated === 0
        ? 'none cut'
        : [`${r.sampling.truncated} of ${samples} samples cut at ${r.sampling.stackDepth} frames: their bottom is lost, ` +
           'and flame graphs show their top under "(truncated)". Start the JVM with ',
           h('code', null, `-XX:FlightRecorderOptions:stackdepth=${r.sampling.suggestedStackDepth}`),
           ', or let jfr-web start before anything else records with JFR: it raises the depth then.']),
      h('dt', null, 'Events'), h('dd', null, `${r.recording.enabledEvents} enabled`)),
    h('h3', null, 'Known biases'),
    h('ul', null, r.biases.map(b => h('li', null, b))));
}

/**
 * @param {api.Sampling} s
 */
function sampling(s) {
  if (s.period === null)
    return 'not sampled';
  const observed = s.observed !== null && s.observed > s.period * 1.25
    ? `, observed every ${fmt.duration(s.observed)}`
    : '';
  return `every ${fmt.duration(s.period)} asked${observed}; ${s.samples} samples, ${s.inOperations} in operations`;
}
