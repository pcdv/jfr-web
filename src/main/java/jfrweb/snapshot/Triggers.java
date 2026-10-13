package jfrweb.snapshot;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import flak.App;
import jfrweb.ops.Operation;
import jfrweb.ops.OperationListener;
import jfrweb.ops.OperationSources;
import jfrweb.stats.Histogram;

/**
 * Tells when something goes wrong, from the operations as they end, with
 * nothing to configure: each operation name is compared with itself over the
 * last hour.
 * <ul>
 *   <li>an operation much slower than usual for its name: {@link #SLOWER}
 *   times its p99 of the last hour, and at least {@link #MIN_SLOW_NANOS}; or,
 *   for a name with too few operations to tell what is usual, at least
 *   {@link #UNKNOWN_SLOW_NANOS}
 *   <li>a burst of errors: at least {@link #MIN_ERRORS} operations that failed
 *   (requests with a 5xx status) in a minute, {@link #MIN_ERROR_SHARE} of the
 *   operations of that minute at least, and {@link #SLOWER} times their usual
 *   share
 * </ul>
 */
final class Triggers {

  static final double SLOWER = 4;
  static final long MIN_SLOW_NANOS = 1_000_000_000L;
  static final long UNKNOWN_SLOW_NANOS = 10_000_000_000L;
  /** Operations a name needs in the last hour for its p99 to be usual. */
  static final int MIN_BASELINE_OPERATIONS = 20;
  static final int MIN_ERRORS = 5;
  static final double MIN_ERROR_SHARE = 0.1;

  private static final int MINUTES = 60;

  private final Clock clock;
  private final Consumer<String> onTrigger;
  private final Map<String, Name> names = new ConcurrentHashMap<>();
  private final OperationSources sources;

  /** All operations together, for errors. */
  private final Minutes all = new Minutes();

  Triggers(Clock clock, Consumer<String> onTrigger) {
    this.clock = clock;
    this.onTrigger = onTrigger;
    this.sources = new OperationSources(new OperationListener() {
      @Override
      public void onEnd(Operation op) {
        ended(op.getName(), op.getDurationNanos(), op.isFailed());
      }
    });
  }

  /** Operations counted per minute, over the last hour. */
  private static final class Minutes {
    final long[] minute = new long[MINUTES];
    final int[] count = new int[MINUTES];
    final int[] errors = new int[MINUTES];
    final Histogram[] durations = new Histogram[MINUTES];

    /** Records an operation, and returns the slot of its minute. */
    synchronized int add(long m, long nanos, boolean error) {
      int i = (int) (m % MINUTES);
      if (minute[i] != m) {
        minute[i] = m;
        count[i] = 0;
        errors[i] = 0;
        durations[i] = null;
      }
      count[i]++;
      if (error)
        errors[i]++;
      if (durations[i] == null)
        durations[i] = new Histogram();
      durations[i].add(nanos);
      return i;
    }
  }

  private static final class Name {
    final Minutes minutes = new Minutes();
    /** p99 of the last hour, the current minute left out; 0 if not enough operations. */
    volatile long usualP99;
  }

  void observe(App app) {
    sources.observe(app);
  }

  void ended(String name, long nanos, boolean failed) {
    long m = clock.millis() / 60_000;
    Name n = names.computeIfAbsent(name, k -> new Name());
    n.minutes.add(m, nanos, false);

    long usual = n.usualP99;
    if (usual > 0 ? nanos >= Math.max(MIN_SLOW_NANOS, SLOWER * usual) : nanos >= UNKNOWN_SLOW_NANOS) {
      onTrigger.accept(usual > 0
        ? String.format(Locale.ROOT, "%s took %s, %.0fx its usual p99 of %s", name, seconds(nanos),
                        (double) nanos / usual, seconds(usual))
        : name + " took " + seconds(nanos));
    }

    int i = all.add(m, nanos, failed);
    if (failed)
      checkErrors(m, i);
  }

  private void checkErrors(long m, int i) {
    int errors;
    int count;
    long usualErrors = 0;
    long usualCount = 0;
    synchronized (all) {
      errors = all.errors[i];
      count = all.count[i];
      for (int j = 0; j < MINUTES; j++)
        if (j != i && all.minute[j] > m - MINUTES && all.minute[j] < m) {
          usualErrors += all.errors[j];
          usualCount += all.count[j];
        }
    }
    double share = (double) errors / count;
    double usualShare = usualCount == 0 ? 0 : (double) usualErrors / usualCount;
    // once per minute: at the error that crosses the line
    if (errors == MIN_ERRORS && share >= MIN_ERROR_SHARE && share >= SLOWER * usualShare)
      onTrigger.accept(String.format(Locale.ROOT, "%d errors in a minute, %.0f%% of the operations, usually %.1f%%",
                                     errors, 100 * share, 100 * usualShare));
    else if (errors > MIN_ERRORS && errors % 50 == 0 && share >= MIN_ERROR_SHARE && share >= SLOWER * usualShare)
      onTrigger.accept(errors + " errors in a minute");
  }

  /**
   * Computes the usual p99 of each name: once a minute, not per operation.
   */
  void refreshBaselines() {
    long m = clock.millis() / 60_000;
    for (Name n : names.values()) {
      Histogram h = new Histogram();
      synchronized (n.minutes) {
        for (int j = 0; j < MINUTES; j++)
          if (n.minutes.minute[j] > m - MINUTES && n.minutes.minute[j] < m && n.minutes.durations[j] != null)
            h.addAll(n.minutes.durations[j]);
      }
      n.usualP99 = h.getTotal() >= MIN_BASELINE_OPERATIONS ? h.percentile(99) : 0;
    }
  }

  void close() {
    sources.close();
  }

  private static String seconds(long nanos) {
    return String.format(Locale.ROOT, "%.1f s", nanos / 1e9);
  }
}
