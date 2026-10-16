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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

  @BeforeEach
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

  @AfterEach
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
    auto.arm(new Trigger(List.of("GET /busy"), null, false, Duration.ofMillis(300), Duration.ofSeconds(10), " slow busy "));

    get("/hello", null);
    Assertions.assertTrue(sessions.list().isEmpty(), "not a matching endpoint");

    recordingInHandler = false;
    get("/busy", null);
    Assertions.assertTrue(recordingInHandler, "recording when the handler ran");
    Session s = sessions.list().get(0);
    Assertions.assertEquals("auto: GET /busy", s.getTrigger());
    Assertions.assertEquals("slow busy", s.getName(), "named by the trigger");

    await(() -> s.getState() == Session.State.STOPPED);
    Assertions.assertNull(auto.getTrigger(), "once: disarmed after the session");
    get("/busy", null);
    Assertions.assertEquals(1, sessions.list().size());

    // the request that triggered the session is in it, whole
    Assertions.assertEquals(1, s.getAnalysis().getOperations().stream()
                             .filter(r -> r.name().equals("GET /busy")).count());
  }

  @Test
  public void testAsyncOperationOfTheApi() throws Exception {
    auto.arm(new Trigger(List.of("quote"), null, false, Duration.ofMillis(300), Duration.ofSeconds(10)));

    Operation op = Operations.beginAsync("quote").detail("ACME");
    Assertions.assertNotNull(sessions.getRecording(), "recording before the operation runs");
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
    Assertions.assertEquals("auto: quote", s.getTrigger());
    await(() -> s.getState() == Session.State.STOPPED);

    // in it, whole, with the segment of the worker
    RecordedOperation r = s.getAnalysis().getOperations().stream()
                           .filter(o -> o.name().equals("quote")).findFirst().orElseThrow();
    Assertions.assertTrue(r.async());
    Assertions.assertEquals("ACME", r.detail());
    Assertions.assertEquals(1, s.getAnalysis().getSegments(r).size());
  }

  @Test
  public void testArmedBrowser() throws Exception {
    auto.arm(new Trigger(List.of(), "token", false, Duration.ofMillis(300), Duration.ofSeconds(10)));

    get("/hello", null);
    get("/hello", "other");
    Assertions.assertTrue(sessions.list().isEmpty(), "another client");

    get("/hello", "token");
    Assertions.assertEquals("auto: GET /hello, armed browser", sessions.list().get(0).getTrigger());
  }

  @Test
  public void testRepeatWithMaximumDuration() throws Exception {
    auto.arm(new Trigger(List.of("GET /hel*"), null, true, Duration.ofSeconds(10), Duration.ofMillis(400)));

    long end = System.currentTimeMillis() + 1500;
    while (System.currentTimeMillis() < end)
      get("/hello", null);

    Assertions.assertTrue(sessions.list().size() >= 2, "several sessions: " + sessions.list().size());
    for (Session s : sessions.list())
      if (s.getState() == Session.State.STOPPED)
        Assertions.assertTrue(s.getDuration().toMillis() < 1000, s.getDuration().toString());
    Assertions.assertNotNull(auto.getTrigger(), "still armed");
  }

  @Test
  public void testDisarmedDuringASession() throws Exception {
    auto.arm(new Trigger(List.of(), null, true, Duration.ofMillis(200), Duration.ofSeconds(10)));
    get("/hello", null);
    Session s = auto.getSession();
    Assertions.assertNotNull(s);
    auto.disarm();
    // it ends with the default idle time
    await(() -> s.getState() == Session.State.STOPPED);
  }

  @Test
  public void testFedRecordsAsArmedUntilAnOperationEnded() throws Exception {
    try (AutoMode fed = AutoMode.fed(sessions)) {
      fed.arm(new Trigger(List.of("work"), null, false, Duration.ofMillis(200), Duration.ofSeconds(10), "fed"));
      // operations are only known once they end: it records from now
      Session s = fed.getSession();
      Assertions.assertNotNull(s);
      Assertions.assertEquals("auto: waiting for work", s.getTrigger());
      Assertions.assertTrue(fed.isWaiting());
      fed.ended("other");
      Thread.sleep(500);
      Assertions.assertEquals(Session.State.RECORDING, s.getState(), "waits, whatever the idle time");

      fed.ended("work");
      Assertions.assertFalse(fed.isWaiting());
      for (int i = 0; i < 50 && s.getState() == Session.State.RECORDING; i++)
        Thread.sleep(50);
      Assertions.assertEquals(Session.State.STOPPED, s.getState(), "stopped once idle");
      Assertions.assertNull(fed.getTrigger(), "once");

      // disarmed while waiting: nothing to keep recording for
      fed.arm(new Trigger(List.of("work"), null, false, Duration.ofMillis(200), Duration.ofSeconds(10)));
      Session waiting = fed.getSession();
      fed.disarm();
      Assertions.assertEquals(Session.State.STOPPED, waiting.getState());
      Assertions.assertNull(fed.getSession());
    }
  }

  @Test
  public void testPatterns() {
    Trigger t = new Trigger(List.of("GET /api/*", "* /static/*.js"), null, false,
                            Trigger.DEFAULT_IDLE, Trigger.DEFAULT_MAX);
    Assertions.assertTrue(t.matches("GET /api/user/:id", null));
    Assertions.assertTrue(t.matches("HEAD /static/*splat.js", null));
    Assertions.assertFalse(t.matches("POST /api/user/:id", null));
    Assertions.assertFalse(Trigger.glob("GET /a.c").matcher("GET /abc").matches(), "regex characters are literal");
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; i < 100 && !condition.getAsBoolean(); i++)
      Thread.sleep(50);
    Assertions.assertTrue(condition.getAsBoolean(), "timed out");
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
