package jfrweb.history;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

import jfrweb.stats.Histogram;

/**
 * The requests that ended during one interval: per endpoint, their number,
 * durations and sizes; and the slowest of them all.
 */
final class IntervalStats {

  private final Instant start;
  private final int slowestKept;
  private final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();

  /** The slowest requests, the fastest of them first. */
  private final PriorityQueue<Slow> slowest = new PriorityQueue<>(Comparator.comparingLong(Slow::nanos));
  /** Below it, a request cannot be among the slowest: no need to lock. */
  private volatile long slowThreshold;

  IntervalStats(Instant start, int slowestKept) {
    this.start = start;
    this.slowestKept = slowestKept;
  }

  record Slow(Instant end, String endpoint, String path, int status, long nanos, String thread) {}

  private static final class Endpoint {
    int count;
    long totalNanos;
    long maxNanos;
    int errors;
    long bytesIn;
    long bytesOut;
    final Histogram durations = new Histogram();
  }

  Instant getStart() {
    return start;
  }

  /**
   * The number of requests recorded so far.
   */
  int count() {
    int n = 0;
    for (Endpoint e : endpoints.values())
      synchronized (e) {
        n += e.count;
      }
    return n;
  }

  void record(String endpoint, String path, int status, long nanos, long bytesIn, long bytesOut,
              Instant end, String thread) {
    Endpoint e = endpoints.computeIfAbsent(endpoint, k -> new Endpoint());
    synchronized (e) {
      e.count++;
      e.totalNanos += nanos;
      e.maxNanos = Math.max(e.maxNanos, nanos);
      if (status >= 500)
        e.errors++;
      e.bytesIn += bytesIn;
      e.bytesOut += bytesOut;
      e.durations.add(nanos);
    }

    if (slowestKept > 0 && nanos > slowThreshold) {
      synchronized (slowest) {
        slowest.add(new Slow(end, endpoint, path, status, nanos, thread));
        if (slowest.size() > slowestKept)
          slowest.poll();
        if (slowest.size() == slowestKept)
          slowThreshold = slowest.peek().nanos();
      }
    }
  }

  /**
   * The interval as one line of the history: times in ISO-8601, durations
   * in milliseconds, endpoints by total time.
   */
  Map<String, Object> toJson(Instant end) {
    List<Map.Entry<String, Endpoint>> sorted = new ArrayList<>(endpoints.entrySet());
    sorted.sort(Comparator.comparingLong((Map.Entry<String, Endpoint> x) -> x.getValue().totalNanos).reversed());

    List<Object> rows = new ArrayList<>();
    for (Map.Entry<String, Endpoint> x : sorted) {
      Endpoint e = x.getValue();
      synchronized (e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("endpoint", x.getKey());
        m.put("count", e.count);
        m.put("total", ms(e.totalNanos));
        m.put("p50", ms(Math.min(e.durations.percentile(50), e.maxNanos)));
        m.put("p99", ms(Math.min(e.durations.percentile(99), e.maxNanos)));
        m.put("max", ms(e.maxNanos));
        m.put("errors", e.errors);
        m.put("bytesIn", e.bytesIn);
        m.put("bytesOut", e.bytesOut);
        m.put("histogram", e.durations.toSparse());
        rows.add(m);
      }
    }

    List<Slow> slow;
    synchronized (slowest) {
      slow = new ArrayList<>(slowest);
    }
    slow.sort(Comparator.comparingLong(Slow::nanos).reversed());
    List<Object> slowRows = new ArrayList<>();
    for (Slow s : slow) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("end", s.end());
      m.put("endpoint", s.endpoint());
      m.put("path", s.path());
      m.put("status", s.status());
      m.put("duration", ms(s.nanos()));
      m.put("thread", s.thread());
      slowRows.add(m);
    }

    Map<String, Object> line = new LinkedHashMap<>();
    line.put("start", start);
    line.put("end", end);
    line.put("endpoints", rows);
    line.put("slowest", slowRows);
    return line;
  }

  static double ms(long nanos) {
    return Math.round(nanos / 1e4) / 100.0;
  }
}
