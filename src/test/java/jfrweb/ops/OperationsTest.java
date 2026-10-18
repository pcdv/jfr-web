package jfrweb.ops;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jfrweb.analysis.Analysis;
import jfrweb.analysis.FlameGraphs;
import jfrweb.analysis.OperationDetails;
import jfrweb.analysis.OperationStats;
import jfrweb.analysis.RecordedOperation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static jfrweb.analysis.AnalysisTest.Handlers.burn;

/**
 * Records operations of the API, synchronous and asynchronous, then analyzes
 * the recording: each gets the samples of the threads that worked for it.
 */
public class OperationsTest {

  private static Path file;
  private static Analysis analysis;

  @BeforeAll
  public static void record() throws Exception {
    file = Files.createTempFile("jfr-web", ".jfr");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Recording rec = new Recording(Configuration.getConfiguration("profile"))) {
      rec.enable(Operations.EVENT);
      rec.enable(Operations.SEGMENT_EVENT);
      rec.start();

      for (int i = 0; i < 3; i++)
        try (Operation op = Operations.begin("sync").detail("job " + i)) {
          burn(150);
        }

      // asynchronous: begun here, worked on by two tasks of the pool in
      // turn, ended by the second one
      CountDownLatch done = new CountDownLatch(1);
      Operation op = Operations.beginAsync("async").detail("quote").bytesIn(10);
      pool.execute(op.wrap(() -> {
        burn(150);
        pool.execute(op.wrap(() -> {
          burn(150);
          op.bytesOut(20).close();
          done.countDown();
        }));
      }));
      // not this thread's: it waits
      Assertions.assertTrue(done.await(10, TimeUnit.SECONDS));

      try (Operation failed = Operations.begin("sync").detail("bad")) {
        failed.failed(new IllegalStateException("no luck"));
      }

      rec.stop();
      rec.dump(file);
    }
    finally {
      pool.shutdownNow();
    }
    analysis = Analysis.load(file);
  }

  @AfterAll
  public static void cleanUp() throws IOException {
    Files.deleteIfExists(file);
  }

  @Test
  public void testOperationsRead() {
    List<OperationStats> stats = OperationStats.of(analysis.getOperations());
    Assertions.assertEquals(List.of("sync", "async"), stats.stream().map(OperationStats::name).collect(Collectors.toList()));
    OperationStats sync = stats.get(0);
    Assertions.assertEquals(4, sync.count());
    Assertions.assertEquals(1, sync.errors());

    RecordedOperation async = find("quote");
    Assertions.assertTrue(async.async());
    Assertions.assertTrue(async.durationNanos() >= 300_000_000, "both tasks: " + async.durationNanos());
    Assertions.assertEquals(10, async.bytesIn());
    Assertions.assertEquals(20, async.bytesOut());

    RecordedOperation bad = find("bad");
    Assertions.assertTrue(bad.failed());
    Assertions.assertEquals("java.lang.IllegalStateException: no luck", bad.error());
  }

  @Test
  public void testSegments() throws IOException {
    RecordedOperation async = find("quote");
    OperationDetails d = OperationDetails.of(analysis, async);
    Assertions.assertEquals(2, d.getSegments().size(), "two tasks");
    Assertions.assertTrue(d.getSegments().get(0).threadName().startsWith("pool-"), "pool threads: " + d.getSegments());
    Assertions.assertTrue(d.getJavaSamples() >= 10, "samples of both tasks: " + d.getJavaSamples());

    RecordedOperation sync = find("job 0");
    Assertions.assertEquals(1, analysis.getSegments(sync).size(), "a synchronous one is its own segment");
  }

  @Test
  public void testFlameGraphRootedByOperation() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FlameGraphs.write(analysis, new FlameGraphs.Options(),
                      new PrintStream(out, true, StandardCharsets.UTF_8));
    String collapsed = out.toString(StandardCharsets.UTF_8);
    Assertions.assertTrue(collapsed.contains("\nsync;") || collapsed.startsWith("sync;"));
    Assertions.assertTrue(collapsed.contains("async;"), "the samples of the pool threads, in segments");
    Assertions.assertTrue(collapsed.contains("burn"));
  }

  private static RecordedOperation find(String detail) {
    return analysis.getOperations().stream().filter(op -> detail.equals(op.detail())).findFirst()
                   .orElseThrow(() -> new AssertionError("No " + detail));
  }
}
