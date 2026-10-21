package jfrweb.session;

import java.nio.file.Path;
import java.util.Map;

import jdk.jfr.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A session being recorded tells an estimate of the size of its file, which
 * JFR does not tell before a chunk is complete.
 */
public class RecordingSizeTest {

  @TempDir
  Path dir;

  @Test
  public void sizeGrowsWhileRecording() throws Exception {
    Sessions sessions = new Sessions(dir, Configuration.getConfiguration("profile").getSettings(), false);
    Session s = sessions.start("manual");
    try {
      long deadline = System.currentTimeMillis() + 10_000;
      while (s.getSize() == 0 && System.currentTimeMillis() < deadline)
        Thread.sleep(100);
      assertTrue(s.getSize() > 0, "no size while recording");
    }
    finally {
      sessions.stop(s.getId());
    }
    assertEquals(s.getFile().toFile().length(), s.getSize());
  }
}
