package jfrweb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import flak.App;
import flak.AppFactory;
import flak.Flak;
import flak.HttpException;
import flak.annotations.Route;
import jfrweb.history.OperationHistory;
import jfrweb.ops.Operations;
import jfrweb.snapshot.Snapshots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the profiler through its REST API, while a flak app of the same JVM
 * serves requests.
 */
public class ProfilerTest {

  private final HttpClient http = HttpClient.newHttpClient();
  private Path dir;
  private App app;
  private Profiler profiler;

  public static class Handlers {
    static volatile long sink;

    @Route("/busy")
    public String busy() {
      long end = System.nanoTime() + 200_000_000;
      Random random = new Random(1);
      while (System.nanoTime() < end) {
        int[] a = random.ints(10_000).toArray();
        Arrays.sort(a);
        sink += a[0];
      }
      return "done";
    }

    @Route("/io")
    public String io() throws IOException {
      try {
        throw new IllegalStateException("boom");
      }
      catch (IllegalStateException e) {
        // a slow request's exceptions, even caught, tell what went on
      }
      Path f = Files.createTempFile("jfr-web", ".bin");
      try {
        Files.write(f, new byte[8 << 20]);
        return Files.readAllBytes(f).length + " bytes";
      }
      finally {
        Files.delete(f);
      }
    }
  }

  @BeforeEach
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("jfr-web-test");
    AppFactory factory = Flak.getFactory();
    factory.setLocalAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    app = factory.createApp();
    app.scan(new Handlers());
    app.start();
  }

  @AfterEach
  public void tearDown() throws IOException {
    if (profiler != null)
      profiler.close();
    app.stop();
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  @Test
  public void testManualSession() throws Exception {
    profiler = Profiler.builder().directory(dir).start();

    HttpResponse<String> started = post("api/sessions?name=first+try");
    Assertions.assertEquals(200, started.statusCode(), started.body());
    Assertions.assertTrue(started.body().contains("\"state\":\"RECORDING\",\"name\":\"first try\""), started.body());
    Assertions.assertEquals(409, post("api/sessions").statusCode(), "one session at a time");
    Assertions.assertEquals(409, get("api/sessions/1/report").statusCode(), "still recording");

    for (int i = 0; i < 3; i++)
      Assertions.assertEquals("done", http.send(HttpRequest.newBuilder(URI.create(app.getRootUrl() + "/busy")).build(),
                                            HttpResponse.BodyHandlers.ofString()).body());
    http.send(HttpRequest.newBuilder(URI.create(app.getRootUrl() + "/io")).build(),
              HttpResponse.BodyHandlers.ofString());

    HttpResponse<String> stopped = post("api/sessions/1/stop");
    Assertions.assertTrue(stopped.body().contains("\"state\":\"STOPPED\""), stopped.body());

    String list = get("api/sessions").body();
    Assertions.assertTrue(list.startsWith("[{\"id\":1,"), list);
    // the application of this JVM, e.g. the test runner's main class
    Assertions.assertTrue(get("api/about").body().matches("\\{\"app\":\"[\\w.-]+\",\"recordingsOnly\":false,\"history\":false}"));
    Assertions.assertTrue(post("api/sessions/1/name?name=+renamed+").body().contains("\"name\":\"renamed\""));
    Assertions.assertTrue(post("api/sessions/1/name?name=").body().contains("\"name\":null"), "blank: no name");
    Assertions.assertEquals(404, post("api/sessions/9/name?name=x").statusCode());

    HttpResponse<String> report = get("api/sessions/1/report");
    Assertions.assertEquals("application/json", report.headers().firstValue("Content-Type").orElse(null));
    Assertions.assertTrue(report.body().contains("\"name\":\"GET /busy\",\"count\":3"), report.body());
    Assertions.assertTrue(report.body().contains("\"operationEvents\":true"), report.body());
    Assertions.assertTrue(report.body().matches(".*\"truncated\":\\d+,\"stackDepth\":\\d+,.*"), report.body());

    String slowest = get("api/sessions/1/instances?name=GET+/busy&limit=2").body();
    Assertions.assertTrue(slowest.startsWith("[{\"id\":"), slowest);
    Assertions.assertEquals(2, slowest.split("\"name\":\"GET /busy\"").length - 1, "limited to 2");

    // 10 ms within the first of 3 consecutive /busy: only that one in flight
    double first = Arrays.stream(get("api/sessions/1/instances?name=GET+/busy").body()
                                   .split("\"start\":"))
                         .skip(1)
                         .mapToDouble(s -> Double.parseDouble(s.substring(0, s.indexOf(','))))
                         .min().orElseThrow();
    String interval = "from=" + (first + 0.05) + "&to=" + (first + 0.06);
    String inInterval = get("api/sessions/1/operations?" + interval).body();
    Assertions.assertTrue(inInterval.startsWith("[{\"name\":\"GET /busy\",\"count\":1,"), inInterval);
    Assertions.assertEquals(1, get("api/sessions/1/instances?" + interval).body().split("\"name\":").length - 1);
    Assertions.assertEquals("[]", get("api/sessions/1/operations?from=999").body(), "after the session");
    Assertions.assertEquals("[]", get("api/sessions/1/instances?from=999").body());

    String io = get("api/sessions/1/instances?name=GET+/io").body();
    String ioId = io.replaceAll("^\\[\\{\"id\":(\\d+),.*", "$1");
    String details = get("api/sessions/1/instances/" + ioId).body();
    Assertions.assertTrue(details.contains("\"type\":\"java.lang.IllegalStateException\",\"message\":\"boom\","), details);
    Assertions.assertTrue(details.contains("\"frame\":\"jfrweb.ProfilerTest$Handlers.io\""), details);
    Assertions.assertTrue(details.contains("\"jdk.FileRead\":\"1 ms\""), details);
    Assertions.assertEquals(404, get("api/sessions/1/instances/9999").statusCode());

    String timeline = get("api/sessions/1/timeline").body();
    Assertions.assertTrue(timeline.contains("\"series\":[{\"name\":\"GET /busy\",\"values\":["), timeline);
    Assertions.assertEquals(400, get("api/sessions/1/timeline?from=x").statusCode());
    // the page draws its flame graphs from the profile
    Assertions.assertEquals(404, get("api/sessions/1/flame").statusCode());

    // the profile: kept gzipped, sent so when the request accepts it
    HttpResponse<byte[]> gzipped = http.send(request("api/sessions/1/profile").header("Accept-Encoding", "gzip, br").build(),
                                             HttpResponse.BodyHandlers.ofByteArray());
    Assertions.assertEquals("gzip", gzipped.headers().firstValue("Content-Encoding").orElse(null));
    HttpResponse<String> profile = get("api/sessions/1/profile");
    Assertions.assertEquals("application/json", profile.headers().firstValue("Content-Type").orElse(null));
    Assertions.assertTrue(profile.headers().firstValue("Content-Encoding").isEmpty());
    Assertions.assertEquals(profile.body(), new String(new GZIPInputStream(new ByteArrayInputStream(gzipped.body()))
                                                         .readAllBytes(), StandardCharsets.UTF_8));
    Assertions.assertTrue(profile.body().startsWith("{\"version\":3,"), profile.body().substring(0, 40));
    Assertions.assertTrue(profile.body().contains("ProfilerTest$Handlers.busy"), "front-coded, but whole here");
    Assertions.assertEquals(404, get("api/sessions/9/profile").statusCode());

    HttpResponse<byte[]> jfr = http.send(request("api/sessions/1/recording.jfr").build(),
                                         HttpResponse.BodyHandlers.ofByteArray());
    Assertions.assertEquals("FLR", new String(jfr.body(), 0, 3));
    Assertions.assertTrue(jfr.headers().firstValue("Content-Disposition").orElse("").contains(".jfr"));

    Assertions.assertEquals(404, get("api/sessions/9/report").statusCode());
  }

  @Test
  public void testWebInterface() throws Exception {
    profiler = Profiler.builder().directory(dir).start();

    HttpResponse<String> index = get("");
    Assertions.assertEquals(200, index.statusCode());
    Assertions.assertTrue(index.body().contains("<script type=\"module\" src=\"js/main.js\">"));

    // browsers refuse a module served with another type
    for (String module : new String[]{"main", "api", "format", "views/sessions", "views/session",
                                      "views/live", "components/operations", "components/flame",
                                      "components/timeline", "components/slowest", "components/auto",
                                      "components/section", "views/history",
                                      "components/sessionTimeline", "lib/dom", "lib/settings",
                                      "lib/dialog", "lib/router", "profile/profile", "profile/operations", "profile/timeline", "flame/render", "flame/heat",
                                      "flamePage", "components/hotMethods", "components/share", "components/tabs"}) {
      HttpResponse<String> js = get("js/" + module + ".js");
      Assertions.assertEquals(200, js.statusCode(), module);
      Assertions.assertEquals("application/javascript", js.headers().firstValue("Content-Type").orElse(null), module);
    }
    Assertions.assertEquals(200, get("css/style.css").statusCode());
  }

  @Test
  public void testLiveOperations() throws Exception {
    profiler = Profiler.builder().directory(dir).observe(app).start();
    http.send(HttpRequest.newBuilder(URI.create(app.getRootUrl() + "/busy")).build(),
              HttpResponse.BodyHandlers.ofString());

    String live = "";
    for (int i = 0; i < 100 && !live.contains("GET /busy"); i++) {
      Thread.sleep(20);
      live = get("api/live?window=1").body();
    }
    Assertions.assertTrue(live.startsWith("{\"observing\":true,\"fed\":false,\"apps\":[\"\"],\"window\":1,"), live);
    Assertions.assertTrue(live.contains("\"name\":\"GET /busy\",\"count\":1"), live);
    Assertions.assertEquals(400, get("api/live?window=7").statusCode());
  }

  @Test
  public void testAutoModeArmedBrowser() throws Exception {
    profiler = Profiler.builder().directory(dir).observe(app).start();

    HttpResponse<String> armed = post("api/auto?patterns=GET+/busy&browser=true&idle=0.5&name=slow+busy");
    Assertions.assertTrue(armed.body().contains("\"name\":\"slow busy\""), armed.body());
    Assertions.assertTrue(armed.body().contains("\"armed\":true,\"patterns\":[\"GET /busy\"],\"browser\":true,\"thisBrowser\":true"), armed.body());
    String cookie = armed.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    Assertions.assertTrue(cookie.startsWith("jfrweb-armed="), cookie);

    busy(null);
    Assertions.assertEquals("[]", get("api/sessions").body(), "another client");

    busy(cookie);
    String list = get("api/sessions").body();
    Assertions.assertTrue(list.contains("\"name\":\"slow busy\",\"trigger\":\"auto: GET /busy, armed browser\""), list);
    for (int i = 0; i < 100 && !get("api/sessions").body().contains("STOPPED"); i++)
      Thread.sleep(50);
    Assertions.assertTrue(get("api/sessions").body().contains("STOPPED"), "stopped once idle");

    HttpResponse<String> disarmed = http.send(request("api/auto").DELETE().build(), HttpResponse.BodyHandlers.ofString());
    Assertions.assertTrue(disarmed.body().startsWith("{\"observing\":true,\"fed\":false,\"flakApps\":true,\"armed\":false"), disarmed.body());
    Assertions.assertTrue(disarmed.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));
  }

  @Test
  public void testHistory() throws Exception {
    Path historyDir = dir.resolve("history");
    try (OperationHistory history = OperationHistory.builder().directory(historyDir)
                                                  .interval(java.time.Duration.ofSeconds(1)).observe(app).start()) {
      profiler = Profiler.builder().directory(dir).history(historyDir).start();
      busy(null);
      Thread.sleep(1500);
    }

    String body = get("api/history").body();
    Assertions.assertTrue(body.startsWith("{\"available\":true,"), body);
    Assertions.assertTrue(body.contains("\"operations\":[{\"name\":\"GET /busy\",\"count\":1,"), body);
    Assertions.assertTrue(body.contains("\"slowest\":[{\"end\":"), body);
    Assertions.assertTrue(body.contains("\"timeline\":{\"start\":"), body);
    Assertions.assertEquals(400, get("api/history?from=yesterday").statusCode());

    // the same from the command line
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Assertions.assertEquals(0, jfrweb.cli.MainAccess.run(new String[]{"history", historyDir.toString()},
                                                     new PrintStream(out, true, StandardCharsets.UTF_8)));
    String text = out.toString(StandardCharsets.UTF_8);
    Assertions.assertTrue(text.contains("GET /busy"), text);
    Assertions.assertTrue(text.contains("Slowest operations:"), text);
  }

  @Test
  public void testSnapshots() throws Exception {
    try (Snapshots snapshots = Snapshots.builder().directory(dir.resolve("snapshots")).observe(app).start()) {
      profiler = Profiler.builder().directory(dir).snapshots(snapshots).start();
      Assertions.assertTrue(get("api/snapshots").body().startsWith("{\"available\":true,\"window\":300.0,"));

      busy(null);
      HttpResponse<String> saved = post("api/snapshots");
      Assertions.assertTrue(saved.body().contains("\"state\":\"STOPPED\",\"name\":null,\"trigger\":\"snapshot: saved by hand\""), saved.body());
      String id = saved.body().replaceAll("^\\{\"id\":(\\d+),.*", "$1");

      String report = get("api/sessions/" + id + "/report").body();
      Assertions.assertTrue(report.contains("\"name\":\"GET /busy\",\"count\":1"), "the request made before the snapshot: " + report);
      Assertions.assertTrue(get("api/sessions").body().contains("snapshot: saved by hand"));
    }
  }

  @Test
  public void testNoSnapshots() throws Exception {
    profiler = Profiler.builder().directory(dir).start();
    Assertions.assertEquals("{\"available\":false}", get("api/snapshots").body());
    Assertions.assertEquals(409, post("api/snapshots").statusCode());
  }

  @Test
  public void testNoHistory() throws Exception {
    profiler = Profiler.builder().directory(dir).start();
    Assertions.assertEquals("{\"available\":false}", get("api/history").body());
  }

  @Test
  public void testAutoModeNeedsObservedApps() throws Exception {
    // operations of the API may come once one was begun, by another test
    Assumptions.assumeFalse(Operations.isUsed());
    profiler = Profiler.builder().directory(dir).start();
    Assertions.assertEquals(409, post("api/auto").statusCode());
  }

  private void busy(String cookie) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(app.getRootUrl() + "/busy"));
    if (cookie != null)
      b.header("Cookie", cookie);
    http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  public void testOtherSitesCannotStartSessions() throws Exception {
    profiler = Profiler.builder().directory(dir).start();
    HttpResponse<String> r = http.send(request("api/sessions")
                                         .header("Origin", "https://evil.example")
                                         .POST(HttpRequest.BodyPublishers.noBody())
                                         .build(),
                                       HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(403, r.statusCode());
    Assertions.assertNull(profiler.getSessions().getRecording());
  }

  @Test
  public void testGuardReplacesLoopbackCheck() throws Exception {
    profiler = Profiler.builder()
                       .directory(dir)
                       .guard(req -> {
                         throw new HttpException(401, "who are you?");
                       })
                       .start();
    Assertions.assertEquals(401, get("api/sessions").statusCode());
  }

  @Test
  public void testMountedOnAnExistingServer() throws Exception {
    AppFactory factory = Flak.getFactory();
    factory.setLocalAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    App host = factory.createApp("/host");
    host.scan(new Handlers());
    host.start();
    try {
      profiler = Profiler.builder().directory(dir).mount(factory, "/profiler").start();
      Assertions.assertTrue(profiler.getUrl().endsWith("/profiler/"), profiler.getUrl());
      Assertions.assertEquals("[]", get("api/sessions").body());
    }
    finally {
      profiler.close();
      profiler = null;
      host.stop();
    }
  }

  @Test
  public void testSessionsKeptAcrossRestarts() throws Exception {
    profiler = Profiler.builder().directory(dir).start();
    post("api/sessions?name=before");
    post("api/sessions/1/stop");
    post("api/sessions/1/name?name=renamed");
    // recording when the profiler goes: stopped as it closes
    post("api/sessions");
    profiler.close();

    profiler = Profiler.builder().directory(dir).start();
    String list = get("api/sessions").body();
    Assertions.assertTrue(list.matches("\\[\\{\"id\":2,\"state\":\"STOPPED\",\"name\":null,.*"
                                         + "\\{\"id\":1,\"state\":\"STOPPED\",\"name\":\"renamed\",\"trigger\":\"manual\",.*"), list);
    Assertions.assertEquals(200, get("api/sessions/1/report").statusCode());
    // the next one after them
    Assertions.assertTrue(post("api/sessions").body().startsWith("{\"id\":3,"));
    post("api/sessions/3/stop");

    // a file deleted: its session goes
    try (Stream<Path> files = Files.list(dir)) {
      for (Path f : files.filter(f -> f.getFileName().toString().startsWith("session-1-")).toList())
        Files.delete(f);
    }
    Assertions.assertFalse(get("api/sessions").body().contains("\"id\":1,"));
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(profiler.getUrl() + path));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path) throws Exception {
    return http.send(request(path).POST(HttpRequest.BodyPublishers.noBody()).build(),
                     HttpResponse.BodyHandlers.ofString());
  }
}
