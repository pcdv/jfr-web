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
 * @property {number} inRequests
 */

/**
 * @typedef {object} EndpointRow
 * @property {string} endpoint e.g. "GET /api/user/:id"
 * @property {number} count
 * @property {number} total in ms
 * @property {number} p50 in ms
 * @property {number} p99 in ms
 * @property {number} max in ms
 * @property {number} errors 5xx responses
 * @property {number} bytesIn
 * @property {number} bytesOut
 */

/**
 * @typedef {object} Report
 * @property {Session} session
 * @property {{start: string, end: string, jvm: string, jvmArguments: string,
 *            javaArguments: string, os: string, enabledEvents: number}} recording
 * @property {{java: Sampling, native: Sampling, requestShareOfNative: number | null,
 *            requestEvent: boolean, requests: number}} sampling
 * @property {string[]} biases
 * @property {{name: string, samples: number}[]} threads
 * @property {EndpointRow[]} endpoints
 */

/**
 * @typedef {object} RequestRow
 * @property {number} id the index of the request in the recording
 * @property {string} endpoint
 * @property {string} method
 * @property {string} path
 * @property {number} status
 * @property {number} start in seconds since the start of the recording
 * @property {number} duration in ms
 * @property {string} thread
 * @property {number} bytesIn
 * @property {number} bytesOut
 */

/**
 * @typedef {object} RequestDetail
 * @property {number} javaSamples
 * @property {number} nativeSamples
 * @property {boolean} exceptionsRecorded
 * @property {Record<string, string | null>} ioThresholds by event, null if not recorded
 * @property {{offset: number, type: string, message: string | null, frame: string | null}[]} exceptions
 *           offset in ms since the start of the request
 * @property {{offset: number, kind: string, target: string, bytes: number, duration: number}[]} io
 *           file and socket accesses longer than the thresholds
 */

/**
 * @param {number} id
 * @param {string | undefined} endpoint all endpoints if undefined
 * @param {number} limit
 * @returns {Promise<RequestRow[]>} the slowest first
 */
export function listRequests(id, endpoint, limit) {
  const q = new URLSearchParams({limit: String(limit)});
  if (endpoint) q.set('endpoint', endpoint);
  return call(`api/sessions/${id}/requests?${q}`);
}

/**
 * @param {number} id
 * @param {number} request
 * @returns {Promise<RequestRow & RequestDetail>}
 */
export function getRequest(id, request) {
  return call(`api/sessions/${id}/requests/${request}`);
}

/**
 * @typedef {object} FlameFilter
 * @property {number} [request] keeps the samples of one request, ignoring the other filters
 * @property {string} [endpoint] keeps the samples of one endpoint
 * @property {string} [thread] keeps the samples of one thread
 * @property {number} [from] seconds since the start of the recording
 * @property {number} [to] seconds since the start of the recording
 * @property {boolean} [all] adds the samples taken outside requests
 */

/**
 * @param {string} path
 * @param {RequestInit} [init]
 * @returns {Promise<any>}
 */
async function call(path, init) {
  const resp = await fetch(path, init);
  if (!resp.ok)
    throw new Error(`${resp.status} ${(await resp.text()) || resp.statusText}`);
  return resp.json();
}

/**
 * @typedef {object} Live
 * @property {boolean} observing whether the profiler observes any app
 * @property {string[]} apps their paths, "" for an app at the root
 * @property {number} window in minutes
 * @property {number[]} windows those that can be asked for
 * @property {EndpointRow[]} endpoints sorted by total time
 */

/**
 * @param {number} window in minutes
 * @returns {Promise<Live>} the endpoints of the apps observed, over the last minutes
 */
export function getLive(window) {
  return call(`api/live?window=${window}`);
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
 * @property {{endpoint: string, values: number[]}[]} series requests in flight per bucket,
 *           on average, by endpoint, the most time first
 * @property {number[] | null} other the same for the other endpoints, null if none
 * @property {number[]} javaSamples samples of Java code per bucket
 */

/**
 * @param {number} id
 * @returns {Promise<TimelineData>}
 */
export function getTimeline(id) {
  return call(`api/sessions/${id}/timeline`);
}

/**
 * @param {number} id
 * @returns {string} where the .jfr file is downloaded from
 */
export function downloadUrl(id) {
  return `api/sessions/${id}/recording.jfr`;
}

/**
 * @param {number} id
 * @param {FlameFilter} filter
 * @returns {string} the URL of the flame graph, a page of its own
 */
export function flameUrl(id, filter) {
  const q = new URLSearchParams();
  if (filter.request !== undefined) q.set('request', String(filter.request));
  if (filter.endpoint) q.set('endpoint', filter.endpoint);
  if (filter.thread) q.set('thread', filter.thread);
  if (filter.from !== undefined) q.set('from', String(filter.from));
  if (filter.to !== undefined) q.set('to', String(filter.to));
  if (filter.all) q.set('all', 'true');
  const qs = q.toString();
  return `api/sessions/${id}/flame${qs ? '?' + qs : ''}`;
}
