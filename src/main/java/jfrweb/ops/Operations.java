package jfrweb.ops;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Makes the units of work of any application visible to jfr-web, as the
 * requests of a flak app are: live statistics, history, snapshots, auto mode,
 * and in recordings, flame graphs by operation.
 * <pre>
 * try (Operation op = Operations.begin("import").detail(file.getName())) {
 *   ...
 * }
 * </pre>
 * A synchronous operation runs on the thread that begins it, until it ends:
 * the samples of that thread in that time are its own. Synchronous operations
 * do not nest: a thread runs one at a time.
 * <p>
 * An asynchronous operation may end on any thread, e.g. when a reply is sent
 * from a callback. Its duration is that of the whole, but its samples are only
 * those of its {@link Operation#segment() segments}: the threads that declare
 * working for it.
 * <pre>
 * Operation op = Operations.beginAsync("quote").detail(symbol);
 * executor.execute(op.wrap(() -> {
 *   ...
 *   op.close();
 * }));
 * </pre>
 * Recordings get them from their JFR event, {@value #EVENT}, which is disabled
 * by default: the sessions and snapshots of jfr-web enable it.
 */
public final class Operations {

  /** The name of the JFR event of an operation. */
  public static final String EVENT = "jfrweb.Operation";

  /** The name of the JFR event of a segment of an asynchronous operation. */
  public static final String SEGMENT_EVENT = "jfrweb.OperationSegment";

  private static final System.Logger log = System.getLogger(Operations.class.getName());

  private static final List<OperationListener> listeners = new CopyOnWriteArrayList<>();
  private static volatile boolean used;

  private Operations() {}

  /**
   * Begins a synchronous operation, which runs on this thread until closed.
   *
   * @param name what operations are grouped by: a few distinct names, not
   *             one per call (the argument goes in the detail)
   */
  public static Operation begin(String name) {
    return begin(name, false);
  }

  /**
   * Begins an asynchronous operation, which may end on any thread.
   *
   * @param name what operations are grouped by
   */
  public static Operation beginAsync(String name) {
    return begin(name, true);
  }

  private static Operation begin(String name, boolean async) {
    used = true;
    Operation op = new Operation(name, async, true, listeners);
    for (OperationListener l : listeners)
      safely(() -> l.onBegin(op));
    op.beginEvent();
    return op;
  }

  /**
   * Whether the application has begun an operation yet.
   */
  public static boolean isUsed() {
    return used;
  }

  /** The listeners of the operations of this JVM. */
  static Iterable<OperationListener> listeners() {
    return listeners;
  }

  /**
   * Notifies a listener of the operations begun from now on, until removed.
   */
  public static void addListener(OperationListener l) {
    listeners.add(l);
  }

  public static void removeListener(OperationListener l) {
    listeners.remove(l);
  }

  /**
   * Runs a listener: what it throws must not reach the application.
   */
  static void safely(Runnable r) {
    try {
      r.run();
    }
    catch (RuntimeException e) {
      log.log(System.Logger.Level.WARNING, "An operation listener failed", e);
    }
  }
}
