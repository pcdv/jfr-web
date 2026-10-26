import {test} from 'node:test';
import assert from 'node:assert/strict';
import {decode, tree, timeOf, hotMethods, methodTrees, compare, mainType, COMPILED, INTERPRETED, SYNTHETIC, NO_OPERATION}
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
  version: 4,
  weights: [20, 5],
  unit: 'ms',
  start: "2026-10-18T08:00:00Z",
  end: 60_000_000,
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
  assert.throws(() => decode({...doc, version: 6}), /version 6.*\(5\): reload the page/);
  assert.throws(() => decode({...doc, version: 3}), /version 3/);
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

test('when the samples of a frame were taken, per bucket, its frames merged by name', () => {
  // GET /a > Main.run > Main.work > x.Sort.sort: at 0 and 1000 ms
  assert.deepEqual([...timeOf(p, {}, ['GET /a', 'Main.run', 'Main.work', 'x.Sort.sort'], 0, 1000, 4)], [20, 20, 0, 0]);
  // Main.work of GET /b: interpreted at 1500, compiled and native at 2000
  assert.deepEqual([...timeOf(p, {}, ['GET /b', 'Main.run', 'Main.work'], 0, 1000, 4)], [0, 20, 5, 0]);
  assert.deepEqual([...timeOf(p, {}, [], 0, 1000, 4)], [20, 40, 5, 20], 'all the samples kept, one with no stack');
  assert.deepEqual([...timeOf(p, {merged: true}, ['Main.run'], 0, 1000, 4)], [20, 40, 5, 0], 'the filter of the graph');
  assert.deepEqual([...timeOf(p, {}, [], 1000, 500, 2)], [20, 20], 'buckets of a zoomed timeline');
  assert.deepEqual([...timeOf(p, {}, ['GET /c'], 0, 1000, 4)], [0, 0, 0, 0]);
});

test('hot methods: the time each ran in, at the top, and was in, whatever calls it', () => {
  const {methods, total} = hotMethods(p, {});
  assert.equal(total, 85);
  assert.deepEqual(methods, [
    {name: 'x.Sort.sort', self: 40, total: 40},
    {name: 'Main.work', self: 25, total: 65},
    {name: 'Main.run', self: 0, total: 65},
  ]);
  // outside operations too: x.Sort.sort called by Main.run directly
  assert.deepEqual(hotMethods(p, {all: true}).methods[0], {name: 'x.Sort.sort', self: 60, total: 60});
  assert.deepEqual(hotMethods(p, {name: 'GET /b'}).methods.map(m => m.name), ['Main.work', 'Main.run']);
});

test('a method: its callers and callees, wherever it is called from', () => {
  const {callers, callees, samples} = methodTrees(p, {all: true}, 'x.Sort.sort');
  assert.equal(samples, 3);
  // called by Main.work in GET /a, by Main.run directly outside operations
  assert.deepEqual(lines(callers), [
    'x.Sort.sort 60 0',
    `x.Sort.sort;Main.run 20 0`,
    `x.Sort.sort;Main.run;[main tid=1] 20 0`,
    `x.Sort.sort;Main.run;[main tid=1];${NO_OPERATION} 20 20`,
    'x.Sort.sort;Main.work 40 0',
    'x.Sort.sort;Main.work;Main.run 40 0',
    'x.Sort.sort;Main.work;Main.run;GET /a 40 40',
  ]);
  assert.deepEqual(lines(callees), ['x.Sort.sort 60 60'], 'it calls nothing');

  const work = methodTrees(p, {reversed: true}, 'Main.work');
  assert.deepEqual(lines(work.callees), ['Main.work 65 25', 'Main.work;x.Sort.sort 40 40'], 'not reversed');
  assert.equal(work.callees.types[INTERPRETED], 20);
  assert.equal(methodTrees(p, {}, 'nothing').samples, 0);
});

test('frames hidden by a pattern: out of the stacks, their time to their callers', () => {
  assert.deepEqual(lines(tree(p, {name: 'GET /b', hide: 'work'}).root), [
    'all 25 0',
    'all;GET /b 25 0',
    'all;GET /b;Main.run 25 25',
  ]);
  const {methods} = hotMethods(p, {hide: 'Sort'});
  assert.deepEqual(methods.find(m => m.name === 'Main.work'), {name: 'Main.work', self: 65, total: 65});
  assert.equal(methods.some(m => m.name === 'x.Sort.sort'), false);
  assert.deepEqual([...timeOf(p, {hide: 'work'}, ['GET /a', 'Main.run', 'x.Sort.sort'], 0, 1000, 4)], [20, 20, 0, 0]);
  assert.deepEqual(lines(tree(p, {hide: '('}).root), lines(tree(p, {}).root), 'an invalid pattern hides nothing');
  assert.deepEqual(lines(tree(p, {hide: 'GET'}).root), lines(tree(p, {}).root), 'operations are not frames');
});

test('a graph compared with a baseline: each frame with its total there, by path', () => {
  // GET /b's samples now, those before 2 s as the baseline
  const c = compare(tree(p, {to: 2}).root, tree(p, {}).root);
  /** @param {any} f @param {string} [path] @returns {string[]} */
  const bases = (f, path = '') => {
    const at = path ? `${path};${f.name}` : f.name;
    return [`${at} ${f.total}/${f.base}`, ...f.children.flatMap(/** @param {any} x */ x => bases(x, at))];
  };
  assert.deepEqual(bases(c), [
    'all 85/60',
    'all;GET /a 60/40',
    'all;GET /a;Main.run 40/40',
    'all;GET /a;Main.run;Main.work 40/40',
    'all;GET /a;Main.run;Main.work;x.Sort.sort 40/40',
    // the native sample at 2000 ms is not in the baseline
    'all;GET /b 25/20',
    'all;GET /b;Main.run 25/20',
    'all;GET /b;Main.run;Main.work 25/20',
  ]);
  assert.equal(compare(tree(p, {from: 9}).root, tree(p, {}).root).children[0].base, 0, 'none in the baseline');
});

test('one operation compared with another: by the paths under them', () => {
  const c = compare(tree(p, {name: 'GET /b'}).root, tree(p, {name: 'GET /a'}).root, true);
  const a = c.children[0];
  assert.equal(a.name, 'GET /a');
  assert.equal(a.base, 25, 'all of GET /b');
  assert.equal(a.children[0].name, 'Main.run');
  assert.equal(a.children[0].base, 25);
  assert.equal(a.children[0].children[0].base, 25, 'Main.work, in both');
  assert.equal(a.children[0].children[0].children[0].base, 0, 'x.Sort.sort, in GET /a only');
  assert.equal(compare(tree(p, {name: 'GET /b'}).root, tree(p, {name: 'GET /a'}).root).children[0].base, 0,
               'without: GET /a is not in the baseline');
});

test('allocation samples: each its own weight, in bytes', () => {
  const alloc = decode({...doc, unit: 'bytes', samples: {...doc.samples, weight: [100, 200, 300, 400, 500, 600]}});
  assert.equal(alloc.unit, 'bytes');
  const {root, samples} = tree(alloc, {});
  assert.equal(samples, 5);
  // the sample outside operations left out
  assert.equal(root.total, 100 + 200 + 300 + 400 + 600);
  assert.equal(hotMethods(alloc, {}).total, root.total);
});

// version 5, the same profile with its stacks as columns, the samples' stacks as differences of
// the node plus 1, and an operation's thread as an index in threads
const doc5 = {
  version: 5,
  unit: 'ms',
  weights: [20, 5],
  start: "2026-10-18T08:00:00Z",
  end: 60_000_000,
  strings: frontCode(strings),
  stacks: {depth: [0, 1, 2, 1, 1], name: [3, 4, 7, 4, 7], type: [INTERPRETED, COMPILED, COMPILED, INTERPRETED, COMPILED]},
  samples: {
    time: [0, 1000, 500, 500, 1000, 0],
    // the nodes 2, 2, 3, 1, 4 and none, plus 1: 3, 3, 4, 2, 5, 0
    stack: [3, 0, 1, -2, 3, -5],
    thread: [0, 0, 1, 1, 0, 0],
    operation: [0, 0, 1, 1, -1, 0],
    kind: [0, 0, 0, 1, 0, 0],
  },
  threads: [[5, 1], [6, 22]],
  operations: {name: [1, 2], start: [0, 0], duration: [0, 0], thread: [0, 1]},
};

test('version 5: the same profile as version 4', () => {
  assert.deepEqual(decode(doc5), p);
});

test('version 5: what is absent reads as its default', () => {
  const q = decode({version: 5, unit: 'count', strings: frontCode(['main', 'parse']),
                    stacks: {depth: [0, 1], name: [0, 1]}, samples: {stack: [2, -1, 1]}});
  assert.deepEqual([...q.stack], [1, 0, 1]);
  assert.deepEqual([...q.parent], [-1, 0]);
  assert.deepEqual([...q.frame], [0, 1 << 3]);
  assert.deepEqual([...q.thread], [0, 0, 0]);
  assert.deepEqual([...q.operation], [-1, -1, -1]);
  assert.deepEqual([...q.time], [0, 0, 0]);
  assert.deepEqual([...q.kind], [0, 0, 0]);
  assert.deepEqual(q.threads, [{name: '', id: 0}]);
  assert.deepEqual(q.weights, [1]);
  assert.equal(q.operations.count, 0);
  assert.equal(q.unit, 'count');
  assert.equal(q.weight, null);
  assert.equal(q.start, '');
  assert.equal(q.end, 0);
  assert.equal(hotMethods(q, {all: true}).total, 3);
});

test('version 5: an operation of an unnamed thread has none', () => {
  const q = decode({version: 5, unit: 'ms', strings: frontCode(['GET /a', 'main']),
                    stacks: {depth: [0], name: [1]}, samples: {stack: [1]},
                    threads: [[-1, -1], [1, 7]],
                    operations: {name: [0, 0], start: [0, 0], duration: [1, 1], thread: [0, 1]}});
  assert.deepEqual([...q.operations.thread], [-1, 1]);
  assert.deepEqual(q.threads, [{name: '', id: -1}, {name: 'main', id: 7}]);
  assert.deepEqual([...q.operations.status], [0, 0]);
  assert.deepEqual([...q.operations.detail], [-1, -1]);
});
