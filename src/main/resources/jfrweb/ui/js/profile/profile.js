// @ts-check
/**
 * A session's profile, as /api/sessions/:id/profile sends it (see
 * docs/flame-graphs.md): its samples and its operations, decoded once. The
 * samples are summed here into the flame graph of any filter, with no
 * request; the filters are those of the server's flame graphs
 * (FlameGraphs.write), with the same results. The operations are summed in
 * operations.js.
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
 * @property {Operations} operations in the order of the recording: an operation's index is
 *           the id the API gives it
 */

/**
 * The operations of a profile, a column per field. Times in ns, as the server
 * counts them; strings as indexes in the profile's strings, -1 for none.
 *
 * @typedef {object} Operations
 * @property {number} count
 * @property {Int32Array} name
 * @property {Float64Array} start since the start of the recording
 * @property {Float64Array} duration
 * @property {Int32Array} thread that ran it, or that ended an asynchronous one
 * @property {Int32Array} detail
 * @property {Int32Array} status the HTTP status of a request, 0 for other operations
 * @property {Uint8Array} failed
 * @property {Int32Array} error
 * @property {Uint8Array} async
 * @property {Float64Array} bytesIn
 * @property {Float64Array} bytesOut
 */

/** The version of the format this page reads: ProfileWriter.VERSION. */
export const VERSION = 2;

/**
 * @param {any} doc the JSON document
 * @returns {Profile}
 * @throws {Error} for another version: the page was loaded before the server changed
 */
export function decode(doc) {
  if (doc.version !== VERSION)
    throw new Error(`The server sends its samples in a newer form (version ${doc.version}) than this page reads `
                    + `(${VERSION}): reload the page.`);

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
    operations: operations(doc.operations),
  };
}

/**
 * @param {any} o the operations of the document
 * @returns {Operations}
 */
function operations(o) {
  const start = new Float64Array(o.start.length);
  let t = 0;
  for (let i = 0; i < start.length; i++)
    start[i] = t += o.start[i];
  return {
    count: start.length,
    name: Int32Array.from(o.name),
    start,
    duration: Float64Array.from(o.duration),
    thread: Int32Array.from(o.thread),
    detail: Int32Array.from(o.detail),
    status: Int32Array.from(o.status),
    failed: Uint8Array.from(o.failed),
    error: Int32Array.from(o.error),
    async: Uint8Array.from(o.async),
    bytesIn: Float64Array.from(o.bytesIn),
    bytesOut: Float64Array.from(o.bytesOut),
  };
}

/**
 * What a flame graph keeps of the samples.
 *
 * @typedef {object} Filter
 * @property {number} [instance] keeps the samples of one operation, ignoring the other filters
 * @property {string} [name] keeps the samples of the operations of a name
 * @property {string} [thread] keeps the samples of one thread
 * @property {number} [from] seconds since the start of the recording
 * @property {number} [to] seconds since the start of the recording
 * @property {boolean} [all] adds the samples taken outside operations
 * @property {boolean} [merged] merges the operations into one tree, instead of rooting them by name
 * @property {boolean} [reversed] the methods running at the root, their callers above
 * @property {boolean} [java] keeps the samples of Java code only, without native methods
 */

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
 * @param {Filter} filter
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
      path.push(p.strings[p.operations.name[op]]);
    }
    else {
      if (filter.java && p.kind[i] !== 0)
        continue;
      if (filter.thread && p.threads[p.thread[i]].name !== filter.thread)
        continue;
      if (op >= 0) {
        const name = p.strings[p.operations.name[op]];
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
