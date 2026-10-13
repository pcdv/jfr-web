package jfrweb.live;

/**
 * Durations counted in logarithmic buckets, a quarter of a power of two wide
 * (about 19%): enough to tell percentiles within about 10%, in a fixed amount
 * of memory whatever the number of requests.
 */
final class Histogram {

  /** From 1 µs to about 74 minutes. */
  static final int BUCKETS = 4 * 32;

  private final int[] counts = new int[BUCKETS];
  private long total;

  void add(long nanos) {
    counts[index(nanos)]++;
    total++;
  }

  void addAll(Histogram other) {
    for (int i = 0; i < BUCKETS; i++)
      counts[i] += other.counts[i];
    total += other.total;
  }

  /**
   * The duration under which a share of the values fall, as the upper bound of
   * its bucket, or 0 if there is none.
   *
   * @param p between 0 and 100
   */
  long percentile(double p) {
    if (total == 0)
      return 0;
    long rank = Math.max(1, (long) Math.ceil(p / 100 * total));
    long seen = 0;
    for (int i = 0; i < BUCKETS; i++) {
      seen += counts[i];
      if (seen >= rank)
        return upperBound(i);
    }
    return upperBound(BUCKETS - 1);
  }

  static int index(long nanos) {
    double micros = nanos / 1000.0;
    if (micros <= 1)
      return 0;
    int i = (int) Math.ceil(4 * Math.log(micros) / Math.log(2));
    return Math.min(i, BUCKETS - 1);
  }

  static long upperBound(int index) {
    return (long) (Math.pow(2, index / 4.0) * 1000);
  }
}
