package jfrweb.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A session started with a time limit stops by itself.
 */
public class SessionTimeLimitTest {

  @TempDir
  Path dir;

  /** Writes the file when stopped, as JFR would. */
  private static final Recorder RECORDER = (name, settings, file) -> () -> Files.writeString(file, "jfr");

  private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; i < 200 && !condition.getAsBoolean(); i++)
      Thread.sleep(25);
  }

  @Test
  public void stopsWhenItsTimeIsUp() throws Exception {
    Sessions sessions = new Sessions(dir, Map.of(), RECORDER);
    Session s = sessions.start("manual", Duration.ofMillis(150));
    assertNotNull(s.getStopAt(), "tells when it stops");
    assertEquals(Session.State.RECORDING, s.getState());

    waitUntil(() -> s.getState() == Session.State.STOPPED);
    assertEquals(Session.State.STOPPED, s.getState());
    assertTrue(Files.exists(s.getFile()), "its recording was written");
    assertNull(s.getStopAt(), "nothing to tell once it stopped");
    assertNull(sessions.getRecording());
    sessions.close();
  }

  @Test
  public void withoutLimitItKeepsRecording() throws Exception {
    Sessions sessions = new Sessions(dir, Map.of(), RECORDER);
    Session s = sessions.start("manual");
    assertNull(s.getStopAt());
    Thread.sleep(200);
    assertEquals(Session.State.RECORDING, s.getState());
    sessions.close();
  }

  @Test
  public void aSessionStoppedByHandIsLeftAlone() throws Exception {
    Sessions sessions = new Sessions(dir, Map.of(), RECORDER);
    Session first = sessions.start("manual", Duration.ofMillis(250));
    sessions.stop(first.getId());
    // another one starts before the first one's time is up: it is not its to stop
    Session second = sessions.start("manual");
    Thread.sleep(500);
    assertEquals(Session.State.RECORDING, second.getState());
    assertEquals(Session.State.STOPPED, first.getState());
    sessions.close();
    assertEquals(Session.State.STOPPED, second.getState());
  }

  @Test
  public void closingStopsTheTimer() throws IOException {
    Sessions sessions = new Sessions(dir, Map.of(), RECORDER);
    Session s = sessions.start("manual", Duration.ofHours(1));
    sessions.close();
    assertEquals(Session.State.STOPPED, s.getState());
  }
}
