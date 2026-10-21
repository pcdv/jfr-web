import {test} from 'node:test';
import assert from 'node:assert/strict';
import {heatOf} from '../../../main/resources/jfrweb/ui/js/flame/heat.js';

/** A frame: its self time, its callees; the total follows. */
function frame(name, self, ...children) {
  return {name, self, children, types: [], total: self + children.reduce((s, c) => s + c.total, 0)};
}

test('a method with one frame is cold, however wide', () => {
  const wide = frame('wide', 600);
  const root = frame('all', 0, frame('a', 0, wide), frame('b', 400));
  const heat = heatOf(root);
  assert.equal(heat.elsewhere(wide), 0);
  assert.equal(heat.of(wide), 0);
  assert.equal(heat.of(root), 0);
});

test('a method spread over several frames is hot in each of them', () => {
  const hash = [frame('hash', 100), frame('hash', 100), frame('hash', 100)];
  const root = frame('all', 0, frame('a', 0, hash[0]), frame('b', 0, hash[1]), frame('c', 0, hash[2]),
                     frame('big', 700));
  const heat = heatOf(root);
  assert.equal(heat.method('hash'), 300);
  // the 200 ms in the two other frames, of 1000
  assert.equal(heat.elsewhere(hash[0]), 0.2);
  // the hottest frame of the graph
  assert.equal(heat.of(hash[0]), 1);
  assert.equal(heat.of(root.children[3]), 0);
});

test('the wider the frame, the cooler: it already shows more of its method', () => {
  const narrow = frame('m', 50);
  const wide = frame('m', 250);
  const root = frame('all', 0, frame('a', 0, narrow), frame('b', 0, wide), frame('c', 700));
  const heat = heatOf(root);
  assert.ok(heat.of(narrow) > heat.of(wide));
  assert.ok(heat.of(wide) > 0);
});

test('a graph without a method spread out stays cold: no frame is hot for a trifle', () => {
  const tiny = [frame('m', 1), frame('m', 1)];
  const root = frame('all', 0, frame('a', 0, tiny[0]), frame('b', 0, tiny[1]), frame('c', 998));
  const heat = heatOf(root);
  assert.ok(heat.of(tiny[0]) < 0.3);
});

test('callers take the heat of their name, not of what they call', () => {
  const callee = frame('hash', 100);
  const caller = frame('caller', 0, callee);
  const root = frame('all', 0, caller, frame('other', 0, frame('hash', 100)), frame('big', 800));
  const heat = heatOf(root);
  assert.equal(heat.of(caller), 0);
  assert.ok(heat.of(callee) > 0);
});
