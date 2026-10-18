# Flame graphs of our own

Plan for replacing async-profiler's flame graph pages with a profile sent to the browser once and
a renderer of our own. Tracked in [ROADMAP.md](../ROADMAP.md) as M10.

## Why

Today each flame graph is an HTML page built by the server with `jfr-converter`: the samples are
read from the `.jfr` file again (`Analysis.visitSamples`), filtered, summed into a tree and laid
out. The page holds that tree only, with no time, so:

- every change of a filter, an interval dragged on the timeline included, is a request that
  reads the whole recording again;
- the static export can only offer what it computed beforehand: no interval, one filter at a
  time;
- the page looks like async-profiler, not like the rest of the interface (fonts, dark mode), and
  knows nothing of it: the timeline cannot show where a frame's samples fall;
- `jfr-converter` is the last dependency besides flak, against the requirement of as few
  dependencies as possible (an embedded profiler imposes their versions on its host).

Instead, the server sends a session's samples once, with their time, thread and operation, and
the browser builds the flame graph of any filter itself, at once.

## Goals

- Every filter of today, combined freely and changed with no request: interval, thread,
  operation, single operation, outside operations, merged, reversed, Java only. In the static
  export too.
- At least what async-profiler's page does: zoom on a frame, search with the share matched,
  tooltips, colors by frame type.
- The look of the interface, light and dark.
- Then what only an integrated renderer allows (see "Features").
- `jfr-converter` removed.

Not goals: profiles from other sources (async-profiler on Linux stays in "Later" of the
roadmap; the format should not prevent it), or a general purpose profile viewer.

## The profile format

A JSON document per session, version 1. Sizes below assume the TCP demo's longest snapshot:
665 s, 55,447 samples (23,886 Java, 31,561 native), 14,078 operations.

| Field | Content |
|---|---|
| `version` | `1` |
| `weights` | the milliseconds one Java sample and one native sample stand for, as `Analysis` computes them today |
| `strings` | every name, sorted and front-coded as in async-profiler's `cpool`: the first character's code minus 32 is the length of the prefix shared with the previous string |
| `stacks` | the tree of stacks with their common beginnings shared, depth first: a node is a pair `depth, frame`, its parent the last node one level up, so no parent index is stored; `frame` is `string << 3 \| type` |
| `samples` | columns of equal length, sorted by time: `time` (ms since the start of the recording, as deltas), `stack` (index of the node of the top frame), `thread`, `operation` (index, or -1 outside any), `kind` (Java or native) |
| `threads` | name and thread id, for the thread filter and the per-thread roots of the samples outside operations |
| `operations` | the operations, in the order of `Analysis.getOperations()` so that an index is the instance id the API already uses, each the index of its name in `strings` |

Frame types: compiled, inlined, interpreted, native method, and synthetic (`(truncated)`, which
stays a frame at the bottom of a truncated stack). Today native methods show as compiled code;
the format keeps them apart and the renderer chooses.

Operations and threads are columns, not frames: the renderer adds the operation roots (unless
merged) and the thread roots of the samples outside operations, as `FlameGraphs.write` pushes them
today. Samples sorted by time make an interval two binary searches.

The format is documented in this file once phase 1 settles it, and changes only with its version.

## Server

- `ProfileWriter` in `jfrweb.analysis`: one pass of `visitSamples` with no filter, building the
  string table, the stack tree and the sample columns.
- `GET /api/sessions/:id/profile`: the document of a stopped session. A session no longer changes
  once stopped: the document is built once and kept, in memory beside the `Analysis` (soft
  reference) or in a file next to the recording; phase 0 decides from its size and build time.
- Compression: flak does not gzip. On loopback it does not matter; for a profiler reached over
  the network, the document can be stored gzipped and sent with `Content-Encoding: gzip` when the
  request accepts it. GitHub Pages gzips the static export by itself.

## Client

Plain modules like the rest of the interface, in `js/flame/`:

- `profile.js`: decodes a document (strings, node parents and depths) and builds the tree of a
  filter: for each sample in the interval that the filter keeps, its weight added along its path,
  from the root or, reversed, from the top frame. No DOM: testable on its own.
- `render.js`: draws a tree on a canvas: layout, zoom on a click with the path back, tooltip
  (name, total and self time in ms, share of the view and of the whole), search by regex with
  the share matched, colors by frame type from the CSS tokens, sharp on high-density screens,
  redrawn on resize.
- `components/flame.js` keeps its panel and filters; the iframe gives way to the canvas, and
  `update()` rebuilds the tree locally. `onlyFilter`, which keeps the static export to one filter
  at a time, goes away.

## Command line

`jfr-web flame` writes one self-contained HTML file: the renderer, its styles and the profile,
with the options given (`--thread`, `--operation`, `--from`...) as the filters it opens with, all
still changeable in the page. Modules loaded from `file://` are refused by browsers, so they are
inlined: through an import map pointing at `data:` URLs, which keeps the modules unchanged with no
build step, or by keeping the renderer free of imports and concatenating it. To try in phase 3.

`--collapsed` is written by our own code: one line per stack, frames joined by `;`, then its
weight. Before switching, its output is compared with `jfr-converter`'s on the same recordings,
for scripts that read it.

## Features

Once at parity, in this order:

1. Timeline and flame graph linked: a frame hovered or clicked shows on the session's timeline
   where its samples fall, spread over the session or in bursts.
2. Hot methods: a table of the methods by self time, sortable, linked to the graph.
3. Method view: one method's callers above it and callees below it, each merged, for a method
   called from many places or recursive.
4. Frames hidden by a pattern (reflection, proxies, plumbing of a framework), their time given to
   their callers.
5. Diff of two intervals or two operations, colored by which spends more (the open "Diff of two
   flame graphs" of M6).

An icicle layout (roots at the top) is a toggle, if wanted.

## Phases

Each phase ends usable, like the milestones of the roadmap.

**0. Measure.** A prototype of the writer, run on the demo's recordings and on a long session of
a real application: samples, stack nodes, strings, size of the document plain and gzipped, time
to build it; in the browser, time to decode it and to build a tree over every sample. Decides
where the document is kept, whether it is gzipped, and whether a session too long needs a limit
(above which the server would still sum the samples itself).

**1. Format and endpoint.** `ProfileWriter`, `/api/sessions/:id/profile`, the format written down
here. Tests on the recordings of the existing tests: per operation, the total weight equals the
one of today's collapsed output; strings and stacks decoded back equal what was written.

**2. Renderer at parity.** `profile.js`, `render.js`, the flame panel on them. The old page stays
reachable (`?flame=classic` or a setting) until this phase is checked in Firefox, Edge, and VS
Code's built-in browser: every filter and combination, zoom, search, tooltips, colors, dark mode.

**3. Switch.** The static export writes one profile per session instead of its flame graph pages
(total size compared before and after); `jfr-web flame` writes our page; `--collapsed` is our
own; `/api/sessions/:id/flame`, `FlameGraphs`' use of the converter, the jar trimming in
`build.gradle` and the dependency go; README and requirements updated (the packaging requirement
names `jfr-converter`).

**4. Features**, in the order above, each on its own.

## Tests

- Java: the writer and the endpoint (phase 1), the collapsed output (phase 3), as above.
- JavaScript: `profile.js` holds the logic worth testing (decoding, filters, reversed and merged
  trees), tested in `src/test/js` like the other modules that run without a browser
  (`./gradlew jsTest`).
- Browsers: checked by hand and with scripted headless runs, as for the dialogs; not in CI.
- `tsc` over `jsconfig.json`, as for every module.

## Open questions

- `/api/sessions/:id/flame` removed in phase 3, or kept, serving our page, for links people keep?
- The document of a session kept in memory or on disk (phase 0 informs it).
- A limit for long sessions, and what happens above it (phase 0 informs it).
