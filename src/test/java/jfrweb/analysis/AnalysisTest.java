package jfrweb.analysis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import flak.App;
import flak.AppFactory;
import flak.Flak;
import flak.annotations.Route;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Records a flak app serving requests, then analyzes the recording: the
 * samples taken while a handler burns CPU must land under its endpoint.
 */
public class AnalysisTest {

  private static Path file;
  private static Analysis analysis;

  public static class Handlers {
    static volatile long sink;

    @Route("/busy/:id")
    public String busy(String id) {
      burn(200);
      return id;
    }

    @Route("/fast")
    public String fast() {
      return "ok";
    }

    @Route("/fail")
    public String fail() {
      throw new IllegalStateException("fail");
    }

    /**
     * Sorts arrays: JFR cannot walk the stack of a tight loop, and would drop
     * nearly every sample of one.
     */
    static void burn(long millis) {
      long end = System.nanoTime() + millis * 1_000_000;
      Random random = new Random(1);
      while (System.nanoTime() < end) {
        int[] a = random.ints(10_000).toArray();
        Arrays.sort(a);
        sink += a[0];
      }
    }
  }

  @BeforeClass
  public static void record() throws Exception {
    file = Files.createTempFile("jfr-web", ".jfr");

    AppFactory factory = Flak.getFactory();
    factory.setLocalAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    App app = factory.createApp();
    app.scan(new Handlers());
    app.start();
    try (Recording rec = new Recording(Configuration.getConfiguration("profile"))) {
      rec.enable("flak.Request");
      rec.start();
      for (int i = 0; i < 5; i++)
        get(app, "/busy/" + i);
      for (int i = 0; i < 20; i++)
        get(app, "/fast");
      get(app, "/fail");
      get(app, "/unknown");
      rec.stop();
      rec.dump(file);
    }
    finally {
      app.stop();
    }

    analysis = Analysis.load(file);
  }

  /**
   * -Djfrweb.keepRecording=<path> keeps the recording there, e.g. to try the
   * command line on it.
   */
  @AfterClass
  public static void cleanUp() throws IOException {
    String keep = System.getProperty("jfrweb.keepRecording");
    if (keep != null)
      Files.move(file, Path.of(keep), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    else
      Files.deleteIfExists(file);
  }

  private static int get(App app, String path) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(app.getRootUrl() + path).openConnection();
    int status = c.getResponseCode();
    try (InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream()) {
      if (in != null)
        in.readAllBytes();
    }
    return status;
  }

  @Test
  public void testRequests() {
    Map<String, Long> counts = analysis.getRequests()
                                       .stream()
                                       .collect(Collectors.groupingBy(RecordedRequest::endpoint,
                                                                      Collectors.counting()));
    Assert.assertEquals(Map.of("GET /busy/:id", 5L,
                               "GET /fast", 20L,
                               "GET /fail", 1L,
                               "GET (no route)", 1L),
                        counts);
  }

  @Test
  public void testEndpointStats() {
    List<EndpointStats> stats = EndpointStats.of(analysis.getRequests());

    EndpointStats busy = stats.get(0);
    Assert.assertEquals("sorted by total time", "GET /busy/:id", busy.endpoint());
    Assert.assertEquals(5, busy.count());
    Assert.assertTrue(busy.p50Nanos() >= 200_000_000);
    Assert.assertTrue(busy.maxNanos() >= busy.p99Nanos());
    Assert.assertEquals(0, busy.errors());
    Assert.assertEquals(5, busy.bytesOut());

    EndpointStats fail = stats.stream().filter(s -> s.endpoint().equals("GET /fail")).findFirst().orElseThrow();
    Assert.assertEquals(1, fail.errors());
  }

  @Test
  public void testInfo() {
    RecordingInfo info = analysis.getInfo();
    Assert.assertNotNull(info.getJvmVersion());
    Assert.assertTrue(info.isEnabled(RecordingInfo.REQUEST));
    Assert.assertEquals(java.time.Duration.ofMillis(10), info.getPeriod(RecordingInfo.EXECUTION_SAMPLE));
    Assert.assertEquals(java.time.Duration.ofMillis(20), info.getPeriod(RecordingInfo.NATIVE_SAMPLE));
    Assert.assertTrue(info.getStart().isBefore(info.getEnd()));
  }

  @Test
  public void testTimeline() throws IOException {
    Timeline t = Timeline.of(analysis);
    Assert.assertTrue(t.getBuckets() <= Timeline.MAX_BUCKETS);
    Assert.assertEquals("the endpoint with the most time first",
                        "GET /busy/:id", t.getSeries().keySet().iterator().next());
    Assert.assertNull("4 endpoints, all shown", t.getOther());

    // in flight per bucket, times the bucket, gives back the time of the requests
    double seconds = 0;
    for (double v : t.getSeries().get("GET /busy/:id"))
      seconds += v * t.getBucket().toNanos() / 1e9;
    double expected = analysis.getRequests().stream()
                              .filter(r -> r.endpoint().equals("GET /busy/:id"))
                              .mapToLong(RecordedRequest::durationNanos).sum() / 1e9;
    Assert.assertEquals(expected, seconds, expected * 0.001);
    Assert.assertTrue(Arrays.stream(t.getJavaSamples()).sum() > 0);

    // zoomed in on 200 ms: it starts there, in the finest buckets
    java.time.Instant from = analysis.getInfo().getStart().plusMillis(200);
    Timeline zoomed = Timeline.of(analysis, from, from.plusMillis(200));
    Assert.assertEquals(from, zoomed.getStart());
    Assert.assertEquals("10 ms buckets", 20, zoomed.getBuckets());
    Assert.assertEquals("the same colors as the whole", t.getSeries().keySet(), zoomed.getSeries().keySet());

    Assert.assertEquals(10_000_000, Timeline.bucketFor(1_000_000_000));
    Assert.assertEquals("10 s in 300 buckets: 33 ms at least", 50_000_000, Timeline.bucketFor(10_000_000_000L));
    Assert.assertEquals(1_000_000_000, Timeline.bucketFor(299_000_000_000L));
    Assert.assertEquals(2_000_000_000, Timeline.bucketFor(301_000_000_000L));
  }

  @Test
  public void testFlameGraphRootedByEndpoint() throws IOException {
    String collapsed = flame(new FlameGraphs.Options().collapsed(true));

    // 1 s burnt in /busy, sampled every 10 ms. JFR drops the samples whose
    // stack it fails to walk, about half of them here
    long busy = weight(collapsed, "GET /busy/:id;", "Handlers.burn");
    Assert.assertTrue(collapsed, busy >= 200);
    Assert.assertEquals("burnt nowhere else", 0, weight(collapsed, "GET /fast;", "Handlers.burn"));
    Assert.assertFalse("samples outside requests are left out", collapsed.contains(FlameGraphs.NO_REQUEST));
  }

  @Test
  public void testFlameGraphOfOneEndpoint() throws IOException {
    String collapsed = flame(new FlameGraphs.Options()
                               .collapsed(true)
                               .requests(r -> r.endpoint().equals("GET /fast")));
    Assert.assertFalse(collapsed, collapsed.contains("GET /busy"));
  }

  @Test
  public void testFlameGraphWithSamplesOutsideRequests() throws IOException {
    String collapsed = flame(new FlameGraphs.Options().collapsed(true).outsideRequests(true));
    Assert.assertTrue(collapsed.contains(FlameGraphs.NO_REQUEST + ";["));
    Assert.assertTrue(collapsed.contains("GET /busy/:id;"));
  }

  @Test
  public void testHtml() throws IOException {
    String html = flame(new FlameGraphs.Options().title("<b>"));
    Assert.assertTrue(html.contains("<h1>&lt;b&gt;</h1>"));
    Assert.assertTrue(html.contains("GET /busy/:id"));
  }

  private static String flame(FlameGraphs.Options o) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FlameGraphs.write(analysis, o, new PrintStream(out, true, StandardCharsets.UTF_8));
    return out.toString(StandardCharsets.UTF_8);
  }

  /**
   * Sums the weights of the collapsed stacks with that root and that frame.
   */
  private static long weight(String collapsed, String root, String frame) {
    return collapsed.lines()
                    .filter(l -> l.startsWith(root) && l.contains(frame))
                    .mapToLong(l -> Long.parseLong(l.substring(l.lastIndexOf(' ') + 1)))
                    .sum();
  }
}
