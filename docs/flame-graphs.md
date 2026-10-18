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

A JSON document per session, version 1 (sizes measured in phase 0, below).

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
  once stopped: the document is built on the first request and kept gzipped in memory, beside the
  `Analysis` and like it behind a soft reference. No file on disk: building it costs about what
  one flame graph costs today (phase 0).
- Sent with `Content-Encoding: gzip` when the request accepts it (flak does not compress by
  itself), plain otherwise. GitHub Pages gzips the static export by itself.

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

**0. Measure.** Done on 2026-10-18, see "Measurements": the document is kept gzipped in memory,
and sessions need no limit for now.

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
