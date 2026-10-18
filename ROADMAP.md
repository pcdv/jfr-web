# Roadmap

Milestones in order. Each one is usable on its own. What the project must do is described in
[docs/requirements.md](docs/requirements.md); this file only tracks where it stands.

## Done

- [x] Exploration on a web UI reload recorded on Windows:
  - the wall-clock view (Java + native samples) is what reveals filesystem time; a CPU-only view
    hides it
  - idle threads blocked in native code take most native samples: filtering is essential
  - `jfr-converter` 4.0 renders JDK-only recordings in `--wall` mode, including native samples;
    `--cpu` produces nothing on them; `-I` matches frames, not thread names, so thread
    filtering has to be done on collapsed stacks before rendering

## M0: prerequisites in flak

Shipped in a flak release.

- [x] Request hook and event contract specified (requirements, "Prerequisites in flak")
- [x] Hook around the whole request (begin after route matching / end after `finish()`)
- [x] `Request` exposes the matched route template
- [x] JFR event `flak.Request`, disabled by default
- [x] Bytes in / bytes out per request, in both backends
- [x] Released: flak 3.4.0 (2026-10-13), from JitPack

## M1: offline analysis

Validates the join between samples and requests before building any UI.

- [x] Command line on a `.jfr` file recorded with flak's request event
- [x] Endpoints table (count, total time, p50/p99/max, errors)
- [x] Flame graph per route: collapsed stacks rooted by route, rendered with `jfr-converter`
- [x] Report header: JDK version, JVM arguments, enabled events, sampling periods

Found on the way: on Windows (Zulu 17.0.14), JFR drops the samples whose stack it fails to
walk. A tight loop got no sample at all over 1 s, sorting arrays about 60 of 100, SHA-256
hashing 12. Reports state it among the known biases.

First real workload (the backend of a web UI, JDK 21, `profile` settings): JFR takes one native sample per
period across all threads, and idle threads blocked in `accept` / `select` won 75% of them while
`GET /api/repository` ran. The request threads got the rest: the flame graph kept the right
proportions, but showed about a quarter of the time. A shorter period gives more samples, not a
bigger share: native times can only be read as proportions.

On Windows, the native period is not honored: 2 ms asked gave one sample every 12 ms in that application.
On JDK 17 and 21 alike, a daemon thread in `Thread.sleep(Long.MAX_VALUE)` raises the rate about
2.5× (1 ms asked: about 215/s without, 540/s with), likely by holding the timer resolution at
1 ms for the process. Measured on 2026-10-13 with a standalone program, not in that application.

Done since: samples weigh the interval observed in the recording when it is longer than their
period (native: the mean interval, one sample per period; Java: the shorter gaps between
rounds, since rounds with no Java thread running leave no sample), the span of a recording runs
from its first to its last committed event, and reports state which share of the native samples
the request threads got.

Done in M2: on Windows, the sleeping thread runs while a session records, and only then. For an
application that does not embed jfr-web, it could ship as a Java agent (`-javaagent`, or loaded
into a running JVM through the Attach API), which could also start the recording.

## M2: embedded, manual sessions

- [x] `Profiler.start()`: UI on loopback, URL logged
- [x] Mounting on an existing flak server instead
- [x] Security: loopback-only default, pluggable guard, `CrossOriginProtection`
- [x] Manual sessions (start / stop), sessions list, download of the `.jfr`
- [x] Flame graph per session, time interval and thread

Recordings use the JDK's `profile` settings, plus flak's request event and native calls sampled
every 5 ms. The interface is plain JS modules served from the jar, type-checked from their JSDoc
(`jsconfig.json`), with no build step.

## M3: flak-aware views

- [x] Live endpoint statistics, without profiling
- [x] Timeline of requests in flight, stacked by route, with interval selection
- [x] Flame graph per route and per slow request (with its exceptions and file accesses)

Live statistics come from flak's request listener (`Profiler.builder().observe(app)`), per minute
over the last hour; percentiles from a logarithmic histogram, within about 10%. Recordings now
also hold the exceptions created (`jdk.JavaExceptionThrow`, with their stack: an application
creating thousands per second should turn it off) and the file and socket accesses from 1 ms.
Only accesses with a JFR event show: checking a file's attributes or listing a directory has
none, which is what made that application's repository view slow on Windows.

## M4: auto mode

- [x] Trigger by route patterns
- [x] "Arm my browser" trigger (cookie), once or every time
- [x] Idle stop, grace period, maximum session duration

The session starts in the request listener's `onBegin`, before the handler: the request that
triggers it is recorded whole. The first recording of a JVM costs about 230 ms to start, the next
ones about 10 ms: arming warms JFR up. The idle time and the grace period are one setting. The
cookie of an armed browser has `Path=/`, and cookies ignore ports: the profiler on its own port
arms the requests of an app on another one of the same host.

## M5: endpoint history for production

- [x] Endpoints written per interval to hourly files, no JFR: counts, durations with their
  histogram, sizes, slowest requests
- [x] Gzipped hours, purge by age and total size
- [x] History page: a period, zoomable timeline of requests, endpoints, slowest requests
- [x] Command line: `jfr-web history <dir> --from ... --to ...`

At 1-minute intervals, a line takes about 2 KB with a few endpoints, most of it the slowest
requests: about 3 MB a day before gzip.

## M6: snapshots, sessions kept

- [x] Rolling recording, saved by hand ("Save the last 5 minutes")
- [x] Snapshots saved by themselves: request much slower than usual for its endpoint, burst of
  errors; no threshold to configure
- [x] Retention by snapshot count and disk size, kept across restarts
- [x] Measure the cost of the rolling recording and the size of a snapshot on a real application

Measured on an application server (Windows, JDK 21, idle): with the JDK's `default` settings, the rolling
recording cost about 7% of a core, nearly all of it `jdk.CPULoad`, taken every second through
Windows' performance counters at about 65 ms each. Taken every minute instead (sessions too), the
thread of periodic events uses 0.09 s of CPU per minute, about 0.15% of a core. A snapshot of
5 idle minutes weighs 2.7 MB.
- [ ] Other triggers: saturation (requests in flight far above usual), JVM signals (long GC
  pauses, CPU saturated) watched from the rolling recording itself
- [ ] Snapshots marked on the History page's timeline
- [x] Manual and automatic sessions kept across restarts too (M9)
- [ ] Diff of two flame graphs

## M7: any application, operations

- [x] Operations instead of endpoints: flak's requests are one source, grouped and shown the same
- [x] `jfrweb.ops` API: synchronous and asynchronous operations, segments, failures, sizes; JFR
  events `jfrweb.Operation` and `jfrweb.OperationSegment`
- [x] Live statistics, history, snapshots and auto mode fed by the API with no set-up
- [x] Recordings: an asynchronous operation gets the samples of its segments; its report lists
  the threads that worked for it
- [x] Demo without HTTP: an order server over TCP (`./gradlew tcpDemo`)
- [x] Events of the application mapped to operations, with no code: a name and a detail built
  from their fields, and a field telling a failure (`EventMappings`, `operation-events.properties`)
- [x] Mapped events in the live view, history, snapshots and auto mode of an embedded profiler,
  streamed in-process while something listens to operations
- [x] Nested operations on a thread (an operation inside a request), shown by the innermost
- [x] Events known out of the box, with no mapping: Quarkus REST requests, JUnit tests
- [ ] Other frameworks (e.g. RPC), through a bridge module that starts jfr-web and feeds the
  API from the framework's hooks: services never depend on jfr-web

Renamed while no one used it: `EndpointHistory` is `OperationHistory`, and the REST API, the
history files and the command line changed their keys (files written before still read). Applications
already wired must follow.

## M8: static export

- [x] `jfr-web export`: the interface and recordings as a static site, the responses computed
  beforehand for what can be reached by clicking
- [x] The TCP demo exports itself (`-Pdemo.export=dir`), without the environment of the JVM
- [x] Published on GitHub Pages, rebuilt by a workflow
- [ ] A link to the repository from the static site
- [ ] Two demos on the site: the flak app and the TCP server

## Next

- [x] Deeper stacks: JFR keeps the top 64 frames of a stack by default, and deep applications
  get truncated flame graphs. `JFR.configure stackdepth=` raises it from inside the JVM (JDK 17
  and 21 alike), but only before its first recording: the profiler and the snapshots raise it to
  256 frames as they start, when they are first (`stackDepth()` of their builders), and reports
  tell how many stacks were cut and the JVM option otherwise
  (`-XX:FlightRecorderOptions:stackdepth=`)
- [x] Operations tables: a bar of each operation's share of the time
- [x] Timelines: the operation under the pointer outlined and brought forward; in a session, a
  click on one narrows the slowest and the flame graph to it, in that bucket
- [x] Live operations: arm a session on the next call of one of them
- [x] Sessions named: when started, armed or later
- [x] Session ids kept across restarts: a snapshot keeps its id, and none is given twice
- [x] Flame graphs merged across operations, reversed (hot methods), or Java only
- [x] Top bar always visible; a session's row opens its report
- [ ] Session named after the actual path of the call that started it, or a flame graph of one
  call showing its path: parked, a session and a flame graph aggregate calls with different
  values

## M9: standalone

- [x] `jfr-web standalone`: a page lists the JVMs of the machine; one is attached to (attach API,
  JMX), with nothing installed in it, and profiled through the same interface
- [x] Sessions recorded by the JVM over JMX, written where jfr-web keeps them; operations from
  their events, streamed (flak's requests, the `jfrweb.ops` API), for live statistics, auto mode
  and Arm
- [x] Sessions kept by application in `~/.jfr-web`, listed again after a restart; embedded
  profilers given a directory keep theirs too
- [x] `jfr-web open`: recordings made elsewhere, files or directories, browsed in the interface as
  sessions, with an operation history if given one
- [ ] A recording dropped on the page of the JVMs, to open it there
- [ ] Snapshots and operation history of the JVM attached
- [ ] Several JVMs attached at once

A session's span starts with it: a recording made while another one runs (the stream, the
rolling recording of the snapshots) gets the events of the other from the start of the current
chunk.

## M10: flame graphs of our own

A session's samples sent to the browser once, with their time, thread and operation, and drawn
by a renderer of our own: filters change and combine with no request, in the static export too.
Plan and format in [docs/flame-graphs.md](docs/flame-graphs.md).

- [x] Measured: size of a session's profile, time to build and to sum it (drawing waits for the
  renderer)
- [x] Profile format and endpoint
- [x] Renderer at parity with async-profiler's page (zoom, search, tooltips, colors), in the
  interface's look
- [x] Operations table and slowest list of an interval computed by the page, from the operations
  added to the profile
- [ ] Static export, `jfr-web flame` and `--collapsed` on it; `jfr-converter` removed
- [ ] Timeline and flame graph linked; hot methods; method view; frames hidden by pattern; diff

## Later

- [ ] async-profiler as an additional source on Linux and macOS
