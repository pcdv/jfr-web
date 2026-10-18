import {test} from 'node:test';
import assert from 'node:assert/strict';
import * as fmt from '../../main/resources/jfrweb/ui/js/format.js';

test('durations: tenths below 100 ms, whole ms up to 10 s, then tenths of seconds', () => {
  assert.equal(fmt.duration(0.42), '0.4 ms');
  assert.equal(fmt.duration(99.94), '99.9 ms');
  assert.equal(fmt.duration(100), '100 ms');
  assert.equal(fmt.duration(9999), '9999 ms');
  assert.equal(fmt.duration(12_345), '12.3 s');
});

test('sizes in binary units', () => {
  assert.equal(fmt.bytes(512), '512 B');
  assert.equal(fmt.bytes(1536), '1.5 KiB');
  assert.equal(fmt.bytes(3 * 1024 * 1024), '3.0 MiB');
});

test('intervals: empty for the whole session, an open bound shown', () => {
  assert.equal(fmt.interval({}), '');
  assert.equal(fmt.interval({from: 2, to: 4.2}), '2–4.2 s');
  assert.equal(fmt.interval({from: 2}), '2–end');
  assert.equal(fmt.interval({to: 3}), '0–3 s');
});

test('percentages rounded', () => {
  assert.equal(fmt.percent(0.256), '26%');
  assert.equal(fmt.percent(1), '100%');
});
