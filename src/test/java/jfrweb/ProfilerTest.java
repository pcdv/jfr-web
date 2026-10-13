package jfrweb;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.junit.After;
import org.junit.Assert;
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

    HttpResponse<String> started = post("api/sessions");
    Assert.assertEquals(started.body(), 200, started.statusCode());
    Assert.assertTrue(started.body(), started.body().contains("\"state\":\"RECORDING\""));
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

    HttpResponse<String> report = get("api/sessions/1/report");
    Assert.assertEquals("application/json", report.headers().firstValue("Content-Type").orElse(null));
    Assert.assertTrue(report.body(), report.body().contains("\"endpoint\":\"GET /busy\",\"count\":3"));
    Assert.assertTrue(report.body(), report.body().contains("\"requestEvent\":true"));

    String slowest = get("api/sessions/1/requests?endpoint=GET+/busy&limit=2").body();
    Assert.assertTrue(slowest, slowest.startsWith("[{\"id\":"));
    Assert.assertEquals("limited to 2", 2, slowest.split("\"endpoint\":\"GET /busy\"").length - 1);

    String io = get("api/sessions/1/requests?endpoint=GET+/io").body();
    String ioId = io.replaceAll("^\\[\\{\"id\":(\\d+),.*", "$1");
    String details = get("api/sessions/1/requests/" + ioId).body();
    Assert.assertTrue(details, details.contains("\"type\":\"java.lang.IllegalStateException\",\"message\":\"boom\","));
    Assert.assertTrue(details, details.contains("\"frame\":\"jfrweb.ProfilerTest$Handlers.io\""));
    Assert.assertTrue(details, details.contains("\"jdk.FileRead\":\"1 ms\""));
    Assert.assertEquals(404, get("api/sessions/1/requests/9999").statusCode());

    String requestFlame = get("api/sessions/1/flame?request=" + ioId).body();
    Assert.assertTrue(requestFlame, requestFlame.contains("request " + ioId + ": GET /io"));

    String timeline = get("api/sessions/1/timeline").body();
    Assert.assertTrue(timeline, timeline.contains("\"series\":[{\"endpoint\":\"GET /busy\",\"values\":["));

    String flame = get("api/sessions/1/flame?endpoint=GET+/busy").body();
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
                                      "views/live", "components/endpoints", "components/flame",
                                      "components/timeline", "components/requests"}) {
      HttpResponse<String> js = get("js/" + module + ".js");
      Assert.assertEquals(module, 200, js.statusCode());
      Assert.assertEquals(module, "application/javascript", js.headers().firstValue("Content-Type").orElse(null));
    }
    Assert.assertEquals(200, get("css/style.css").statusCode());
  }

  @Test
  public void testLiveEndpoints() throws Exception {
    profiler = Profiler.builder().directory(dir).observe(app).start();
    http.send(HttpRequest.newBuilder(URI.create(app.getRootUrl() + "/busy")).build(),
              HttpResponse.BodyHandlers.ofString());

    String live = "";
    for (int i = 0; i < 100 && !live.contains("GET /busy"); i++) {
      Thread.sleep(20);
      live = get("api/live?window=1").body();
    }
    Assert.assertTrue(live, live.startsWith("{\"observing\":true,\"apps\":[\"\"],\"window\":1,"));
    Assert.assertTrue(live, live.contains("\"endpoint\":\"GET /busy\",\"count\":1"));
    Assert.assertEquals(400, get("api/live?window=7").statusCode());
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
