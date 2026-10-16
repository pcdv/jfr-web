package jfrweb.history;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import flak.App;
import flak.AppFactory;
import flak.Flak;
import flak.annotations.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OperationHistoryTest {

  /** A clock the test moves. */
  static final class TestClock extends Clock {
    volatile Instant now;

    TestClock(String iso) {
      now = Instant.parse(iso);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  public static class Handlers {
    @Route("/item/:id")
    public String item(String id) throws InterruptedException {
      if (id.startsWith("slow"))
        Thread.sleep(Integer.parseInt(id.substring(4)));
      return id;
    }

    @Route("/fail")
    public String fail() {
      throw new IllegalStateException("fail");
    }
  }

  private Path dir;
  private App app;
  private OperationHistory history;
  private final TestClock clock = new TestClock("2026-10-13T10:00:30Z");

  @BeforeEach
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("jfr-web-history");
    AppFactory factory = Flak.getFactory();
    factory.setLocalAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    app = factory.createApp("/api");
    app.scan(new Handlers());
    app.start();
  }

  @AfterEach
  public void tearDown() throws IOException {
    if (history != null)
      history.close();
    app.stop();
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  private OperationHistory.Builder builder() {
    return OperationHistory.builder().directory(dir).observe(app).clock(clock, false);
  }

  @Test
  public void testWritesAndReadsBack() throws Exception {
    history = builder().slowest(2).start();
    get("/item/a");
    get("/item/slow60");
    get("/item/slow30");
    get("/fail");
    awaitPending(4);
    history.flush(Instant.parse("2026-10-13T10:01:00Z"));

    clock.now = Instant.parse("2026-10-13T10:01:10Z");
    get("/item/b");
    awaitPending(1);
    history.flush(Instant.parse("2026-10-13T10:02:00Z"));
    history.flush(Instant.parse("2026-10-13T10:03:00Z"));

    Path file = dir.resolve("2026-10-13").resolve("10.jsonl");
    List<String> lines = Files.readAllLines(file);
    Assertions.assertEquals(3, lines.size(), "one line per interval, even without requests");
    Assertions.assertTrue(lines.get(0).startsWith(
      "{\"start\":\"2026-10-13T10:00:30Z\",\"end\":\"2026-10-13T10:01:00Z\",\"operations\":[{\"name\":\"GET /api/item/:id\",\"count\":3,"), lines.get(0));
    Assertions.assertTrue(lines.get(0).contains("\"histogram\":[["), "histogram, to add intervals up");
    Assertions.assertTrue(lines.get(2).endsWith("\"operations\":[],\"slowest\":[]}"), lines.get(2));

    HistoryReader r = HistoryReader.read(dir, Instant.parse("2026-10-13T10:00:00Z"), Instant.parse("2026-10-13T11:00:00Z"));
    Assertions.assertEquals(3, r.getIntervalCount());
    Assertions.assertEquals(Map.of("GET /api/item/:id", 4, "GET /api/fail", 1), r.counts());
    Assertions.assertEquals(1, r.getOperations().stream().filter(e -> e.name().equals("GET /api/fail"))
                            .findFirst().orElseThrow().errors());

    List<HistoryReader.Slow> slowest = r.getSlowest(null, 10);
    Assertions.assertEquals(3, slowest.size(), "2 kept by the first interval, 1 by the second");
    Assertions.assertEquals("GET /item/slow60", slowest.get(0).detail());
    Assertions.assertTrue(slowest.get(0).nanos() >= 60_000_000);
    Assertions.assertEquals(2, r.getSlowest("GET /api/item/:id", 2).size());

    HistoryReader second = HistoryReader.read(dir, Instant.parse("2026-10-13T10:01:30Z"), Instant.parse("2026-10-13T10:02:00Z"));
    Assertions.assertEquals(List.of(Instant.parse("2026-10-13T10:01:00Z")), second.starts(), "intervals overlapping the period");
  }

  @Test
  public void testHourOverIsGzippedAndStillRead() throws Exception {
    history = builder().start();
    get("/item/a");
    awaitPending(1);
    history.flush(Instant.parse("2026-10-13T10:59:00Z"));
    Path plain = dir.resolve("2026-10-13").resolve("10.jsonl");
    Assertions.assertTrue(Files.exists(plain));

    clock.now = Instant.parse("2026-10-13T11:00:20Z");
    history.flush(Instant.parse("2026-10-13T11:00:00Z"));
    history.flush(Instant.parse("2026-10-13T11:01:00Z"));
    Assertions.assertFalse(Files.exists(plain), "gzipped once the hour is over");
    Assertions.assertTrue(Files.exists(dir.resolve("2026-10-13").resolve("10.jsonl.gz")));

    HistoryReader r = HistoryReader.read(dir, Instant.parse("2026-10-13T10:00:00Z"), Instant.parse("2026-10-13T12:00:00Z"));
    Assertions.assertEquals(Map.of("GET /api/item/:id", 1), r.counts());
    Assertions.assertEquals(3, r.getIntervalCount());
  }

  @Test
  public void testScheduled() throws Exception {
    // the real clock and scheduler, intervals of a second on the second
    history = OperationHistory.builder().directory(dir).observe(app).interval(Duration.ofSeconds(1)).start();
    get("/item/a");
    Thread.sleep(2500);
    history.close();
    history = null;

    HistoryReader r = HistoryReader.read(dir, Instant.now().minusSeconds(60), Instant.now().plusSeconds(1));
    Assertions.assertTrue(r.getIntervalCount() >= 3, "an interval per second, and one when closing: " + r.getIntervalCount());
    Assertions.assertEquals(Map.of("GET /api/item/:id", 1), r.counts());
    for (Instant start : r.starts().subList(1, r.starts().size()))
      Assertions.assertEquals(0, start.getNano(), "on the second");
  }

  @Test
  public void testPurgeByAge() throws Exception {
    write("2026-10-01", "05.jsonl.gz", 100);
    write("2026-10-06", "05.jsonl.gz", 100);
    write("2026-10-12", "05.jsonl.gz", 100);
    history = builder().maxAge(Duration.ofDays(7)).start();
    Assertions.assertFalse(Files.exists(dir.resolve("2026-10-01")));
    Assertions.assertTrue(Files.exists(dir.resolve("2026-10-06")), "on the edge: kept");
    Assertions.assertTrue(Files.exists(dir.resolve("2026-10-12")));
  }

  @Test
  public void testPurgeBySize() throws Exception {
    write("2026-10-12", "05.jsonl.gz", 1000);
    write("2026-10-12", "06.jsonl.gz", 1000);
    write("2026-10-13", "09.jsonl.gz", 1000);
    write("2026-10-13", "10.jsonl", 1000);
    history = builder().maxSize(2500).start();

    List<String> left;
    try (Stream<Path> s = Files.walk(dir)) {
      left = s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace('\\', '/'))
              .sorted().collect(Collectors.toList());
    }
    Assertions.assertEquals(List.of("2026-10-13/09.jsonl.gz", "2026-10-13/10.jsonl"), left, "the oldest deleted, the hour in progress kept");
    Assertions.assertFalse(Files.exists(dir.resolve("2026-10-12")), "empty days deleted");
  }

  @Test
  public void testLineBeingWrittenIsSkipped() throws Exception {
    history = builder().start();
    history.flush(Instant.parse("2026-10-13T10:01:00Z"));
    Files.writeString(dir.resolve("2026-10-13").resolve("10.jsonl"), "{\"start\":\"2026-10-13T10:01",
                      java.nio.file.StandardOpenOption.APPEND);
    Assertions.assertEquals(1, HistoryReader.read(dir, Instant.parse("2026-10-13T10:00:00Z"),
                                              Instant.parse("2026-10-13T11:00:00Z")).getIntervalCount());
  }

  @Test
  public void testTimelineBucketsHoldWholeIntervals() throws Exception {
    clock.now = Instant.parse("2026-10-13T10:00:00Z");
    history = builder().interval(Duration.ofSeconds(10)).start();
    for (int s = 10; s <= 120; s += 10)
      history.flush(Instant.parse("2026-10-13T10:00:00Z").plusSeconds(s));

    HistoryReader.CountTimeline t = HistoryReader.read(dir, Instant.parse("2026-10-13T10:00:00Z"),
                                                       Instant.parse("2026-10-13T11:00:00Z")).getTimeline();
    // an hour in at most 300 buckets: 12 s at least; 15 s would hold 1 or 2
    // intervals of 10 s in turn
    Assertions.assertEquals(Duration.ofSeconds(30), t.bucket());
  }

  @Test
  public void testTimelineAfterPause() throws Exception {
    clock.now = Instant.parse("2026-10-13T10:00:00Z");
    history = builder().start();
    for (int m = 1; m <= 10; m++)
      history.flush(Instant.parse("2026-10-13T10:00:00Z").plusSeconds(60L * m));
    // written before the fix: an interval stretched over 8 hours of sleep,
    // its operations at its end
    clock.now = Instant.parse("2026-10-13T18:10:10Z");
    get("/item/a");
    awaitPending(1);
    history.flush(Instant.parse("2026-10-13T18:11:00Z"));
    // the scheduler late after another pause: the pause left out
    history.flush(Instant.parse("2026-10-13T18:12:00Z"), Instant.parse("2026-10-13T20:00:00Z"));
    history.flush(Instant.parse("2026-10-13T20:01:00Z"));

    HistoryReader.CountTimeline t = HistoryReader.read(dir, Instant.parse("2026-10-13T00:00:00Z"),
                                                       Instant.parse("2026-10-14T00:00:00Z")).getTimeline();
    // a day in at most 300 buckets, whole minutes: not one bucket for the day
    Assertions.assertEquals(Duration.ofMinutes(5), t.bucket());
    int afterSleep = (int) (Duration.ofHours(18).plusMinutes(10).toSeconds() / t.bucket().toSeconds());
    Assertions.assertEquals(1, t.series().get("GET /api/item/:id")[afterSleep], 0);
    Assertions.assertFalse(t.covered()[(int) (Duration.ofHours(19).toSeconds() / t.bucket().toSeconds())], "left out");
  }

  @Test
  public void testTimeline() throws Exception {
    history = builder().start();
    get("/item/a");
    get("/item/b");
    awaitPending(2);
    // a minute after another, as the scheduler does
    for (int m = 1; m <= 5; m++)
      history.flush(Instant.parse("2026-10-13T10:0" + m + ":00Z"));
    clock.now = Instant.parse("2026-10-13T10:05:10Z");
    get("/fail");
    awaitPending(1);
    history.flush(Instant.parse("2026-10-13T10:06:00Z"));
    // then stopped: no line from 10:06 on

    HistoryReader.CountTimeline t = HistoryReader.read(dir, Instant.parse("2026-10-13T10:00:00Z"),
                                                       Instant.parse("2026-10-13T10:10:00Z")).getTimeline();
    Assertions.assertEquals(Duration.ofMinutes(1), t.bucket(), "never finer than an interval, here a whole minute");
    Assertions.assertEquals(2, t.series().get("GET /api/item/:id")[0], 0);
    Assertions.assertTrue(t.seconds().get("GET /api/item/:id")[0] > 0, "the time they took too");
    Assertions.assertEquals(1, t.series().get("GET /api/fail")[(int) (Duration.ofMinutes(5).toSeconds() / t.bucket().toSeconds())], 0);
    Assertions.assertTrue(t.covered()[2]);
    Assertions.assertFalse(t.covered()[7], "not written then");
  }

  private void write(String day, String file, int size) throws IOException {
    Path d = Files.createDirectories(dir.resolve(day));
    byte[] content = new byte[size];
    if (file.endsWith(".gz")) {
      try (var out = new GZIPOutputStream(Files.newOutputStream(d.resolve(file)))) {
        out.write("{}\n".getBytes(StandardCharsets.UTF_8));
      }
      // padded to the size wanted: only the size matters here
      Files.write(d.resolve(file), content, java.nio.file.StandardOpenOption.APPEND);
    }
    else {
      Files.write(d.resolve(file), content);
    }
  }

  private void awaitPending(int n) throws InterruptedException {
    // the listener runs once the response is sent, which the client may see first
    for (int i = 0; i < 100 && history.pending() < n; i++)
      Thread.sleep(20);
    Assertions.assertEquals(n, history.pending());
  }

  private void get(String path) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(app.getRootUrl() + path).openConnection();
    try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
      if (in != null)
        in.readAllBytes();
    }
  }
}
