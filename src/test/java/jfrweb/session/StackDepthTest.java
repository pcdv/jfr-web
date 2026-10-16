package jfrweb.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import jdk.jfr.Recording;
import jfrweb.analysis.Analysis;
import jfrweb.analysis.SamplingSummary;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class StackDepthTest {

  /**
   * In a JVM of its own: in this one, other tests may have recorded already.
   */
  @Test
  public void testRaisedBeforeTheFirstRecording() throws Exception {
    String out = probe(300, 200);
    Assertions.assertTrue(out.startsWith("raised=true truncated=0 samples="), out);
    Assertions.assertFalse(out.endsWith("samples=0"), "sampled: " + out);
  }

  @Test
  public void testDefaultCutsDeepStacks() throws Exception {
    // the JVM's own depth, 64, as when the profiler cannot raise it
    String out = probe(0, 200);
    Assertions.assertTrue(out.startsWith("raised=false truncated="), out);
    Assertions.assertFalse(out.startsWith("raised=false truncated=0 "), out);
  }

  @Test
  public void testTooLateOnceJfrRecorded() {
    try (Recording r = new Recording()) {
      r.start();
    }
    Assertions.assertFalse(StackDepth.raise(512));
    // the depth kept, which it logs: 64, or what a test raised it to first
    int current = StackDepth.current();
    Assertions.assertTrue(current >= 64 && current < 512, "depth: " + current);
  }

  @Test
  public void testReportTellsTruncation() throws Exception {
    Path f = Files.createTempFile("jfr-web-depth", ".jfr");
    try (Recording r = new Recording()) {
      r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
      r.start();
      // deeper than JFR ever keeps
      StackDepthProbe.deep(StackDepth.MAX + 100, 300);
      r.stop();
      r.dump(f);
    }
    try {
      SamplingSummary s = SamplingSummary.of(Analysis.load(f));
      Assertions.assertTrue(s.getTruncated() > 0, "samples: " + s.getJavaSamples());
      Assertions.assertTrue(s.getStackDepth() >= 64 && s.getStackDepth() <= StackDepth.MAX, "depth: " + s.getStackDepth());
      Assertions.assertEquals(StackDepth.DEFAULT, StackDepth.suggest(64));
      Assertions.assertEquals(StackDepth.MAX, StackDepth.suggest(StackDepth.MAX));
    }
    finally {
      Files.delete(f);
    }
  }

  /**
   * @param depth asked for, or 0 to leave it
   */
  private static String probe(int depth, int frames) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                   "-cp", System.getProperty("java.class.path"),
                                   StackDepthProbe.class.getName(),
                                   String.valueOf(depth), String.valueOf(frames))
      .redirectErrorStream(true)
      .start();
    Assertions.assertTrue(p.waitFor(60, TimeUnit.SECONDS), "probe ended");
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    String last = out.substring(out.lastIndexOf('\n') + 1);
    return last;
  }
}
