package jfrweb.snapshot;

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
import java.util.stream.Stream;

import flak.App;
import flak.AppFactory;
import flak.Flak;
import flak.annotations.Route;
import jfrweb.analysis.Analysis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SnapshotsTest {

  private Path dir;
  private App app;
  private Snapshots snapshots;

  public static class Handlers {
    @Route("/item/:id")
    public String item(String id) {
      return id;
    }

    @Route("/fail")
    public String fail() {
      throw new IllegalStateException("fail");
    }
  }

  @BeforeEach
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("jfr-web-snapshots");
    AppFactory factory = Flak.getFactory();
    factory.setLocalAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    app = factory.createApp();
    app.scan(new Handlers());
    app.start();
  }

  @AfterEach
  public void tearDown() throws IOException {
    if (snapshots != null)
      snapshots.close();
    app.stop();
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  @Test
  public void testHoldsWhatCameBefore() throws Exception {
    snapshots = Snapshots.builder().directory(dir).observe(app).start();
    for (int i = 0; i < 5; i++)
      get("/item/" + i);

    // saved after the requests: the rolling recording had them already
    Snapshots.Snapshot s = snapshots.save("saved by hand");
    Assertions.assertTrue(Files.size(s.file()) > 0);
    Assertions.assertEquals("saved by hand", s.reason());
    Assertions.assertEquals(Duration.ofMinutes(5), Duration.between(s.start(), s.end()));
    Assertions.assertTrue(Files.readString(dir.resolve(s.file().getFileName().toString().replace(".jfr", ".json")))
                           .contains("\"reason\":\"saved by hand\""), "the reason next to the file");

    Analysis a = Analysis.load(s.file());
    Assertions.assertEquals(5, a.getOperations().stream().filter(r -> r.name().equals("GET /item/:id")).count());
  }

  @Test
  public void testAutomaticSnapshotsAreSpaced() throws Exception {
    snapshots = Snapshots.builder().directory(dir).aftermath(Duration.ZERO).minGap(Duration.ofHours(1)).start();
    List<Snapshots.Snapshot> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    snapshots.addListener(seen::add);

    snapshots.trigger("first");
    snapshots.trigger("too soon");
    for (int i = 0; i < 100 && seen.isEmpty(); i++)
      Thread.sleep(50);
    Thread.sleep(300);
    Assertions.assertEquals(1, seen.size());
    Assertions.assertEquals("first", seen.get(0).reason());
  }

  @Test
  public void testErrorBurstTakesASnapshotByItself() throws Exception {
    snapshots = Snapshots.builder().directory(dir).aftermath(Duration.ZERO).observe(app).start();
    List<Snapshots.Snapshot> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    snapshots.addListener(seen::add);

    for (int i = 0; i < 5; i++)
      get("/fail");
    for (int i = 0; i < 100 && seen.isEmpty(); i++)
      Thread.sleep(50);
    Assertions.assertEquals(1, seen.size());
    Assertions.assertTrue(seen.get(0).reason().startsWith("5 errors in a minute"), seen.get(0).reason());
    Assertions.assertEquals(5,
                        Analysis.load(seen.get(0).file()).getOperations().stream().filter(r -> r.status() == 500).count(), "the failing requests are in it");
  }

  @Test
  public void testPurgeAndRestart() throws Exception {
    snapshots = Snapshots.builder().directory(dir).maxCount(2).start();
    Snapshots.Snapshot first = snapshots.save("1");
    snapshots.save("2");
    snapshots.save("3");
    Assertions.assertEquals(List.of("2", "3"), snapshots.list().stream().map(Snapshots.Snapshot::reason).toList());
    Assertions.assertFalse(Files.exists(first.file()));
    snapshots.close();

    // after a restart, as after an incident: the snapshots are still there
    snapshots = Snapshots.builder().directory(dir).start();
    Assertions.assertEquals(List.of("2", "3"), snapshots.list().stream().map(Snapshots.Snapshot::reason).toList());
  }

  private void get(String path) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(app.getRootUrl() + path).openConnection();
    try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
      if (in != null)
        in.readAllBytes();
    }
  }
}
