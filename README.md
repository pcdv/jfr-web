# jfr-web

A profiler embedded in a JVM, with a web interface. It records with Java Flight Recorder, so
it needs no agent or native library and runs everywhere the JDK does, Windows included.

In a [flak](https://github.com/pcdv/flak) application, it also knows which endpoint every
sample belongs to, with no annotation: most expensive endpoints, timeline of requests, flame
graph per route or per slow request.

## Status

Design stage: nothing is usable yet.

- [docs/requirements.md](docs/requirements.md): what the project must do
- [ROADMAP.md](ROADMAP.md): milestones, what is done and what comes next
