package jfrweb.analysis;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static jfrweb.analysis.RecordingInfo.EXECUTION_SAMPLE;

/**
 * The activity of a recording over time, in buckets: the requests in flight,
 * by endpoint, and the samples of Java code.
 */
public final class Timeline {

  /** Endpoints shown on their own, the others are added up. */
  public static final int MAX_SERIES = 8;

  /** At most this many buckets. */
  static final int MAX_BUCKETS = 300;

  /** The bucket durations to choose from, in ms. */
  private static final long[] STEPS = {100, 200, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000,
                                       120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000};

  private final Instant start;
  private final Duration bucket;
  private final int buckets;
  private final Map<String, double[]> series = new LinkedHashMap<>();
  private double[] other;
  private final long[] javaSamples;

  private Timeline(Instant start, Duration bucket, int buckets) {
    this.start = start;
    this.bucket = bucket;
    this.buckets = buckets;
    this.javaSamples = new long[buckets];
  }

  public static Timeline of(Analysis analysis) throws IOException {
    RecordingInfo info = analysis.getInfo();
    Instant start = info.getStart();
    long span = start == null ? 0 : Duration.between(start, info.getEnd()).toNanos();
    long bucketNanos = bucketFor(span);
    int buckets = (int) Math.max(1, (span + bucketNanos - 1) / bucketNanos);
    Timeline t = new Timeline(start != null ? start : Instant.EPOCH, Duration.ofNanos(bucketNanos), buckets);
    long origin = Analysis.nanos(t.start);

    // the endpoints with the most time get a series of their own
    Map<String, Long> totals = new HashMap<>();
    for (RecordedRequest r : analysis.getRequests())
      totals.merge(r.endpoint(), r.durationNanos(), Long::sum);
    List<String> top = totals.entrySet().stream()
                             .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                             .limit(MAX_SERIES)
                             .map(Map.Entry::getKey)
                             .collect(Collectors.toList());
    for (String e : top)
      t.series.put(e, new double[buckets]);
    if (totals.size() > MAX_SERIES)
      t.other = new double[buckets];

    for (RecordedRequest r : analysis.getRequests()) {
      double[] values = t.series.getOrDefault(r.endpoint(), t.other);
      long from = Math.max(r.startNanos() - origin, 0);
      long to = Math.min(r.endNanos() - origin, (long) buckets * bucketNanos);
      for (long b = from / bucketNanos; b * bucketNanos < to && b < buckets; b++) {
        long overlap = Math.min(to, (b + 1) * bucketNanos) - Math.max(from, b * bucketNanos);
        // a request in flight for the whole bucket counts 1
        values[(int) b] += (double) overlap / bucketNanos;
      }
    }

    analysis.visitSamples((sample, thread, request, weight) -> {
      if (!sample.getEventType().getName().equals(EXECUTION_SAMPLE))
        return;
      long b = (Analysis.nanos(sample.getStartTime()) - origin) / bucketNanos;
      if (b >= 0 && b < buckets)
        t.javaSamples[(int) b]++;
    });
    return t;
  }

  /**
   * The shortest round bucket duration that keeps at most {@link #MAX_BUCKETS}.
   */
  static long bucketFor(long spanNanos) {
    for (long ms : STEPS)
      if (spanNanos <= ms * 1_000_000 * MAX_BUCKETS)
        return ms * 1_000_000;
    return STEPS[STEPS.length - 1] * 1_000_000;
  }

  public Instant getStart() {
    return start;
  }

  public Duration getBucket() {
    return bucket;
  }

  public int getBuckets() {
    return buckets;
  }

  /**
   * The requests in flight in each bucket, on average, by endpoint: the
   * endpoints with the most total time first.
   */
  public Map<String, double[]> getSeries() {
    return series;
  }

  /**
   * The requests of the other endpoints, or null if all have a series.
   */
  public double[] getOther() {
    return other;
  }

  /**
   * The number of samples of Java code in each bucket: the activity of an
   * application that serves no request.
   */
  public long[] getJavaSamples() {
    return javaSamples;
  }
}
