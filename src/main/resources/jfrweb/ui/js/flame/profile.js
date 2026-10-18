// @ts-check
/**
 * A session's profile, as /api/sessions/:id/profile sends it (see
 * docs/flame-graphs.md): decoded once, then summed into the tree of any
 * filter, with no request. The filters are those of the server's flame
 * graphs (FlameGraphs.write), with the same results.
 *
 * No DOM: tested with Node.
 */

/** Frame types, in the low 3 bits of a frame. */
export const COMPILED = 0, INLINED = 1, INTERPRETED = 2, NATIVE = 3, SYNTHETIC = 4;

/** The root of the samples taken outside any operation, as on the server. */
export const NO_OPERATION = '(no operation)';

/**
 * @typedef {object} Profile
 * @property {string[]} strings
 * @property {Int32Array} parent the parent of each stack node, -1 for a root
 * @property {Int32Array} frame the frame of each stack node: string << 3 | type
 * @property {Float64Array} time of each sample, in ms since the start of the recording
 * @property {Int32Array} stack the node of each sample's top frame, -1 when it has none
 * @property {Int32Array} thread of each sample, an index in threads
 * @property {Int32Array} operation of each sample, an index in operations, -1 outside any
 * @property {Uint8Array} kind of each sample: 0 Java, 1 native
 * @property {number[]} weights the ms a Java and a native sample stand for
 * @property {{name: string, id: number}[]} threads
 * @property {Int32Array} operations the name of each, an index in strings
 */

/**
 * @param {any} doc the JSON document, version 1
 * @returns {Profile}
 */
export function decode(doc) {
  if (doc.version !== 1)
    throw new Error(`Profile of version ${doc.version}: this page reads version 1`);

  // front-coded: the first character tells the length of the prefix shared
  // with the previous string
  const strings = new Array(doc.strings.length);
  let previous = '';
  for (let i = 0; i < strings.length; i++) {
    const s = doc.strings[i];
    strings[i] = previous = previous.substring(0, s.charCodeAt(0) - 32) + s.substring(1);
  }

  // depth first: a node's parent is the last node one level up
  const n = doc.stacks.length / 2;
  const parent = new Int32Array(n), frame = new Int32Array(n);
  const last = [];
  for (let i = 0; i < n; i++) {
    const depth = doc.stacks[2 * i];
    parent[i] = depth === 0 ? -1 : last[depth - 1];
    frame[i] = doc.stacks[2 * i + 1];
    last[depth] = i;
  }

  const s = doc.samples;
  const time = new Float64Array(s.time.length);
  let t = 0;
  for (let i = 0; i < time.length; i++)
    time[i] = t += s.time[i];

  return {
    strings, parent, frame, time,
    stack: Int32Array.from(s.stack),
    thread: Int32Array.from(s.thread),
    operation: Int32Array.from(s.operation),
    kind: Uint8Array.from(s.kind),
    weights: doc.weights,
    threads: doc.threads.map((/** @type {number[]} */ [name, id]) => ({name: strings[name], id})),
    operations: Int32Array.from(doc.operations),
  };
}

/**
 * A frame of a flame graph: the frames of one name under the same path,
 * merged whatever their type.
 *
 * @typedef {object} Frame
 * @property {string} name
 * @property {number} total ms, its callees included
 * @property {number} self ms, running it
 * @property {number[]} types ms by frame type (COMPILED...)
 * @property {Frame[]} children sorted by name
 */

/**
 * Sums the samples a filter keeps, as the server's flame graphs did: rooted
 * by operation unless merged, the samples outside operations under one root
 * and their thread when added.
 *
 * @param {Profile} p
 * @param {import('../api.js').FlameFilter} filter
 * @returns {{root: Frame, samples: number}} the samples kept
 */
export function tree(p, filter) {
  /** @type {MutableFrame} */
  const root = mutable('all', SYNTHETIC);
  let samples = 0;
  const one = filter.instance;

  // one operation: the other filters do not apply
  let start = 0, end = p.time.length;
  if (one === undefined) {
    if (filter.from !== undefined)
      start = firstAtOrAfter(p.time, filter.from * 1000);
    if (filter.to !== undefined)
      end = firstAtOrAfter(p.time, filter.to * 1000);
  }
  /** @type {(string | number)[]} the keys of a sample's path: names of roots, or frames */
  const path = [];
  for (let i = start; i < end; i++) {
    const op = p.operation[i];
    path.length = 0;
    if (one !== undefined) {
      if (op !== one)
        continue;
      path.push(p.strings[p.operations[op]]);
    }
    else {
      if (filter.java && p.kind[i] !== 0)
        continue;
      if (filter.thread && p.threads[p.thread[i]].name !== filter.thread)
        continue;
      if (op >= 0) {
        const name = p.strings[p.operations[op]];
        if (filter.name && name !== filter.name)
          continue;
        if (!filter.merged)
          path.push(name);
      }
      else {
        if (!filter.all)
          continue;
        const t = p.threads[p.thread[i]];
        path.push(NO_OPERATION, `[${t.name} tid=${t.id}]`);
      }
    }
    const roots = path.length;
    for (let node = p.stack[i]; node >= 0; node = p.parent[node])
      path.push(p.frame[node]);
    // the frames came from the top of the stack: callers first
    reverse(path, roots, path.length);
    if (filter.reversed && one === undefined)
      reverse(path, 0, path.length);

    const w = p.weights[p.kind[i]];
    let f = root;
    f.total += w;
    for (const key of path) {
      f = typeof key === 'string' ? child(f, key, key, SYNTHETIC) : child(f, key >>> 3, p.strings[key >>> 3], key & 7);
      f.total += w;
      f.types[f.type] += w;
    }
    f.self += w;
    samples++;
  }
  root.types[SYNTHETIC] = root.total;
  return {root: freeze(root), samples};
}

/**
 * @typedef {object} MutableFrame
 * @property {string} name
 * @property {number} type of the frame being added
 * @property {number} total
 * @property {number} self
 * @property {number[]} types
 * @property {Map<string | number, MutableFrame>} children by name: a string for roots, a string index for frames
 */

/**
 * @param {string} name
 * @param {number} type
 * @returns {MutableFrame}
 */
function mutable(name, type) {
  return {name, type, total: 0, self: 0, types: [0, 0, 0, 0, 0], children: new Map()};
}

/**
 * @param {MutableFrame} f
 * @param {string | number} key
 * @param {string} name
 * @param {number} type
 */
function child(f, key, name, type) {
  let c = f.children.get(key);
  if (!c)
    f.children.set(key, c = mutable(name, type));
  c.type = type;
  return c;
}

/**
 * @param {MutableFrame} f
 * @returns {Frame}
 */
function freeze(f) {
  const children = [...f.children.values()].map(freeze);
  children.sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
  return {name: f.name, total: f.total, self: f.self, types: f.types, children};
}

/**
 * @param {unknown[]} a
 * @param {number} from
 * @param {number} to excluded
 */
function reverse(a, from, to) {
  for (let i = from, j = to - 1; i < j; i++, j--) {
    const x = a[i];
    a[i] = a[j];
    a[j] = x;
  }
}

/**
 * @param {Float64Array} times sorted
 * @param {number} t
 * @returns {number} the index of the first time at or after t, the length if none
 */
function firstAtOrAfter(times, t) {
  let lo = 0, hi = times.length;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (times[mid] < t)
      lo = mid + 1;
    else
      hi = mid;
  }
  return lo;
}

/**
 * @param {Frame} f
 * @returns {number} the type most of its time was spent in
 */
export function mainType(f) {
  let best = 0;
  for (let t = 1; t < f.types.length; t++)
    if (f.types[t] > f.types[best])
      best = t;
  return best;
}
