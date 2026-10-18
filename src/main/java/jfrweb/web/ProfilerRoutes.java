package jfrweb.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import flak.HttpException;
import flak.Query;
import flak.Request;
import flak.Response;
import flak.annotations.Delete;
import flak.annotations.OutputFormat;
import flak.annotations.Post;
import flak.annotations.Route;
import jfrweb.analysis.Analysis;
import jfrweb.analysis.OperationDetails;
import jfrweb.analysis.OperationIndex;
import jfrweb.analysis.OperationStats;
import jfrweb.analysis.RecordedOperation;
import jfrweb.analysis.RecordingInfo;
import jfrweb.analysis.SamplingSummary;
import jfrweb.analysis.Timeline;
import jfrweb.auto.AutoMode;
import jfrweb.auto.Trigger;
import jfrweb.live.LiveStats;
import jfrweb.session.Session;
import jfrweb.session.Sessions;
import jfrweb.session.StackDepth;

import static jfrweb.analysis.RecordingInfo.EXECUTION_SAMPLE;
import static jfrweb.analysis.RecordingInfo.FLAK_REQUEST;
import static jfrweb.analysis.RecordingInfo.NATIVE_SAMPLE;
import static jfrweb.analysis.RecordingInfo.OPERATION;

/**
 * The REST API behind the web interface. Durations are in milliseconds, times
 * in ISO-8601.
 */
public class ProfilerRoutes {

  /** Name of the output formatter writing {@link jfrweb.json.Json}. */
  public static final String JSON = "JSON";

  private final Sessions sessions;
  private final LiveStats live;
  private final AutoMode auto;
  private final About about;
  private final boolean history;

  /**
   * @param about   what is profiled
   * @param history whether an operation history can be browsed
   */
  public ProfilerRoutes(Sessions sessions, LiveStats live, AutoMode auto, About about, boolean history) {
    this.sessions = sessions;
    this.live = live;
    this.auto = auto;
    this.about = about;
    this.history = history;
  }

  /**
   * What is profiled: the name of the application, whether there are only
   * recordings to browse, with no JVM behind them, and an operation history.
   */
  @Route("/api/about")
  @OutputFormat(JSON)
  public Map<String, Object> about() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("app", about.app());
    m.put("recordingsOnly", about.recordingsOnly());
    m.put("history", history);
    return m;
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
   * Query: {@code patterns}, operations separated by commas or new lines, *
   * matching anything, none for any operation; {@code browser=true} to only
   * count the requests of the browser arming, which gets a cookie;
   * {@code repeat=true} to start a session each time, not once; {@code idle}
   * and {@code max}, in seconds; {@code name}, that of the sessions started.
   */
  @Route("/api/auto")
  @Post
  @OutputFormat(JSON)
  public Map<String, Object> arm(Query q, Response resp) {
    if (!live.isObserving())
      throw new HttpException(409, "No operation is observed: nothing can trigger a session");
    if (auto.isFed() && q.getBool("browser", false))
      throw new HttpException(400, "The browser of a request is not known from the events of a JVM");

    List<String> patterns = new ArrayList<>();
    for (String p : q.get("patterns", "").split("[,\\n]"))
      if (!p.isBlank())
        patterns.add(p.trim());
    String browser = q.getBool("browser", false) ? AutoMode.newBrowserToken() : null;
    Trigger t = new Trigger(patterns, browser, q.getBool("repeat", false),
                            seconds(q, "idle", Trigger.DEFAULT_IDLE),
                            seconds(q, "max", Trigger.DEFAULT_MAX), q.get("name"));
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
    m.put("fed", auto.isFed());
    m.put("flakApps", !live.getAppPaths().isEmpty());
    m.put("armed", t != null);
    if (t != null) {
      m.put("patterns", t.patterns());
      m.put("browser", t.browser() != null);
      m.put("thisBrowser", t.browser() != null && t.browser().equals(cookie));
      m.put("repeat", t.repeat());
      m.put("idle", t.idle().toMillis() / 1000.0);
      m.put("max", t.max().toMillis() / 1000.0);
      m.put("name", t.name());
    }
    Session s = auto.getSession();
    m.put("recording", s == null ? null : s.getId());
    m.put("waiting", auto.isWaiting());
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
   * The operations of the application, over the last minutes.
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

    List<Object> operations = new ArrayList<>();
    for (OperationStats o : live.getOperations(window))
      operations.add(operationRow(o));

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("observing", live.isObserving());
    res.put("fed", live.isFed());
    res.put("apps", live.getAppPaths());
    res.put("window", window);
    res.put("windows", LiveStats.WINDOWS);
    res.put("operations", operations);
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

  /**
   * Starts a session. Query: {@code name}, optional.
   */
  @Route("/api/sessions")
  @Post
  @OutputFormat(JSON)
  public Map<String, Object> start(Query q) throws IOException {
    try {
      Session s = sessions.start("manual");
      s.setName(q.get("name"));
      return session(s);
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

  /**
   * Names a session. Query: {@code name}, none or blank to remove it.
   */
  @Route("/api/sessions/:id/name")
  @Post
  @OutputFormat(JSON)
  public Map<String, Object> rename(int id, Query q) {
    Session s = find(id);
    s.setName(q.get("name"));
    return session(s);
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
    sampling.put("java", sampling(info, EXECUTION_SAMPLE, summary.getJavaSamples(), summary.getJavaInOperations()));
    sampling.put("native", sampling(info, NATIVE_SAMPLE, summary.getNativeSamples(), summary.getNativeInOperations()));
    sampling.put("operationShareOfNative", summary.getOperationShareOfNative());
    sampling.put("operationEvents", info.hasOperationEvents());
    sampling.put("operations", a.getOperations().size());
    sampling.put("truncated", summary.getTruncated());
    sampling.put("stackDepth", summary.getStackDepth());
    sampling.put("suggestedStackDepth", StackDepth.suggest(summary.getStackDepth()));

    List<Object> threads = new ArrayList<>();
    summary.getSamplesByThread().forEach((name, count) -> {
      Map<String, Object> t = new LinkedHashMap<>();
      t.put("name", name);
      t.put("samples", count);
      threads.add(t);
    });

    List<Object> operations = new ArrayList<>();
    for (OperationStats o : OperationStats.of(a.getOperations()))
      operations.add(operationRow(o));

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("session", session(s));
    res.put("recording", recording);
    res.put("sampling", sampling);
    res.put("biases", RecordingInfo.KNOWN_BIASES);
    res.put("threads", threads);
    res.put("operations", operations);
    return res;
  }

  /**
   * The activity of a session over time: operations in flight by name, and
   * samples of Java code, in buckets of a round duration.
   * <p>
   * Query: {@code from} and {@code to}, in seconds since the start of the
   * recording, to zoom in on a part of it, in finer buckets.
   */
  @Route("/api/sessions/:id/timeline")
  @OutputFormat(JSON)
  public Map<String, Object> timeline(int id, Query q) throws IOException {
    Analysis a = stopped(id).getAnalysis();
    Instant start = a.getInfo().getStart();
    Timeline t = Timeline.of(a, offset(start, q.get("from")), offset(start, q.get("to")));

    List<Object> series = new ArrayList<>();
    t.getSeries().forEach((name, values) -> {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("name", name);
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
   * The operations of a session by name, sorted by total time, over the whole
   * session or the operations in flight during an interval.
   * <p>
   * Query: {@code from} and {@code to}, in seconds since the start of the
   * recording.
   */
  @Route("/api/sessions/:id/operations")
  @OutputFormat(JSON)
  public List<Object> operations(int id, Query q) throws IOException {
    Analysis a = stopped(id).getAnalysis();
    Predicate<RecordedOperation> during = during(a, q);
    List<Object> res = new ArrayList<>();
    for (OperationStats o : OperationStats.of(a.getOperations().stream().filter(during).collect(Collectors.toList())))
      res.add(operationRow(o));
    return res;
  }

  /**
   * The slowest operations of a session, the slowest first. An operation is
   * known by its index among those of the recording.
   * <p>
   * Query: {@code name} to keep the operations of a name; {@code from} and
   * {@code to}, in seconds since the start of the recording, to keep those in
   * flight during an interval; {@code limit}, 20 by default.
   */
  @Route("/api/sessions/:id/instances")
  @OutputFormat(JSON)
  public List<Object> instances(int id, Query q) throws IOException {
    Analysis a = stopped(id).getAnalysis();
    String name = q.get("name");
    int limit = q.getInt("limit", 20);
    Predicate<RecordedOperation> during = during(a, q);
    List<RecordedOperation> all = a.getOperations();

    List<Integer> indexes = new ArrayList<>();
    for (int i = 0; i < all.size(); i++)
      if ((name == null || name.isEmpty() || all.get(i).name().equals(name)) && during.test(all.get(i)))
        indexes.add(i);
    indexes.sort((i, j) -> Long.compare(all.get(j).durationNanos(), all.get(i).durationNanos()));

    List<Object> res = new ArrayList<>();
    for (int i : indexes.subList(0, Math.min(limit, indexes.size())))
      res.add(instanceRow(a, i));
    return res;
  }

  /**
   * One operation of a session: the threads that worked for it, its
   * exceptions, and its file and socket accesses longer than the thresholds
   * of the recording.
   */
  @Route("/api/sessions/:id/instances/:n")
  @OutputFormat(JSON)
  public Map<String, Object> instance(int id, int n) throws IOException {
    Analysis a = stopped(id).getAnalysis();
    RecordedOperation op = instance(a, String.valueOf(n));
    OperationDetails d = OperationDetails.of(a, op);

    List<Object> segments = new ArrayList<>();
    for (OperationIndex.Segment s : d.getSegments()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("thread", s.threadName());
      m.put("offset", ms(s.startNanos() - op.startNanos()));
      m.put("duration", ms(s.endNanos() - s.startNanos()));
      segments.add(m);
    }

    List<Object> thrown = new ArrayList<>();
    for (OperationDetails.Thrown t : d.getThrown()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("offset", ms(t.offsetNanos()));
      m.put("type", t.type());
      m.put("message", t.message());
      m.put("frame", t.frame());
      thrown.add(m);
    }
    List<Object> io = new ArrayList<>();
    for (OperationDetails.Io i : d.getIo()) {
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
    for (String e : new String[]{OperationDetails.FILE_READ, OperationDetails.FILE_WRITE,
                                 OperationDetails.SOCKET_READ, OperationDetails.SOCKET_WRITE})
      thresholds.put(e, info.isEnabled(e) ? info.getSettings(e).get("threshold") : null);

    Map<String, Object> res = instanceRow(a, n);
    res.put("javaSamples", d.getJavaSamples());
    res.put("nativeSamples", d.getNativeSamples());
    res.put("segments", segments);
    res.put("exceptionsRecorded", info.isEnabled(OperationDetails.EXCEPTION));
    res.put("ioThresholds", thresholds);
    res.put("exceptions", thrown);
    res.put("io", io);
    return res;
  }

  /**
   * Keeps the operations in flight at some point of the interval of the
   * query, all of them if it has none. An operation keeps its whole duration:
   * cut at the bounds, percentiles would mean nothing.
   */
  private static Predicate<RecordedOperation> during(Analysis a, Query q) {
    Instant start = a.getInfo().getStart();
    Instant from = offset(start, q.get("from"));
    Instant to = offset(start, q.get("to"));
    long fromNanos = from == null ? Long.MIN_VALUE : Analysis.nanos(from);
    long toNanos = to == null ? Long.MAX_VALUE : Analysis.nanos(to);
    return r -> r.startNanos() < toNanos && r.endNanos() > fromNanos;
  }

  private static RecordedOperation instance(Analysis a, String index) {
    try {
      return a.getOperations().get(Integer.parseInt(index));
    }
    catch (NumberFormatException | IndexOutOfBoundsException e) {
      throw new HttpException(404, "No operation " + index);
    }
  }

  private static Map<String, Object> instanceRow(Analysis a, int index) {
    RecordedOperation op = a.getOperations().get(index);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", index);
    m.put("name", op.name());
    m.put("detail", op.detail());
    m.put("status", op.status());
    m.put("failed", op.failed());
    m.put("error", op.error());
    m.put("async", op.async());
    m.put("start", Math.round((op.startNanos() - Analysis.nanos(a.getInfo().getStart())) / 1e6) / 1000.0);
    m.put("duration", ms(op.durationNanos()));
    m.put("thread", op.threadName());
    m.put("bytesIn", op.bytesIn());
    m.put("bytesOut", op.bytesOut());
    return m;
  }

  /**
   * The samples of a session as a profile, from which the page draws flame
   * graphs by itself: see ProfileWriter. Kept gzipped, and sent so when the
   * request accepts it.
   */
  @Route("/api/sessions/:id/profile")
  public InputStream profile(int id, Request req, Response resp) throws IOException {
    byte[] gzipped = stopped(id).getProfile();
    resp.addHeader("Content-Type", "application/json");
    resp.addHeader("Vary", "Accept-Encoding");
    String accepted = req.getHeader("Accept-Encoding");
    if (accepted != null && accepted.contains("gzip")) {
      resp.addHeader("Content-Encoding", "gzip");
      return new ByteArrayInputStream(gzipped);
    }
    return new GZIPInputStream(new ByteArrayInputStream(gzipped));
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

  static Map<String, Object> session(Session s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", s.getId());
    m.put("state", s.getState());
    m.put("name", s.getName());
    m.put("trigger", s.getTrigger());
    m.put("start", s.getStart());
    m.put("end", s.getEnd());
    m.put("duration", s.getDuration().toMillis());
    m.put("size", s.getSize());
    return m;
  }

  static Map<String, Object> operationRow(OperationStats e) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", e.name());
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

  private static Map<String, Object> sampling(RecordingInfo info, String event, long samples, long inOperations) {
    Map<String, Object> m = new LinkedHashMap<>();
    Duration period = info.getPeriod(event);
    Duration observed = info.getObservedInterval(event);
    m.put("period", period == null ? null : ms(period.toNanos()));
    m.put("observed", observed == null ? null : ms(observed.toNanos()));
    m.put("samples", samples);
    m.put("inOperations", inOperations);
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

  static double ms(long nanos) {
    return Math.round(nanos / 1e4) / 100.0;
  }
}
