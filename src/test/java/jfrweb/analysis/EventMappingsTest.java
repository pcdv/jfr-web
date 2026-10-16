package jfrweb.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import jdk.jfr.Configuration;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jfrweb.auto.AutoMode;
import jfrweb.auto.Trigger;
import jfrweb.live.LiveStats;
import jfrweb.ops.MappedOperations;
import jfrweb.session.Session;
import jfrweb.session.Sessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static jfrweb.analysis.AnalysisTest.Handlers.burn;

/**
 * Operations read from an event of the application, which jfr-web does not
 * know.
 */
public class EventMappingsTest {

  /** What an RPC framework could record for each call: off by default. */
  @Name("test.RpcCall")
  @Enabled(false)
  static class RpcCall extends Event {
    String service;
    String method;
    String requestId;
    String error;
  }

  /** Quarkus' event for a REST request, its fields at least. */
  @Name("quarkus.Rest")
  static class QuarkusRest extends Event {
    String traceId;
    String httpMethod;
    String uri;
    String resourceClass;
    String resourceMethod;
  }

  /** The JUnit Platform's event for a test, its fields at least. */
  @Name("org.junit.TestExecution")
  static class JUnitTest extends Event {
    String uniqueId;
    String displayName;
    String result;
    Class<?> exceptionClass;
    String exceptionMessage;
  }

  private Path dir;

  @BeforeEach
  public void setUp() throws Exception {
    dir = Files.createTempDirectory("jfr-web-mappings");
  }

  @AfterEach
  public void tearDown() throws Exception {
    EventMappings.unregister("test.RpcCall");
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  private static void call(String method, String error) {
    RpcCall c = new RpcCall();
    c.begin();
    burn(20);
    c.service = "orders";
    c.method = method;
    c.requestId = "r-" + method;
    c.error = error;
    c.commit();
  }

  @Test
  public void testSessionsRecordAndReadMappedEvents() throws Exception {
    Path file = dir.resolve("operation-events.properties");
    Files.writeString(file, String.join("\n",
      "# an RPC framework's own event",
      "test.RpcCall.name = {service}.{method}",
      "test.RpcCall.detail = {requestId}",
      "test.RpcCall.failed = error"));
    List<EventMapping> loaded = EventMappings.load(file);
    Assertions.assertEquals(List.of(new EventMapping("test.RpcCall", "{service}.{method}", "{requestId}", "error")), loaded);

    // the session enables the event, off by default
    Sessions sessions = new Sessions(dir, Sessions.defaultSettings(), false);
    Session s = sessions.start("manual");
    call("place", null);
    call("place", null);
    call("cancel", "no such order");
    sessions.stop(s.getId());

    Analysis a = s.getAnalysis();
    Assertions.assertTrue(a.getInfo().hasOperationEvents());
    List<RecordedOperation> ops = a.getOperations().stream()
                                   .sorted(Comparator.comparing(RecordedOperation::name))
                                   .toList();
    Assertions.assertEquals(List.of("orders.cancel", "orders.place", "orders.place"),
                        ops.stream().map(RecordedOperation::name).toList());
    RecordedOperation cancel = ops.get(0);
    Assertions.assertEquals("r-cancel", cancel.detail());
    Assertions.assertTrue(cancel.failed());
    Assertions.assertEquals("no such order", cancel.error());
    Assertions.assertFalse(ops.get(1).failed());
    Assertions.assertEquals(Thread.currentThread().getName(), cancel.threadName());
    Assertions.assertTrue(cancel.durationNanos() >= 15_000_000, "its duration");
  }

  @Test
  public void testEmbeddedLiveStatsAndAutoMode() throws Exception {
    Sessions sessions = new Sessions(dir, Sessions.defaultSettings(), false);
    try (LiveStats live = new LiveStats();
         AutoMode auto = new AutoMode(sessions)) {
      EventMappings.register(new EventMapping("test.RpcCall", "{service}.{method}", null, "error"));
      Assertions.assertTrue(live.isObserving(), "an operation may come");

      // known once over: the session records as armed, until one has ended
      auto.arm(new Trigger(List.of("orders.*"), null, false, Duration.ofMillis(300), Duration.ofSeconds(10)));
      Session s = auto.getSession();
      Assertions.assertNotNull(s);
      Assertions.assertTrue(auto.isWaiting());

      call("place", null);
      call("cancel", "no such order");
      // streamed, about a second later
      long end = System.nanoTime() + 10_000_000_000L;
      while (live.getOperations(1).size() < 2 && System.nanoTime() < end)
        Thread.sleep(50);
      List<OperationStats> ops = live.getOperations(1).stream()
                                     .sorted(Comparator.comparing(OperationStats::name)).toList();
      Assertions.assertEquals(List.of("orders.cancel", "orders.place"), ops.stream().map(OperationStats::name).toList());
      Assertions.assertEquals(1, ops.get(0).errors());

      while (s.getState() == Session.State.RECORDING && System.nanoTime() < end)
        Thread.sleep(50);
      Assertions.assertEquals(Session.State.STOPPED, s.getState(), "stopped once idle");
      Assertions.assertTrue(s.getAnalysis().getOperations().stream().anyMatch(o -> o.name().equals("orders.place")));
    }
    finally {
      sessions.close();
    }
  }

  private static void test(String name, Class<?> exception) {
    JUnitTest t = new JUnitTest();
    t.begin();
    t.uniqueId = "[engine:junit-jupiter]/[class:com.acme.OrderTest]/[method:" + name + "]";
    t.displayName = name;
    t.result = exception == null ? "SUCCESSFUL" : "FAILED";
    t.exceptionClass = exception;
    t.commit();
  }

  @Test
  public void testBuiltIn() throws Exception {
    Path f = dir.resolve("r.jfr");
    // as -XX:StartFlightRecording records them, on by default
    try (Recording r = new Recording(Configuration.getConfiguration("default"))) {
      r.start();
      QuarkusRest q = new QuarkusRest();
      q.begin();
      q.httpMethod = "GET";
      q.uri = "/orders/12";
      q.resourceClass = "com.acme.OrderResource";
      q.resourceMethod = "get";
      q.commit();
      test("place()", null);
      test("cancel()", AssertionError.class);
      r.stop();
      r.dump(f);
    }
    Analysis a = Analysis.load(f);
    Assertions.assertTrue(a.getInfo().hasOperationEvents());
    List<RecordedOperation> ops = a.getOperations().stream()
                                   .sorted(Comparator.comparing(RecordedOperation::name))
                                   .toList();
    Assertions.assertEquals(List.of("cancel()", "com.acme.OrderResource.get", "place()"),
                        ops.stream().map(RecordedOperation::name).toList());
    Assertions.assertEquals("GET /orders/12", ops.get(1).detail());
    Assertions.assertFalse(ops.get(1).failed());
    Assertions.assertTrue(ops.get(0).failed());
    Assertions.assertEquals("java.lang.AssertionError", ops.get(0).error());
    Assertions.assertFalse(ops.get(2).failed());
    Assertions.assertNull(ops.get(2).error());
  }

  @Test
  public void testBuiltInNotStreamed() throws Exception {
    try (LiveStats live = new LiveStats()) {
      Assertions.assertFalse(MappedOperations.isActive(), "sessions would start as armed");
    }
  }

  @Test
  public void testUnknownFieldAndBooleanFailure() throws Exception {
    EventMappings.register(new EventMapping("test.RpcCall", "{service}/{nope}", null, null));
    Path f = dir.resolve("r.jfr");
    try (Recording r = new Recording()) {
      r.enable("test.RpcCall");
      r.start();
      call("place", null);
      r.stop();
      r.dump(f);
    }
    RecordedOperation op = Analysis.load(f).getOperations().get(0);
    Assertions.assertEquals("orders/null", op.name(), "a field it does not have");
    Assertions.assertNull(op.detail());
    Assertions.assertFalse(op.failed());
  }

  @Test
  public void testBadKey() throws Exception {
    Path file = dir.resolve("bad.properties");
    Files.writeString(file, "test.RpcCall.label = {method}\n");
    Assertions.assertThrows(IllegalArgumentException.class, () -> EventMappings.load(file));
  }

  @Test
  public void testNoName() throws Exception {
    Path file = dir.resolve("bad.properties");
    Files.writeString(file, "test.RpcCall.detail = {method}\n");
    Assertions.assertThrows(IllegalArgumentException.class, () -> EventMappings.load(file));
  }
}
