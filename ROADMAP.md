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
- [ ] Hook around the whole request (begin after route matching / end after `finish()`)
- [ ] `Request` exposes the matched route template
- [ ] JFR event `flak.Request`, disabled by default
- [ ] Bytes in / bytes out per request, in both backends

M1 does not wait for the release: its tests can emit the event themselves, since only the
contract matters.

## M1: offline analysis

Validates the join between samples and requests before building any UI.

- [ ] Command line on a `.jfr` file recorded with flak's request event
- [ ] Endpoints table (count, total time, p50/p99/max, errors)
- [ ] Flame graph per route: collapsed stacks rooted by route, rendered with `jfr-converter`
- [ ] Report header: JDK version, JVM arguments, enabled events, sampling periods

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
