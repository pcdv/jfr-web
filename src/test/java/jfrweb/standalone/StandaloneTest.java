package jfrweb.standalone;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Profiles another JVM, {@link RemoteApp}, through the REST API of jfr-web
 * on its own.
 */
public class StandaloneTest {

  private static final String APP = "jfrweb.standalone.RemoteApp";
  private static final String BASE = "app/" + APP + "/";

  private final HttpClient http = HttpClient.newHttpClient();
  private Path dir;
  private Process child;
  private Standalone standalone;

  @Before
  public void setUp() throws Exception {
    dir = Files.createTempDirectory("jfr-web-standalone");
    child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                               "-cp", System.getProperty("java.class.path"), RemoteApp.class.getName())
      .redirectErrorStream(true)
      .start();
    BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
    Assert.assertEquals("ready", out.readLine());
  }

  @After
  public void tearDown() throws Exception {
    if (standalone != null)
      standalone.close();
    child.destroyForcibly();
    child.waitFor(10, TimeUnit.SECONDS);
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  @Test
  public void testAppKey() {
    Assert.assertEquals("myapp", Jvms.app("/opt/myapp-7.40.0-5-e71cad50-dirty+devSnapshot-all.jar --port 80", 1));
    Assert.assertEquals("myapp", Jvms.app("C:\\apps\\myapp.jar", 1));
    Assert.assertEquals("org.example.Main", Jvms.app("org.example.Main arg", 1));
    Assert.assertEquals("pid-12", Jvms.app("", 12));
    Assert.assertEquals("pid-12", Jvms.app(null, 12));
  }

  @Test
  public void testProfileAnotherJvm() throws Exception {
    standalone = Standalone.start(dir, 0);
    long pid = child.pid();

    String jvms = get("api/jvms").body();
    Assert.assertTrue(jvms, jvms.contains("{\"pid\":" + pid + ",\"name\":\"" + APP + "\",\"app\":\"" + APP
                                          + "\",\"attached\":false}"));
    Assert.assertEquals(404, post("api/jvms/1/attach").statusCode());

    HttpResponse<String> attached = post("api/jvms/" + pid + "/attach");
    Assert.assertEquals(attached.body(), 200, attached.statusCode());
    Assert.assertEquals("{\"app\":\"" + APP + "\",\"path\":\"" + BASE + "\"}", attached.body());
    Assert.assertTrue(get(BASE + "api/target").body().contains("\"pid\":" + pid + ","));
    Assert.assertEquals("{\"app\":\"" + APP + "\"}", get(BASE + "api/about").body());
    Assert.assertEquals("the interface of the profiler", 200, get(BASE).statusCode());

    // its operations, from their events, once they end
    String live = waitFor(BASE + "api/live?window=1", b -> b.contains("\"name\":\"work\""));
    Assert.assertTrue(live, live.contains("\"observing\":true,\"fed\":true"));

    Assert.assertTrue(post(BASE + "api/sessions?name=remote").body().contains("\"state\":\"RECORDING\""));
    Thread.sleep(1500);
    Assert.assertTrue(post(BASE + "api/sessions/1/stop").body().contains("\"state\":\"STOPPED\""));
    String report = get(BASE + "api/sessions/1/report").body();
    Assert.assertTrue(report, report.contains("\"name\":\"work\",\"count\":"));
    Assert.assertTrue(report, report.contains("\"javaArguments\":\"" + APP + "\""));
    Assert.assertEquals(200, get(BASE + "api/sessions/1/flame?name=work").statusCode());

    // armed: a session once a matching operation ends, as long as they come
    Assert.assertEquals("no browser in events", 400, post(BASE + "api/auto?patterns=work&browser=true").statusCode());
    Assert.assertEquals(200, post(BASE + "api/auto?patterns=work&max=1&name=auto+one").statusCode());
    waitFor(BASE + "api/sessions", b -> b.contains("\"id\":2,\"state\":\"STOPPED\",\"name\":\"auto one\","
                                                   + "\"trigger\":\"auto: waiting for work\""));

    // disconnected, then connected again
    Assert.assertEquals(200, post("api/detach").statusCode());
    Assert.assertTrue(get(BASE + "api/target").body().contains("\"pid\":null"));
    Assert.assertEquals(200, post("api/jvms/" + pid + "/attach").statusCode());

    // the JVM ends: detached, its sessions stay
    child.destroyForcibly();
    waitFor(BASE + "api/target", b -> b.contains("\"pid\":null"));
    Assert.assertEquals("no JVM to record", 409, post(BASE + "api/sessions").statusCode());

    // kept for the next run
    standalone.close();
    standalone = Standalone.start(dir, 0);
    String apps = get("api/jvms").body();
    Assert.assertTrue(apps, apps.contains("{\"app\":\"" + APP + "\",\"path\":\"" + BASE + "\",\"pid\":null,\"sessions\":2,"));
    Assert.assertTrue(get(BASE + "api/sessions").body().contains("\"name\":\"remote\""));
  }

  private String waitFor(String path, Predicate<String> condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    String body;
    do {
      body = get(path).body();
      if (condition.test(body))
        return body;
      Thread.sleep(200);
    } while (System.nanoTime() < end);
    Assert.fail("Not in time: " + body);
    return body;
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(standalone.getUrl() + path));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path) throws Exception {
    return http.send(request(path).POST(HttpRequest.BodyPublishers.noBody()).build(),
                     HttpResponse.BodyHandlers.ofString());
  }
}
