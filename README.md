# jfr-web

jfr-web is a profiler for the JVM with a web interface. It records with Java Flight Recorder,
which ships with the JDK, so there is no agent or native library to install, and it works the
same on Linux, macOS and Windows.

What sets it apart from a plain flame graph is that it knows about **operations**: the requests a
server handles, the jobs it runs, the orders it processes. Every sample is tied to the operation
it was taken in, so you can ask "where does `GET /api/repository` spend its time?" rather than
staring at a flame graph of the whole JVM. You get the most expensive operations, a timeline of
what was running when, the slowest calls with their exceptions and slow I/O, and flame graphs for
one operation or a single call.

**[Try the demo](https://pcdv.github.io/jfr-web/)**: a static copy of the TCP demo described
below, rebuilt on every push. Being static, it can't do everything (no interval selection, no
live view); run `./gradlew tcpDemo -Pdemo.session=true -Pdemo.load=true` locally for the real
thing.

## Ways to run it

| Mode | Use it when | How |
|---|---|---|
| [Embedded](#embedded) | You own the application and can add a dependency | `Profiler.builder().start()` in the app |
| [Standalone](#standalone) | You want to profile a JVM you can't (or don't want to) change | `jfr-web standalone` |
| [Open recordings](#opening-recordings) | You already have `.jfr` files: snapshots, `jcmd` dumps, files from a colleague | `jfr-web open <files or dirs>` |
| [Static export](#static-export) | You want to share a report, or publish a demo, with no server | `jfr-web export ... -o site/` |
| [Command line](#command-line) | You want text or a single HTML flame graph, e.g. in a script | `jfr-web report`, `flame`, `history` |

Embedded is the most complete: it sees operations as they start, which is what makes auto mode
and the browser-armed sessions work. The others build on the same interface and lose a little
along the way, as explained in each section.

In the examples, `jfr-web <command>` stands for `./gradlew run --args="<command>"` from a clone
of this repository.

## What your application needs to provide

Profiling works on any JVM. How much jfr-web can tell you depends on whether it knows what the
operations are:

- **A [flak](https://github.com/pcdv/flak) application** has nothing to do. Its requests are
  operations, named after their route (`GET /api/user/:id`), and recordings get them from flak's
  own JFR event (flak 3.4.0 or later).
- **Any other application** can declare its operations with a few lines of code, using the
  [`jfrweb.ops` API](#declaring-operations). An order server, a batch, a message consumer: anything
  with units of work.
- **A JVM that does neither** still gets sessions, flame graphs by thread and by time interval,
  and the report on how sampling went. Everything that is per operation stays empty.

|  | flak app | Declared operations | Any JVM |
|---|---|---|---|
| Sessions, flame graphs by thread and interval | yes | yes | yes |
| Operations table, timeline, slowest calls, flame graph per operation | yes | yes | |
| Live operations, Arm, auto mode | yes | yes | |
| Only the requests of your own browser trigger a session | embedded only | | |
| History of operations, snapshots taken on slow calls | embedded only | embedded only | |

## Getting it

Java 17 or later. Releases are on [JitPack](https://jitpack.io/#pcdv/jfr-web), like flak:

```groovy
repositories {
  maven { url 'https://jitpack.io' }
}

dependencies {
  implementation 'com.github.pcdv:jfr-web:0.2.0'
}
```

To try your own build, publish it locally as the next version, `0.3.0-SNAPSHOT`:

```sh
./gradlew publish -Pversion=0.3.0-SNAPSHOT               # local Ivy repository
./gradlew publishToMavenLocal -Pversion=0.3.0-SNAPSHOT   # local Maven repository
```

## Embedded

Add the dependency, then start the profiler from the application:

```java
Profiler.builder().observe(app).start();   // serves the interface on loopback and logs its URL
```

`observe(app)` is for flak apps; operations declared with the `jfrweb.ops` API are picked up
without it. Here is what you get.

**Live operations.** Count, total time and its share, p50/p99/max, errors and bytes for each
operation over the last 1 to 60 minutes. These are plain counters, so they cost next to nothing
and run whether or not anything is being recorded. **Arm** on a row records a session on the next
call of that operation, from any client.

![Live operations](docs/screenshots/live.png)

**Sessions.** Start one, use the application, stop it. The report tells you how the recording
went (sampling rates, truncated stacks, the usual JFR biases), then shows a timeline of the
operations in flight. Hover to bring one forward, drag to select an interval, click a bar to see
that operation alone. Below it: the operations by total time, the slowest calls, and flame graphs
by operation, thread, interval or single call. A single call also lists the exceptions it created
and its slow file and socket accesses. You can name sessions, download the `.jfr` for JDK Mission
Control, and if you give the profiler a `directory(...)`, it lists them again after a restart.

![Report of a session: how it was recorded, timeline](docs/screenshots/report.png)

Selecting an interval on the timeline, or zooming in, narrows everything below it:

![Interval selected on the timeline](docs/screenshots/interval.png)

The flame graph of one route, picked in the table:

![Flame graph of a route](docs/screenshots/flame.png)

And a single request, with its exceptions, slow I/O and flame graph:

![A request and its flame graph](docs/screenshots/request.png)

**Auto mode.** Arm it with name patterns, and optionally "only requests from this browser", and a
session starts by itself when a matching operation begins, before the handler runs, so the
operation is recorded from its first line. It stops once nothing matching has run for a few
seconds. Once, or every time.

![Sessions, with the auto mode panel](docs/screenshots/sessions.png)

**History of operations**, for production. No profiling here: the same counters as the live view,
written to disk every minute into hourly files, purged by age and size. It runs on its own; give
its directory to the profiler to browse it, or use the `history` command.

```java
OperationHistory.builder()
                .directory(Path.of("logs/operations"))  // a dedicated directory: old files get deleted
                .interval(Duration.ofMinutes(1))        // the defaults: every minute,
                .maxAge(Duration.ofDays(7))             // kept 7 days,
                .maxSize(100L << 20)                    // 100 MiB at most
                .observe(app)
                .start();
Profiler.builder().observe(app).history(Path.of("logs/operations")).start();
```

![History of operations page](docs/screenshots/history.png)

**Snapshots**, for production too. A rolling recording keeps the last 5 minutes with the JDK's
lightweight settings, and saves them when something looks wrong: a call 4 times slower than the
usual p99 of its operation (and over a second), or a burst of errors. There is nothing to tune.
You can also save one by hand, right after you saw a problem. Snapshots show up with the
sessions, along with the reason they were taken.

```java
Snapshots snapshots = Snapshots.builder()
                               .directory(Path.of("logs/snapshots"))   // kept across restarts
                               .observe(app)
                               .start();
Profiler.builder().observe(app).snapshots(snapshots).start();
```

The builder covers the rest: address, port, who may connect, where recordings go, JFR settings,
or mounting the interface on a flak server you already run:

```java
Profiler.builder()
        .mount(factory, "/profiler")     // one more app on the server of an AppFactory
        .guard(loginCheck)               // a BeforeHook; by default only local clients get in
        .start();
```

jfr-web depends on flak's JDK backend, so that `Profiler.start()` works in any application. If
your flak app runs on Netty, pick its backend explicitly: `Flak.getFactory()` takes the first one
it finds on the classpath.

## Declaring operations

If your application isn't a flak app, tell jfr-web what its operations are. The live view,
history, snapshots, auto mode and per-operation reports then work just as they do for requests.

```java
try (Operation op = Operations.begin("quote").detail(symbol)) {   // grouped by name
  ...
}
```

A synchronous operation like this one runs on the thread that began it. When the work hops
between threads, say a request checked on one thread and answered from a callback on another,
use an asynchronous operation. Its duration covers the whole thing, waiting included, and its
samples come from the threads that declare they're working for it:

```java
Operation op = Operations.beginAsync("order").detail(line);
engine.execute(op.wrap(() -> {    // this thread works for the operation while it runs this
  ...
  op.close();
}));
```

`op.failed(e)` counts it as an error. Recordings see operations through the `jfrweb.Operation`
event, which jfr-web's sessions and snapshots turn on.

`./gradlew tcpDemo -Pdemo.session=true -Pdemo.load=true` runs an order server over plain TCP, with
its own clients. Quotes are synchronous. Orders are checked on the connection's thread, then wait
for a single-threaded matching engine, which replies:

![A session of the TCP demo: operations in flight](docs/screenshots/tcp-report.png)

Here an order spent 5 ms on the connection's thread, waited 80 ms in the engine's queue, then
took 50 ms there. Its flame graph only has the samples of those two stretches:

![An asynchronous operation, its threads and its flame graph](docs/screenshots/tcp-request.png)

## Standalone

Standalone mode profiles the other JVMs on your machine without touching them: no dependency, no
change to their command line. It needs JDK 17 or later on both sides, and the same user.

```sh
./gradlew standalone          # or: jfr-web standalone [--port 8095] [--dir ~/.jfr-web]
```

A page lists the running JVMs. **Connect** attaches to one the way JDK Mission Control does
(attach API, then JMX) and opens the usual interface for it: sessions, reports, live operations,
Arm and auto mode. It stays connected until you click **Disconnect**, connect to another JVM, or
the JVM exits. One JVM at a time.

Operations come from the target's own JFR events (flak's requests, the `jfrweb.ops` API),
streamed as they end. A JVM with neither still gets sessions and flame graphs by thread.

Sessions are stored per application in `~/.jfr-web/<application>`, where the application is the
main class, or the jar name without its version. They survive restarts of both sides, and you
can browse them from the same page even when the application isn't running.

What's different from embedding:

- The target only reports an operation once it's over, too late to start recording it. So **Arm**
  and auto mode start recording right away and stop once a matching operation has completed.
  You'll see "waiting for..." until then.
- "Only requests from this browser" isn't available: the events don't carry the cookie.
- On Windows, JFR samples less often than you ask unless a timer trick runs inside the profiled
  JVM, which standalone mode can't do.
- The operation stream is a small recording inside the target, capped at one minute of data. If
  jfr-web gets killed rather than stopped, that recording lingers until the target exits.
- No history of operations or snapshots yet.

## Opening recordings

Got `.jfr` files already? Snapshots, a `jcmd` dump, something a colleague sent? Open them in the
same interface:

```sh
jfr-web open app.jfr
jfr-web open logs/snapshots --history logs/operations      # every .jfr in the directory
```

Each file becomes a session with the full report: timeline with interval selection and zoom,
operations, slowest calls, and flame graphs with any combination of filters. `--history` adds the
History of operations page. There's no JVM behind the files, so the live view, auto mode and
starting sessions are left out.

Operations only show up if the recording has their events. jfr-web's own sessions and snapshots
always do. For a recording you make yourself, turn on flak's request event or jfr-web's operation
events (`jfrweb.Operation` and `jfrweb.OperationSegment`), all off by default. For instance, to
record an application that is already running (JDK 21):

```sh
jcmd <pid> JFR.start name=jfrweb settings=profile +flak.Request#enabled=true
# ... run the workload ...
jcmd <pid> JFR.dump name=jfrweb filename=workload.jfr
jcmd <pid> JFR.stop name=jfrweb
```

## Static export

`export` writes the interface and some recordings as a static site that any web server can host,
with no Java behind it. Handy to share a report, and it's how the demo site is built.

```sh
jfr-web export app.jfr other.jfr -o site/ --history logs/operations
./gradlew tcpDemo -Pdemo.export=site/       # the TCP demo: one session, 3 minutes of history
```

Every API response reachable by clicking is computed up front. What needs free input isn't:
selecting an interval on a timeline, the From and To of a flame graph, combined filters.

Before you publish one, remember what a recording can contain: the JVM's environment variables,
system properties and command line, and the processes of the machine with theirs, unless the
recording turned those events off (`jdk.InitialEnvironmentVariable`, `jdk.InitialSystemProperty`,
`jdk.SystemProcess`). Recordings running at the same time share their events, too.
`--no-recordings` leaves the `.jfr` files out of the site, and `jfr scrub --exclude-events ...`
(JDK 19 and later) removes events from a copy of one.

## Command line

For scripts, or when a browser is overkill:

```sh
jfr-web report app.jfr                                   # how it was recorded, operations table
jfr-web flame app.jfr -o flame.html                      # wall-clock flame graph, rooted by operation
jfr-web flame app.jfr -o busy.html --operation 'GET /api/.*'
jfr-web history logs/operations --from 2026-10-13T10:00 --to 2026-10-13T11:00
```

`flame` also takes `--thread <regex>` to keep some threads, `--all` to include samples taken
outside operations, and `--collapsed` for collapsed stacks instead of HTML.

## Trying it out

- `./gradlew demo -Pdemo.session=true -Pdemo.load=true` runs a small flak app with the profiler,
  records a first session and keeps some traffic going. Most screenshots above come from it;
  `node docs/screenshots/capture.mjs <profiler URL>` takes them again in headless Edge or Chrome.
- `./gradlew tcpDemo -Pdemo.session=true -Pdemo.load=true` does the same for the TCP order server.
- With either one running, `./gradlew standalone` lets you connect to it from outside.

More: [docs/requirements.md](docs/requirements.md) for what the project sets out to do, and
[ROADMAP.md](ROADMAP.md) for what's done and what's next.

## License

[Apache License 2.0](LICENSE).
