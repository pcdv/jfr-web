package jfrweb;

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

import flak.App;
import flak.AppFactory;
import flak.Flak;
import flak.HttpException;
import flak.annotations.Route;
import jfrweb.history.OperationHistory;
import jfrweb.ops.Operations;
import jfrweb.snapshot.Snapshots;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

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

  @Before
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("jfr-web-test");
    AppFactory factory = Flak.getFactory();
    factory.setLocalAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    app = factory.createApp();
    app.scan(new Handlers());
    app.start();
  }

  @After
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
    Assert.assertEquals(started.body(), 200, started.statusCode());
    Assert.assertTrue(started.body(), started.body().contains("\"state\":\"RECORDING\",\"name\":\"first try\""));
    Assert.assertEquals("one session at a time", 409, post("api/sessions").statusCode());
    Assert.assertEquals("still recording", 409, get("api/sessions/1/report").statusCode());

    for (int i = 0; i < 3; i++)
      Assert.assertEquals("done", http.send(HttpRequest.newBuilder(URI.create(app.getRootUrl() + "/busy")).build(),
                                            HttpResponse.BodyHandlers.ofString()).body());
    http.send(HttpRequest.newBuilder(URI.create(app.getRootUrl() + "/io")).build(),
              HttpResponse.BodyHandlers.ofString());

    HttpResponse<String> stopped = post("api/sessions/1/stop");
    Assert.assertTrue(stopped.body(), stopped.body().contains("\"state\":\"STOPPED\""));

    String list = get("api/sessions").body();
    Assert.assertTrue(list, list.startsWith("[{\"id\":1,"));
    Assert.assertTrue(post("api/sessions/1/name?name=+renamed+").body().contains("\"name\":\"renamed\""));
    Assert.assertTrue("blank: no name", post("api/sessions/1/name?name=").body().contains("\"name\":null"));
    Assert.assertEquals(404, post("api/sessions/9/name?name=x").statusCode());

    HttpResponse<String> report = get("api/sessions/1/report");
    Assert.assertEquals("application/json", report.headers().firstValue("Content-Type").orElse(null));
    Assert.assertTrue(report.body(), report.body().contains("\"name\":\"GET /busy\",\"count\":3"));
    Assert.assertTrue(report.body(), report.body().contains("\"operationEvents\":true"));
    Assert.assertTrue(report.body(), report.body().matches(".*\"truncated\":\\d+,\"stackDepth\":\\d+,.*"));

    String slowest = get("api/sessions/1/instances?name=GET+/busy&limit=2").body();
    Assert.assertTrue(slowest, slowest.startsWith("[{\"id\":"));
    Assert.assertEquals("limited to 2", 2, slowest.split("\"name\":\"GET /busy\"").length - 1);

    // 10 ms within the first of 3 consecutive /busy: only that one in flight
    double first = Arrays.stream(get("api/sessions/1/instances?name=GET+/busy").body()
                                   .split("\"start\":"))
                         .skip(1)
                         .mapToDouble(s -> Double.parseDouble(s.substring(0, s.indexOf(','))))
                         .min().orElseThrow();
    String interval = "from=" + (first + 0.05) + "&to=" + (first + 0.06);
    String inInterval = get("api/sessions/1/operations?" + interval).body();
    Assert.assertTrue(inInterval, inInterval.startsWith("[{\"name\":\"GET /busy\",\"count\":1,"));
    Assert.assertEquals(1, get("api/sessions/1/instances?" + interval).body().split("\"name\":").length - 1);
    Assert.assertEquals("after the session", "[]", get("api/sessions/1/operations?from=999").body());
    Assert.assertEquals("[]", get("api/sessions/1/instances?from=999").body());

    String io = get("api/sessions/1/instances?name=GET+/io").body();
    String ioId = io.replaceAll("^\\[\\{\"id\":(\\d+),.*", "$1");
    String details = get("api/sessions/1/instances/" + ioId).body();
    Assert.assertTrue(details, details.contains("\"type\":\"java.lang.IllegalStateException\",\"message\":\"boom\","));
    Assert.assertTrue(details, details.contains("\"frame\":\"jfrweb.ProfilerTest$Handlers.io\""));
    Assert.assertTrue(details, details.contains("\"jdk.FileRead\":\"1 ms\""));
    Assert.assertEquals(404, get("api/sessions/1/instances/9999").statusCode());

    String instanceFlame = get("api/sessions/1/flame?instance=" + ioId).body();
    Assert.assertTrue(instanceFlame, instanceFlame.contains("operation " + ioId + ": GET /io"));

    String timeline = get("api/sessions/1/timeline").body();
    Assert.assertTrue(timeline, timeline.contains("\"series\":[{\"name\":\"GET /busy\",\"values\":["));

    String flame = get("api/sessions/1/flame?name=GET+/busy").body();
    Assert.assertTrue(flame.startsWith("<!DOCTYPE html>"));
    Assert.assertTrue(flame.contains("GET /busy"));
    Assert.assertTrue("an interval with no sample", get("api/sessions/1/flame?from=0&to=0").body()
                                                      .startsWith("<!DOCTYPE html>"));
    Assert.assertEquals(400, get("api/sessions/1/flame?from=x").statusCode());

    HttpResponse<byte[]> jfr = http.send(request("api/sessions/1/recording.jfr").build(),
                                         HttpResponse.BodyHandlers.ofByteArray());
    Assert.assertEquals("FLR", new String(jfr.body(), 0, 3));
    Assert.assertTrue(jfr.headers().firstValue("Content-Disposition").orElse("").contains(".jfr"));

    Assert.assertEquals(404, get("api/sessions/9/report").statusCode());
  }

  @Test
  public void testWebInterface() throws Exception {
    profiler = Profiler.builder().directory(dir).start();

    HttpResponse<String> index = get("");
    Assert.assertEquals(200, index.statusCode());
    Assert.assertTrue(index.body().contains("<script type=\"module\" src=\"js/main.js\">"));

    // browsers refuse a module served with another type
    for (String module : new String[]{"main", "api", "dom", "format", "views/sessions", "views/session",
                                      "views/live", "components/operations", "components/flame",
                                      "components/timeline", "components/slowest", "components/auto",
                                      "components/section", "views/history",
                                      "components/sessionTimeline", "settings"}) {
      HttpResponse<String> js = get("js/" + module + ".js");
      Assert.assertEquals(module, 200, js.statusCode());
      Assert.assertEquals(module, "application/javascript", js.headers().firstValue("Content-Type").orElse(null));
    }
    Assert.assertEquals(200, get("css/style.css").statusCode());
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
    Assert.assertTrue(live, live.startsWith("{\"observing\":true,\"apps\":[\"\"],\"window\":1,"));
    Assert.assertTrue(live, live.contains("\"name\":\"GET /busy\",\"count\":1"));
    Assert.assertEquals(400, get("api/live?window=7").statusCode());
  }

  @Test
  public void testAutoModeArmedBrowser() throws Exception {
    profiler = Profiler.builder().directory(dir).observe(app).start();

    HttpResponse<String> armed = post("api/auto?patterns=GET+/busy&browser=true&idle=0.5&name=slow+busy");
    Assert.assertTrue(armed.body(), armed.body().contains("\"name\":\"slow busy\""));
    Assert.assertTrue(armed.body(), armed.body().contains("\"armed\":true,\"patterns\":[\"GET /busy\"],\"browser\":true,\"thisBrowser\":true"));
    String cookie = armed.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    Assert.assertTrue(cookie, cookie.startsWith("jfrweb-armed="));

    busy(null);
    Assert.assertEquals("another client", "[]", get("api/sessions").body());

    busy(cookie);
    String list = get("api/sessions").body();
    Assert.assertTrue(list, list.contains("\"name\":\"slow busy\",\"trigger\":\"auto: GET /busy, armed browser\""));
    for (int i = 0; i < 100 && !get("api/sessions").body().contains("STOPPED"); i++)
      Thread.sleep(50);
    Assert.assertTrue("stopped once idle", get("api/sessions").body().contains("STOPPED"));

    HttpResponse<String> disarmed = http.send(request("api/auto").DELETE().build(), HttpResponse.BodyHandlers.ofString());
    Assert.assertTrue(disarmed.body(), disarmed.body().startsWith("{\"observing\":true,\"flakApps\":true,\"armed\":false"));
    Assert.assertTrue(disarmed.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));
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
    Assert.assertTrue(body, body.startsWith("{\"available\":true,"));
    Assert.assertTrue(body, body.contains("\"operations\":[{\"name\":\"GET /busy\",\"count\":1,"));
    Assert.assertTrue(body, body.contains("\"slowest\":[{\"end\":"));
    Assert.assertTrue(body, body.contains("\"timeline\":{\"start\":"));
    Assert.assertEquals(400, get("api/history?from=yesterday").statusCode());

    // the same from the command line
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Assert.assertEquals(0, jfrweb.cli.MainAccess.run(new String[]{"history", historyDir.toString()},
                                                     new PrintStream(out, true, StandardCharsets.UTF_8)));
    String text = out.toString(StandardCharsets.UTF_8);
    Assert.assertTrue(text, text.contains("GET /busy"));
    Assert.assertTrue(text, text.contains("Slowest operations:"));
  }

  @Test
  public void testSnapshots() throws Exception {
    try (Snapshots snapshots = Snapshots.builder().directory(dir.resolve("snapshots")).observe(app).start()) {
      profiler = Profiler.builder().directory(dir).snapshots(snapshots).start();
      Assert.assertTrue(get("api/snapshots").body().startsWith("{\"available\":true,\"window\":300.0,"));

      busy(null);
      HttpResponse<String> saved = post("api/snapshots");
      Assert.assertTrue(saved.body(), saved.body().contains("\"state\":\"STOPPED\",\"name\":null,\"trigger\":\"snapshot: saved by hand\""));
      String id = saved.body().replaceAll("^\\{\"id\":(\\d+),.*", "$1");

      String report = get("api/sessions/" + id + "/report").body();
      Assert.assertTrue("the request made before the snapshot: " + report,
                        report.contains("\"name\":\"GET /busy\",\"count\":1"));
      Assert.assertTrue(get("api/sessions").body().contains("snapshot: saved by hand"));
    }
  }

  @Test
  public void testNoSnapshots() throws Exception {
    profiler = Profiler.builder().directory(dir).start();
    Assert.assertEquals("{\"available\":false}", get("api/snapshots").body());
    Assert.assertEquals(409, post("api/snapshots").statusCode());
  }

  @Test
  public void testNoHistory() throws Exception {
    profiler = Profiler.builder().directory(dir).start();
    Assert.assertEquals("{\"available\":false}", get("api/history").body());
  }

  @Test
  public void testAutoModeNeedsObservedApps() throws Exception {
    // operations of the API may come once one was begun, by another test
    Assume.assumeFalse(Operations.isUsed());
    profiler = Profiler.builder().directory(dir).start();
    Assert.assertEquals(409, post("api/auto").statusCode());
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
    Assert.assertEquals(403, r.statusCode());
    Assert.assertNull(profiler.getSessions().getRecording());
  }

  @Test
  public void testGuardReplacesLoopbackCheck() throws Exception {
    profiler = Profiler.builder()
                       .directory(dir)
                       .guard(req -> {
                         throw new HttpException(401, "who are you?");
                       })
                       .start();
    Assert.assertEquals(401, get("api/sessions").statusCode());
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
      Assert.assertTrue(profiler.getUrl(), profiler.getUrl().endsWith("/profiler/"));
      Assert.assertEquals("[]", get("api/sessions").body());
    }
    finally {
      profiler.close();
      profiler = null;
      host.stop();
    }
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
