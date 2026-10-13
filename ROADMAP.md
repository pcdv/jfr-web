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
- [ ] Released: on flak's branch `request-listener` for now, used here as 3.4.0-SNAPSHOT from
  Maven Local

M1 does not wait for the release: its tests can emit the event themselves, since only the
contract matters.

## M1: offline analysis

Validates the join between samples and requests before building any UI.

- [x] Command line on a `.jfr` file recorded with flak's request event
- [x] Endpoints table (count, total time, p50/p99/max, errors)
- [x] Flame graph per route: collapsed stacks rooted by route, rendered with `jfr-converter`
- [x] Report header: JDK version, JVM arguments, enabled events, sampling periods

Found on the way: on Windows (Zulu 17.0.14), JFR drops the samples whose stack it fails to
walk. A tight loop got no sample at all over 1 s, sorting arrays about 60 of 100, SHA-256
hashing 12. Reports state it among the known biases.

## M2: embedded, manual sessions

- [ ] `Profiler.start()`: UI on loopback, URL logged
- [ ] Mounting on an existing flak server instead
- [ ] Security: loopback-only default, pluggable guard, `CrossOriginProtection`
- [ ] Manual sessions (start / stop), sessions list, download of the `.jfr`
- [ ] Flame graph per session, time interval and thread

## M3: flak-aware views

- [ ] Live endpoint statistics, without profiling
- [ ] Timeline of requests in flight, stacked by route, with interval selection
- [ ] Flame graph per route and per slow request (with its exceptions and file accesses)

## M4: auto mode

- [ ] Trigger by route patterns
- [ ] "Arm my browser" trigger (cookie), once or every time
- [ ] Idle stop, grace period, maximum session duration

## M5: history

- [ ] Continuous mode (rolling recording)
- [ ] Retention by session count and disk size
- [ ] Diff of two flame graphs

## Later

- [ ] async-profiler as an additional source on Linux and macOS
