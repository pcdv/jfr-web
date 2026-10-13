# jfr-web

A profiler embedded in a JVM, with a web interface. It records with Java Flight Recorder, so
it needs no agent or native library and runs everywhere the JDK does, Windows included.

In a [flak](https://github.com/pcdv/flak) application, it also knows which endpoint every
sample belongs to, with no annotation: most expensive endpoints, timeline of requests, flame
graph per route or per slow request.

## Status

M1: offline analysis of a recording, from the command line. Nothing is embedded yet.

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

- [docs/requirements.md](docs/requirements.md): what the project must do
- [ROADMAP.md](ROADMAP.md): milestones, what is done and what comes next
