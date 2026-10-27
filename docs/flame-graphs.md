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

A JSON document per session, from `GET /api/sessions/:id/profile` (sizes measured in phase 0,
below), written by `ProfileWriter`. The format is named **Flamepack** and specified in its own
repository, [pcdv/flamepack](https://github.com/pcdv/flamepack) (`SPEC.md`, with a JSON Schema,
examples and a validator), since jheap and mountain-graph read and write it too and other tools
may: a change of the format is made there as well. The server writes version 5 (`SPEC.md`, a draft);
the page reads that and version 4 (`SPEC-4.md`). In version 5 everything but the stacks is
optional. The rest of this section says how jfr-web uses it.

Any change of the format raises its version, however small, released or not: a page loaded
before the server changed (a tab left open across an upgrade, modules in the browser's cache)
then refuses the document and asks to be reloaded, instead of reading it wrong. Version 1 had
the operations' names only; phase 2b added their other fields in place, and a page of version 1
read the new document without noticing: the flame graph of any operation came out empty. Version
2 is that document; 3 added the span of the recording, for the page to draw its timeline zoomed in;
4 the `unit`, and the allocation samples (`?samples=alloc`), each with its own `weight`.

What jfr-web puts in it, beyond the spec:

- `weights`: the milliseconds a Java and a native sample stand for, as `Analysis` computes them;
  allocation samples (`?samples=alloc`, `unit` `"bytes"`) have their own `weight` instead.
- `strings`: methods as `class.method`, sorted and front-coded as in async-profiler's pages.
- Operations in the order of `Analysis.getOperations()`, so that an operation's index is the id
  the API already gives it. Times in ns, as the server counts them: the page's totals and
  percentiles are the server's, to the hundredth of a millisecond.
- Native methods keep their own frame type, which the renderer may show as compiled code, as
  async-profiler's pages did.
- jfr-web neither writes nor reads `samples.objects`, the column a heap's profile adds.

The type is part of a node: a method interpreted in one sample and compiled in the next makes two
nodes. The renderer merges frames by name, as async-profiler does, and counts their types for the
tooltip (async-profiler shows "int=" and "inln=").

Operations and threads are columns, not frames: the renderer adds the operation roots (unless
merged) and the thread roots of the samples outside operations, as `FlameGraphs.write` pushes them
today. Samples sorted by time make an interval two binary searches.

## Server

- `ProfileWriter` in `jfrweb.analysis`: one pass of `visitSamples` with no filter, building the
  string table, the stack tree and the sample columns.
- `GET /api/sessions/:id/profile`: the document of a stopped session. A session no longer changes
  once stopped: the document is built on the first request and kept gzipped in memory, beside the
  `Analysis` and like it behind a soft reference. No file on disk: building it costs about what
  one flame graph costs today (phase 0).
- Sent with `Content-Encoding: gzip` as kept when the request accepts it, unzipped otherwise.
  flak can compress a response itself (`setCompressionAllowed`), but on each request. GitHub
  Pages gzips the static export by itself.

## Client

Plain modules like the rest of the interface:

- `profile/profile.js`: decodes a document (strings, node parents and depths, operations) and
  builds the tree of a filter: for each sample in the interval that the filter keeps, its weight
  added along its path, from the root or, reversed, from the top frame. No DOM: tested with Node.
- `profile/operations.js` (phase 2b): the operations table and the slowest list of an interval
  or a name, as the server computes them. No DOM either.
- `flame/render.js`: draws a tree on a canvas: layout, zoom on a click with the path back, tooltip
  (name, total and self time in ms, share of the view and of the whole), search by regex with
  the share matched, colors by frame type from the CSS tokens (or by heat, below), sharp on
  high-density screens, redrawn on resize.
- `flame/heat.js`: the temperature of a frame, for the **Heat** checkbox of the graph's
  toolbar (remembered): the self time of its method in all the frames of the graph, minus its own,
  as a share of the whole time. A method running in many narrow frames is hot in each of them;
  one with a single frame is cold however wide it is, since it is easy to see already. Scaled
  to the hottest frame of the graph (at least 5% of the time) by a power of 0.4, so that
  middling frames show too; drawn as a gradient: the frame's usual color, darkened toward deep
  red (brightened in the dark theme). A search and a comparison keep their colors. No DOM: tested with Node.
- `components/flame.js` keeps its panel and filters; the iframe gives way to the canvas, and
  `update()` rebuilds the tree locally. `onlyFilter`, which kept the static export to one filter
  at a time, goes away.

## Command line

`jfr-web flame` writes one self-contained HTML file (`FlamePage`): the profile, the styles, and
the interface's flame graph panel (`components/flame.js`, which needs nothing but a profile).
`--operation` and `--thread` keep the samples written; `--all`, `--merged`, `--reversed` and
`--java` are the filters it opens with, all still changeable in the page.

Browsers refuse modules loaded from `file://`, and an inlined module cannot resolve its relative
imports (an import map to `data:` URLs fails there too: a `data:` module has no base to resolve
`./x.js` against). So `ModuleBundle` joins them into one script, with no build step and no
dependency: each module becomes a function returning its exports, called after the modules it
imports from, its imports lookups of their exports. It understands the forms the interface uses
(`import {a, b as c} from`, `import * as x from`, `export function`, `export const`) and refuses
any other, rather than writing a page that breaks.

`--collapsed` is written by our own code (`FlameGraphs`): one line per stack, frames joined by
`;`, then its weight in ms. Frames are known by their name, as in the page: async-profiler's
converter marked their types (`_[j]`, `_[i]`, `_[0]`), which split a method interpreted then
compiled in two for the tools reading the lines; the standard format has no marks.

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

**0. Measure.** Done on 2026-10-18, see "Measurements": the document is kept gzipped in memory,
and sessions need no limit for now.

**1. Format and endpoint.** Done on 2026-10-18: `ProfileWriter`, `/api/sessions/:id/profile`, the
format above. `AnalysisTest` decodes the profile back into collapsed stacks and finds every stack
of today's collapsed output with its weight, frames compared by name; `ProfilerTest` checks the
endpoint, gzipped and plain.

**2. Renderer at parity.** `profile.js`, `render.js`, the flame panel on them. The old page stays
reachable, a link "Drawn by the server" beside the caption with the same filters, until this phase
is checked in Firefox, Edge, and VS Code's built-in browser: every filter and combination, zoom,
search, tooltips, colors, dark mode. A static export keeps the server's pages until phase 3.

Built on 2026-10-18. `profile.js` gives the trees of the server, checked against `jfr-web flame
--collapsed` on a recording of the TCP demo and on one of an application server: every stack
with its weight, for each filter and combination (operation, thread, outside operations, merged,
reversed, Java only), and the totals of intervals and of single operations against the server's
pages. Checked in headless Edge: light and dark, zoom and back, search, hot methods, one
operation picked among the slowest; Firefox and VS Code's browser remain.

**2b. Operations in the browser.** An interval selected on the timeline narrows the operations
table and the slowest list too, and those still ask the server (`/operations`, `/instances`), so
the static export cannot select an interval at all. The profile gets the operations themselves,
in columns like the samples: start and end, thread (the one that ended an asynchronous operation),
detail, status, failed and its error, asynchronous or not, bytes in and out; details and errors
go into `strings`: version 2 of the format (see the format). The page
then computes, for an interval or a name:

- the operations table (`OperationStats`): count, total, p50, p99, max, errors, bytes in and out,
  over the operations in flight during the interval, each with its whole duration;
- the slowest list, the slowest first.

Both checked against the server's answers on the same recordings, as the flame graphs were. The
zoomed timeline's finer buckets may follow from the same data; the details of one operation
(exceptions, slow accesses, threads) stay a request, which a static export answers for the
operations it exported.

Built on 2026-10-18, in `js/profile/` with the decoding (`profile.js`, `operations.js`); the
session view decodes the profile once for its three panels. On 11 recordings (two of the TCP
demo, seven sessions and two snapshots of an application server), 923 answers of
`/operations` and `/instances` compared with the page's, over intervals across each session
(bounds on an operation's own start and end included), with and without a name, the slowest
lists whole (up to 14,078 operations): none differs. A timeline selection makes no request
any more. The operations weigh about 48 bytes each in JSON, more than the samples' 14: the
demo's busiest session, 14,078 short operations, goes from 148 KB to 295 KB gzipped; an
application server's sessions take a few KB more.

**3. Switch.** The static export writes one profile per session instead of its flame graph pages
(total size compared before and after); `jfr-web flame` writes our page; `--collapsed` is our
own; `/api/sessions/:id/flame`, `FlameGraphs`' use of the converter, the jar trimming in
`build.gradle` and the dependency go; README and requirements updated (the packaging requirement
names `jfr-converter`).

Done on 2026-10-18. The page uses the profile everywhere, a static export included: there, an
interval dragged on the timeline narrows the operations, the slowest and the flame graph as it
does live, and the flame graph's filters combine; only the details of operations other than the
slowest of each name are missing. Since then (`profile/timeline.js`), the timeline zoomed in, in
finer buckets, is drawn from the profile too, with no request, live and in a static export: its
series equal the server's (`Timeline.of`) over 7 ranges of each of 15 recordings; the Java samples
per bucket, kept in whole ms, may move to a neighbouring bucket (at most 23 counts of 35,127). The export writes the
profile, report and timeline of each session and the details of its slowest operations: for the
demo recording and an application server session, 453 files, 1.9 MB (the export before was not
measured, its build being gone). The flame graph route is removed (no links to keep), and the
page's link to it. Checked in headless Edge: the page of `jfr-web flame` opened from the disk,
and a static export served by a plain file server, an interval dragged with no request.

**4. Features**, in the order above, each on its own.

Done on 2026-10-18, each computed in `profile/profile.js` from the same paths as the flame graph
(`eachPath`: the filters, the operation roots, hidden frames), and tested there:

1. `timeOf`: the frame hovered, else the one zoomed on, drawn on the session's timeline as a line
   on its own scale, its peak in a caption; zooming the timeline redraws it in finer buckets.
2. `hotMethods`: under the graph, the methods by self time (the top of the stack) or total time
   (each sample once, however deep its recursion); a click finds the method in the graph.
3. `methodTrees`: a method picked by a right-click on a frame, or from the hot methods: its callers
   above it, up to the operations, its callees below, each merged by name; a sample counts once,
   at the method's outermost frame.
4. `hide`, a filter: the frames whose name a pattern finds are left out of the paths, their time
   given to their callers, in the graph, the timeline line, the hot methods and the method view.
5. `compare`: **Compare…** keeps the selection (operation, thread, interval, one operation) as a
   baseline; the graph of the next selection, with the same display options, is colored by the
   change of each frame's share of the time, red for more and blue for less. Two different
   operations are compared by the paths under them, each against the baseline's operations
   merged. The frames only the baseline has are not drawn, as in the differential flame graphs
   this follows.

## Tests

- Java: the writer and the endpoint (phase 1), the collapsed output (phase 3), as above.
- JavaScript: `profile.js` holds the logic worth testing (decoding, filters, reversed and merged
  trees), tested in `src/test/js` like the other modules that run without a browser
  (`./gradlew jsTest`).
- Browsers: checked by hand and with scripted headless runs, as for the dialogs; not in CI.
- `tsc` over `jsconfig.json`, as for every module.

## Measurements

Phase 0, 2026-10-18, with a prototype of the writer (`ProfileWriter`) and of the browser's
decoding and tree building (Node 24, the V8 engine of Chrome and Edge). Recordings: the TCP demo,
an application server (two manual sessions, two 5-minute snapshots), a Gradle daemon. Build time
includes reading the samples from the file again, as each flame graph does today; tree time is a
tree over every sample, rooted by operation, each sample's path walked (reversed: the same).

| Recording | Duration | Samples | Stack nodes (depth) | Strings | Operations | JSON | gzip | Build | Decode | Tree |
|---|---|---|---|---|---|---|---|---|---|---|
| TCP demo | 665 s | 55,447 | 511 (25) | 159 | 14,078 | 913 KB | 148 KB | 275 ms | 3.2 ms | 12.5 ms |
| TCP demo | | 33,215 | 411 (24) | 213 | 8,993 | 541 KB | 90 KB | 154 ms | 2.2 ms | 7.6 ms |
| Server, snapshot | 5 min | 158,059 | 1,888 (105) | 1,011 | 119 | 2,151 KB | 117 KB | 339 ms | 6.3 ms | 21.8 ms |
| Server, snapshot | 5 min | 7,177 | 5,652 (101) | 1,595 | 140 | 182 KB | 37 KB | 74 ms | 0.6 ms | 1.6 ms |
| Server, session | | 5,521 | 3,496 (76) | 868 | 933 | 138 KB | 21 KB | 36 ms | 0.4 ms | 1.1 ms |
| Server, session | | 1,691 | 2,969 (100) | 1,123 | 171 | 76 KB | 22 KB | 57 ms | 0.3 ms | 0.4 ms |
| Gradle daemon | | 389 | 144 (68) | 123 | 0 | 9 KB | 2 KB | 4 ms | 0.0 ms | 0.1 ms |

What it tells:

- The samples make the size: 13 to 14 bytes each in JSON, all columns together, and below 3 once
  gzipped (under 1 when the stacks repeat, as in the busiest snapshot). Strings and stacks stay
  under 80 KB together even on the deep stacks of the server.
- Rates reach about 530 samples a second (the busy snapshot). An hour at that rate would be about
  1.9 million samples: some 26 MB of JSON, 1.5 to 5 MB gzipped, and about 0.35 s to decode and
  build a tree over all of it, less for an interval. Hence no limit for now; to revisit if
  sessions of hours become common.
- The trees drawn stay small (up to about 6,000 frames), within what a canvas draws at once.
- The prototype writes each operation's name in full: 113 KB of the demo's 913. Names go into
  `strings` as planned, each operation then an index.
- Building the document costs about one flame graph of today, once per session instead of once
  per filter.

Not measured yet: drawing, which waits for the renderer of phase 2.
