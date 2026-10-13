package jfrweb.auto;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import flak.App;
import flak.AppFactory;
import flak.Flak;
import flak.annotations.Route;
import jfrweb.analysis.RecordedOperation;
import jfrweb.ops.Operation;
import jfrweb.ops.Operations;
import jfrweb.session.Session;
import jfrweb.session.Sessions;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class AutoModeTest {

  private static volatile Sessions sessions;
  private static volatile boolean recordingInHandler;

  private Path dir;
  private App app;
  private AutoMode auto;

  public static class Handlers {
    @Route("/busy")
    public String busy() throws InterruptedException {
      recordingInHandler = sessions.getRecording() != null;
      Thread.sleep(100);
      return "busy";
    }

    @Route("/hello")
    public String hello() {
      return "hello";
    }
  }

  @Before
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("jfr-web-auto");
    sessions = new Sessions(dir, Sessions.defaultSettings(), false);
    auto = new AutoMode(sessions);

    AppFactory factory = Flak.getFactory();
    factory.setLocalAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    app = factory.createApp();
    app.scan(new Handlers());
    app.start();
    auto.observe(app);
  }

  @After
  public void tearDown() throws IOException {
    auto.close();
    sessions.close();
    app.stop();
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  @Test
  public void testStartsBeforeTheHandlerAndStopsWhenIdle() throws Exception {
    auto.arm(new Trigger(List.of("GET /busy"), null, false, Duration.ofMillis(300), Duration.ofSeconds(10)));

    get("/hello", null);
    Assert.assertTrue("not a matching endpoint", sessions.list().isEmpty());

    recordingInHandler = false;
    get("/busy", null);
    Assert.assertTrue("recording when the handler ran", recordingInHandler);
    Session s = sessions.list().get(0);
    Assert.assertEquals("auto: GET /busy", s.getTrigger());

    await(() -> s.getState() == Session.State.STOPPED);
    Assert.assertNull("once: disarmed after the session", auto.getTrigger());
    get("/busy", null);
    Assert.assertEquals(1, sessions.list().size());

    // the request that triggered the session is in it, whole
    Assert.assertEquals(1, s.getAnalysis().getOperations().stream()
                             .filter(r -> r.name().equals("GET /busy")).count());
  }

  @Test
  public void testAsyncOperationOfTheApi() throws Exception {
    auto.arm(new Trigger(List.of("quote"), null, false, Duration.ofMillis(300), Duration.ofSeconds(10)));

    Operation op = Operations.beginAsync("quote").detail("ACME");
    Assert.assertNotNull("recording before the operation runs", sessions.getRecording());
    Thread worker = new Thread(op.wrap(() -> {
      try {
        Thread.sleep(100);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      op.close();
    }));
    worker.start();
    worker.join();

    Session s = sessions.list().get(0);
    Assert.assertEquals("auto: quote", s.getTrigger());
    await(() -> s.getState() == Session.State.STOPPED);

    // in it, whole, with the segment of the worker
    RecordedOperation r = s.getAnalysis().getOperations().stream()
                           .filter(o -> o.name().equals("quote")).findFirst().orElseThrow();
    Assert.assertTrue(r.async());
    Assert.assertEquals("ACME", r.detail());
    Assert.assertEquals(1, s.getAnalysis().getSegments(r).size());
  }

  @Test
  public void testArmedBrowser() throws Exception {
    auto.arm(new Trigger(List.of(), "token", false, Duration.ofMillis(300), Duration.ofSeconds(10)));

    get("/hello", null);
    get("/hello", "other");
    Assert.assertTrue("another client", sessions.list().isEmpty());

    get("/hello", "token");
    Assert.assertEquals("auto: GET /hello, armed browser", sessions.list().get(0).getTrigger());
  }

  @Test
  public void testRepeatWithMaximumDuration() throws Exception {
    auto.arm(new Trigger(List.of("GET /hel*"), null, true, Duration.ofSeconds(10), Duration.ofMillis(400)));

    long end = System.currentTimeMillis() + 1500;
    while (System.currentTimeMillis() < end)
      get("/hello", null);

    Assert.assertTrue("several sessions: " + sessions.list().size(), sessions.list().size() >= 2);
    for (Session s : sessions.list())
      if (s.getState() == Session.State.STOPPED)
        Assert.assertTrue(s.getDuration().toString(), s.getDuration().toMillis() < 1000);
    Assert.assertNotNull("still armed", auto.getTrigger());
  }

  @Test
  public void testDisarmedDuringASession() throws Exception {
    auto.arm(new Trigger(List.of(), null, true, Duration.ofMillis(200), Duration.ofSeconds(10)));
    get("/hello", null);
    Session s = auto.getSession();
    Assert.assertNotNull(s);
    auto.disarm();
    // it ends with the default idle time
    await(() -> s.getState() == Session.State.STOPPED);
  }

  @Test
  public void testPatterns() {
    Trigger t = new Trigger(List.of("GET /api/*", "* /static/*.js"), null, false,
                            Trigger.DEFAULT_IDLE, Trigger.DEFAULT_MAX);
    Assert.assertTrue(t.matches("GET /api/user/:id", null));
    Assert.assertTrue(t.matches("HEAD /static/*splat.js", null));
    Assert.assertFalse(t.matches("POST /api/user/:id", null));
    Assert.assertFalse("regex characters are literal", Trigger.glob("GET /a.c").matcher("GET /abc").matches());
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; i < 100 && !condition.getAsBoolean(); i++)
      Thread.sleep(50);
    Assert.assertTrue("timed out", condition.getAsBoolean());
  }

  private void get(String path, String armedCookie) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(app.getRootUrl() + path).openConnection();
    if (armedCookie != null)
      c.setRequestProperty("Cookie", AutoMode.COOKIE + "=" + armedCookie);
    try (InputStream in = c.getInputStream()) {
      in.readAllBytes();
    }
  }
}
