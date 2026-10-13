package jfrweb.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Statistics of the operations of a name, e.g. the requests of a route.
 *
 * @param totalNanos sum of the durations
 * @param errors     number of operations that failed: 5xx responses for
 *                   requests
 */
public record OperationStats(String name,
                             int count,
                             long totalNanos,
                             long p50Nanos,
                             long p99Nanos,
                             long maxNanos,
                             int errors,
                             long bytesIn,
                             long bytesOut) {

  /**
   * Groups operations by name, sorted by total time, the biggest first.
   */
  public static List<OperationStats> of(Collection<RecordedOperation> operations) {
    Map<String, List<RecordedOperation>> byName =
      operations.stream().collect(Collectors.groupingBy(RecordedOperation::name,
                                                        LinkedHashMap::new,
                                                        Collectors.toList()));
    List<OperationStats> res = new ArrayList<>();
    byName.forEach((name, list) -> res.add(of(name, list)));
    res.sort(Comparator.comparingLong(OperationStats::totalNanos).reversed());
    return res;
  }

  private static OperationStats of(String name, List<RecordedOperation> list) {
    long[] durations = list.stream().mapToLong(RecordedOperation::durationNanos).sorted().toArray();
    long total = 0;
    for (long d : durations)
      total += d;
    return new OperationStats(name,
                              durations.length,
                              total,
                              percentile(durations, 50),
                              percentile(durations, 99),
                              durations[durations.length - 1],
                              (int) list.stream().filter(RecordedOperation::failed).count(),
                              list.stream().mapToLong(RecordedOperation::bytesIn).sum(),
                              list.stream().mapToLong(RecordedOperation::bytesOut).sum());
  }

  /**
   * Nearest-rank percentile of sorted values.
   */
  static long percentile(long[] sorted, int p) {
    int rank = (int) Math.ceil(p / 100.0 * sorted.length);
    return sorted[Math.max(0, rank - 1)];
  }
}
