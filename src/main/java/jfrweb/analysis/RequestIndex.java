package jfrweb.analysis;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Finds the request a thread was serving at a given time. A thread serves one
 * request at a time, so the requests of a thread do not overlap.
 */
final class RequestIndex {

  private final Map<Long, TreeMap<Long, RecordedRequest>> byThread = new HashMap<>();

  void add(RecordedRequest r) {
    byThread.computeIfAbsent(r.threadId(), id -> new TreeMap<>()).put(r.startNanos(), r);
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
