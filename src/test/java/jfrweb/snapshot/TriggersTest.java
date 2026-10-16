package jfrweb.snapshot;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TriggersTest {

  private static final long MS = 1_000_000;

  private final List<String> triggered = new ArrayList<>();
  private Instant now = Instant.parse("2026-10-13T10:00:00Z");
  private final Clock clock = new Clock() {
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  };
  private final Triggers triggers = new Triggers(clock, triggered::add);

  @Test
  public void testWithoutUsualLevelOnlyVerySlowRequests() {
    triggers.ended("GET /x", 2_000 * MS, false);
    Assertions.assertEquals(List.of(), triggered);
    triggers.ended("GET /x", 11_000 * MS, false);
    Assertions.assertEquals(List.of("GET /x took 11.0 s"), triggered);
  }

  @Test
  public void testSlowerThanUsual() {
    for (int i = 0; i < 30; i++)
      triggers.ended("GET /x", 300 * MS, false);
    now = now.plusSeconds(60);
    triggers.refreshBaselines();

    triggers.ended("GET /x", 900 * MS, false);
    Assertions.assertEquals(List.of(), triggered, "3x its usual p99 only");
    triggers.ended("GET /y", 900 * MS, false);
    Assertions.assertEquals(List.of(), triggered);

    triggers.ended("GET /x", 2_000 * MS, false);
    Assertions.assertEquals(1, triggered.size());
    Assertions.assertTrue(triggered.get(0).matches("GET /x took 2\\.0 s, \\dx its usual p99 of 0\\.\\d s"), triggered.get(0));
  }

  @Test
  public void testFastOperationUnderOneSecondNeverTriggers() {
    for (int i = 0; i < 30; i++)
      triggers.ended("GET /fast", 1 * MS, false);
    now = now.plusSeconds(60);
    triggers.refreshBaselines();
    // 500 times its usual p99, but under a second: not worth a snapshot
    triggers.ended("GET /fast", 500 * MS, false);
    Assertions.assertEquals(List.of(), triggered);
  }

  @Test
  public void testErrorBurst() {
    for (int i = 0; i < 100; i++)
      triggers.ended("GET /x", 10 * MS, false);
    triggers.ended("GET /x", 10 * MS, true);
    now = now.plusSeconds(60);

    for (int i = 0; i < 10; i++)
      triggers.ended("GET /x", 10 * MS, false);
    for (int i = 0; i < 6; i++)
      triggers.ended("GET /x", 10 * MS, true);
    Assertions.assertEquals(1, triggered.size(), "once, at the 5th");
    Assertions.assertTrue(triggered.get(0).startsWith("5 errors in a minute, 33% of the operations, usually 1.0%"), triggered.get(0));
  }

  @Test
  public void testErrorsAsUsualDoNotTrigger() {
    // a flaky operation failing half of the time: not a burst
    for (int m = 0; m < 3; m++) {
      for (int i = 0; i < 10; i++)
        triggers.ended("GET /flaky", 10 * MS, i % 2 == 0);
      now = now.plusSeconds(60);
    }
    Assertions.assertEquals(1, triggered.size());
    Assertions.assertTrue(triggered.get(0).contains("usually 0.0%"), "only the first minute, with nothing usual yet");
  }
}
