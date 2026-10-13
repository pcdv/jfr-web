package jfrweb.stats;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class HistogramTest {

  @Test
  public void testPercentiles() {
    Histogram h = new Histogram();
    for (int i = 1; i <= 100; i++)
      h.add(i * 1_000_000L);
    // within a bucket, about 19%, of the exact values
    assertAbout(50_000_000, h.percentile(50));
    assertAbout(99_000_000, h.percentile(99));
    Assert.assertEquals(0, new Histogram().percentile(50));
    Assert.assertEquals(0, Histogram.index(0));
    Assert.assertEquals(Histogram.BUCKETS - 1, Histogram.index(Long.MAX_VALUE));
  }

  @Test
  public void testSparseRoundTrip() {
    Histogram h = new Histogram();
    h.add(1_000_000);
    h.add(1_000_000);
    h.add(80_000_000);
    List<List<Integer>> sparse = h.toSparse();
    Assert.assertEquals("only the buckets used", 2, sparse.size());

    Histogram back = Histogram.fromSparse(sparse);
    Assert.assertEquals(3, back.getTotal());
    Assert.assertEquals(h.percentile(50), back.percentile(50));
    Assert.assertEquals(h.percentile(99), back.percentile(99));
    Assert.assertEquals("unknown buckets ignored", 0, Histogram.fromSparse(List.of(List.of(999, 5))).getTotal());
  }

  @Test
  public void testAddUp() {
    Histogram a = new Histogram();
    Histogram b = new Histogram();
    for (int i = 0; i < 99; i++)
      a.add(1_000_000);
    b.add(500_000_000);
    a.addAll(b);
    Assert.assertEquals(100, a.getTotal());
    assertAbout(500_000_000, a.percentile(100));
  }

  private static void assertAbout(long expected, long actual) {
    Assert.assertTrue(actual + " vs " + expected, actual >= expected && actual <= expected * 1.2);
  }
}
