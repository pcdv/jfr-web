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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
    public static void burn(long millis) {
      long end = System.nanoTime() + millis * 1_000_000;
      Random random = new Random(1);
      while (System.nanoTime() < end) {
        int[] a = random.ints(10_000).toArray();
        Arrays.sort(a);
        sink += a[0];
      }
    }
  }

  @BeforeAll
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
  @AfterAll
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
    Map<String, Long> counts = analysis.getOperations()
                                       .stream()
                                       .collect(Collectors.groupingBy(RecordedOperation::name,
                                                                      Collectors.counting()));
    Assertions.assertEquals(Map.of("GET /busy/:id", 5L,
                               "GET /fast", 20L,
                               "GET /fail", 1L,
                               "GET (no route)", 1L),
                        counts);
  }

  @Test
  public void testOperationStats() {
    List<OperationStats> stats = OperationStats.of(analysis.getOperations());

    OperationStats busy = stats.get(0);
    Assertions.assertEquals("GET /busy/:id", busy.name(), "sorted by total time");
    Assertions.assertEquals(5, busy.count());
    Assertions.assertTrue(busy.p50Nanos() >= 200_000_000);
    Assertions.assertTrue(busy.maxNanos() >= busy.p99Nanos());
    Assertions.assertEquals(0, busy.errors());
    Assertions.assertEquals(5, busy.bytesOut());

    OperationStats fail = stats.stream().filter(s -> s.name().equals("GET /fail")).findFirst().orElseThrow();
    Assertions.assertEquals(1, fail.errors());
  }

  @Test
  public void testInfo() {
    RecordingInfo info = analysis.getInfo();
    Assertions.assertNotNull(info.getJvmVersion());
    Assertions.assertTrue(info.isEnabled(RecordingInfo.FLAK_REQUEST));
    Assertions.assertEquals(java.time.Duration.ofMillis(10), info.getPeriod(RecordingInfo.EXECUTION_SAMPLE));
    Assertions.assertEquals(java.time.Duration.ofMillis(20), info.getPeriod(RecordingInfo.NATIVE_SAMPLE));
    Assertions.assertTrue(info.getStart().isBefore(info.getEnd()));
  }

  @Test
  public void testTimeline() throws IOException {
    Timeline t = Timeline.of(analysis);
    Assertions.assertTrue(t.getBuckets() <= Timeline.MAX_BUCKETS);
    Assertions.assertEquals("GET /busy/:id", t.getSeries().keySet().iterator().next(), "the endpoint with the most time first");
    Assertions.assertNull(t.getOther(), "4 endpoints, all shown");

    // in flight per bucket, times the bucket, gives back the time of the requests
    double seconds = 0;
    for (double v : t.getSeries().get("GET /busy/:id"))
      seconds += v * t.getBucket().toNanos() / 1e9;
    double expected = analysis.getOperations().stream()
                              .filter(r -> r.name().equals("GET /busy/:id"))
                              .mapToLong(RecordedOperation::durationNanos).sum() / 1e9;
    Assertions.assertEquals(expected, seconds, expected * 0.001);
    Assertions.assertTrue(Arrays.stream(t.getJavaSamples()).sum() > 0);

    // zoomed in on 200 ms: it starts there, in the finest buckets
    java.time.Instant from = analysis.getInfo().getStart().plusMillis(200);
    Timeline zoomed = Timeline.of(analysis, from, from.plusMillis(200));
    Assertions.assertEquals(from, zoomed.getStart());
    Assertions.assertEquals(20, zoomed.getBuckets(), "10 ms buckets");
    Assertions.assertEquals(t.getSeries().keySet(), zoomed.getSeries().keySet(), "the same colors as the whole");

    Assertions.assertEquals(10_000_000, Timeline.bucketFor(1_000_000_000));
    Assertions.assertEquals(50_000_000, Timeline.bucketFor(10_000_000_000L), "10 s in 300 buckets: 33 ms at least");
    Assertions.assertEquals(1_000_000_000, Timeline.bucketFor(299_000_000_000L));
    Assertions.assertEquals(2_000_000_000, Timeline.bucketFor(301_000_000_000L));
  }

  @Test
  public void testFlameGraphRootedByOperation() throws IOException {
    String collapsed = flame(new FlameGraphs.Options().collapsed(true));

    // 1 s burnt in /busy, sampled every 10 ms. JFR drops the samples whose
    // stack it fails to walk, about half of them here
    long busy = weight(collapsed, "GET /busy/:id;", "Handlers.burn");
    Assertions.assertTrue(busy >= 200, collapsed);
    Assertions.assertEquals(0, weight(collapsed, "GET /fast;", "Handlers.burn"), "burnt nowhere else");
    Assertions.assertFalse(collapsed.contains(FlameGraphs.NO_OPERATION), "samples outside requests are left out");
  }

  @Test
  public void testFlameGraphOfOneOperation() throws IOException {
    String collapsed = flame(new FlameGraphs.Options()
                               .collapsed(true)
                               .operations(r -> r.name().equals("GET /fast")));
    Assertions.assertFalse(collapsed.contains("GET /busy"), collapsed);
  }

  @Test
  public void testFlameGraphWithSamplesOutsideRequests() throws IOException {
    String collapsed = flame(new FlameGraphs.Options().collapsed(true).outsideOperations(true));
    Assertions.assertTrue(collapsed.contains(FlameGraphs.NO_OPERATION + ";["));
    Assertions.assertTrue(collapsed.contains("GET /busy/:id;"));
  }

  @Test
  public void testFlameGraphMerged() throws IOException {
    String collapsed = flame(new FlameGraphs.Options().collapsed(true).merged(true));
    Assertions.assertFalse(collapsed.contains("GET /"), "no operation at the root");
    Assertions.assertEquals(weight(flame(new FlameGraphs.Options().collapsed(true)), "", "Handlers.burn"),
                        weight(collapsed, "", "Handlers.burn"), "the same samples");
  }

  @Test
  public void testFlameGraphReversed() throws IOException {
    String collapsed = flame(new FlameGraphs.Options().collapsed(true).reversed(true));
    Assertions.assertTrue(collapsed.lines().noneMatch(l -> l.startsWith("GET /")), collapsed);
    Assertions.assertTrue(collapsed.lines().anyMatch(l -> l.contains(";GET /busy/:id ")), "the operation last");
  }

  @Test
  public void testFlameGraphJavaOnly() throws IOException {
    String all = flame(new FlameGraphs.Options().collapsed(true));
    String java = flame(new FlameGraphs.Options().collapsed(true).javaOnly(true));
    Assertions.assertEquals(weight(all, "GET /busy/:id;", "Handlers.burn"),
                        weight(java, "GET /busy/:id;", "Handlers.burn"), "burnt in Java");
    Assertions.assertTrue(weight(java, "", "") <= weight(all, "", ""));
  }

  @Test
  public void testHtml() throws IOException {
    String html = flame(new FlameGraphs.Options().title("<b>"));
    Assertions.assertTrue(html.contains("<h1>&lt;b&gt;</h1>"));
    Assertions.assertTrue(html.contains("GET /busy/:id"));
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
