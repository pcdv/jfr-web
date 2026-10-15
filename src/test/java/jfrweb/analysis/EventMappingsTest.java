package jfrweb.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jfrweb.session.Session;
import jfrweb.session.Sessions;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

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

  private Path dir;

  @Before
  public void setUp() throws Exception {
    dir = Files.createTempDirectory("jfr-web-mappings");
  }

  @After
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
    Assert.assertEquals(List.of(new EventMapping("test.RpcCall", "{service}.{method}", "{requestId}", "error")), loaded);

    // the session enables the event, off by default
    Sessions sessions = new Sessions(dir, Sessions.defaultSettings(), false);
    Session s = sessions.start("manual");
    call("place", null);
    call("place", null);
    call("cancel", "no such order");
    sessions.stop(s.getId());

    Analysis a = s.getAnalysis();
    Assert.assertTrue(a.getInfo().hasOperationEvents());
    List<RecordedOperation> ops = a.getOperations().stream()
                                   .sorted(Comparator.comparing(RecordedOperation::name))
                                   .toList();
    Assert.assertEquals(List.of("orders.cancel", "orders.place", "orders.place"),
                        ops.stream().map(RecordedOperation::name).toList());
    RecordedOperation cancel = ops.get(0);
    Assert.assertEquals("r-cancel", cancel.detail());
    Assert.assertTrue(cancel.failed());
    Assert.assertEquals("no such order", cancel.error());
    Assert.assertFalse(ops.get(1).failed());
    Assert.assertEquals(Thread.currentThread().getName(), cancel.threadName());
    Assert.assertTrue("its duration", cancel.durationNanos() >= 15_000_000);
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
    Assert.assertEquals("a field it does not have", "orders/null", op.name());
    Assert.assertNull(op.detail());
    Assert.assertFalse(op.failed());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadKey() throws Exception {
    Path file = dir.resolve("bad.properties");
    Files.writeString(file, "test.RpcCall.label = {method}\n");
    EventMappings.load(file);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNoName() throws Exception {
    Path file = dir.resolve("bad.properties");
    Files.writeString(file, "test.RpcCall.detail = {method}\n");
    EventMappings.load(file);
  }
}
