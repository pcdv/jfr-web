package jfrweb.live;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import flak.App;
import flak.AppFactory;
import flak.Flak;
import flak.annotations.Route;
import jfrweb.analysis.OperationStats;
import jfrweb.ops.Operation;
import jfrweb.ops.Operations;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LiveStatsTest {

  private final AtomicLong minute = new AtomicLong(1000);
  private final LiveStats live = new LiveStats(minute::get);
  private App app;

  public static class Handlers {
    @Route("/item/:id")
    public String item(String id) throws InterruptedException {
      Thread.sleep(id.equals("slow") ? 50 : 1);
      return id;
    }

    @Route("/fail")
    public String fail() {
      throw new IllegalStateException("fail");
    }
  }

  @Before
  public void setUp() throws IOException {
    AppFactory factory = Flak.getFactory();
    factory.setLocalAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    app = factory.createApp("/api");
    app.scan(new Handlers());
    app.start();
    live.observe(app);
  }

  @After
  public void tearDown() {
    live.close();
    app.stop();
  }

  @Test
  public void testOperationsOfTheApi() {
    try (Operation op = Operations.begin("live-test job")) {
      op.detail("one");
    }
    Operations.beginAsync("live-test job").failed("no luck").close();
    OperationStats job = find(live.getOperations(60), "live-test job");
    Assert.assertEquals(2, job.count());
    Assert.assertEquals(1, job.errors());
  }

  @Test
  public void testGroupedByName() throws Exception {
    for (int i = 0; i < 9; i++)
      get("/item/" + i);
    get("/item/slow");
    get("/fail");
    get("/nothing");

    List<OperationStats> stats = awaitCount(12);
    OperationStats item = stats.get(0);
    Assert.assertEquals("sorted by total time", "GET /api/item/:id", item.name());
    Assert.assertEquals(10, item.count());
    Assert.assertTrue(item.maxNanos() >= 50_000_000);
    Assert.assertTrue("p50 is not the slow one", item.p50Nanos() < 50_000_000);
    Assert.assertEquals("p99 is the slow one", item.maxNanos(), item.p99Nanos());
    Assert.assertTrue(item.bytesOut() > 0);

    OperationStats fail = find(stats, "GET /api/fail");
    Assert.assertEquals(1, fail.errors());
    Assert.assertEquals(1, find(stats, "GET (no route)").count());
  }

  @Test
  public void testWindows() throws Exception {
    get("/item/a");
    awaitCount(1);
    minute.addAndGet(3);
    get("/item/b");
    awaitCount(2);

    Assert.assertEquals("the current minute only", 1, live.getOperations(1).get(0).count());
    Assert.assertEquals(2, live.getOperations(5).get(0).count());

    minute.addAndGet(60);
    Assert.assertTrue("older than an hour", live.getOperations(60).isEmpty());
  }

  @Test
  public void testStopsObserving() throws Exception {
    live.close();
    get("/item/a");
    Assert.assertFalse(live.isObserving());
    Assert.assertTrue(live.getOperations(60).isEmpty());
  }

  /**
   * The listener runs once the response is sent, which the client may see
   * first.
   */
  private List<OperationStats> awaitCount(int count) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      List<OperationStats> stats = live.getOperations(60);
      if (stats.stream().mapToInt(OperationStats::count).sum() >= count)
        return stats;
      Thread.sleep(20);
    }
    throw new AssertionError("Not " + count + " requests: " + live.getOperations(60));
  }

  private static OperationStats find(List<OperationStats> stats, String endpoint) {
    return stats.stream().filter(s -> s.name().equals(endpoint)).findFirst()
                .orElseThrow(() -> new AssertionError("No " + endpoint + " in " + stats));
  }

  private void get(String path) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(app.getRootUrl() + path).openConnection();
    try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
      if (in != null)
        in.readAllBytes();
    }
  }
}
