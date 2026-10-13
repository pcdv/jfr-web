package jfrweb.live;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import flak.App;
import jfrweb.analysis.OperationStats;
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
  private final OperationSources sources;

  public LiveStats() {
    this(() -> System.currentTimeMillis() / 60_000);
  }

  /**
   * @param minuteClock the current minute, for tests
   */
  LiveStats(LongSupplier minuteClock) {
    this.minuteClock = minuteClock;
    this.sources = new OperationSources(new OperationListener() {
      @Override
      public void onEnd(Operation op) {
        operations.computeIfAbsent(op.getName(), OperationCounter::new)
                  .record(minuteClock.getAsLong(), op.getDurationNanos(), op.isFailed(),
                          op.getBytesIn(), op.getBytesOut());
      }
    });
  }

  /**
   * Counts the requests of a flak app from now on.
   */
  public void observe(App app) {
    sources.observe(app);
  }

  /**
   * Whether operations may come: a flak app is observed, or the application
   * has used the jfrweb.ops API.
   */
  public boolean isObserving() {
    return sources.isObserving();
  }

  /**
   * The paths of the flak apps observed, "" for an app at the root of its
   * server.
   */
  public List<String> getAppPaths() {
    List<String> res = new ArrayList<>();
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
    sources.close();
  }
}
