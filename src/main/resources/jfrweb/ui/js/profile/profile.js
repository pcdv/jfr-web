// @ts-check
/**
 * A session's profile, as /api/sessions/:id/profile sends it, a Flamepack
 * (https://github.com/pcdv/flamepack): its samples and its operations, decoded once. The
 * samples are summed here into the flame graph of any filter, with no
 * request; the filters are those of the server's flame graphs
 * (FlameGraphs.write), with the same results. The operations are summed in
 * operations.js.
 *
 * No DOM: tested with Node.
 */

/** Frame types, in the low 3 bits of a frame; ALLOCATED, the class an allocation sample allocated. */
export const COMPILED = 0, INLINED = 1, INTERPRETED = 2, NATIVE = 3, SYNTHETIC = 4, ALLOCATED = 5;

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
 * @property {'ms' | 'bytes'} unit what the weights count: bytes for allocation samples. A profile
 *           of version 5 may count 'count', which this page has no word for: its viewers say
 * @property {Float64Array | null} weight of each sample, for allocation samples: the bytes each
 *           stands for; null when weights tell them
 * @property {string} start of the recording, ISO-8601
 * @property {number} end of the recording, in ns since its start
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

/** The version of the format the server writes: ProfileWriter.VERSION. */
export const VERSION = 4;

/** The versions of the format this page reads. */
export const VERSIONS = [4, 5];

/**
 * @param {any} doc the JSON document
 * @returns {Profile}
 * @throws {Error} for another version: the page was loaded before the server changed
 */
export function decode(doc) {
  if (!VERSIONS.includes(doc.version))
    throw new Error(`The server sends its samples in a newer form (version ${doc.version}) than this page reads `
                    + `(${VERSIONS[VERSIONS.length - 1]}): reload the page.`);
  return doc.version === 4 ? decode4(doc) : decode5(doc);
}

/**
 * @param {string[]} coded front-coded: the first character tells the length of the prefix shared
 *        with the previous string
 * @returns {string[]}
 */
function unpack(coded) {
  const strings = new Array(coded.length);
  let previous = '';
  for (let i = 0; i < strings.length; i++) {
    const s = coded[i];
    strings[i] = previous = previous.substring(0, s.charCodeAt(0) - 32) + s.substring(1);
  }
  return strings;
}

/**
 * Version 5 (https://github.com/pcdv/flamepack/blob/master/SPEC.md): everything but the
 * strings, the stacks and the samples' stacks is optional, and reads as its default.
 *
 * @param {any} doc
 * @returns {Profile}
 */
function decode5(doc) {
  const strings = unpack(doc.strings);

  // columns: a node's parent is the last node one level up
  const {depth, name, type} = doc.stacks;
  const n = depth.length;
  const parent = new Int32Array(n), frame = new Int32Array(n);
  const last = [];
  for (let i = 0; i < n; i++) {
    parent[i] = depth[i] === 0 ? -1 : last[depth[i] - 1];
    frame[i] = name[i] << 3 | (type ? type[i] : 0);
    last[depth[i]] = i;
  }

  const s = doc.samples;
  const count = s.stack.length;
  const stack = new Int32Array(count);
  let sum = 0;
  for (let i = 0; i < count; i++)
    stack[i] = (sum += s.stack[i]) - 1;
  const time = new Float64Array(count);
  if (s.time) {
    let t = 0;
    for (let i = 0; i < count; i++)
      time[i] = t += s.time[i];
  }

  /** @type {Profile['threads']} */
  const threads = doc.threads
    ? doc.threads.map((/** @type {number[]} */ [name, id]) => ({name: name < 0 ? '' : strings[name], id}))
    : [{name: '', id: 0}];

  return {
    strings, parent, frame, time, stack,
    thread: s.thread ? Int32Array.from(s.thread) : new Int32Array(count),
    operation: s.operation ? Int32Array.from(s.operation) : new Int32Array(count).fill(-1),
    kind: s.kind ? Uint8Array.from(s.kind) : new Uint8Array(count),
    weights: doc.weights ?? [1],
    unit: /** @type {any} */ (doc.unit),
    weight: s.weight ? Float64Array.from(s.weight) : null,
    start: doc.start ?? '',
    end: doc.end ?? 0,
    threads,
    operations: operations5(doc.operations, doc.threads),
  };
}

/**
 * @param {any} o the operations of the document, if it has any
 * @param {number[][] | undefined} threads those of the document, which an operation's thread is an
 *        index in
 * @returns {Operations}
 */
function operations5(o, threads) {
  if (!o)
    return operations({name: [], start: [], duration: [], thread: [], detail: [], status: [],
                       failed: [], error: [], async: [], bytesIn: [], bytesOut: []});
  const count = o.name.length;
  /** @param {string} name @param {number} value @returns {number[]} */
  const column = (name, value) => o[name] ?? new Array(count).fill(value);
  // the profile keeps the name of an operation's thread, as an index in the strings: -1 for none
  const thread = column('thread', -1).map(t => t < 0 || !threads ? -1 : threads[t][0]);
  return operations({...o, thread, detail: column('detail', -1), status: column('status', 0),
                     failed: column('failed', 0), error: column('error', -1), async: column('async', 0),
                     bytesIn: column('bytesIn', 0), bytesOut: column('bytesOut', 0)});
}

/**
 * @param {any} doc
 * @returns {Profile}
 */
function decode4(doc) {
  const strings = unpack(doc.strings);

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
    unit: doc.unit,
    weight: s.weight ? Float64Array.from(s.weight) : null,
    start: doc.start,
    end: doc.end,
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
 * @property {string} [hide] a regular expression: the frames whose name it finds are left out of
 *           the stacks, their time given to their callers (reflection, proxies, plumbing); an
 *           invalid one hides nothing
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
 * @property {number} [base] in a comparison, its total in the baseline: ms, 0 if it had none
 */

/**
 * @param {Profile} p
 * @param {number} i a sample
 * @returns {number} what it stands for, in the unit of the profile
 */
export function weightOf(p, i) {
  return p.weight ? p.weight[i] : p.weights[p.kind[i]];
}

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
  eachPath(p, filter, (i, path) => {
    const w = weightOf(p, i);
    let f = root;
    f.total += w;
    for (const key of path) {
      f = typeof key === 'string' ? child(f, key, key, SYNTHETIC) : child(f, key >>> 3, p.strings[key >>> 3], key & 7);
      f.total += w;
      f.types[f.type] += w;
    }
    f.self += w;
    samples++;
  });
  root.types[SYNTHETIC] = root.total;
  return {root: freeze(root), samples};
}

/**
 * When the samples of a frame of a flame graph were taken: their weight per
 * time bucket, e.g. to show on a timeline whether a method's time is spread
 * over the session or comes in bursts.
 *
 * @param {Profile} p
 * @param {Filter} filter that of the flame graph
 * @param {string[]} path the names of the frame and of those under it, from the root's
 *        children up, as tree() gives them; empty for all the samples kept
 * @param {number} start of the first bucket, in ms since the start of the recording
 * @param {number} bucket its duration, in ms
 * @param {number} count the number of buckets
 * @returns {Float64Array} ms of samples per bucket
 */
export function timeOf(p, filter, path, start, bucket, count) {
  const ms = new Float64Array(count);
  eachPath(p, filter, (i, keys) => {
    if (keys.length < path.length)
      return;
    for (let j = 0; j < path.length; j++) {
      const key = keys[j];
      if ((typeof key === 'string' ? key : p.strings[key >>> 3]) !== path[j])
        return;
    }
    const b = Math.floor((p.time[i] - start) / bucket);
    if (b >= 0 && b < count)
      ms[b] += weightOf(p, i);
  });
  return ms;
}

/**
 * One method and what is around it, wherever it is called from: the samples
 * it was in, as two trees rooted at it, its callers and its callees, each
 * merged by name. A sample counts once, at the method's outermost frame: its
 * recursion stays among its callees.
 *
 * @param {Profile} p
 * @param {Filter} filter that of the flame graph, whose reversal does not apply
 * @param {string} name of the method
 * @returns {{callers: Frame, callees: Frame, samples: number}} the callers tree, whose children
 *          are the method's callers and so on down to the operations, and the callees tree
 */
export function methodTrees(p, filter, name) {
  const callers = mutable(name, COMPILED), callees = mutable(name, COMPILED);
  let samples = 0;
  eachPath(p, {...filter, reversed: false}, (i, path) => {
    let at = 0;
    while (at < path.length && (typeof path[at] === 'string' || p.strings[/** @type {number} */ (path[at]) >>> 3] !== name))
      at++;
    if (at === path.length)
      return;
    const w = weightOf(p, i);
    samples++;
    /** @param {MutableFrame} root @param {number} from @param {number} step */
    const add = (root, from, step) => {
      let f = root;
      f.total += w;
      f.types[/** @type {number} */ (path[at]) & 7] += w;
      for (let j = from; j >= 0 && j < path.length; j += step) {
        const key = path[j];
        f = typeof key === 'string' ? child(f, key, key, SYNTHETIC) : child(f, key >>> 3, p.strings[key >>> 3], key & 7);
        f.total += w;
        f.types[f.type] += w;
      }
      f.self += w;
    };
    add(callees, at + 1, 1);
    add(callers, at - 1, -1);
  });
  return {callers: freeze(callers), callees: freeze(callees), samples};
}

/**
 * A method, and the time of the samples a filter keeps that it was in.
 *
 * @typedef {object} HotMethod
 * @property {string} name
 * @property {number} self ms of the samples taken while it ran: it was at the top of the stack
 * @property {number} total ms of the samples it was in, each once however deep its recursion
 */

/**
 * The methods where the time went, whatever calls them: of the samples a
 * filter keeps, those each method ran in, at the top of the stack, and those
 * it was anywhere in.
 *
 * @param {Profile} p
 * @param {Filter} filter that of the flame graph
 * @returns {{methods: HotMethod[], total: number}} the methods, the most self time first, and
 *          the ms of all the samples kept
 */
export function hotMethods(p, filter) {
  /** @type {Map<number, HotMethod>} by the index of the name */
  const methods = new Map();
  /** @param {number} name */
  const method = name => {
    let m = methods.get(name);
    if (!m)
      methods.set(name, m = {name: p.strings[name], self: 0, total: 0});
    return m;
  };
  /** @type {Set<number>} the methods of a stack, once each */
  const seen = new Set();
  let total = 0;
  // from the root to the top, whatever the graph shows
  eachPath(p, {...filter, reversed: false}, (i, path) => {
    const w = weightOf(p, i);
    total += w;
    const top = path[path.length - 1];
    if (typeof top === 'number' && (top & 7) !== SYNTHETIC)
      method(top >>> 3).self += w;
    seen.clear();
    for (const key of path)
      if (typeof key === 'number' && (key & 7) !== SYNTHETIC)
        seen.add(key >>> 3);
    for (const name of seen)
      method(name).total += w;
  });
  return {methods: [...methods.values()].sort((a, b) => b.self - a.self || b.total - a.total), total};
}

/**
 * The samples a filter keeps, each with its path in the flame graph: the
 * keys of its frames from the root, a string for the operation and thread
 * roots, the frame (string << 3 | type) for methods.
 *
 * @param {Profile} p
 * @param {Filter} filter
 * @param {(sample: number, path: (string | number)[]) => void} visit given a path it may not keep
 */
function eachPath(p, filter, visit) {
  const one = filter.instance;
  const hidden = hiddenNames(p, filter.hide);

  // one operation: the other filters do not apply
  let start = 0, end = p.time.length;
  if (one === undefined) {
    if (filter.from !== undefined)
      start = firstAtOrAfter(p.time, filter.from * 1000);
    if (filter.to !== undefined)
      end = firstAtOrAfter(p.time, filter.to * 1000);
  }
  /** @type {(string | number)[]} */
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
      if (!hidden || !hidden[p.frame[node] >>> 3])
        path.push(p.frame[node]);
    // the frames came from the top of the stack: callers first
    reverse(path, roots, path.length);
    if (filter.reversed && one === undefined)
      reverse(path, 0, path.length);
    visit(i, path);
  }
}

/**
 * A flame graph compared with a baseline, e.g. an interval with another one:
 * the frames of the graph, each with its total in the baseline, found by the
 * same path of names. The frames only the baseline has are not in it.
 *
 * @param {Frame} base the baseline's root
 * @param {Frame} now the root of the graph compared
 * @param {boolean} [across] whether the roots under the root (the operations)
 *        differ on purpose, e.g. one operation compared with another: each is
 *        then compared with all of the baseline's, merged
 * @returns {Frame} a copy of now, each frame with its base
 */
export function compare(base, now, across = false) {
  if (across) {
    /** @type {Frame} */
    const none = {name: '', total: 0, self: 0, types: base.types.map(() => 0), children: []};
    const all = base.children.reduce(merge, none);
    return {...now, base: base.total, children: now.children.map(c => compare({...all, name: c.name}, c))};
  }
  /** @type {Map<string, Frame>} */
  const before = new Map(base.children.map(c => [c.name, c]));
  return {
    ...now,
    base: base.total,
    children: now.children.map(c => {
      const b = before.get(c.name);
      return b ? compare(b, c) : withBase0(c);
    }),
  };
}

/**
 * @param {Frame} a
 * @param {Frame} b
 * @returns {Frame} the children of both, merged by name, under a's name
 */
function merge(a, b) {
  /** @type {Map<string, Frame>} */
  const children = new Map(a.children.map(c => [c.name, c]));
  for (const c of b.children) {
    const same = children.get(c.name);
    children.set(c.name, same ? merge(same, c) : c);
  }
  return {
    name: a.name,
    total: a.total + b.total,
    self: a.self + b.self,
    types: a.types.map((t, i) => t + b.types[i]),
    children: [...children.values()],
  };
}

/**
 * @param {Frame} f
 * @returns {Frame} a copy, none of its frames in the baseline
 */
function withBase0(f) {
  return {...f, base: 0, children: f.children.map(withBase0)};
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
  return {name, type, total: 0, self: 0, types: [0, 0, 0, 0, 0, 0], children: new Map()};
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
 * @param {Profile} p
 * @param {string | undefined} hide a regular expression
 * @returns {Uint8Array | null} 1 for each string it finds, null if it hides nothing
 */
function hiddenNames(p, hide) {
  if (!hide)
    return null;
  let pattern;
  try {
    pattern = new RegExp(hide);
  }
  catch (e) {
    return null;
  }
  const hidden = new Uint8Array(p.strings.length);
  p.strings.forEach((s, i) => hidden[i] = pattern.test(s) ? 1 : 0);
  return hidden;
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
