package jfrweb.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A link to a session keeps working across restarts: registered recordings
 * (snapshots) keep their id, and no id is given twice.
 */
public class SessionIdsTest {

  @TempDir
  Path dir;

  /** Writes the file when stopped, as JFR would. */
  private static final Recorder RECORDER = (name, settings, file) -> () -> Files.writeString(file, "jfr");

  private Sessions sessions() {
    return new Sessions(dir.resolve("sessions"), Map.of(), RECORDER);
  }

  private Path snapshot(String name) throws IOException {
    Path f = Files.createDirectories(dir.resolve("snapshots")).resolve(name);
    Files.writeString(f, "jfr");
    return f;
  }

  private static int register(Sessions s, Path file) {
    Instant t = Instant.parse("2026-10-16T08:00:00Z");
    return s.register(t, t.plusSeconds(60), "snapshot", file).getId();
  }

  @Test
  public void registeredKeepTheirIds() throws IOException {
    Path a = snapshot("a.jfr"), b = snapshot("b.jfr");
    Sessions s = sessions();
    int manual = s.start("manual").getId();
    s.stop(manual);
    int ia = register(s, a), ib = register(s, b);

    // registered in another order, after a restart
    Sessions again = sessions();
    assertEquals(ib, register(again, b));
    assertEquals(ia, register(again, a));
    assertEquals(manual, again.get(manual).getId());
  }

  @Test
  public void idsNeverGivenTwice() throws IOException {
    Path a = snapshot("a.jfr");
    Sessions s = sessions();
    int ia = register(s, a);
    // purged, as the oldest snapshots are
    Files.delete(a);

    Sessions again = sessions();
    int next = register(again, snapshot("c.jfr"));
    assertEquals(ia + 1, next);
    int started = again.start("manual").getId();
    assertEquals(next + 1, started);
  }
}
