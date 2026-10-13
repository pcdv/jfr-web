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
import flak.Request;
import flak.Response;
import flak.annotations.Delete;
import flak.annotations.OutputFormat;
import flak.annotations.Post;
import flak.annotations.Route;
import jfrweb.analysis.Analysis;
import jfrweb.analysis.EndpointStats;
import jfrweb.analysis.FlameGraphs;
import jfrweb.analysis.RecordingInfo;
import jfrweb.analysis.RecordedRequest;
import jfrweb.analysis.RequestDetails;
import jfrweb.analysis.SamplingSummary;
import jfrweb.analysis.Timeline;
import jfrweb.auto.AutoMode;
import jfrweb.auto.Trigger;
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
  private final AutoMode auto;

  public ProfilerRoutes(Sessions sessions, LiveStats live, AutoMode auto) {
    this.sessions = sessions;
    this.live = live;
    this.auto = auto;
  }

  /**
   * The state of the auto mode, as seen from the browser asking.
   */
  @Route("/api/auto")
  @OutputFormat(JSON)
  public Map<String, Object> autoState(Request req) {
    return autoState(auto.getTrigger(), req.getCookie(AutoMode.COOKIE));
  }

  /**
   * Arms the auto mode.
   * <p>
   * Query: {@code patterns}, endpoints separated by commas or new lines, *
   * matching anything, none for any endpoint; {@code browser=true} to only
   * count the requests of the browser arming, which gets a cookie;
   * {@code repeat=true} to start a session each time, not once; {@code idle}
   * and {@code max}, in seconds.
   */
  @Route("/api/auto")
  @Post
  @OutputFormat(JSON)
  public Map<String, Object> arm(Query q, Response resp) {
    if (!live.isObserving())
      throw new HttpException(409, "No app is observed: nothing can trigger a session");

    List<String> patterns = new ArrayList<>();
    for (String p : q.get("patterns", "").split("[,\\n]"))
      if (!p.isBlank())
        patterns.add(p.trim());
    String browser = q.getBool("browser", false) ? AutoMode.newBrowserToken() : null;
    Trigger t = new Trigger(patterns, browser, q.getBool("repeat", false),
                            seconds(q, "idle", Trigger.DEFAULT_IDLE),
                            seconds(q, "max", Trigger.DEFAULT_MAX));
    auto.arm(t);
    if (browser != null) {
      // to every path of the host: the apps observed may be anywhere on it
      resp.addHeader("Set-Cookie", AutoMode.COOKIE + "=" + browser + "; Path=/; SameSite=Lax; HttpOnly");
    }
    return autoState(t, browser);
  }

  @Route("/api/auto")
  @Delete
  @OutputFormat(JSON)
  public Map<String, Object> disarm(Response resp) {
    auto.disarm();
    resp.addHeader("Set-Cookie", AutoMode.COOKIE + "=; Path=/; Max-Age=0; SameSite=Lax; HttpOnly");
    return autoState(null, null);
  }

  private Map<String, Object> autoState(Trigger t, String cookie) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("observing", live.isObserving());
    m.put("armed", t != null);
    if (t != null) {
      m.put("patterns", t.patterns());
      m.put("browser", t.browser() != null);
      m.put("thisBrowser", t.browser() != null && t.browser().equals(cookie));
      m.put("repeat", t.repeat());
      m.put("idle", t.idle().toMillis() / 1000.0);
      m.put("max", t.max().toMillis() / 1000.0);
    }
    Session s = auto.getSession();
    m.put("recording", s == null ? null : s.getId());
    return m;
  }

  private static Duration seconds(Query q, String name, Duration byDefault) {
    String v = q.get(name);
    if (v == null || v.isBlank())
      return byDefault;
    try {
      double s = Double.parseDouble(v);
      if (s <= 0)
        throw new NumberFormatException();
      return Duration.ofMillis((long) (s * 1000));
    }
    catch (NumberFormatException e) {
      throw new HttpException(400, "Not a positive number of seconds for " + name + ": " + v);
    }
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
   * The activity of a session over time: requests in flight by endpoint, and
   * samples of Java code, in buckets of a round duration.
   */
  @Route("/api/sessions/:id/timeline")
  @OutputFormat(JSON)
  public Map<String, Object> timeline(int id) throws IOException {
    Timeline t = Timeline.of(stopped(id).getAnalysis());

    List<Object> series = new ArrayList<>();
    t.getSeries().forEach((endpoint, values) -> {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("endpoint", endpoint);
      s.put("values", rounded(values));
      series.add(s);
    });
    List<Long> java = new ArrayList<>();
    for (long n : t.getJavaSamples())
      java.add(n);

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("start", t.getStart());
    res.put("bucket", t.getBucket().toMillis());
    res.put("buckets", t.getBuckets());
    res.put("series", series);
    res.put("other", t.getOther() == null ? null : rounded(t.getOther()));
    res.put("javaSamples", java);
    return res;
  }

  private static List<Double> rounded(double[] values) {
    List<Double> res = new ArrayList<>(values.length);
    for (double v : values)
      res.add(Math.round(v * 1000) / 1000.0);
    return res;
  }

  /**
   * The slowest requests of a session, the slowest first. A request is known
   * by its index among those of the recording.
   * <p>
   * Query: {@code endpoint} to keep the requests of one; {@code limit}, 20 by
   * default.
   */
  @Route("/api/sessions/:id/requests")
  @OutputFormat(JSON)
  public List<Object> requests(int id, Query q) throws IOException {
    Analysis a = stopped(id).getAnalysis();
    String endpoint = q.get("endpoint");
    int limit = q.getInt("limit", 20);
    List<RecordedRequest> all = a.getRequests();

    List<Integer> indexes = new ArrayList<>();
    for (int i = 0; i < all.size(); i++)
      if (endpoint == null || endpoint.isEmpty() || all.get(i).endpoint().equals(endpoint))
        indexes.add(i);
    indexes.sort((i, j) -> Long.compare(all.get(j).durationNanos(), all.get(i).durationNanos()));

    List<Object> res = new ArrayList<>();
    for (int i : indexes.subList(0, Math.min(limit, indexes.size())))
      res.add(requestRow(a, i));
    return res;
  }

  /**
   * One request of a session: its exceptions, and its file and socket
   * accesses longer than the thresholds of the recording.
   */
  @Route("/api/sessions/:id/requests/:n")
  @OutputFormat(JSON)
  public Map<String, Object> request(int id, int n) throws IOException {
    Analysis a = stopped(id).getAnalysis();
    RequestDetails d = RequestDetails.of(a, request(a, String.valueOf(n)));

    List<Object> thrown = new ArrayList<>();
    for (RequestDetails.Thrown t : d.getThrown()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("offset", ms(t.offsetNanos()));
      m.put("type", t.type());
      m.put("message", t.message());
      m.put("frame", t.frame());
      thrown.add(m);
    }
    List<Object> io = new ArrayList<>();
    for (RequestDetails.Io i : d.getIo()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("offset", ms(i.offsetNanos()));
      m.put("kind", i.kind());
      m.put("target", i.target());
      m.put("bytes", i.bytes());
      m.put("duration", ms(i.durationNanos()));
      io.add(m);
    }

    RecordingInfo info = a.getInfo();
    Map<String, Object> thresholds = new LinkedHashMap<>();
    for (String e : new String[]{RequestDetails.FILE_READ, RequestDetails.FILE_WRITE,
                                 RequestDetails.SOCKET_READ, RequestDetails.SOCKET_WRITE})
      thresholds.put(e, info.isEnabled(e) ? info.getSettings(e).get("threshold") : null);

    Map<String, Object> res = requestRow(a, n);
    res.put("javaSamples", d.getJavaSamples());
    res.put("nativeSamples", d.getNativeSamples());
    res.put("exceptionsRecorded", info.isEnabled(RequestDetails.EXCEPTION));
    res.put("ioThresholds", thresholds);
    res.put("exceptions", thrown);
    res.put("io", io);
    return res;
  }

  private static RecordedRequest request(Analysis a, String index) {
    try {
      return a.getRequests().get(Integer.parseInt(index));
    }
    catch (NumberFormatException | IndexOutOfBoundsException e) {
      throw new HttpException(404, "No request " + index);
    }
  }

  private static Map<String, Object> requestRow(Analysis a, int index) {
    RecordedRequest r = a.getRequests().get(index);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", index);
    m.put("endpoint", r.endpoint());
    m.put("method", r.method());
    m.put("path", r.path());
    m.put("status", r.status());
    m.put("start", Math.round((r.startNanos() - Analysis.nanos(a.getInfo().getStart())) / 1e6) / 1000.0);
    m.put("duration", ms(r.durationNanos()));
    m.put("thread", r.threadName());
    m.put("bytesIn", r.bytesIn());
    m.put("bytesOut", r.bytesOut());
    return m;
  }

  /**
   * A flame graph, as a self-contained HTML page.
   * <p>
   * Query: {@code from} and {@code to}, in seconds since the start of the
   * recording; {@code thread}, the name of a thread; {@code endpoint}, e.g.
   * "GET /api/user/:id", or {@code request}, the index of one request;
   * {@code all=true} to add the samples taken outside requests.
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
    String request = q.get("request");
    if (request != null && !request.isEmpty()) {
      RecordedRequest r = request(a, request);
      o.requests(r::equals);
      title.append(", request ").append(request).append(": ").append(r.method()).append(' ').append(r.path());
    }
    else if (endpoint != null && !endpoint.isEmpty()) {
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
