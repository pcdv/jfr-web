package jfrweb.live;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import flak.App;
import jfrweb.analysis.OperationStats;
import jfrweb.analysis.RecordedOperation;
import jfrweb.ops.Operation;
import jfrweb.ops.OperationListener;
import jfrweb.ops.OperationSources;

/**
 * Statistics of the operations of the application, kept by counters as they
 * end, whether a session records or not: those of the jfrweb.ops API, and the
 * requests of the flak apps observed. Nothing is recorded with JFR: the cost
 * is a few counters per operation.
 */
public final class LiveStats implements AutoCloseable {

  /** The windows statistics can be asked for, in minutes. */
  public static final List<Integer> WINDOWS = List.of(1, 5, 15, 60);

  private final Map<String, OperationCounter> operations = new ConcurrentHashMap<>();
  private final LongSupplier minuteClock;
  /** Null when fed, see {@link #fed()}. */
  private final OperationSources sources;
  private volatile boolean counted;
  private volatile boolean closed;

  public LiveStats() {
    this(() -> System.currentTimeMillis() / 60_000, true);
  }

  /**
   * @param minuteClock the current minute, for tests
   * @param observe     whether to count the operations of this JVM
   */
  LiveStats(LongSupplier minuteClock, boolean observe) {
    this.minuteClock = minuteClock;
    this.sources = !observe ? null : new OperationSources(new OperationListener() {
      @Override
      public void onEnd(Operation op) {
        count(op.getName(), op.getDurationNanos(), op.isFailed(), op.getBytesIn(), op.getBytesOut());
      }
    });
  }

  LiveStats(LongSupplier minuteClock) {
    this(minuteClock, true);
  }

  /**
   * Statistics of operations reported by {@link #count(RecordedOperation)}
   * only, e.g. those of another JVM, read from its events: none of this JVM.
   */
  public static LiveStats fed() {
    return new LiveStats(() -> System.currentTimeMillis() / 60_000, false);
  }

  /**
   * Whether it counts the operations reported to it rather than those of
   * this JVM.
   */
  public boolean isFed() {
    return sources == null;
  }

  /**
   * Counts an operation that ended.
   */
  public void count(RecordedOperation op) {
    count(op.name(), op.durationNanos(), op.failed(), op.bytesIn(), op.bytesOut());
  }

  private void count(String name, long durationNanos, boolean failed, long bytesIn, long bytesOut) {
    counted = true;
    operations.computeIfAbsent(name, OperationCounter::new)
              .record(minuteClock.getAsLong(), durationNanos, failed, bytesIn, bytesOut);
  }

  /**
   * Counts the requests of a flak app from now on.
   */
  public void observe(App app) {
    if (sources == null)
      throw new IllegalStateException("Fed by the operations reported to it");
    sources.observe(app);
  }

  /**
   * Whether operations may come: a flak app is observed, or the application
   * has used the jfrweb.ops API; when fed, once one was reported.
   */
  public boolean isObserving() {
    return sources != null ? sources.isObserving() : counted && !closed;
  }

  /**
   * The paths of the flak apps observed, "" for an app at the root of its
   * server.
   */
  public List<String> getAppPaths() {
    List<String> res = new ArrayList<>();
    if (sources == null)
      return res;
    for (App a : sources.getApps())
      res.add(a.getPath());
    return res;
  }

  /**
   * The operations of the last minutes, the current one included, sorted by
   * total time, the biggest first.
   *
   * @param window a number of minutes, at most 60
   */
  public List<OperationStats> getOperations(int window) {
    if (window < 1 || window > OperationCounter.MINUTES)
      throw new IllegalArgumentException("Window out of 1.." + OperationCounter.MINUTES + ": " + window);
    long now = minuteClock.getAsLong();
    List<OperationStats> res = new ArrayList<>();
    for (OperationCounter c : operations.values()) {
      OperationStats s = c.over(now, window);
      if (s != null)
        res.add(s);
    }
    res.sort(Comparator.comparingLong(OperationStats::totalNanos).reversed());
    return res;
  }

  /**
   * Stops observing.
   */
  @Override
  public void close() {
    closed = true;
    if (sources != null)
      sources.close();
  }
}
