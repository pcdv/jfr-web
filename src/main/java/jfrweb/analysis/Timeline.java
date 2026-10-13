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
 * The activity of a recording over time, in buckets: the operations in
 * flight, by name, and the samples of Java code.
 */
public final class Timeline {

  /** Operation names shown on their own, the others are added up. */
  public static final int MAX_SERIES = 8;

  /** At most this many buckets. */
  static final int MAX_BUCKETS = 300;

  /**
   * The bucket durations to choose from, in ms: from 10 ms, so that zooming in
   * on a second or two still shows more detail.
   */
  private static final long[] STEPS = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000,
                                       60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000};

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
    return of(analysis, null, null);
  }

  /**
   * The timeline of a part of the recording, in buckets as fine as its span
   * allows: to zoom in. The operations with a series of their own are those
   * of the whole recording, so that each keeps its color.
   *
   * @param from the start of the part, or null from the start of the recording
   * @param to   the end of the part, or null to the end of the recording
   */
  public static Timeline of(Analysis analysis, Instant from, Instant to) throws IOException {
    RecordingInfo info = analysis.getInfo();
    Instant start = info.getStart();
    Instant end = info.getEnd();
    if (start != null && from != null && from.isAfter(start))
      start = from;
    if (end != null && to != null && to.isBefore(end))
      end = to;
    long span = start == null || end.isBefore(start) ? 0 : Duration.between(start, end).toNanos();
    long bucketNanos = bucketFor(span);
    int buckets = (int) Math.max(1, (span + bucketNanos - 1) / bucketNanos);
    Timeline t = new Timeline(start != null ? start : Instant.EPOCH, Duration.ofNanos(bucketNanos), buckets);
    long origin = Analysis.nanos(t.start);

    // the operations with the most time get a series of their own
    Map<String, Long> totals = new HashMap<>();
    for (RecordedOperation op : analysis.getOperations())
      totals.merge(op.name(), op.durationNanos(), Long::sum);
    List<String> top = totals.entrySet().stream()
                             .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                             .limit(MAX_SERIES)
                             .map(Map.Entry::getKey)
                             .collect(Collectors.toList());
    for (String e : top)
      t.series.put(e, new double[buckets]);
    if (totals.size() > MAX_SERIES)
      t.other = new double[buckets];

    for (RecordedOperation op : analysis.getOperations()) {
      double[] values = t.series.getOrDefault(op.name(), t.other);
      long begin = Math.max(op.startNanos() - origin, 0);
      long finish = Math.min(op.endNanos() - origin, (long) buckets * bucketNanos);
      for (long b = begin / bucketNanos; b * bucketNanos < finish && b < buckets; b++) {
        long overlap = Math.min(finish, (b + 1) * bucketNanos) - Math.max(begin, b * bucketNanos);
        // an operation in flight for the whole bucket counts 1
        values[(int) b] += (double) overlap / bucketNanos;
      }
    }

    analysis.visitSamples((sample, thread, operation, weight) -> {
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
   * The operations in flight in each bucket, on average, by name: those
   * with the most total time first.
   */
  public Map<String, double[]> getSeries() {
    return series;
  }

  /**
   * The operations of the other names, or null if all have a series.
   */
  public double[] getOther() {
    return other;
  }

  /**
   * The number of samples of Java code in each bucket: the activity of an
   * application that records no operation.
   */
  public long[] getJavaSamples() {
    return javaSamples;
  }
}
