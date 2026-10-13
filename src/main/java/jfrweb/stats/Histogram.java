package jfrweb.stats;

import java.util.ArrayList;
import java.util.List;

/**
 * Durations counted in logarithmic buckets, a quarter of a power of two wide
 * (about 19%): enough to tell percentiles within about 10%, in a fixed amount
 * of memory whatever the number of requests. Histograms add up, so the
 * percentiles of several periods can be computed from theirs.
 */
public final class Histogram {

  /** From 1 µs to about 74 minutes. */
  public static final int BUCKETS = 4 * 32;

  private final int[] counts = new int[BUCKETS];
  private long total;

  public void add(long nanos) {
    counts[index(nanos)]++;
    total++;
  }

  public void addAll(Histogram other) {
    for (int i = 0; i < BUCKETS; i++)
      counts[i] += other.counts[i];
    total += other.total;
  }

  public long getTotal() {
    return total;
  }

  /**
   * The duration under which a share of the values fall, as the upper bound of
   * its bucket, or 0 if there is none.
   *
   * @param p between 0 and 100
   */
  public long percentile(double p) {
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

  /**
   * The buckets that are not empty, as pairs of index and count: a few
   * numbers instead of {@link #BUCKETS}, to write it.
   */
  public List<List<Integer>> toSparse() {
    List<List<Integer>> res = new ArrayList<>();
    for (int i = 0; i < BUCKETS; i++)
      if (counts[i] > 0)
        res.add(List.of(i, counts[i]));
    return res;
  }

  /**
   * Reads back what {@link #toSparse()} wrote. Unknown buckets are ignored.
   */
  public static Histogram fromSparse(List<? extends List<? extends Number>> pairs) {
    Histogram h = new Histogram();
    for (List<? extends Number> p : pairs) {
      int i = p.get(0).intValue();
      int n = p.get(1).intValue();
      if (i >= 0 && i < BUCKETS && n > 0) {
        h.counts[i] += n;
        h.total += n;
      }
    }
    return h;
  }

  public static int index(long nanos) {
    double micros = nanos / 1000.0;
    if (micros <= 1)
      return 0;
    int i = (int) Math.ceil(4 * Math.log(micros) / Math.log(2));
    return Math.min(i, BUCKETS - 1);
  }

  public static long upperBound(int index) {
    return (long) (Math.pow(2, index / 4.0) * 1000);
  }
}
