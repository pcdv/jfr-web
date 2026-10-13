package jfrweb.export;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jfrweb.Profiler;
import jfrweb.analysis.Analysis;
import jfrweb.analysis.RecordingInfo;
import jfrweb.history.HistoryReader;
import jfrweb.json.Json;
import jfrweb.session.Session;

/**
 * Writes the web interface and a set of recordings as a static site, which
 * any web server can serve (e.g. GitHub Pages), with no Java behind it: the
 * responses of the REST API are computed beforehand, for everything that can
 * be reached by clicking. What takes a free input is left out: an interval
 * selected on a timeline, the From and To of a flame graph, a filter combined
 * with another.
 * <p>
 * It runs a profiler on the recordings, crawls its API, and writes each
 * response to a file. {@code static.json} maps each request to its file, as
 * the interface asks it: the path, then its parameters sorted by name, not
 * encoded, e.g. {@code api/sessions/1/instances?limit=20&name=GET /x}.
 * <p>
 * Before publishing one, mind what a recording holds: unless its settings
 * turned them off, the environment variables and system properties of the
 * JVM ({@code jdk.InitialEnvironmentVariable}, {@code
 * jdk.InitialSystemProperty}), and its command line. The .jfr files can be left
 * out; the report still shows the JVM arguments.
 */
public final class StaticExport {

  /** The slowest operations listed, as the interface asks for them. */
  private static final int LIMIT = 20;

  /** The flame graphs of the busiest threads only. */
  private static final int MAX_THREADS = 20;

  /** Times as JavaScript's Date.toISOString() writes them. */
  private static final DateTimeFormatter JS_TIME =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final Path out;
  private final boolean withRecordings;
  private final HttpClient http = HttpClient.newHttpClient();
  private final Map<String, String> files = new TreeMap<>();
  private String base;
  private int count;

  private StaticExport(Path out, boolean withRecordings) {
    this.out = out;
    this.withRecordings = withRecordings;
  }

  /**
   * @param recordings     .jfr files, each listed as a session
   * @param history        the directory of an operation history, or null
   * @param out            where the site goes, created if needed
   * @param withRecordings whether the .jfr files can be downloaded from it
   */
  public static void export(List<Path> recordings, Path history, Path out, boolean withRecordings)
    throws IOException {
    new StaticExport(out, withRecordings).run(recordings, history);
  }

  private void run(List<Path> recordings, Path history) throws IOException {
    Files.createDirectories(out.resolve("data"));
    copyInterface();

    Path work = Files.createTempDirectory("jfr-web-export");
    try (Profiler p = Profiler.builder().directory(work).history(history).start()) {
      base = p.getUrl();
      List<Integer> ids = new ArrayList<>();
      for (Path f : recordings) {
        RecordingInfo info = Analysis.load(f).getInfo();
        Session s = p.getSessions().register(info.getStart(), info.getEnd(), f.getFileName().toString(), f);
        ids.add(s.getId());
      }

      get("api/sessions", Map.of(), ".json");
      get("api/auto", Map.of(), ".json");
      get("api/snapshots", Map.of(), ".json");
      for (int id : ids)
        session(id);

      Map<String, Object> manifest = new LinkedHashMap<>();
      manifest.put("exported", Instant.now().truncatedTo(ChronoUnit.SECONDS));
      manifest.put("history", history == null ? null : history(history));
      manifest.put("files", files);
      Files.writeString(out.resolve("static.json"), Json.write(manifest), StandardCharsets.UTF_8);
    }
    finally {
      deleteTree(work);
    }
  }

  @SuppressWarnings("unchecked")
  private void session(int id) throws IOException {
    String api = "api/sessions/" + id;
    get(api, Map.of(), ".json");
    Map<String, Object> report = (Map<String, Object>) Json.parse(get(api + "/report", Map.of(), ".json"));
    get(api + "/timeline", Map.of(), ".json");
    if (withRecordings)
      download(api + "/recording.jfr");

    List<String> names = new ArrayList<>();
    for (Object o : (List<Object>) report.get("operations"))
      names.add((String) ((Map<String, Object>) o).get("name"));

    // the slowest operations, of all names and of each one, and their details
    Set<Object> instances = new LinkedHashSet<>();
    List<Map<String, String>> lists = new ArrayList<>();
    lists.add(Map.of("limit", String.valueOf(LIMIT)));
    for (String n : names)
      lists.add(Map.of("limit", String.valueOf(LIMIT), "name", n));
    for (Map<String, String> q : lists)
      for (Object o : (List<Object>) Json.parse(get(api + "/instances", q, ".json")))
        instances.add(((Map<String, Object>) o).get("id"));
    for (Object i : instances)
      get(api + "/instances/" + number(i), Map.of(), ".json");

    // flame graphs: all, then one filter at a time
    get(api + "/flame", Map.of(), ".html");
    get(api + "/flame", Map.of("all", "true"), ".html");
    for (String n : names)
      get(api + "/flame", Map.of("name", n), ".html");
    List<Map<String, Object>> threads = new ArrayList<>((List<Map<String, Object>>) report.get("threads"));
    threads.sort(Comparator.comparingLong((Map<String, Object> t) -> ((Number) t.get("samples")).longValue()).reversed());
    for (Map<String, Object> t : threads.subList(0, Math.min(MAX_THREADS, threads.size())))
      get(api + "/flame", Map.of("thread", (String) t.get("name")), ".html");
    for (Object i : instances)
      get(api + "/flame", Map.of("instance", number(i)), ".html");
  }

  /**
   * The whole period of the history, and the slowest operations of each name.
   *
   * @return the period, as the interface asks for it
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> history(Path directory) throws IOException {
    HistoryReader.Span span = HistoryReader.span(directory);
    if (span == null)
      return null;
    Instant to = span.to().isAfter(Instant.now()) ? Instant.now() : span.to();
    String from = JS_TIME.format(span.from());
    String until = JS_TIME.format(to);
    // the name of the directory, not where it was on this machine
    String where = Json.write(directory.toString());
    String name = Json.write(directory.toAbsolutePath().normalize().getFileName().toString());
    Map<String, Object> h = (Map<String, Object>) Json.parse(
      get("api/history", Map.of("from", from, "to", until), ".json", body -> body.replace(where, name)));
    for (Object o : (List<Object>) h.get("operations"))
      get("api/history", Map.of("from", from, "to", until, "name", (String) ((Map<String, Object>) o).get("name")),
          ".json", body -> body.replace(where, name));
    Map<String, Object> period = new LinkedHashMap<>();
    period.put("from", from);
    period.put("to", until);
    return period;
  }

  /**
   * Fetches a response and writes it to a file of its own.
   *
   * @return the response
   */
  private String get(String path, Map<String, String> params, String ext) throws IOException {
    return get(path, params, ext, body -> body);
  }

  /**
   * @param edit changes the response before it is written
   */
  private String get(String path, Map<String, String> params, String ext, UnaryOperator<String> edit)
    throws IOException {
    String body = edit.apply(send(path, params, HttpResponse.BodyHandlers.ofString()).body());
    String file = "data/" + (++count) + ext;
    Files.writeString(out.resolve(file), body, StandardCharsets.UTF_8);
    files.put(key(path, params), file);
    return body;
  }

  private void download(String path) throws IOException {
    String file = "data/" + (++count) + ".jfr";
    send(path, Map.of(), HttpResponse.BodyHandlers.ofFile(out.resolve(file)));
    files.put(key(path, Map.of()), file);
  }

  private <T> HttpResponse<T> send(String path, Map<String, String> params, HttpResponse.BodyHandler<T> body)
    throws IOException {
    String query = params.entrySet().stream()
                         .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                         .collect(Collectors.joining("&"));
    HttpRequest req = HttpRequest.newBuilder(URI.create(base + path + (query.isEmpty() ? "" : "?" + query))).build();
    try {
      HttpResponse<T> r = http.send(req, body);
      if (r.statusCode() != 200)
        throw new IOException(r.statusCode() + " for " + req.uri());
      return r;
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted", e);
    }
  }

  /**
   * What the interface looks a request up by: the path, then its parameters
   * sorted by name, not encoded.
   */
  static String key(String path, Map<String, String> params) {
    if (params.isEmpty())
      return path;
    return path + "?" + new TreeMap<>(params).entrySet().stream()
                                             .map(e -> e.getKey() + "=" + e.getValue())
                                             .collect(Collectors.joining("&"));
  }

  private static String number(Object n) {
    return String.valueOf(((Number) n).longValue());
  }

  /**
   * Copies the files of the interface, from the jar or from the classes
   * directory.
   */
  private void copyInterface() throws IOException {
    URL index = StaticExport.class.getResource("/jfrweb/ui/index.html");
    if (index == null)
      throw new IOException("The interface is not on the classpath");
    URI uri;
    try {
      uri = index.toURI();
    }
    catch (URISyntaxException e) {
      throw new IOException(e);
    }
    if (uri.getScheme().equals("jar")) {
      FileSystem fs;
      boolean opened = false;
      try {
        fs = FileSystems.newFileSystem(uri, Map.of());
        opened = true;
      }
      catch (FileSystemAlreadyExistsException e) {
        fs = FileSystems.getFileSystem(uri);
      }
      try {
        copyTree(fs.getPath("/jfrweb/ui"));
      }
      finally {
        if (opened)
          fs.close();
      }
    }
    else {
      copyTree(Path.of(uri).getParent());
    }
  }

  private void copyTree(Path root) throws IOException {
    try (Stream<Path> all = Files.walk(root)) {
      for (Path p : (Iterable<Path>) all::iterator) {
        if (Files.isDirectory(p) || p.getFileName().toString().equals("jsconfig.json"))
          continue;
        Path target = out.resolve(root.relativize(p).toString());
        Files.createDirectories(target.getParent());
        try (InputStream in = Files.newInputStream(p)) {
          Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static void deleteTree(Path dir) throws IOException {
    try (Stream<Path> all = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) all.sorted(Comparator.reverseOrder())::iterator)
        Files.deleteIfExists(p);
    }
  }
}
