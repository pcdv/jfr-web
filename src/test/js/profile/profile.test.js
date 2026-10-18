import {test} from 'node:test';
import assert from 'node:assert/strict';
import {decode, tree, mainType, COMPILED, INTERPRETED, SYNTHETIC, NO_OPERATION}
  from '../../../main/resources/jfrweb/ui/js/profile/profile.js';

// strings, sorted: 0 "(truncated)", 1 "GET /a", 2 "GET /b", 3 "Main.run",
// 4 "Main.work", 5 "main", 6 "pool-1", 7 "x.Sort.sort"
const strings = ['(truncated)', 'GET /a', 'GET /b', 'Main.run', 'Main.work', 'main', 'pool-1', 'x.Sort.sort'];

/** Front-coded as ProfileWriter writes them. */
function frontCode(list) {
  let previous = '';
  return list.map(s => {
    let shared = 0;
    while (shared < Math.min(previous.length, s.length, 95) && previous[shared] === s[shared])
      shared++;
    previous = s;
    return String.fromCharCode(32 + shared) + s.substring(shared);
  });
}

const f = (string, type) => string << 3 | type;

// nodes, depth first: 0 Main.run, 1 Main.run > Main.work, 2 Main.run >
// Main.work > x.Sort.sort, 3 Main.run > Main.work (interpreted), 4 Main.run >
// x.Sort.sort
const doc = {
  version: 1,
  weights: [20, 5],
  strings: frontCode(strings),
  stacks: [0, f(3, INTERPRETED),
           1, f(4, COMPILED),
           2, f(7, COMPILED),
           1, f(4, INTERPRETED),
           1, f(7, COMPILED)],
  samples: {
    // at 0, 1000, 1500, 2000, 3000 and 3000 ms
    time: [0, 1000, 500, 500, 1000, 0],
    stack: [2, 2, 3, 1, 4, -1],
    thread: [0, 0, 1, 1, 0, 0],
    operation: [0, 0, 1, 1, -1, 0],
    kind: [0, 0, 0, 1, 0, 0],
  },
  threads: [[5, 1], [6, 22]],
  // the columns operations.test.js looks at, of no interest here
  operations: {name: [1, 2], start: [0, 0], duration: [0, 0], thread: [5, 6], detail: [-1, -1], status: [0, 0],
               failed: [0, 0], error: [-1, -1], async: [0, 0], bytesIn: [0, 0], bytesOut: [0, 0]},
};

const p = decode(doc);

/** A tree as lines "path total self", the root first, to compare at a glance. */
function lines(t, prefix = '') {
  const path = prefix ? `${prefix};${t.name}` : t.name;
  return [`${path} ${t.total} ${t.self}`, ...t.children.flatMap(c => lines(c, path))];
}

test('strings, stacks and samples decoded', () => {
  assert.deepEqual(p.strings, strings);
  assert.deepEqual([...p.parent], [-1, 0, 1, 0, 0]);
  assert.deepEqual([...p.time], [0, 1000, 1500, 2000, 3000, 3000]);
  assert.deepEqual(p.threads, [{name: 'main', id: 1}, {name: 'pool-1', id: 22}]);
  assert.throws(() => decode({...doc, version: 2}), /version 2/);
});

test('rooted by operation; samples outside left out; a sample with no stack counts for its operation', () => {
  const {root, samples} = tree(p, {});
  assert.equal(samples, 5);
  assert.deepEqual(lines(root), [
    'all 85 0',
    'all;GET /a 60 20',
    'all;GET /a;Main.run 40 0',
    'all;GET /a;Main.run;Main.work 40 0',
    'all;GET /a;Main.run;Main.work;x.Sort.sort 40 40',
    'all;GET /b 25 0',
    'all;GET /b;Main.run 25 0',
    'all;GET /b;Main.run;Main.work 25 25',
  ]);
});

test('frames of a name merged whatever their type, the types counted', () => {
  const {root} = tree(p, {name: 'GET /b'});
  const work = root.children[0].children[0].children[0];
  assert.equal(work.name, 'Main.work');
  assert.equal(work.types[INTERPRETED], 20);
  assert.equal(work.types[COMPILED], 5);
  assert.equal(mainType(work), INTERPRETED);
  assert.equal(mainType(root.children[0]), SYNTHETIC, 'an operation');
});

test('samples outside operations under their thread', () => {
  assert.deepEqual(lines(tree(p, {all: true}).root).filter(l => l.includes(NO_OPERATION)), [
    `all;${NO_OPERATION} 20 0`,
    `all;${NO_OPERATION};[main tid=1] 20 0`,
    `all;${NO_OPERATION};[main tid=1];Main.run 20 0`,
    `all;${NO_OPERATION};[main tid=1];Main.run;x.Sort.sort 20 20`,
  ]);
});

test('merged: one tree for all operations', () => {
  assert.deepEqual(lines(tree(p, {merged: true}).root), [
    'all 85 20',
    'all;Main.run 65 0',
    'all;Main.run;Main.work 65 25',
    'all;Main.run;Main.work;x.Sort.sort 40 40',
  ]);
});

test('reversed: the methods running at the root, the operation last', () => {
  assert.deepEqual(lines(tree(p, {reversed: true, name: 'GET /a'}).root), [
    'all 60 0',
    'all;GET /a 20 20',
    'all;x.Sort.sort 40 0',
    'all;x.Sort.sort;Main.work 40 0',
    'all;x.Sort.sort;Main.work;Main.run 40 0',
    'all;x.Sort.sort;Main.work;Main.run;GET /a 40 40',
  ]);
});

test('Java only, one thread, one operation name', () => {
  assert.equal(tree(p, {java: true}).root.total, 80, 'the native sample left out');
  assert.equal(tree(p, {thread: 'pool-1'}).root.total, 25);
  assert.equal(tree(p, {thread: 'pool-1', all: true}).samples, 2);
  assert.equal(tree(p, {name: 'GET /a'}).samples, 3);
});

test('an interval keeps the samples from its start, its end excluded', () => {
  assert.equal(tree(p, {from: 1, to: 2}).samples, 2, 'at 1000 and 1500 ms');
  assert.equal(tree(p, {from: 1.5}).samples, 3, 'at 1500, 2000 ms, and the one at 3000 with no stack');
  assert.equal(tree(p, {to: 1}).samples, 1);
  assert.equal(tree(p, {from: 9}).samples, 0);
});

test('one operation alone: the other filters do not apply', () => {
  const {root, samples} = tree(p, {instance: 1, java: true, from: 9, reversed: true, merged: true});
  assert.equal(samples, 2);
  assert.equal(root.children[0].name, 'GET /b');
  assert.equal(root.children[0].children[0].name, 'Main.run');
});
