package jfrweb.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import jdk.jfr.Recording;
import jfrweb.analysis.Analysis;
import jfrweb.analysis.SamplingSummary;

import static jfrweb.analysis.AnalysisTest.Handlers.burn;

/**
 * Run in a JVM of its own by StackDepthTest: raises the stack depth before
 * anything records (unless asked for 0), then records a deep stack. Prints
 * "raised=<bool> truncated=<count> samples=<count>".
 */
public class StackDepthProbe {

  static void deep(int n, long millis) {
    if (n > 0)
      deep(n - 1, millis);
    else
      burn(millis);
  }

  public static void main(String[] args) throws Exception {
    int depth = Integer.parseInt(args[0]);
    boolean raised = depth > 0 && StackDepth.raise(depth);
    Path f = Files.createTempFile("jfr-web-depth", ".jfr");
    try (Recording r = new Recording()) {
      r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
      r.start();
      deep(Integer.parseInt(args[1]), 500);
      r.stop();
      r.dump(f);
    }
    SamplingSummary s = SamplingSummary.of(Analysis.load(f));
    System.out.println("raised=" + raised + " truncated=" + s.getTruncated() + " samples=" + s.getJavaSamples());
    Files.delete(f);
  }
}
