# jfr-web: requirements

A profiler embedded in a JVM, with a web interface served by flak. Its first target is
understanding where time goes during a burst of activity (e.g. reloading a web UI), on every
platform including Windows.

## Prerequisites in flak

Shipped in a flak release before the profiler development starts:

- A hook around the whole request (begin / end). The SPI only has `BeforeHook`,
  `SuccessHandler` and `ErrorHandler` today.
- One JFR event per request: app, route template, method, status, duration, thread. Free when
  no recording listens to it.
- Bytes in / bytes out per request, counted in both backends (JDK, Netty). Chunked responses
  have no declared length, so the count comes from counting streams.

### Request hook

- **Begin** fires once the route is matched and before the handler runs, on the thread that
  serves the request. It cannot be a `BeforeHook`: those run before matching, and the auto mode
  needs the route to decide whether to start a session before the handler runs. Matching and
  invoking both happen inside the backend's dispatch, so begin is called between the two.
- **End** fires for every request, matched or not (404, rejected by a `BeforeHook`, failed), once
  the response is finished: both backends finish it (`JdkRequest.finish()`,
  `NettyRequest.finish()`) on the serving thread, after `AbstractApp.handle()` returns. Only
  then are the duration and the bytes out known. A request with no begin (no matching route, or
  rejected by a `BeforeHook`) has no route.
- `Request` exposes the matched route template (`getHandler()` only returns the Java method).

### Request event (contract)

The contract any HTTP stack can emit to get route context. Fields beyond JFR's own `startTime`,
`duration` and `eventThread`:

| Field      | Type   | Content                                                            |
|------------|--------|--------------------------------------------------------------------|
| `app`      | String | root path of the app, e.g. `/api`                                  |
| `method`   | String | HTTP method                                                        |
| `route`    | String | route template, e.g. `/user/:id`; null when no route matched       |
| `path`     | String | path relative to the app, without the query string                 |
| `status`   | int    | response status                                                    |
| `bytesIn`  | long   | request body bytes read                                            |
| `bytesOut` | long   | response body bytes sent, after compression                        |

- Name `flak.Request`, category `flak`.
- Committed on the thread that served the request, so `eventThread` is the thread whose samples
  belong to the request, and `startTime` / `duration` bound them.
- **Disabled by default** (`@Enabled(false)`): a custom event is otherwise recorded by any
  recording, including JMC's or `-XX:StartFlightRecording` with `default.jfc`. The profiler
  enables it in its own recordings.
- Duration covers the handler and writing the response, up to `finish()`. A small Netty
  response is handed to the event loop there and leaves the socket slightly later.
- The query string is left out of `path`: it may carry secrets.

## Packaging

- Independent project, depending on flak to expose its web interface.
- Rendering of flame graphs and heatmaps delegated to async-profiler's `jfr-converter`
  (pure Java, Apache 2.0, on Maven Central). Recordings are read with the JDK's
  `jdk.jfr.consumer`: joining samples to requests and filtering threads happen there, on
  collapsed stacks, and `jfr-converter` only renders them.
- As few other dependencies as possible: the profiler is embedded in host applications, and
  each dependency is a version it may impose on them (see the open question on JSON).
- Java 17, like flak.

## Enabling it

- One line of code: `Profiler.start()` serves the UI on loopback and logs its URL.
- Finer tuning through the API only: address, port, guard, recording settings, retention. The
  embedding application decides how to expose them (system properties, config file...); the
  profiler reads no configuration of its own.
- For a flak application, the UI can instead be mounted as one more app on an existing server
  (e.g. under `/profiler`).
- Installing it records nothing: recording is a separate, runtime decision (see Recording
  modes).

## Operations

Everything is grouped by **operation**: a unit of work of the application, with a name that
operations are grouped by (like the route of a request), a start, a duration, an outcome
(failed or not), and optionally a detail (what this one worked on) and sizes in and out.

- The application does not have to be a flak application, nor even an HTTP one. Without
  operations, the profiler still provides sessions, timelines of samples and flame graphs.
- **flak**: the requests of the apps given to the profiler are operations, named after their
  method and route ("GET /api/user/:id"), failed on a 5xx status, with no annotation. Their
  samples come from flak's request event, a documented contract (name and fields) that another
  HTTP stack can emit to get the same.
- **Any application**: the `jfrweb.ops` API begins and ends operations, and records them as the
  `jfrweb.Operation` JFR event. The profiler, the history and the snapshots hear of them with no
  set-up.
  - Synchronous: run by the thread that begins it, which works only for it until it ends: the
    samples of that thread in that time are its own. Synchronous operations do not nest.
  - Asynchronous: may end on any thread (a reply sent from a callback). Its duration is that of
    the whole, waiting included; its samples are those of its segments, the times when a thread
    declares working for it (`jfrweb.OperationSegment` events, `op.wrap(task)` for an
    executor).
- Other sources adapt to it rather than depend on jfr-web: e.g. an RPC framework would emit its
  own JFR events and counters, with an adapter on the jfr-web side.

## Operation statistics (without profiling)

- Per operation name: count, durations (p50/p99/max), errors, bytes in / bytes out.
- Available even when no profiling runs, so the statistics are kept by in-memory counters fed
  as operations end, not derived from a JFR recording.
- Shown per time window, sorted by total time (count × duration).

## Operation history (production)

A mode that can stay on in production, to tell afterwards which operations ran during a period,
and how they behaved. No profiling: the same counters, written to disk.

- Low impact: counters updated as operations end, written once per interval (1 minute by
  default, configurable).
- An interval is one JSON line: per operation name, count, total, p50/p99/max, errors, bytes,
  and the histogram the percentiles come from, so that intervals add up; plus the slowest
  operations of the interval (10 by default: time, detail, outcome, duration, thread). An
  interval without operations is written all the same: no line means the history was not
  running. Files written when the history counted endpoints still read.
- Hourly files in a dedicated directory, `<dir>/<yyyy-MM-dd>/<HH>.jsonl` in UTC, gzipped once
  the hour is over. Purged by age (7 days by default) and total size (100 MiB by default),
  both configurable.
- Runs alone, without the web interface or JFR. The embedding application decides whether it
  runs and how, from its own configuration.
- Read from the profiler's interface (a period, zoomable: operations, operations over time,
  slowest operations) or from the command line, e.g. on files copied off a server.

## Snapshots (production)

Something to investigate after a problem, with no need to have started a session beforehand,
and nothing to configure, otherwise it would never be turned on.

- A rolling recording keeps the last minutes (5 by default) on disk, with the JDK's `default`
  settings (about 1%) plus the operation events. A snapshot saves them as a `.jfr` file, with a
  `.json` file telling why and when.
- Taken by hand (e.g. right after a problem), or by themselves when:
  - an operation is much slower than usual for its name: 4 times its p99 of the last hour, and
    over 1 s; for a name with too few operations to tell what is usual, over 10 s
  - errors burst: at least 5 operations failed in a minute, 10% of the operations of that
    minute at least, and 4 times their usual share
- Automatic snapshots wait 10 s to hold the aftermath, and are 15 minutes apart at least: an
  incident gives one snapshot, not one per slow operation.
- A dedicated directory, kept across restarts (an incident is often followed by one), purged by
  number of snapshots (20) and total size (1 GiB).
- Listed with the sessions, with their reason; their report is that of a session.

## Profiling

Collected with JFR. Wall-clock view by default: Java execution samples merged with native
method samples, so that time blocked in native calls (filesystem, sockets) is visible.

### Sessions

A session is one profiling recording, kept on disk as one `.jfr` file. Its bounds depend on the
recording mode below. A session records its start time, duration and how it was started; with
operations, also their count and, in auto mode, its trigger.

### Recording modes

- Manual: a session runs from start to stop, from the UI or the REST API.
- Continuous: one open-ended session, a rolling recording with a maximum age and size, browsed
  by time interval.
- Auto: a session starts when matching operations begin, and stops when idle.
  - Trigger: name patterns, "arm my browser" (a cookie set from the UI, so that only the
    requests of that browser trigger a session; flak only), or both; once or every time.
  - The recording starts synchronously, before the triggering operation runs (in the request
    hook's begin for flak, in `Operations.begin` for the API). JFR is warmed up when auto mode is enabled, by a short recording that is
    thrown away, so a start costs a few milliseconds. Installing the profiler alone still
    records nothing.
  - Stop once no matching operation has run for T seconds (3 by default, long enough for
    asynchronous work to finish: one setting rather than an idle time plus a grace period, which
    would only add up), with a maximum session duration (2 minutes by default).
  - The profiler's own requests never count as activity.

### History

- Retention by number of sessions and total disk size.
- A session can be downloaded (to open it in JDK Mission Control) or compared with another.

## Web interface

- Sessions list.
- Operations table for a session or a time window.
- Timeline: activity per second (operations in flight stacked by name); select an interval to
  zoom in.
- Flame graph for any combination of time interval, thread and operation name; for a single
  slow operation, its flame graph plus the threads that worked for it (asynchronous), the
  exceptions thrown and the files accessed by them.
- Diff of two flame graphs (before / after a fix, Windows / Linux).
- Every report states how it was recorded: JDK version, JVM arguments, enabled events, sampling
  periods, and the known sampling biases.

## Security

- Loopback only by default: without a configured guard, non-loopback clients are refused.
- Pluggable guard for real deployments (typically flak-login).
- The endpoints that change state (start / stop, arm) are protected by
  `flak.util.CrossOriginProtection`.

## Overhead

- Not recording: no measurable cost (counters only).
- Recording: target below 2% CPU with the default settings.

## Non-goals

- Distributed tracing, production metrics backends (Prometheus, OpenTelemetry): other tools do
  that.
- A replacement for JDK Mission Control: the `.jfr` files remain available for it.

## Known limitations

- Sampling density: at 10-20 ms per sample, a single short operation gets few samples. Flame
  graphs per name aggregate many operations; per-operation ones are only meaningful for slow
  ones.
- Work handed to another thread (executor) falls outside the operation's window and is not
  attributed to it, unless it runs in a segment of an asynchronous operation.
- JFR samples only a few threads per period, and idle threads blocked in native code (selectors,
  `accept`) take part of the native samples. Filtering by operation hides them in the views but
  does not recover the lost samples.
- Threads blocked on a lock, sleeping or parked are sampled by neither event: the wall-clock
  view shows Java code and native calls, not waiting.
- JFR drops a sample whose stack it fails to walk: tight loops and intrinsics (e.g. hashing)
  get few samples, or none.

## Decisions

- **Operations, not endpoints**: the profiler started with flak's requests, and grouped by
  endpoint. Its views only need a unit of work (a name, a start, a duration, an outcome, a
  thread or segments), so flak became one source of operations among others, and the interface
  says "operations" everywhere, a flak endpoint included. Renamed while no one used it yet:
  `EndpointHistory` became `OperationHistory`, the REST API and the files changed their keys
  (the old files still read).

- **JSON is written by hand**, with no dependency: flak-jackson would impose a Jackson version
  on host applications (some already ship their own). The profiler only produces JSON (its
  inputs are query parameters), in a few fixed shapes, so a small writer with string escaping
  is enough. To revisit only if the shapes multiply.
- **The web interface is plain JavaScript, with no build step**: ES modules served from the
  jar, one per view or component rather than one big page, type-checked from their JSDoc
  annotations (`jsconfig.json`, `// @ts-check`). Moving to TypeScript stays a mechanical step.
