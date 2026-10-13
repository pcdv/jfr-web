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
 * The operations that ended during one interval: per name, their number,
 * durations and sizes; and the slowest of them all.
 */
final class IntervalStats {

  private final Instant start;
  private final int slowestKept;
  private final Map<String, Name> names = new ConcurrentHashMap<>();

  /** The slowest operations, the fastest of them first. */
  private final PriorityQueue<Slow> slowest = new PriorityQueue<>(Comparator.comparingLong(Slow::nanos));
  /** Below it, an operation cannot be among the slowest: no need to lock. */
  private volatile long slowThreshold;

  IntervalStats(Instant start, int slowestKept) {
    this.start = start;
    this.slowestKept = slowestKept;
  }

  record Slow(Instant end, String name, String detail, int status, boolean failed, long nanos, String thread) {}

  private static final class Name {
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
   * The number of operations recorded so far.
   */
  int count() {
    int n = 0;
    for (Name e : names.values())
      synchronized (e) {
        n += e.count;
      }
    return n;
  }

  /**
   * @param status the HTTP status of a request, 0 for other operations
   */
  void record(String name, String detail, int status, boolean failed, long nanos, long bytesIn, long bytesOut,
              Instant end, String thread) {
    Name e = names.computeIfAbsent(name, k -> new Name());
    synchronized (e) {
      e.count++;
      e.totalNanos += nanos;
      e.maxNanos = Math.max(e.maxNanos, nanos);
      if (failed)
        e.errors++;
      e.bytesIn += bytesIn;
      e.bytesOut += bytesOut;
      e.durations.add(nanos);
    }

    if (slowestKept > 0 && nanos > slowThreshold) {
      synchronized (slowest) {
        slowest.add(new Slow(end, name, detail, status, failed, nanos, thread));
        if (slowest.size() > slowestKept)
          slowest.poll();
        if (slowest.size() == slowestKept)
          slowThreshold = slowest.peek().nanos();
      }
    }
  }

  /**
   * The interval as one line of the history: times in ISO-8601, durations
   * in milliseconds, operations by total time.
   */
  Map<String, Object> toJson(Instant end) {
    List<Map.Entry<String, Name>> sorted = new ArrayList<>(names.entrySet());
    sorted.sort(Comparator.comparingLong((Map.Entry<String, Name> x) -> x.getValue().totalNanos).reversed());

    List<Object> rows = new ArrayList<>();
    for (Map.Entry<String, Name> x : sorted) {
      Name e = x.getValue();
      synchronized (e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", x.getKey());
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
      m.put("operation", s.name());
      m.put("detail", s.detail());
      m.put("status", s.status());
      m.put("failed", s.failed());
      m.put("duration", ms(s.nanos()));
      m.put("thread", s.thread());
      slowRows.add(m);
    }

    Map<String, Object> line = new LinkedHashMap<>();
    line.put("start", start);
    line.put("end", end);
    line.put("operations", rows);
    line.put("slowest", slowRows);
    return line;
  }

  static double ms(long nanos) {
    return Math.round(nanos / 1e4) / 100.0;
  }
}
