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
 * @typedef {object} FlameFilter
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
  if (filter.endpoint) q.set('endpoint', filter.endpoint);
  if (filter.thread) q.set('thread', filter.thread);
  if (filter.from !== undefined) q.set('from', String(filter.from));
  if (filter.to !== undefined) q.set('to', String(filter.to));
  if (filter.all) q.set('all', 'true');
  const qs = q.toString();
  return `api/sessions/${id}/flame${qs ? '?' + qs : ''}`;
}
