# jfr-web

A profiler embedded in a JVM, with a web interface. It records with Java Flight Recorder, so
it needs no agent or native library and runs everywhere the JDK does, Windows included.

In a [flak](https://github.com/pcdv/flak) application, it also knows which endpoint every
sample belongs to, with no annotation: most expensive endpoints, timeline of requests, flame
graph per route or per slow request.

## Status

M5: embedded, with manual or automatic sessions, flak-aware views, and an endpoint history for
production.

```java
Profiler.builder().observe(app).start();   // serves the interface on loopback, logs its URL
```

- **Live endpoints**: the endpoints of the apps observed over the last 1 to 60 minutes (count,
  total time, p50/p99/max, 5xx, bytes), kept by counters whether a session records or not.
- **Sessions**: start one, use the application, stop it. Its report states how it was recorded,
  shows a timeline of the requests in flight by endpoint (drag to select an interval), lists the
  endpoints by total time and their slowest requests, and shows flame graphs by endpoint, thread,
  interval or single request. A request also shows the exceptions it created and its slow file
  and socket accesses. The `.jfr` file can be downloaded, e.g. for JDK Mission Control.
- **Auto mode**: sessions start by themselves when a matching request comes (endpoint patterns,
  and/or only the requests of the browser armed), before its handler runs, and stop once no
  matching request has run for a few seconds; once or every time.
- **Endpoint history**, for production: no profiling, only the endpoints' counters written to
  disk at a regular interval, in hourly files purged by age and size. It runs alone; the
  profiler's History page and the `history` command read it.

```java
EndpointHistory.builder()
               .directory(Path.of("logs/endpoints"))   // dedicated: what it holds may be deleted
               .interval(Duration.ofMinutes(1))        // the defaults: 1 minute,
               .maxAge(Duration.ofDays(7))             // 7 days,
               .maxSize(100L << 20)                    // 100 MiB
               .observe(app)
               .start();
Profiler.builder().observe(app).history(Path.of("logs/endpoints")).start();   // to browse it
```

```sh
./gradlew run --args="history logs/endpoints --from 2026-10-13T10:00 --to 2026-10-13T11:00"
```

- **Snapshots**, for production too: a rolling recording keeps the last 5 minutes, with the JDK's
  light settings, and saves them when something goes wrong, with nothing to configure: a request
  4 times slower than its endpoint's usual p99 (and over 1 s), or a burst of errors. Also saved by
  hand, e.g. right after a problem. They are listed with the sessions, with why they were taken.

```java
Snapshots snapshots = Snapshots.builder()
                               .directory(Path.of("logs/snapshots"))   // dedicated, kept across restarts
                               .observe(app)
                               .start();
Profiler.builder().observe(app).snapshots(snapshots).start();          // to list and take them
```

A builder sets the rest: address, port, guard, directory of the recordings, JFR settings, or
mounting on an existing flak server:

```java
Profiler.builder()
        .mount(factory, "/profiler")     // one more app on the server of an AppFactory
        .guard(loginCheck)               // a BeforeHook; by default, non-local clients are refused
        .start();
```

jfr-web depends on flak's JDK backend, so that `Profiler.start()` works in any application. A flak
application on Netty should then pick its backend explicitly: `Flak.getFactory()` takes the first
one it finds on the classpath.

To try it: `./gradlew demo -Pdemo.session=true` runs a small flak app with the profiler, records
a first session, and prints the URLs.

### Offline analysis

A recording made outside jfr-web can be analyzed from the command line:

```sh
./gradlew run --args="report app.jfr"                    # how it was recorded, endpoints table
./gradlew run --args="flame app.jfr -o flame.html"       # wall-clock flame graph, rooted by endpoint
./gradlew run --args="flame app.jfr -o busy.html --endpoint 'GET /api/.*'"
```

`flame` takes `--thread <regex>` to keep some threads, `--all` to add the samples taken outside
requests, and `--collapsed` for collapsed stacks instead of HTML.

The recording must enable flak's request event (flak 3.4.0+), which is off by default:
`recording.enable("flak.Request")`, or in a `.jfc` file. The `profile` settings of the JDK
sample Java code every 10 ms and native calls every 20 ms.

To record an application that is already running, with no change to it (JDK 21):

```sh
jcmd <pid> JFR.start name=jfrweb settings=profile +flak.Request#enabled=true
# ... run the workload ...
jcmd <pid> JFR.dump name=jfrweb filename=workload.jfr
jcmd <pid> JFR.stop name=jfrweb
```

- [docs/requirements.md](docs/requirements.md): what the project must do
- [ROADMAP.md](ROADMAP.md): milestones, what is done and what comes next
