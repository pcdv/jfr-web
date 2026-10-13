// @ts-check
/**
 * Client of the REST API of the profiler. Paths are relative to the page,
 * which is served at the root of the profiler's app.
 */

/**
 * @typedef {object} Session
 * @property {number} id
 * @property {'RECORDING' | 'STOPPED'} state
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
 * @property {{java: Sampling, native: Sampling, operationShareOfNative: number | null,
 *            operationEvents: boolean, operations: number}} sampling
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
 * @param {Interval} interval keeps the operations in flight during it
 * @returns {Promise<OperationRow[]>} sorted by total time
 */
export function listOperations(id, interval) {
  const q = new URLSearchParams();
  addInterval(q, interval);
  return call(`api/sessions/${id}/operations?${q}`);
}

/**
 * @param {number} id
 * @param {string | undefined} name all operations if undefined
 * @param {Interval} interval keeps the operations in flight during it
 * @param {number} limit
 * @returns {Promise<InstanceRow[]>} the slowest first
 */
export function listInstances(id, name, interval, limit) {
  const q = new URLSearchParams({limit: String(limit)});
  if (name) q.set('name', name);
  addInterval(q, interval);
  return call(`api/sessions/${id}/instances?${q}`);
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
 * @typedef {object} FlameFilter
 * @property {number} [instance] keeps the samples of one operation, ignoring the other filters
 * @property {string} [name] keeps the samples of the operations of a name
 * @property {string} [thread] keeps the samples of one thread
 * @property {number} [from] seconds since the start of the recording
 * @property {number} [to] seconds since the start of the recording
 * @property {boolean} [all] adds the samples taken outside operations
 */

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
 * @property {boolean} flakApps whether flak apps are observed, whose requests may come from a browser
 * @property {boolean} armed
 * @property {string[]} [patterns] operations that trigger a session, * matching anything; empty for any
 * @property {boolean} [browser] whether only the requests of an armed browser count
 * @property {boolean} [thisBrowser] whether this browser is the one armed
 * @property {boolean} [repeat] a session each time, or once
 * @property {number} [idle] seconds without a matching operation before a session stops
 * @property {number} [max] maximum duration of a session, in seconds
 * @property {number | null} recording the id of the session it started, while it records
 */

/**
 * @typedef {object} AutoArming
 * @property {string} patterns operations separated by commas
 * @property {boolean} browser only the requests of this browser count
 * @property {boolean} repeat a session each time, or once
 * @property {number} idle in seconds
 * @property {number} max in seconds
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
  return call(`api/auto?${q}`, {method: 'POST'});
}

/** @returns {Promise<AutoState>} */
export function disarmAuto() {
  return call('api/auto', {method: 'DELETE'});
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

/** @returns {Promise<Session>} */
export function startSession() {
  return call('api/sessions', {method: 'POST'});
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
 * @param {number} id
 * @param {FlameFilter} filter
 * @returns {string} the URL of the flame graph, a page of its own
 */
export function flameUrl(id, filter) {
  const q = new URLSearchParams();
  if (filter.instance !== undefined) q.set('instance', String(filter.instance));
  if (filter.name) q.set('name', filter.name);
  if (filter.thread) q.set('thread', filter.thread);
  if (filter.from !== undefined) q.set('from', String(filter.from));
  if (filter.to !== undefined) q.set('to', String(filter.to));
  if (filter.all) q.set('all', 'true');
  const qs = q.toString();
  return staticUrl(`api/sessions/${id}/flame${qs ? '?' + qs : ''}`);
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
    }
  })
  .catch(() => undefined);

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
