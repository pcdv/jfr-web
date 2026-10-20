package jfrweb.standalone;

import java.nio.file.Path;

import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jfrweb.Profiler;
import jfrweb.ops.Operation;
import jfrweb.ops.Operations;
import jfrweb.snapshot.Snapshots;

import static jfrweb.analysis.AnalysisTest.Handlers.burn;

/**
 * Run in a JVM of its own by StandaloneTest, to attach to: an application with
 * no profiler, whose operations "work" take about 20 ms each, until killed;
 * every fifth, an event of its own, as a framework would record.
 */
public class RemoteApp {

  /** Unknown to jfr-web, which reads it as an operation once told how. */
  @Name("test.Batch")
  @Enabled(false)
  static class Batch extends Event {
    String kind;
  }

  /**
   * @param args none, or the directories of a profiler embedded: sessions, history, snapshots
   */
  public static void main(String[] args) throws Exception {
    if (args.length == 3)
      Profiler.builder()
        .directory(Path.of(args[0]))
        .history(Path.of(args[1]))
        .snapshots(Snapshots.builder().directory(Path.of(args[2])).start())
        .start();
    System.out.println("ready");
    for (int i = 0; ; i++) {
      try (Operation op = Operations.begin("work").detail("item " + i)) {
        burn(20);
      }
      if (i % 5 == 0) {
        Batch b = new Batch();
        b.begin();
        burn(5);
        b.kind = "nightly";
        b.commit();
      }
    }
  }
}
