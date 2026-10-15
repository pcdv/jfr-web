package jfrweb.standalone;

import jfrweb.ops.Operation;
import jfrweb.ops.Operations;

import static jfrweb.analysis.AnalysisTest.Handlers.burn;

/**
 * Run in a JVM of its own by StandaloneTest, to attach to: an application with
 * no profiler, whose operations "work" take about 20 ms each, until killed.
 */
public class RemoteApp {

  public static void main(String[] args) {
    System.out.println("ready");
    for (int i = 0; ; i++) {
      try (Operation op = Operations.begin("work").detail("item " + i)) {
        burn(20);
      }
    }
  }
}
