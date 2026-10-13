package jfrweb.web;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import flak.HttpException;
import flak.Query;
import flak.annotations.OutputFormat;
import flak.annotations.Route;
import jfrweb.analysis.OperationStats;
import jfrweb.history.HistoryReader;

/**
 * The REST API browsing an operation history. Durations are in milliseconds,
 * times in ISO-8601.
 */
public class HistoryRoutes {

  private static final int SLOWEST = 20;

  /** Null when the profiler was given no history. */
  private final Path directory;

  public HistoryRoutes(Path directory) {
    this.directory = directory;
  }

  /**
   * A period of the history: its operations by name, its slowest operations,
   * and its operations over time.
   * <p>
   * Query: {@code from} and {@code to}, ISO-8601 times, the last hour by
   * default; {@code name} to keep the slowest operations of a name.
   */
  @Route("/api/history")
  @OutputFormat(ProfilerRoutes.JSON)
  public Map<String, Object> history(Query q) throws IOException {
    Map<String, Object> res = new LinkedHashMap<>();
    res.put("available", directory != null);
    if (directory == null)
      return res;

    Instant to = time(q.get("to"), Instant.now());
    Instant from = time(q.get("from"), to.minus(Duration.ofHours(1)));
    if (!from.isBefore(to))
      throw new HttpException(400, "from must be before to");
    HistoryReader r = HistoryReader.read(directory, from, to);

    List<Object> operations = new ArrayList<>();
    for (OperationStats o : r.getOperations())
      operations.add(ProfilerRoutes.operationRow(o));

    String name = q.get("name");
    List<Object> slowest = new ArrayList<>();
    for (HistoryReader.Slow s : r.getSlowest(name == null || name.isEmpty() ? null : name, SLOWEST)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("end", s.end());
      m.put("name", s.name());
      m.put("detail", s.detail());
      m.put("status", s.status());
      m.put("failed", s.failed());
      m.put("duration", ProfilerRoutes.ms(s.nanos()));
      m.put("thread", s.thread());
      slowest.add(m);
    }

    HistoryReader.CountTimeline t = r.getTimeline();
    List<Boolean> covered = new ArrayList<>();
    for (boolean c : t.covered())
      covered.add(c);
    Map<String, Object> timeline = new LinkedHashMap<>();
    timeline.put("start", t.start());
    timeline.put("bucket", t.bucket().toMillis());
    timeline.put("buckets", t.buckets());
    // operations per bucket
    timeline.put("series", series(t.series()));
    timeline.put("other", t.other() == null ? null : list(t.other()));
    // the seconds they took
    timeline.put("seriesSeconds", series(t.seconds()));
    timeline.put("otherSeconds", t.otherSeconds() == null ? null : list(t.otherSeconds()));
    timeline.put("covered", covered);

    res.put("directory", directory.toString());
    res.put("from", from);
    res.put("to", to);
    res.put("intervals", r.getIntervalCount());
    res.put("operations", operations);
    res.put("slowest", slowest);
    res.put("timeline", timeline);
    return res;
  }

  private static Instant time(String iso, Instant byDefault) {
    if (iso == null || iso.isEmpty())
      return byDefault;
    try {
      return Instant.parse(iso);
    }
    catch (DateTimeParseException e) {
      throw new HttpException(400, "Not an ISO-8601 time: " + iso);
    }
  }

  private static List<Object> series(Map<String, double[]> byName) {
    List<Object> res = new ArrayList<>();
    byName.forEach((name, values) -> {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("name", name);
      s.put("values", list(values));
      res.add(s);
    });
    return res;
  }

  private static List<Double> list(double[] values) {
    List<Double> res = new ArrayList<>(values.length);
    for (double v : values)
      // seconds to the millisecond: sums of doubles otherwise read 0.30000000000000004
      res.add(Math.round(v * 1000) / 1000.0);
    return res;
  }
}
