package jfrweb.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Statistics of the requests of an endpoint (method and route).
 *
 * @param totalNanos sum of the durations
 * @param errors     number of 5xx responses
 */
public record EndpointStats(String endpoint,
                            int count,
                            long totalNanos,
                            long p50Nanos,
                            long p99Nanos,
                            long maxNanos,
                            int errors,
                            long bytesIn,
                            long bytesOut) {

  /**
   * Groups requests by endpoint, sorted by total time, the biggest first.
   */
  public static List<EndpointStats> of(Collection<RecordedRequest> requests) {
    Map<String, List<RecordedRequest>> byEndpoint =
      requests.stream().collect(Collectors.groupingBy(RecordedRequest::endpoint,
                                                      LinkedHashMap::new,
                                                      Collectors.toList()));
    List<EndpointStats> res = new ArrayList<>();
    byEndpoint.forEach((endpoint, list) -> res.add(of(endpoint, list)));
    res.sort(Comparator.comparingLong(EndpointStats::totalNanos).reversed());
    return res;
  }

  private static EndpointStats of(String endpoint, List<RecordedRequest> list) {
    long[] durations = list.stream().mapToLong(RecordedRequest::durationNanos).sorted().toArray();
    long total = 0;
    for (long d : durations)
      total += d;
    return new EndpointStats(endpoint,
                             durations.length,
                             total,
                             percentile(durations, 50),
                             percentile(durations, 99),
                             durations[durations.length - 1],
                             (int) list.stream().filter(RecordedRequest::isError).count(),
                             list.stream().mapToLong(RecordedRequest::bytesIn).sum(),
                             list.stream().mapToLong(RecordedRequest::bytesOut).sum());
  }

  /**
   * Nearest-rank percentile of sorted values.
   */
  static long percentile(long[] sorted, int p) {
    int rank = (int) Math.ceil(p / 100.0 * sorted.length);
    return sorted[Math.max(0, rank - 1)];
  }
}
