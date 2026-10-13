package jfrweb.live;

import jfrweb.analysis.EndpointStats;

/**
 * The requests of one endpoint, counted per minute over the last hour.
 */
final class EndpointCounter {

  static final int MINUTES = 60;

  private final String endpoint;

  /** Indexed by minute modulo {@link #MINUTES}; null until a request comes. */
  private final Minute[] minutes = new Minute[MINUTES];

  EndpointCounter(String endpoint) {
    this.endpoint = endpoint;
  }

  private static final class Minute {
    long minute;
    int count;
    long totalNanos;
    long maxNanos;
    int errors;
    long bytesIn;
    long bytesOut;
    final Histogram durations = new Histogram();

    Minute(long minute) {
      this.minute = minute;
    }
  }

  synchronized void record(long minute, long nanos, int status, long bytesIn, long bytesOut) {
    int i = (int) (minute % MINUTES);
    Minute m = minutes[i];
    if (m == null || m.minute != minute)
      m = minutes[i] = new Minute(minute);
    m.count++;
    m.totalNanos += nanos;
    m.maxNanos = Math.max(m.maxNanos, nanos);
    if (status >= 500)
      m.errors++;
    m.bytesIn += bytesIn;
    m.bytesOut += bytesOut;
    m.durations.add(nanos);
  }

  /**
   * The requests of the last minutes, the current one included.
   *
   * @return the statistics, or null if there was no request
   */
  synchronized EndpointStats over(long now, int window) {
    int count = 0;
    long total = 0;
    long max = 0;
    int errors = 0;
    long in = 0;
    long out = 0;
    Histogram durations = new Histogram();
    for (Minute m : minutes) {
      if (m == null || m.minute <= now - window || m.minute > now)
        continue;
      count += m.count;
      total += m.totalNanos;
      max = Math.max(max, m.maxNanos);
      errors += m.errors;
      in += m.bytesIn;
      out += m.bytesOut;
      durations.addAll(m.durations);
    }
    if (count == 0)
      return null;
    // a percentile cannot exceed the maximum, which is exact
    return new EndpointStats(endpoint, count, total,
                             Math.min(durations.percentile(50), max),
                             Math.min(durations.percentile(99), max),
                             max, errors, in, out);
  }
}
