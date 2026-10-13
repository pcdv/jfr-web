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

## Applications observed

- The application does not have to be a flak application, nor even an HTTP one. Without flak,
  the profiler provides sessions, timelines and flame graphs, without route context.
- The API accepts references to one or several flak `App`s (a server can host several), which
  allows introspecting their endpoints/routes.
- With a flak app, every sample taken while a request runs is attributed to its route, with no
  annotation, thanks to flak's request event.
- The request event is a documented contract (name and fields), so another HTTP stack can emit
  it and get the same route context.

## Endpoint statistics (without profiling)

- Flak apps only.
- Per endpoint: invocation count, durations (p50/p99/max), errors, bytes in / bytes out.
- Available even when no profiling runs, so the statistics are kept by in-memory counters fed
  by flak, not derived from a JFR recording.
- Shown per time window, sorted by total time (count × duration).

## Profiling

Collected with JFR. Wall-clock view by default: Java execution samples merged with native
method samples, so that time blocked in native calls (filesystem, sockets) is visible.

### Sessions

A session is one profiling recording, kept on disk as one `.jfr` file. Its bounds depend on the
recording mode below. A session records its start time, duration and how it was started; with a
flak app, also its request count and, in auto mode, its trigger.

### Recording modes

- Manual: a session runs from start to stop, from the UI or the REST API.
- Continuous: one open-ended session, a rolling recording with a maximum age and size, browsed
  by time interval.
- Auto, flak apps only: a session starts when matching endpoints are invoked, and stops when
  idle.
  - Trigger: route patterns, or "arm my browser" (a cookie set from the UI, so that only the
    requests of that browser trigger a session); once or every time.
  - The recording starts synchronously, before the triggering request runs (in the request
    hook's begin). JFR is warmed up when auto mode is enabled, by a short recording that is
    thrown away, so a start costs a few milliseconds. Installing the profiler alone still
    records nothing.
  - Stop after T seconds without requests (plus a grace period for asynchronous work), with a
    maximum session duration.
  - The profiler's own requests never count as activity.

### History

- Retention by number of sessions and total disk size.
- A session can be downloaded (to open it in JDK Mission Control) or compared with another.

## Web interface

- Sessions list.
- Endpoints table for a session or a time window (flak apps).
- Timeline: activity per second (requests in flight stacked by route, for flak apps); select an
  interval to zoom in.
- Flame graph for any combination of time interval, thread and, for flak apps, route; for a
  single slow request, its flame graph plus the exceptions thrown and files accessed by its
  thread.
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

- Sampling density: at 10-20 ms per sample, a single short request gets few samples. Per-route
  flame graphs aggregate many requests; per-request ones are only meaningful for slow requests.
- Work handed to another thread (executor) falls outside the request window and is not
  attributed to the route.
- JFR samples only a few threads per period, and idle threads blocked in native code (selectors,
  `accept`) take part of the native samples. Filtering by request hides them in the views but
  does not recover the lost samples.
- Threads blocked on a lock, sleeping or parked are sampled by neither event: the wall-clock
  view shows Java code and native calls, not waiting.
- JFR drops a sample whose stack it fails to walk: tight loops and intrinsics (e.g. hashing)
  get few samples, or none.

## Decisions

- **JSON is written by hand**, with no dependency: flak-jackson would impose a Jackson version
  on host applications (some already ship their own). The profiler only produces JSON (its
  inputs are query parameters), in a few fixed shapes, so a small writer with string escaping
  is enough. To revisit only if the shapes multiply.
- **The web interface is plain JavaScript, with no build step**: ES modules served from the
  jar, one per view or component rather than one big page, type-checked from their JSDoc
  annotations (`jsconfig.json`, `// @ts-check`). Moving to TypeScript stays a mechanical step.
