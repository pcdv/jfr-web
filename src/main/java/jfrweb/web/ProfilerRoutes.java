package jfrweb.web;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import flak.HttpException;
import flak.Query;
import flak.Response;
import flak.annotations.OutputFormat;
import flak.annotations.Post;
import flak.annotations.Route;
import jfrweb.analysis.Analysis;
import jfrweb.analysis.EndpointStats;
import jfrweb.analysis.FlameGraphs;
import jfrweb.analysis.RecordingInfo;
import jfrweb.analysis.SamplingSummary;
import jfrweb.live.LiveStats;
import jfrweb.session.Session;
import jfrweb.session.Sessions;

import static jfrweb.analysis.RecordingInfo.EXECUTION_SAMPLE;
import static jfrweb.analysis.RecordingInfo.NATIVE_SAMPLE;
import static jfrweb.analysis.RecordingInfo.REQUEST;

/**
 * The REST API behind the web interface. Durations are in milliseconds, times
 * in ISO-8601.
 */
public class ProfilerRoutes {

  /** Name of the output formatter writing {@link Json}. */
  public static final String JSON = "JSON";

  private final Sessions sessions;
  private final LiveStats live;

  public ProfilerRoutes(Sessions sessions, LiveStats live) {
    this.sessions = sessions;
    this.live = live;
  }

  /**
   * The endpoints of the apps observed, over the last minutes.
   * <p>
   * Query: {@code window}, a number of minutes among {@link LiveStats#WINDOWS}.
   */
  @Route("/api/live")
  @OutputFormat(JSON)
  public Map<String, Object> live(Query q) {
    int window;
    try {
      window = Integer.parseInt(q.get("window", "5"));
    }
    catch (NumberFormatException e) {
      throw new HttpException(400, "Not a number of minutes: " + q.get("window"));
    }
    if (!LiveStats.WINDOWS.contains(window))
      throw new HttpException(400, "Window not among " + LiveStats.WINDOWS + ": " + window);

    List<Object> endpoints = new ArrayList<>();
    for (EndpointStats e : live.getEndpoints(window))
      endpoints.add(endpointRow(e));

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("observing", live.isObserving());
    res.put("apps", live.getAppPaths());
    res.put("window", window);
    res.put("windows", LiveStats.WINDOWS);
    res.put("endpoints", endpoints);
    return res;
  }

  @Route("/api/sessions")
  @OutputFormat(JSON)
  public List<Object> list() {
    List<Object> res = new ArrayList<>();
    for (Session s : sessions.list())
      res.add(session(s));
    return res;
  }

  @Route("/api/sessions")
  @Post
  @OutputFormat(JSON)
  public Map<String, Object> start() throws IOException {
    try {
      return session(sessions.start("manual"));
    }
    catch (IllegalStateException e) {
      throw new HttpException(409, e.getMessage());
    }
  }

  @Route("/api/sessions/:id")
  @OutputFormat(JSON)
  public Map<String, Object> get(int id) {
    return session(find(id));
  }

  @Route("/api/sessions/:id/stop")
  @Post
  @OutputFormat(JSON)
  public Map<String, Object> stop(int id) {
    find(id);
    return session(sessions.stop(id));
  }

  @Route("/api/sessions/:id/recording.jfr")
  public InputStream download(int id, Response resp) throws IOException {
    Session s = stopped(id);
    resp.addHeader("Content-Type", "application/octet-stream");
    resp.addHeader("Content-Disposition",
                   "attachment; filename=\"" + s.getFile().getFileName() + "\"");
    return Files.newInputStream(s.getFile());
  }

  @Route("/api/sessions/:id/report")
  @OutputFormat(JSON)
  public Map<String, Object> report(int id) throws IOException {
    Session s = stopped(id);
    Analysis a = s.getAnalysis();
    RecordingInfo info = a.getInfo();
    SamplingSummary summary = SamplingSummary.of(a);

    Map<String, Object> recording = new LinkedHashMap<>();
    recording.put("start", info.getStart());
    recording.put("end", info.getEnd());
    recording.put("jvm", info.getJvmVersion());
    recording.put("jvmArguments", info.getJvmArguments());
    recording.put("javaArguments", info.getJavaArguments());
    recording.put("os", info.getOs());
    recording.put("enabledEvents", info.getEnabledEvents().size());

    Map<String, Object> sampling = new LinkedHashMap<>();
    sampling.put("java", sampling(info, EXECUTION_SAMPLE, summary.getJavaSamples(), summary.getJavaInRequests()));
    sampling.put("native", sampling(info, NATIVE_SAMPLE, summary.getNativeSamples(), summary.getNativeInRequests()));
    sampling.put("requestShareOfNative", summary.getRequestShareOfNative());
    sampling.put("requestEvent", info.isEnabled(REQUEST));
    sampling.put("requests", a.getRequests().size());

    List<Object> threads = new ArrayList<>();
    summary.getSamplesByThread().forEach((name, count) -> {
      Map<String, Object> t = new LinkedHashMap<>();
      t.put("name", name);
      t.put("samples", count);
      threads.add(t);
    });

    List<Object> endpoints = new ArrayList<>();
    for (EndpointStats e : EndpointStats.of(a.getRequests()))
      endpoints.add(endpointRow(e));

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("session", session(s));
    res.put("recording", recording);
    res.put("sampling", sampling);
    res.put("biases", RecordingInfo.KNOWN_BIASES);
    res.put("threads", threads);
    res.put("endpoints", endpoints);
    return res;
  }

  /**
   * A flame graph, as a self-contained HTML page.
   * <p>
   * Query: {@code from} and {@code to}, in seconds since the start of the
   * recording; {@code thread}, the name of a thread; {@code endpoint}, e.g.
   * "GET /api/user/:id"; {@code all=true} to add the samples taken outside
   * requests.
   */
  @Route("/api/sessions/:id/flame")
  public Response flame(int id, Query q, Response resp) throws IOException {
    Analysis a = stopped(id).getAnalysis();
    Instant start = a.getInfo().getStart();

    FlameGraphs.Options o = new FlameGraphs.Options()
      .interval(offset(start, q.get("from")), offset(start, q.get("to")))
      .outsideRequests(q.getBool("all", false));
    StringBuilder title = new StringBuilder("Wall clock");
    String thread = q.get("thread");
    if (thread != null && !thread.isEmpty()) {
      o.thread(Pattern.compile(Pattern.quote(thread)));
      title.append(", thread ").append(thread);
    }
    String endpoint = q.get("endpoint");
    if (endpoint != null && !endpoint.isEmpty()) {
      o.requests(r -> r.endpoint().equals(endpoint));
      title.append(", ").append(endpoint);
    }
    o.title(title.append(" (ms)").toString());

    resp.addHeader("Content-Type", "text/html; charset=utf-8");
    PrintStream out = new PrintStream(new BufferedOutputStream(resp.getOutputStream()),
                                      false, StandardCharsets.UTF_8);
    FlameGraphs.write(a, o, out);
    out.flush();
    return resp;
  }

  private Session find(int id) {
    Session s = sessions.get(id);
    if (s == null)
      throw new HttpException(404, "No session " + id);
    return s;
  }

  private Session stopped(int id) {
    Session s = find(id);
    if (s.getState() != Session.State.STOPPED)
      throw new HttpException(409, "Session " + id + " is still recording");
    return s;
  }

  private static Map<String, Object> session(Session s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", s.getId());
    m.put("state", s.getState());
    m.put("trigger", s.getTrigger());
    m.put("start", s.getStart());
    m.put("end", s.getEnd());
    m.put("duration", s.getDuration().toMillis());
    m.put("size", s.getSize());
    return m;
  }

  private static Map<String, Object> endpointRow(EndpointStats e) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("endpoint", e.endpoint());
    m.put("count", e.count());
    m.put("total", ms(e.totalNanos()));
    m.put("p50", ms(e.p50Nanos()));
    m.put("p99", ms(e.p99Nanos()));
    m.put("max", ms(e.maxNanos()));
    m.put("errors", e.errors());
    m.put("bytesIn", e.bytesIn());
    m.put("bytesOut", e.bytesOut());
    return m;
  }

  private static Map<String, Object> sampling(RecordingInfo info, String event, long samples, long inRequests) {
    Map<String, Object> m = new LinkedHashMap<>();
    Duration period = info.getPeriod(event);
    Duration observed = info.getObservedInterval(event);
    m.put("period", period == null ? null : ms(period.toNanos()));
    m.put("observed", observed == null ? null : ms(observed.toNanos()));
    m.put("samples", samples);
    m.put("inRequests", inRequests);
    return m;
  }

  private static Instant offset(Instant start, String seconds) {
    if (seconds == null || seconds.isEmpty() || start == null)
      return null;
    try {
      return start.plusNanos((long) (Double.parseDouble(seconds) * 1e9));
    }
    catch (NumberFormatException e) {
      throw new HttpException(400, "Not a number of seconds: " + seconds);
    }
  }

  private static double ms(long nanos) {
    return Math.round(nanos / 1e4) / 100.0;
  }
}
