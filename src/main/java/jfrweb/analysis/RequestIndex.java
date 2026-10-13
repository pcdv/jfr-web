package jfrweb.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Finds the request a thread was serving at a given time. A thread serves one
 * request at a time, so the requests of a thread do not overlap.
 */
final class RequestIndex {

  private final Map<Long, TreeMap<Long, RecordedRequest>> byThread = new HashMap<>();

  /**
   * When at least one request was running, whatever its thread: sorted
   * intervals that do not overlap, starts at even indexes, ends at odd ones.
   */
  private long[] running = {};

  void add(RecordedRequest r) {
    byThread.computeIfAbsent(r.threadId(), id -> new TreeMap<>()).put(r.startNanos(), r);
  }

  /**
   * Computes the intervals when requests were running, once they are all
   * added.
   */
  void seal() {
    List<RecordedRequest> all = new ArrayList<>();
    byThread.values().forEach(m -> all.addAll(m.values()));
    all.sort(Comparator.comparingLong(RecordedRequest::startNanos));

    LongList merged = new LongList();
    long start = 0;
    long end = Long.MIN_VALUE;
    for (RecordedRequest r : all) {
      if (r.startNanos() > end) {
        if (end != Long.MIN_VALUE) {
          merged.add(start);
          merged.add(end);
        }
        start = r.startNanos();
      }
      end = Math.max(end, r.endNanos());
    }
    if (end != Long.MIN_VALUE) {
      merged.add(start);
      merged.add(end);
    }
    running = merged.sorted();
  }

  boolean isAnyRunning(long timeNanos) {
    int i = Arrays.binarySearch(running, timeNanos);
    // found: a bound. Otherwise, inside an interval if after a start
    return i >= 0 || (-i - 1) % 2 == 1;
  }

  /**
   * @return the request, or null if the thread was serving none
   */
  RecordedRequest find(long threadId, long timeNanos) {
    TreeMap<Long, RecordedRequest> requests = byThread.get(threadId);
    if (requests == null)
      return null;
    Map.Entry<Long, RecordedRequest> e = requests.floorEntry(timeNanos);
    return e != null && timeNanos <= e.getValue().endNanos() ? e.getValue() : null;
  }
}
