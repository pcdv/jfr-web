// @ts-check
/**
 * The report of a session: how it was recorded, its operations, and its
 * flame graph.
 */
import * as api from '../api.js';
import {tell} from '../lib/dialog.js';
import {h, replace} from '../lib/dom.js';
import {errorBox} from '../components/errorBox.js';
import * as fmt from '../format.js';
import {flamePanel} from '../components/flame.js';
import {operationsPanel} from '../components/operations.js';
import {section} from '../components/section.js';
import {tabbedSection} from '../components/tabs.js';
import {renameButton} from '../components/sessionName.js';
import {sessionTimeline} from '../components/sessionTimeline.js';
import {slowestPanel} from '../components/slowest.js';
import {decode} from '../profile/profile.js';

/** How often a session still recording is checked, in ms. */
const POLL = 1000;

/**
 * The params are the id of the session.
 *
 * @type {import('../lib/router.js').View}
 */
export function renderSession(root, params, signal) {
  const id = Number(params[0]);
  const content = h('div', null, h('p', {class: 'empty'}, 'Reading the recording…'));
  const title = h('h1', null, `Session ${id}`);
  root.append(h('p', null, h('a', {href: '#/'}, '← Sessions')),
              title,
              content);

  /** @type {number | undefined} */
  let timer;

  /** @param {api.Session} s */
  const showTitle = s => replace(title, `Session ${id}`, s.name ? ` · ${s.name}` : null, ' ',
    api.isStatic() ? null : h('span', {class: 'title-action'}, renameButton(s, showTitle)));

  /** @type {ReturnType<typeof recordingNow> | undefined} */
  let progress;
  /** Whether the report was asked for: a check after a stop may cross the one polling. */
  let reported = false;

  // a session still recording has no report yet: how far it got, until it
  // stops, here or elsewhere
  async function waitForStop() {
    clearTimeout(timer);
    try {
      const s = await api.getSession(id);
      if (signal.aborted || reported)
        return;
      if (s.state === 'STOPPED') {
        reported = true;
        replace(content, h('p', {class: 'empty'}, 'Reading the recording…'));
        showReport();
        return;
      }
      if (!progress) {
        // no rename button while it records: the report's title has one
        replace(title, `Session ${id}`, s.name ? ` · ${s.name}` : null);
        progress = recordingNow(id, waitForStop);
        replace(content, progress.element);
      }
      progress.show(s);
      clearTimeout(timer);
      timer = window.setTimeout(waitForStop, POLL);
    }
    catch (e) {
      if (!signal.aborted)
        replace(content, errorBox(e));
    }
  }

  // sessions of a static export are all stopped
  if (api.isStatic())
    showReport();
  else
    waitForStop();
  signal.addEventListener('abort', () => clearTimeout(timer));

  function showReport() {
    // the samples and operations, for the flame graph and the tables of an
    // interval: computed here, in a static export too
    const profile = api.getProfile(id).then(decode);
    // each panel tells its own failure
    profile.catch(() => {});
    Promise.all([api.getReport(id), api.getTimeline(id)]).then(
      ([report, timeline]) => {
        if (signal.aborted)
          return;
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
        const timelinePanel = sessionTimeline(timeline, profile, showInterval);
        // the operation picked among the slowest is outlined on the timeline
        // while its flame graph shows
        const flame = flamePanel(profile, signal, {
          onInstance: op => timelinePanel.mark(op),
          // the frame hovered or zoomed on in the flame graph: when its samples were taken
          onFrame: f => timelinePanel.showFrame(f),
        });
        const slowest = slowestPanel(id, profile, flame.showInstance);
        const operations = operationsPanel(report.operations, profile, n => {
          slowest.showName(n);
          flame.showName(n);
        });
        replace(content,
          summary(report),
          recording(report),
          section('timeline', 'Timeline', timelinePanel.element),
          // right under the timeline, which shows when the frame hovered
          // was sampled: both on one screen
          section('flame', 'Flame graph', flame.element),
          tabbedSection('tables', [
            {key: 'operations', title: operations.title, content: operations.content},
            {key: 'slowest', title: slowest.title, content: slowest.content},
            {key: 'hot', title: flame.hot.title, content: flame.hot.content},
          ]));
      },
      e => {
        if (!signal.aborted)
          replace(content, errorBox(e));
      });
  }
}

/**
 * A session still recording: since when, and a button to stop it. Built
 * once, its text updated as it goes.
 *
 * @param {number} id
 * @param {() => void} refresh checks the session again
 */
function recordingNow(id, refresh) {
  const text = h('p', {class: 'summary'});
  const stop = h('button', {
    class: 'primary',
    onclick: async () => {
      stop.disabled = true;
      try {
        await api.stopSession(id);
      }
      catch (e) {
        tell(e instanceof Error ? e.message : String(e));
        stop.disabled = false;
      }
      refresh();
    },
  }, 'Stop');
  return {
    element: h('div', null, text, h('div', {class: 'toolbar'}, stop)),
    /** @param {api.Session} s */
    show: s => replace(text,
      // whole seconds: it ticks once a second
      `Recording since ${fmt.time(s.start)}, ${Math.floor(s.duration / 1000)} s so far. The report shows once it stops.`),
  };
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
