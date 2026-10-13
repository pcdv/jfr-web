package jfrweb.history;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import jfrweb.analysis.EndpointStats;
import jfrweb.json.Json;
import jfrweb.stats.Histogram;

/**
 * Reads a period of a history written by {@link EndpointHistory}, and adds up
 * its intervals.
 */
public final class HistoryReader {

  /** Endpoints shown on their own in a timeline, the others added up. */
  public static final int MAX_SERIES = 8;

  /** At most this many buckets in a timeline. */
  static final int MAX_BUCKETS = 300;

  /** The bucket durations a timeline chooses from, in seconds. */
  private static final long[] STEPS = {1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600,
                                       7200, 10800, 21600, 43200, 86400};

  /**
   * A request among the slowest of an interval.
   *
   * @param end when it ended
   */
  public record Slow(Instant end, String endpoint, String path, int status, long nanos, String thread) {}

  private record Row(String endpoint, int count, long totalNanos, long maxNanos, int errors,
                     long bytesIn, long bytesOut, Histogram durations) {}

  private record Interval(Instant start, Instant end, List<Row> rows, List<Slow> slowest) {}

  private final Instant from;
  private final Instant to;
  private final List<Interval> intervals = new ArrayList<>();

  private HistoryReader(Instant from, Instant to) {
    this.from = from;
    this.to = to;
  }

  /**
   * Reads the intervals of a history that overlap a period.
   */
  public static HistoryReader read(Path directory, Instant from, Instant to) throws IOException {
    HistoryReader r = new HistoryReader(from, to);
    // an interval is in the file of the hour it starts in, which may be the
    // hour before the period
    Instant firstHour = from.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
    for (Path f : HistoryFiles.list(directory)) {
      Instant hour = HistoryFiles.hourOf(f);
      if (!hour.isBefore(firstHour) && hour.isBefore(to))
        r.readFile(f);
    }
    r.intervals.sort(Comparator.comparing(Interval::start));
    return r;
  }

  private void readFile(Path f) throws IOException {
    try (InputStream raw = Files.newInputStream(f);
         InputStream in = f.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
         BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      for (String line; (line = lines.readLine()) != null; ) {
        Interval i;
        try {
          i = parse(line);
        }
        catch (RuntimeException e) {
          // e.g. the line being written
          continue;
        }
        if (i.start().isBefore(to) && i.end().isAfter(from))
          intervals.add(i);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Interval parse(String line) {
    Map<String, Object> m = (Map<String, Object>) Json.parse(line);
    List<Row> rows = new ArrayList<>();
    for (Object o : (List<Object>) m.get("endpoints")) {
      Map<String, Object> e = (Map<String, Object>) o;
      rows.add(new Row((String) e.get("endpoint"),
                       ((Number) e.get("count")).intValue(),
                       nanos(e.get("total")),
                       nanos(e.get("max")),
                       ((Number) e.get("errors")).intValue(),
                       ((Number) e.get("bytesIn")).longValue(),
                       ((Number) e.get("bytesOut")).longValue(),
                       Histogram.fromSparse((List<List<Number>>) e.get("histogram"))));
    }
    List<Slow> slowest = new ArrayList<>();
    for (Object o : (List<Object>) m.get("slowest")) {
      Map<String, Object> s = (Map<String, Object>) o;
      slowest.add(new Slow(Instant.parse((String) s.get("end")),
                           (String) s.get("endpoint"),
                           (String) s.get("path"),
                           ((Number) s.get("status")).intValue(),
                           nanos(s.get("duration")),
                           (String) s.get("thread")));
    }
    return new Interval(Instant.parse((String) m.get("start")), Instant.parse((String) m.get("end")), rows, slowest);
  }

  private static long nanos(Object ms) {
    return Math.round(((Number) ms).doubleValue() * 1e6);
  }

  public Instant getFrom() {
    return from;
  }

  public Instant getTo() {
    return to;
  }

  /**
   * The number of intervals found: none means the history was not written
   * then, whereas an interval without requests was written all the same.
   */
  public int getIntervalCount() {
    return intervals.size();
  }

  /**
   * The endpoints of the period, sorted by total time, the biggest first.
   */
  public List<EndpointStats> getEndpoints() {
    Map<String, Row> sums = new LinkedHashMap<>();
    for (Interval i : intervals)
      for (Row r : i.rows())
        sums.merge(r.endpoint(), r, HistoryReader::add);
    List<EndpointStats> res = new ArrayList<>();
    for (Row r : sums.values())
      res.add(new EndpointStats(r.endpoint(), r.count(), r.totalNanos(),
                                Math.min(r.durations().percentile(50), r.maxNanos()),
                                Math.min(r.durations().percentile(99), r.maxNanos()),
                                r.maxNanos(), r.errors(), r.bytesIn(), r.bytesOut()));
    res.sort(Comparator.comparingLong(EndpointStats::totalNanos).reversed());
    return res;
  }

  private static Row add(Row a, Row b) {
    Histogram h = new Histogram();
    h.addAll(a.durations());
    h.addAll(b.durations());
    return new Row(a.endpoint(), a.count() + b.count(), a.totalNanos() + b.totalNanos(),
                   Math.max(a.maxNanos(), b.maxNanos()), a.errors() + b.errors(),
                   a.bytesIn() + b.bytesIn(), a.bytesOut() + b.bytesOut(), h);
  }

  /**
   * The slowest requests of the period, the slowest first, among those each
   * interval kept.
   *
   * @param endpoint keeps those of an endpoint, or all if null
   */
  public List<Slow> getSlowest(String endpoint, int limit) {
    return intervals.stream()
                    .flatMap(i -> i.slowest().stream())
                    .filter(s -> endpoint == null || s.endpoint().equals(endpoint))
                    .filter(s -> !s.end().isBefore(from) && s.end().isBefore(to))
                    .sorted(Comparator.comparingLong(Slow::nanos).reversed())
                    .limit(limit)
                    .collect(Collectors.toList());
  }

  /**
   * The number of requests of the period over time, in buckets, by endpoint:
   * the {@link #MAX_SERIES} with the most time on their own.
   */
  public CountTimeline getTimeline() {
    long span = Duration.between(from, to).getSeconds();
    // the longest interval: the first and the last ones of a run are partial
    long interval = Math.max(1, intervals.stream()
                                         .mapToLong(i -> Duration.between(i.start(), i.end()).getSeconds())
                                         .max().orElse(1));
    long bucket = STEPS[STEPS.length - 1];
    for (long s : STEPS)
      if (s >= interval && span <= s * MAX_BUCKETS) {
        bucket = s;
        break;
      }
    int buckets = (int) Math.max(1, (span + bucket - 1) / bucket);

    List<String> top = getEndpoints().stream().limit(MAX_SERIES).map(EndpointStats::endpoint)
                                     .collect(Collectors.toList());
    Map<String, double[]> series = new LinkedHashMap<>();
    for (String e : top)
      series.put(e, new double[buckets]);
    double[] other = getEndpoints().size() > MAX_SERIES ? new double[buckets] : null;
    boolean[] covered = new boolean[buckets];

    for (Interval i : intervals) {
      long b = Duration.between(from, i.start()).getSeconds() / bucket;
      if (b < 0 || b >= buckets)
        continue;
      covered[(int) b] = true;
      for (Row r : i.rows()) {
        double[] values = series.getOrDefault(r.endpoint(), other);
        values[(int) b] += r.count();
      }
    }
    return new CountTimeline(from, Duration.ofSeconds(bucket), buckets, series, other, covered);
  }

  /**
   * Requests per bucket, by endpoint.
   *
   * @param covered whether the history was written during each bucket
   */
  public record CountTimeline(Instant start, Duration bucket, int buckets, Map<String, double[]> series,
                              double[] other, boolean[] covered) {}

  /**
   * The intervals' starting times, for tests.
   */
  List<Instant> starts() {
    List<Instant> res = new ArrayList<>();
    for (Interval i : intervals)
      res.add(i.start());
    return res;
  }

  /**
   * Requests by endpoint over the period, for tests.
   */
  Map<String, Integer> counts() {
    Map<String, Integer> res = new HashMap<>();
    for (EndpointStats e : getEndpoints())
      res.put(e.endpoint(), e.count());
    return res;
  }
}
