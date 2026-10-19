// @ts-check
/**
 * Client of the REST API of the profiler. Paths are relative to the page,
 * which is served at the root of the profiler's app.
 */

/**
 * @typedef {object} Session
 * @property {number} id
 * @property {'RECORDING' | 'STOPPED'} state
 * @property {string | null} name what the user called it
 * @property {string} trigger how it was started, e.g. "manual"
 * @property {string} start ISO-8601
 * @property {string | null} end ISO-8601, null while recording
 * @property {number} duration in ms
 * @property {number} size of the .jfr file in bytes, 0 while recording
 */

/**
 * @typedef {object} Sampling
 * @property {number | null} period asked, in ms
 * @property {number | null} observed interval in ms, null if too few samples to tell
 * @property {number} samples
 * @property {number} inOperations
 */

/**
 * The operations of a name, e.g. the requests of a route.
 *
 * @typedef {object} OperationRow
 * @property {string} name e.g. "GET /api/user/:id"
 * @property {number} count
 * @property {number} total in ms
 * @property {number} p50 in ms
 * @property {number} p99 in ms
 * @property {number} max in ms
 * @property {number} errors operations that failed: 5xx responses for requests
 * @property {number} bytesIn
 * @property {number} bytesOut
 */

/**
 * @typedef {object} Report
 * @property {Session} session
 * @property {{start: string, end: string, jvm: string, jvmArguments: string,
 *            javaArguments: string, os: string, enabledEvents: number}} recording
 * @property {{java: Sampling, native: Sampling, allocations: number, operationShareOfNative: number | null,
 *            operationEvents: boolean, operations: number, truncated: number, stackDepth: number,
 *            suggestedStackDepth: number}} sampling truncated: the samples whose stack JFR cut at
 *            stackDepth frames
 * @property {string[]} biases
 * @property {{name: string, samples: number}[]} threads
 * @property {OperationRow[]} operations
 */

/**
 * One operation of a session, e.g. a request.
 *
 * @typedef {object} InstanceRow
 * @property {number} id the index of the operation in the recording
 * @property {string} name
 * @property {string | null} detail e.g. "GET /api/user/12"
 * @property {number} status the HTTP status of a request, 0 for other operations
 * @property {boolean} failed
 * @property {string | null} error why it failed
 * @property {boolean} async whether it ran on several threads
 * @property {number} start in seconds since the start of the recording
 * @property {number} duration in ms
 * @property {string} thread that ran it, or that ended an asynchronous one
 * @property {number} bytesIn
 * @property {number} bytesOut
 */

/**
 * @typedef {object} InstanceDetail
 * @property {number} javaSamples
 * @property {number} nativeSamples
 * @property {{thread: string, offset: number, duration: number}[]} segments the threads
 *           that worked for it, offset in ms since its start
 * @property {boolean} exceptionsRecorded
 * @property {Record<string, string | null>} ioThresholds by event, null if not recorded
 * @property {{offset: number, type: string, message: string | null, frame: string | null}[]} exceptions
 *           offset in ms since the start of the operation
 * @property {{offset: number, kind: string, target: string, bytes: number, duration: number}[]} io
 *           file and socket accesses longer than the thresholds
 */

/**
 * An interval of a session, in seconds since its start; both undefined for
 * the whole session.
 *
 * @typedef {{from?: number, to?: number}} Interval
 */

/**
 * @param {URLSearchParams} q
 * @param {Interval} interval
 */
function addInterval(q, interval) {
  if (interval.from !== undefined) q.set('from', String(interval.from));
  if (interval.to !== undefined) q.set('to', String(interval.to));
}

/**
 * @param {number} id
 * @param {number} instance
 * @returns {Promise<InstanceRow & InstanceDetail>}
 */
export function getInstance(id, instance) {
  return call(`api/sessions/${id}/instances/${instance}`);
}

/**
 * @param {string} path
 * @param {RequestInit} [init]
 * @returns {Promise<any>}
 */
async function call(path, init) {
  if (staticFiles) {
    if (init && init.method && init.method !== 'GET')
      throw new Error('Not available in a static export');
    const file = staticFiles[staticKey(path)];
    if (!file)
      throw new Error(`Not in this static export: ${staticKey(path)}`);
    path = file;
  }
  const resp = await fetch(path, init);
  if (!resp.ok)
    throw new Error(`${resp.status} ${(await resp.text()) || resp.statusText}`);
  return resp.json();
}

/**
 * @typedef {object} Live
 * @property {boolean} observing whether operations may come: a flak app is observed, or the API was used
 * @property {boolean} [fed] whether they are those of another JVM, read from its events
 * @property {string[]} apps the paths of the flak apps observed, "" for an app at the root
 * @property {number} window in minutes
 * @property {number[]} windows those that can be asked for
 * @property {OperationRow[]} operations sorted by total time
 */

/**
 * @param {number} window in minutes
 * @returns {Promise<Live>} the operations of the application, over the last minutes
 */
export function getLive(window) {
  return call(`api/live?window=${window}`);
}

/**
 * @typedef {object} AutoState
 * @property {boolean} observing whether operations may come: nothing triggers sessions otherwise
 * @property {boolean} [fed] whether they are those of another JVM, read from its events once they end
 * @property {boolean} flakApps whether flak apps are observed, whose requests may come from a browser
 * @property {boolean} armed
 * @property {string[]} [patterns] operations that trigger a session, * matching anything; empty for any
 * @property {boolean} [browser] whether only the requests of an armed browser count
 * @property {boolean} [thisBrowser] whether this browser is the one armed
 * @property {boolean} [repeat] a session each time, or once
 * @property {number} [idle] seconds without a matching operation before a session stops
 * @property {number} [max] maximum duration of a session, in seconds
 * @property {string | null} [name] that of the sessions it starts
 * @property {number | null} recording the id of the session it started, while it records
 * @property {boolean} [waiting] whether that session records while waiting for a matching operation to
 *           end: from a JVM connected to from outside, operations are only known once they end
 */

/**
 * @typedef {object} AutoArming
 * @property {string} patterns operations separated by commas
 * @property {boolean} browser only the requests of this browser count
 * @property {boolean} repeat a session each time, or once
 * @property {number} idle in seconds
 * @property {number} max in seconds
 * @property {string} [name] that of the sessions it starts
 */

/** @returns {Promise<AutoState>} */
export function getAuto() {
  return call('api/auto');
}

/**
 * @param {AutoArming} a
 * @returns {Promise<AutoState>}
 */
export function armAuto(a) {
  const q = new URLSearchParams({
    patterns: a.patterns, browser: String(a.browser), repeat: String(a.repeat),
    idle: String(a.idle), max: String(a.max),
  });
  if (a.name)
    q.set('name', a.name);
  return call(`api/auto?${q}`, {method: 'POST'});
}

/** @returns {Promise<AutoState>} */
export function disarmAuto() {
  return call('api/auto', {method: 'DELETE'});
}

/**
 * A JVM of the machine, in standalone mode.
 *
 * @typedef {object} Jvm
 * @property {number} pid
 * @property {string} name as it lists itself: its main class or jar, and its arguments
 * @property {string} app what its sessions are kept by: its main class, or its jar without version
 * @property {boolean} [attached]
 */

/**
 * An application whose sessions are kept, in standalone mode.
 *
 * @typedef {object} StoredApp
 * @property {string} app
 * @property {string} path of its interface, relative to the page of the JVMs
 * @property {number | null} pid of its JVM attached
 * @property {number} sessions
 * @property {string | null} last start of its latest session
 */

/**
 * @typedef {object} Jvms
 * @property {string} directory where the sessions are kept
 * @property {Jvm[]} jvms
 * @property {StoredApp[]} apps the latest session first
 * @property {{event: string, name: string}[]} mappedEvents events of the applications read as operations,
 *           e.g. from ~/.jfr-web/operation-events.properties
 */

/** @returns {Promise<Jvms>} the JVMs of the machine, from the page that lists them */
export function listJvms() {
  return call('api/jvms');
}

/**
 * Attaches to a JVM, instead of the one attached.
 *
 * @param {number} pid
 * @param {string} [root] the path of the page of the JVMs, relative to this one
 * @returns {Promise<{app: string, path: string}>} path: of the interface of its
 *          application, relative to the page of the JVMs
 */
export function attachJvm(pid, root = '') {
  return call(`${root}api/jvms/${pid}/attach`, {method: 'POST'});
}

/**
 * The application profiled, in standalone mode.
 *
 * @typedef {object} Target
 * @property {string} app
 * @property {number | null} pid of its JVM attached
 * @property {string | null} name of its JVM attached
 * @property {Jvm[]} running its JVMs running
 */

/**
 * Disconnects from the JVM attached, stopping its session if one records.
 *
 * @param {string} [root] the path of the page of the JVMs, relative to this one
 */
export function detachJvm(root = '') {
  return call(`${root}api/detach`, {method: 'POST'});
}

/**
 * What is profiled.
 *
 * @typedef {object} About
 * @property {string} app the name of the application, or of the recordings opened
 * @property {boolean} recordingsOnly whether there are only recordings, opened from files, with no JVM
 *           behind them: no live operations, auto mode or session to start
 * @property {boolean} history whether an operation history can be browsed
 * @property {'wall' | 'alloc'} samples those flame graphs open on: jfr-web open --alloc
 */

/** @type {About | null} */
let about = null;

/** @returns {About | null} what is profiled, null if not told, e.g. in a static export */
export function getAbout() {
  return about;
}

/**
 * Whether there are only recordings to browse, with no JVM behind them: a
 * static export, or recordings opened from files.
 */
export function recordingsOnly() {
  return staticFiles !== null || about?.recordingsOnly === true;
}

/** @returns {Promise<Target | null>} null when the profiler is embedded in the JVM it profiles */
export async function getTarget() {
  if (staticFiles)
    return null;
  const resp = await fetch('api/target');
  return resp.ok ? resp.json() : null;
}

/**
 * @typedef {object} SnapshotState
 * @property {boolean} available whether a rolling recording runs, to take snapshots of
 * @property {number} [window] what a snapshot holds: the last seconds
 * @property {string} [directory]
 */

/** @returns {Promise<SnapshotState>} */
export function getSnapshots() {
  return call('api/snapshots');
}

/** @returns {Promise<Session>} the session listing the snapshot */
export function saveSnapshot() {
  return call('api/snapshots', {method: 'POST'});
}

/** @returns {Promise<Session[]>} the latest first */
export function listSessions() {
  return call('api/sessions');
}

/**
 * @param {number} id
 * @returns {Promise<Session>}
 */
export function getSession(id) {
  return call(`api/sessions/${id}`);
}

/** @returns {Promise<Session>} */
export function startSession() {
  return call('api/sessions', {method: 'POST'});
}

/**
 * @param {number} id
 * @param {string} name empty to remove it
 * @returns {Promise<Session>}
 */
export function renameSession(id, name) {
  return call(`api/sessions/${id}/name?${new URLSearchParams({name})}`, {method: 'POST'});
}

/**
 * @param {number} id
 * @returns {Promise<Session>}
 */
export function stopSession(id) {
  return call(`api/sessions/${id}/stop`, {method: 'POST'});
}

/**
 * @param {number} id
 * @returns {Promise<Report>}
 */
export function getReport(id) {
  return call(`api/sessions/${id}/report`);
}

/**
 * @param {number} id
 * @param {'alloc'} [samples] its allocation samples, instead of its Java and native ones
 * @returns {Promise<any>} the samples of the session, for profile/profile.js to decode
 */
export function getProfile(id, samples) {
  return call(`api/sessions/${id}/profile${samples ? `?samples=${samples}` : ''}`);
}

/**
 * @typedef {object} TimelineData
 * @property {string} start ISO-8601, the start of the recording
 * @property {number} bucket the duration of a bucket, in ms
 * @property {number} buckets their number
 * @property {{name: string, values: number[]}[]} series operations in flight per bucket,
 *           on average, by name, the most time first
 * @property {number[] | null} other the same for the other names, null if none
 * @property {number[]} [javaSamples] samples of Java code per bucket, for a session
 */

/**
 * @typedef {object} HistorySlow
 * @property {string} end ISO-8601, when it ended
 * @property {string} name
 * @property {string | null} detail
 * @property {number} status the HTTP status of a request, 0 for other operations
 * @property {boolean} failed
 * @property {number} duration in ms
 * @property {string} thread
 */

/**
 * @typedef {object} History
 * @property {boolean} available whether the profiler was given a history
 * @property {string} [directory]
 * @property {string} [from] ISO-8601
 * @property {string} [to] ISO-8601
 * @property {number} [intervals] written during the period: none means the history was not written then
 * @property {OperationRow[]} [operations] sorted by total time
 * @property {HistorySlow[]} [slowest] the slowest first
 * @property {TimelineData & {covered: boolean[], seriesSeconds: TimelineData['series'],
 *            otherSeconds: number[] | null}} [timeline] operations per bucket, by name, and
 *           the seconds they took
 */

/**
 * @param {Date} from
 * @param {Date} to
 * @param {string} [name] keeps the slowest operations of a name
 * @returns {Promise<History>}
 */
export function getHistory(from, to, name) {
  const q = new URLSearchParams({from: from.toISOString(), to: to.toISOString()});
  if (name) q.set('name', name);
  return call(`api/history?${q}`);
}

/**
 * @param {number} id
 * @param {Interval} [range] a part of the session, in finer buckets: to zoom in
 * @returns {Promise<TimelineData>} its start is that of the part
 */
export function getTimeline(id, range = {}) {
  const q = new URLSearchParams();
  addInterval(q, range);
  return call(`api/sessions/${id}/timeline?${q}`);
}

/**
 * @param {number} id
 * @returns {string} where the .jfr file is downloaded from
 */
export function downloadUrl(id) {
  return staticUrl(`api/sessions/${id}/recording.jfr`);
}

/**
 * The files of a static export, by request, null when a profiler answers:
 * see jfrweb.export.StaticExport.
 *
 * @type {Record<string, string> | null}
 */
let staticFiles = null;

/** @type {{from: string, to: string} | null} the period of the history exported */
let staticHistory = null;

/** @type {string | null} the title a static export was given, for its top bar */
let staticTitle = null;

/**
 * Resolves once it is known whether the page is a static export, which a
 * static.json next to it tells. Views wait for it.
 */
export const ready = fetch('static.json')
  .then(r => r.ok ? r.json() : null)
  .then(m => {
    if (m) {
      staticFiles = m.files;
      staticHistory = m.history;
      staticTitle = m.title ?? null;
    }
  })
  .catch(() => undefined)
  .then(async () => {
    if (!staticFiles)
      about = await call('api/about').catch(() => null);
  });

/**
 * Whether the page is a static export: only what can be reached by clicking
 * was computed, not what takes a free input (intervals, combined filters).
 */
export function isStatic() {
  return staticFiles !== null;
}

/** @returns {{from: string, to: string} | null} the period of the history exported, if any */
export function getStaticHistory() {
  return staticHistory;
}

/** @returns {string | null} the title a static export was given, if any */
export function getStaticTitle() {
  return staticTitle;
}

/**
 * What a static export looks a request up by: the path, then its parameters
 * sorted by name, not encoded.
 *
 * @param {string} path
 */
function staticKey(path) {
  const [p, q] = path.split('?');
  const params = [...new URLSearchParams(q ?? '')].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0);
  return params.length ? `${p}?${params.map(([k, v]) => `${k}=${v}`).join('&')}` : p;
}

/**
 * A URL the browser loads by itself (a flame graph, a download), mapped to
 * its file in a static export, or to a page telling it is not there.
 *
 * @param {string} path
 */
function staticUrl(path) {
  if (!staticFiles)
    return path;
  return staticFiles[staticKey(path)] ?? 'missing.html';
}
