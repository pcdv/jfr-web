package jfrweb.analysis;

import java.time.Duration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SamplingTest {

  private static final long MS = 1_000_000;

  @Test
  public void testNativeIntervalIsTheMeanOne() {
    LongList t = new LongList();
    // one sample per period, unsorted as in a recording
    for (long i : new long[]{3, 0, 2, 1, 4})
      t.add(i * 12 * MS);
    Assertions.assertEquals(Duration.ofMillis(12), Analysis.observedSampleInterval(t));
  }

  @Test
  public void testJavaRoundsMayBeMissing() {
    LongList t = new LongList();
    long time = 0;
    for (int round = 0; round < 100; round++) {
      // rounds every 15 ms, but no Java thread was running in every third one
      time += 15 * MS;
      if (round % 3 == 0)
        continue;
      // several threads sampled in a round, within a millisecond
      t.add(time);
      t.add(time + 100_000);
    }
    Assertions.assertEquals(Duration.ofMillis(15), Analysis.observedRoundInterval(t));
  }

  @Test
  public void testTooFewRounds() {
    LongList t = new LongList();
    t.add(0);
    t.add(10 * MS);
    Assertions.assertNull(Analysis.observedRoundInterval(t));
  }

  @Test
  public void testAnyOperationRunning() {
    OperationIndex index = new OperationIndex();
    index.add(segment(1, 10, 20));
    index.add(segment(2, 15, 30));
    index.add(segment(1, 40, 50));
    index.seal();

    Assertions.assertFalse(index.isAnyRunning(5));
    Assertions.assertTrue(index.isAnyRunning(10));
    Assertions.assertTrue(index.isAnyRunning(25));
    Assertions.assertTrue(index.isAnyRunning(30));
    Assertions.assertFalse(index.isAnyRunning(35));
    Assertions.assertTrue(index.isAnyRunning(45));
    Assertions.assertFalse(index.isAnyRunning(51));

    Assertions.assertNull(index.find(1, 25), "thread 1 was idle");
    Assertions.assertNotNull(index.find(2, 25));
  }

  @Test
  public void testNestedOperations() {
    OperationIndex index = new OperationIndex();
    OperationIndex.Segment cls = segment(1, 10, 100);
    OperationIndex.Segment test1 = segment(1, 20, 30);
    OperationIndex.Segment test2 = segment(1, 40, 50);
    index.add(cls);
    index.add(test1);
    index.add(test2);
    index.seal();

    Assertions.assertSame(cls.operation(), index.find(1, 15));
    Assertions.assertSame(test1.operation(), index.find(1, 25), "the innermost");
    Assertions.assertSame(cls.operation(), index.find(1, 35), "back to the class between tests");
    Assertions.assertSame(test2.operation(), index.find(1, 45));
    Assertions.assertSame(cls.operation(), index.find(1, 60), "after the last test");
    Assertions.assertNull(index.find(1, 101));
  }

  private static OperationIndex.Segment segment(long thread, long start, long end) {
    RecordedOperation op = new RecordedOperation(thread, "t" + thread, start, end, "GET /x", "GET /x", 200, false,
                                                 null, 0, 0, false);
    return new OperationIndex.Segment(thread, "t" + thread, start, end, op);
  }
}
