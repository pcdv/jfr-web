package jfrweb.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A session recording when the profiler ended wrote nothing: it is not listed
 * again, its report could not be made.
 */
public class SessionRecoveryTest {

  @TempDir
  Path dir;

  /** Writes nothing before it is stopped, as JFR does. */
  private static final Recorder RECORDER = (name, settings, file) -> {
    Files.createFile(file);
    return () -> Files.writeString(file, "jfr");
  };

  private Sessions sessions() {
    return new Sessions(dir, Map.of(), RECORDER);
  }

  @Test
  public void interruptedSessionIsDropped() throws IOException {
    Sessions s = sessions();
    int done = s.start("manual").getId();
    s.stop(done);
    int interrupted = s.start("manual").getId();

    // restarted while recording
    Sessions again = sessions();
    assertEquals(done, again.get(done).getId());
    assertNull(again.get(interrupted));
    try (var files = Files.list(dir)) {
      assertEquals(2, files.filter(f -> f.getFileName().toString().startsWith("session-")).count(),
        "the .jfr and .properties of the one stopped");
    }
  }
}
