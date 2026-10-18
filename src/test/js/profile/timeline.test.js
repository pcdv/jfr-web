import {test} from 'node:test';
import assert from 'node:assert/strict';
import {decode} from '../../../main/resources/jfrweb/ui/js/profile/profile.js';
import {timeline} from '../../../main/resources/jfrweb/ui/js/profile/timeline.js';

const ms = 1e6;

/**
 * A recording of 2 s with operations of the names given, each [name, start
 * ms, duration ms], and Java samples at the times given, in ms.
 */
function profile(operations, javaAt = [], end = 2000) {
  const names = [...new Set(operations.map(o => o[0]))].sort();
  let previous = 0;
  return decode({
    version: 3,
    weights: [20, 5],
    start: '2026-10-18T08:00:00.000Z',
    end: end * ms,
    strings: names.map(n => ' ' + n),
    stacks: [],
    samples: {time: javaAt.map((t, i) => t - (i ? javaAt[i - 1] : 0)), stack: javaAt.map(() => -1),
              thread: javaAt.map(() => 0), operation: javaAt.map(() => -1), kind: javaAt.map(() => 0)},
    threads: [[0, 1]],
    operations: {
      name: operations.map(o => names.indexOf(o[0])),
      start: operations.map(o => { const d = o[1] * ms - previous; previous = o[1] * ms; return d; }),
      duration: operations.map(o => o[2] * ms),
      thread: operations.map(() => 0), detail: operations.map(() => -1), status: operations.map(() => 0),
      failed: operations.map(() => 0), error: operations.map(() => -1), async: operations.map(() => 0),
      bytesIn: operations.map(() => 0), bytesOut: operations.map(() => 0),
    },
  });
}

test('the whole recording: round buckets, operations in flight on average', () => {
  // 2 s: 300 buckets of 10 ms at most, so 200
  const t = timeline(profile([['a', 0, 15], ['a', 5, 10]], [3, 12, 1999]), {});
  assert.equal(t.bucket, 10);
  assert.equal(t.buckets, 200);
  assert.equal(t.start, '2026-10-18T08:00:00.000Z');
  assert.deepEqual(t.series.map(s => s.name), ['a']);
  // 0-10 ms: the first all along, the second half of it; 10-20: halves of each
  assert.deepEqual(t.series[0].values.slice(0, 3), [1.5, 1, 0]);
  assert.equal(t.other, null);
  assert.deepEqual([t.javaSamples[0], t.javaSamples[1], t.javaSamples[199]], [1, 1, 1]);
});

test('a part, in finer buckets, starting from it', () => {
  const t = timeline(profile([['a', 100, 50]]), {from: 0.1, to: 0.2});
  assert.equal(t.bucket, 10);
  assert.equal(t.buckets, 10);
  assert.equal(t.start, '2026-10-18T08:00:00.100Z');
  assert.deepEqual(t.series[0].values, [1, 1, 1, 1, 1, 0, 0, 0, 0, 0]);
  // a part past the end stops at it
  assert.equal(timeline(profile([]), {from: 1.5, to: 9}).buckets, 50);
});

test('the 8 names with the most time get a series, the others are added up', () => {
  const ops = [];
  for (let i = 0; i < 10; i++)
    ops.push([`op${i}`, 0, (i + 1) * 10]);
  const t = timeline(profile(ops), {});
  assert.deepEqual(t.series.map(s => s.name), ['op9', 'op8', 'op7', 'op6', 'op5', 'op4', 'op3', 'op2']);
  // op0 and op1 in the first bucket: 2 in flight
  assert.equal(t.other?.[0], 2);
  // the same series in a part: colors kept when zooming
  assert.deepEqual(timeline(profile(ops), {from: 0.05, to: 0.06}).series.map(s => s.name), t.series.map(s => s.name));
});

test('values to the thousandth, as the server sends them', () => {
  const t = timeline(profile([['a', 0, 1 / 3]]), {from: 0, to: 0.01});
  assert.equal(t.series[0].values[0], 0.033);
});
