import {test} from 'node:test';
import assert from 'node:assert/strict';
import {decode} from '../../../main/resources/jfrweb/ui/js/profile/profile.js';
import {operationRows, instanceRows} from '../../../main/resources/jfrweb/ui/js/profile/operations.js';

const ms = 1e6;

// strings, sorted: 0 "GET /a", 1 "GET /a/1", 2 "GET /a/2", 3 "boom", 4 "job", 5 "main", 6 "pool-1"
const strings = ['GET /a', 'GET /a/1', 'GET /a/2', 'boom', 'job', 'main', 'pool-1'];

// operations, in the order of the recording (start, duration in ms):
// 0 GET /a   0-10    detail /a/1, 200, 100 B in, 1000 out
// 1 job      5-25    async, on pool-1
// 2 GET /a   20-21.234567  detail /a/2, 500, failed: boom
// 3 job      30-50   the same duration as 1
// 4 GET /a   40-40.0049
const start = [0, 5, 20, 30, 40].map(t => t * ms);
const doc = {
  version: 2,
  weights: [20, 5],
  strings: strings.map(s => ' ' + s),
  stacks: [],
  samples: {time: [], stack: [], thread: [], operation: [], kind: []},
  threads: [],
  operations: {
    name: [0, 4, 0, 4, 0],
    start: start.map((t, i) => t - (i ? start[i - 1] : 0)),
    duration: [10 * ms, 20 * ms, 1_234_567, 20 * ms, 4_900],
    thread: [5, 6, 5, 5, 5],
    detail: [1, -1, 2, -1, -1],
    status: [200, 0, 500, 0, 200],
    failed: [0, 0, 1, 0, 0],
    error: [-1, -1, 3, -1, -1],
    async: [0, 1, 0, 0, 0],
    bytesIn: [100, 0, 0, 0, 0],
    bytesOut: [1000, 0, 0, 0, 0],
  },
};
const p = decode(doc);

test('the operations by name, the biggest total first, with their percentiles', () => {
  assert.deepEqual(operationRows(p, {}), [
    {name: 'job', count: 2, total: 40, p50: 20, p99: 20, max: 20, errors: 0, bytesIn: 0, bytesOut: 0},
    // 10 + 1.234567 + 0.0049 ms, to the hundredth as the server rounds them
    {name: 'GET /a', count: 3, total: 11.24, p50: 1.23, p99: 10, max: 10, errors: 1, bytesIn: 100, bytesOut: 1000},
  ]);
});

test('an interval keeps the operations in flight during it, with their whole duration', () => {
  // 21 ms: job 1 runs, GET /a 2 has just ended (at 21.234567)
  assert.deepEqual(operationRows(p, {from: 0.021, to: 0.022}).map(r => `${r.name} ${r.count} ${r.total}`),
                   ['job 1 20', 'GET /a 1 1.23']);
  // bounds excluded: GET /a 0 ends at 10 ms, job 3 starts at 30
  assert.deepEqual(operationRows(p, {from: 0.010, to: 0.030}).map(r => `${r.name} ${r.count}`),
                   ['job 1', 'GET /a 1']);
  assert.deepEqual(operationRows(p, {from: 0.060}), []);
  assert.equal(operationRows(p, {to: 0.001})[0].name, 'GET /a', 'open at the start');
});

test('the slowest first, equal durations in the order of the recording', () => {
  assert.deepEqual(instanceRows(p, undefined, {}, 3).map(r => r.id), [1, 3, 0]);
  assert.deepEqual(instanceRows(p, 'GET /a', {}, 20).map(r => r.id), [0, 2, 4]);
  assert.deepEqual(instanceRows(p, '', {from: 0.035}, 20).map(r => r.id), [3, 4]);
});

test('a slowest row as the server writes it', () => {
  assert.deepEqual(instanceRows(p, 'GET /a', {from: 0.02, to: 0.021}, 1), [{
    id: 2, name: 'GET /a', detail: 'GET /a/2', status: 500, failed: true, error: 'boom', async: false,
    start: 0.02, duration: 1.23, thread: 'main', bytesIn: 0, bytesOut: 0,
  }]);
  const job = instanceRows(p, 'job', {}, 1)[0];
  assert.equal(job.async, true);
  assert.equal(job.thread, 'pool-1');
  assert.equal(job.detail, null);
  assert.equal(job.error, null);
});
