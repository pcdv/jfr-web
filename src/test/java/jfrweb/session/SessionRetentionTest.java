package jfrweb.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sessions of a profiler do not pile up: the oldest are deleted beyond a
 * maximum age or size, when a session stops or the application purges.
 */
public class SessionRetentionTest {

  @TempDir
  Path dir;

  /** Writes 1000 bytes when stopped, as JFR would write its file. */
  private static final Recorder RECORDER =
    (name, settings, file) -> () -> Files.writeString(file, "x".repeat(1000));

  private Sessions sessions() {
    return new Sessions(dir.resolve("sessions"), Map.of(), RECORDER);
  }

  private static int record(Sessions s) throws IOException {
    int id = s.start("manual").getId();
    s.stop(id);
    return id;
  }

  private long filesOf(String suffix) throws IOException {
    try (var files = Files.list(dir.resolve("sessions"))) {
      return files.filter(f -> f.getFileName().toString().startsWith("session-")
                               && f.getFileName().toString().endsWith(suffix)).count();
    }
  }

  @Test
  public void nothingIsDeletedByDefault() throws IOException {
    Sessions s = sessions();
    for (int i = 0; i < 4; i++)
      record(s);
    assertEquals(4, s.list().size());
  }

  @Test
  public void oldSessionsAreDeletedWithTheirFiles() throws IOException, InterruptedException {
    Sessions s = sessions();
    int first = record(s);
    int second = record(s);
    Thread.sleep(50);
    int third = record(s);

    s.setRetention(Duration.ofMillis(30), Long.MAX_VALUE);

    assertNull(s.get(first));
    assertNull(s.get(second));
    assertNotNull(s.get(third));
    assertEquals(1, filesOf(".jfr"));
    assertEquals(1, filesOf(".properties"));
  }

  @Test
  public void latestSessionIsKeptWhateverItsSize() throws IOException {
    Sessions s = sessions();
    int only = record(s);

    s.setRetention(null, 1);

    assertNotNull(s.get(only));
    assertEquals(1, filesOf(".jfr"));
  }

  @Test
  public void latestSessionIsDeletedWhenExpired() throws IOException, InterruptedException {
    Sessions s = sessions();
    int only = record(s);
    Thread.sleep(20);

    s.setRetention(Duration.ofMillis(1), Long.MAX_VALUE);

    assertNull(s.get(only));
    assertEquals(0, filesOf(".jfr"));
    assertEquals(0, filesOf(".properties"));
  }

  @Test
  public void purgeDeletesWhatExpiredSinceTheLastOne() throws IOException, InterruptedException {
    Sessions s = sessions();
    s.setRetention(Duration.ofMillis(30), Long.MAX_VALUE);
    int only = record(s);
    Thread.sleep(50);

    // as the application does now and then, with no session stopping
    s.purge();

    assertNull(s.get(only));
  }

  @Test
  public void oldestSessionsAreDeletedBeyondTheMaximumSize() throws IOException {
    Sessions s = sessions();
    int first = record(s);
    int second = record(s);
    int third = record(s);

    // a session takes a little more than 1000 bytes
    s.setRetention(null, 2500);

    assertNull(s.get(first));
    assertNotNull(s.get(second));
    assertNotNull(s.get(third));
  }

  @Test
  public void stoppingASessionPurges() throws IOException, InterruptedException {
    Sessions s = sessions();
    s.setRetention(Duration.ofMillis(30), Long.MAX_VALUE);
    int first = record(s);
    int second = record(s);
    Thread.sleep(50);

    int third = record(s);

    assertNull(s.get(first));
    assertNull(s.get(second));
    assertNotNull(s.get(third));
  }

  @Test
  public void recordingsKeptByOthersAreNeverDeleted() throws IOException {
    Path snapshot = Files.createDirectories(dir.resolve("snapshots")).resolve("snapshot.jfr");
    Files.writeString(snapshot, "x".repeat(5000));
    Sessions s = sessions();
    Instant t = Instant.parse("2020-01-01T08:00:00Z");
    s.register(t, t.plusSeconds(60), "snapshot", snapshot);
    int first = record(s);
    int second = record(s);

    s.setRetention(null, 1);

    assertTrue(Files.exists(snapshot));
    assertEquals(2, s.list().size(), "the snapshot and the latest session");
    assertNull(s.get(first));
    assertNotNull(s.get(second));
  }

  @Test
  public void theSessionRecordingIsNeverDeleted() throws IOException, InterruptedException {
    Sessions s = sessions();
    record(s);
    record(s);
    int recording = s.start("manual").getId();
    Thread.sleep(20);

    s.setRetention(Duration.ofMillis(1), 1);

    assertNotNull(s.get(recording));
    assertEquals(1, s.list().size(), "the sessions stopped expired, the one recording stays");
  }
}
