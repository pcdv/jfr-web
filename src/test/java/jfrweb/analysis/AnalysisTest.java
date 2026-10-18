package jfrweb.analysis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import flak.App;
import flak.AppFactory;
import flak.Flak;
import flak.annotations.Route;
import jfrweb.json.Json;
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

  /**
   * The profile, read back, gives every stack of the flame graphs with its
   * weight, the samples outside operations included.
   * <p>
   * Frames compare by name: async-profiler merges a method's frames whatever
   * their type (a method interpreted, then compiled), and counts the types
   * merged. The profile keeps the type of each.
   */
  @Test
  public void testProfileHoldsTheStacksOfTheFlameGraphs() throws IOException {
    Map<String, Long> expected = new TreeMap<>();
    for (String line : flame(new FlameGraphs.Options().collapsed(true).outsideOperations(true)).split("\n")) {
      int space = line.lastIndexOf(' ');
      // its marks of type: _[j] compiled, _[i] inlined, _[0] interpreted
      expected.merge(line.substring(0, space).replaceAll("_\\[[ij0]\\](?=;|$)", ""),
                     Long.parseLong(line.substring(space + 1)), Long::sum);
    }
    Assertions.assertTrue(expected.keySet().stream().anyMatch(s -> s.startsWith("GET /busy/:id;")), "samples to compare");
    Map<String, Object> profile = profile();
    Map<String, Long> actual = collapse(profile);
    // the stacks that differ only, readable among hundreds
    Map<String, String> differences = new TreeMap<>();
    for (String stack : new TreeSet<>(expected.keySet()) {{ addAll(actual.keySet()); }})
      if (!Objects.equals(expected.get(stack), actual.get(stack)))
        differences.put(stack, expected.get(stack) + " expected, " + actual.get(stack) + " in the profile");
    Assertions.assertEquals(Map.of(), differences);

    // the types: a request handled first runs interpreted, a second burnt
    // sorting gets compiled
    List<?> stacks = (List<?>) profile.get("stacks");
    Set<Integer> types = new TreeSet<>();
    for (int i = 1; i < stacks.size(); i += 2)
      types.add((int) ((Long) stacks.get(i) & 7));
    Assertions.assertTrue(types.containsAll(List.of(ProfileWriter.COMPILED, ProfileWriter.INTERPRETED)),
                          types.toString());
  }

  @Test
  public void testProfileFormat() throws IOException {
    Map<String, Object> p = profile();
    Assertions.assertEquals(1L, p.get("version"));

    List<String> strings = strings(p);
    Assertions.assertEquals(new TreeSet<>(strings).stream().toList(), strings, "sorted, each once");
    Assertions.assertTrue(strings.contains("jfrweb.analysis.AnalysisTest$Handlers.burn"), strings.toString());
    // front-coded: the prefix shared with the previous name goes in one character
    int codedLength = ((List<?>) p.get("strings")).stream().mapToInt(s -> ((String) s).length()).sum();
    int length = strings.stream().mapToInt(String::length).sum();
    Assertions.assertTrue(codedLength < length / 2, codedLength + " of " + length);

    List<?> stacks = (List<?>) p.get("stacks");
    long previous = -1;
    for (int i = 0; i < stacks.size(); i += 2) {
      long depth = (Long) stacks.get(i);
      Assertions.assertTrue(depth <= previous + 1, "depth first, each node after its parent");
      previous = depth;
    }

    Map<?, ?> samples = (Map<?, ?>) p.get("samples");
    Assertions.assertTrue(((List<?>) samples.get("time")).stream().allMatch(t -> (Long) t >= 0), "sorted by time");
    for (String column : List.of("stack", "thread", "operation", "kind"))
      Assertions.assertEquals(((List<?>) samples.get("time")).size(), ((List<?>) samples.get(column)).size(), column);

    // an operation is known by its index in the analysis, as in the API
    List<?> operations = (List<?>) p.get("operations");
    Assertions.assertEquals(analysis.getOperations().size(), operations.size());
    for (int i = 0; i < operations.size(); i++)
      Assertions.assertEquals(analysis.getOperations().get(i).name(), strings.get((int) (long) (Long) operations.get(i)));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> profile() throws IOException {
    StringWriter out = new StringWriter();
    ProfileWriter.write(analysis, out);
    return (Map<String, Object>) Json.parse(out.toString());
  }

  /** The strings of a profile, front coding undone. */
  private static List<String> strings(Map<String, Object> p) {
    List<String> strings = new ArrayList<>();
    String previous = "";
    for (Object o : (List<?>) p.get("strings")) {
      String s = (String) o;
      previous = previous.substring(0, s.charAt(0) - 32) + s.substring(1);
      strings.add(previous);
    }
    return strings;
  }

  /**
   * The samples of a profile as FlameGraphs writes collapsed stacks: rooted by
   * operation, or by thread outside any; frames by name only.
   */
  private static Map<String, Long> collapse(Map<String, Object> p) {
    List<String> strings = strings(p);
    List<?> stacks = (List<?>) p.get("stacks");
    int n = stacks.size() / 2;
    int[] parent = new int[n];
    String[] frame = new String[n];
    int[] last = new int[256];
    for (int i = 0; i < n; i++) {
      int depth = (int) (long) (Long) stacks.get(2 * i);
      int f = (int) (long) (Long) stacks.get(2 * i + 1);
      parent[i] = depth == 0 ? -1 : last[depth - 1];
      last[depth] = i;
      frame[i] = strings.get(f >>> 3);
    }
    List<?> weights = (List<?>) p.get("weights");
    List<?> threads = (List<?>) p.get("threads");
    List<?> operations = (List<?>) p.get("operations");
    Map<?, ?> samples = (Map<?, ?>) p.get("samples");

    Map<String, Long> collapsed = new TreeMap<>();
    int count = ((List<?>) samples.get("time")).size();
    for (int i = 0; i < count; i++) {
      int op = (int) (long) (Long) ((List<?>) samples.get("operation")).get(i);
      String root;
      if (op >= 0) {
        root = strings.get((int) (long) (Long) operations.get(op));
      }
      else {
        List<?> t = (List<?>) threads.get((int) (long) (Long) ((List<?>) samples.get("thread")).get(i));
        root = FlameGraphs.NO_OPERATION + ";[" + strings.get((int) (long) (Long) t.get(0)) + " tid=" + t.get(1) + "]";
      }
      StringBuilder path = new StringBuilder();
      for (int node = (int) (long) (Long) ((List<?>) samples.get("stack")).get(i); node >= 0; node = parent[node])
        path.insert(0, ";" + frame[node]);
      long weight = (Long) weights.get((int) (long) (Long) ((List<?>) samples.get("kind")).get(i));
      collapsed.merge(root + path, weight, Long::sum);
    }
    return collapsed;
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
