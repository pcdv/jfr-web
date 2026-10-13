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
import jfrweb.analysis.EndpointStats;
import jfrweb.history.HistoryReader;

/**
 * The REST API browsing an endpoint history. Durations are in milliseconds,
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
   * A period of the history: its endpoints, its slowest requests, and its
   * requests over time.
   * <p>
   * Query: {@code from} and {@code to}, ISO-8601 times, the last hour by
   * default; {@code endpoint} to keep the slowest requests of one.
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

    List<Object> endpoints = new ArrayList<>();
    for (EndpointStats e : r.getEndpoints())
      endpoints.add(ProfilerRoutes.endpointRow(e));

    String endpoint = q.get("endpoint");
    List<Object> slowest = new ArrayList<>();
    for (HistoryReader.Slow s : r.getSlowest(endpoint == null || endpoint.isEmpty() ? null : endpoint, SLOWEST)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("end", s.end());
      m.put("endpoint", s.endpoint());
      m.put("path", s.path());
      m.put("status", s.status());
      m.put("duration", ProfilerRoutes.ms(s.nanos()));
      m.put("thread", s.thread());
      slowest.add(m);
    }

    HistoryReader.CountTimeline t = r.getTimeline();
    List<Object> series = new ArrayList<>();
    t.series().forEach((name, values) -> {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("endpoint", name);
      s.put("values", list(values));
      series.add(s);
    });
    List<Boolean> covered = new ArrayList<>();
    for (boolean c : t.covered())
      covered.add(c);
    Map<String, Object> timeline = new LinkedHashMap<>();
    timeline.put("start", t.start());
    timeline.put("bucket", t.bucket().toMillis());
    timeline.put("buckets", t.buckets());
    timeline.put("series", series);
    timeline.put("other", t.other() == null ? null : list(t.other()));
    timeline.put("covered", covered);

    res.put("directory", directory.toString());
    res.put("from", from);
    res.put("to", to);
    res.put("intervals", r.getIntervalCount());
    res.put("endpoints", endpoints);
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

  private static List<Double> list(double[] values) {
    List<Double> res = new ArrayList<>(values.length);
    for (double v : values)
      res.add(v);
    return res;
  }
}
