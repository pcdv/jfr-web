package jfrweb.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Finds the operation a thread worked for at a given time. A thread works for
 * one operation at a time: the whole of a synchronous operation it runs, or a
 * segment of an asynchronous one. When operations nest, e.g. a test inside
 * its test class, it works for the innermost one.
 */
public final class OperationIndex {

  /**
   * A thread working for an operation.
   *
   * @param startNanos in nanoseconds since the epoch
   */
  public record Segment(long threadId, String threadName, long startNanos, long endNanos,
                        RecordedOperation operation) {}

  private final Map<Long, TreeMap<Long, Segment>> byThread = new HashMap<>();
  private final Map<RecordedOperation, List<Segment>> byOperation = new IdentityHashMap<>();

  /**
   * The segment of the same thread a segment started in, still running when
   * it started: the one the thread is back to once it ends.
   */
  private final Map<Segment, Segment> enclosing = new IdentityHashMap<>();

  /**
   * When at least one thread worked for an operation: sorted intervals that
   * do not overlap, starts at even indexes, ends at odd ones.
   */
  private long[] running = {};

  void add(Segment s) {
    byThread.computeIfAbsent(s.threadId(), id -> new TreeMap<>()).put(s.startNanos(), s);
    byOperation.computeIfAbsent(s.operation(), o -> new ArrayList<>()).add(s);
  }

  /**
   * Computes the intervals when operations were running, once the segments
   * are all added.
   */
  void seal() {
    List<Segment> all = new ArrayList<>();
    byThread.values().forEach(m -> all.addAll(m.values()));
    all.sort(Comparator.comparingLong(Segment::startNanos));

    LongList merged = new LongList();
    long start = 0;
    long end = Long.MIN_VALUE;
    for (Segment s : all) {
      if (s.startNanos() > end) {
        if (end != Long.MIN_VALUE) {
          merged.add(start);
          merged.add(end);
        }
        start = s.startNanos();
      }
      end = Math.max(end, s.endNanos());
    }
    if (end != Long.MIN_VALUE) {
      merged.add(start);
      merged.add(end);
    }
    running = merged.sorted();

    for (TreeMap<Long, Segment> segments : byThread.values()) {
      ArrayDeque<Segment> open = new ArrayDeque<>();
      for (Segment s : segments.values()) {
        while (!open.isEmpty() && open.peek().endNanos() < s.startNanos())
          open.pop();
        if (!open.isEmpty())
          enclosing.put(s, open.peek());
        open.push(s);
      }
    }
    byOperation.values().forEach(l -> l.sort(Comparator.comparingLong(Segment::startNanos)));
  }

  boolean isAnyRunning(long timeNanos) {
    int i = Arrays.binarySearch(running, timeNanos);
    // found: a bound. Otherwise, inside an interval if after a start
    return i >= 0 || (-i - 1) % 2 == 1;
  }

  /**
   * @return the operation, or null if the thread was working for none
   */
  RecordedOperation find(long threadId, long timeNanos) {
    TreeMap<Long, Segment> segments = byThread.get(threadId);
    if (segments == null)
      return null;
    Map.Entry<Long, Segment> e = segments.floorEntry(timeNanos);
    // the latest started, or once over, one it started in
    Segment s = e == null ? null : e.getValue();
    while (s != null && timeNanos > s.endNanos())
      s = enclosing.get(s);
    return s == null ? null : s.operation();
  }

  /**
   * The segments of an operation, the earliest first.
   */
  List<Segment> segmentsOf(RecordedOperation op) {
    return byOperation.getOrDefault(op, List.of());
  }
}
