package jfrweb.ops;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A unit of work of the application, e.g. a request served or a job run,
 * begun by {@link Operations#begin(String)} or
 * {@link Operations#beginAsync(String)}, and ended by {@link #close()}.
 * Operations are grouped by name, like the endpoints of an HTTP server.
 * <p>
 * Its setters return it, to be chained, and must be called before it ends.
 */
public final class Operation implements AutoCloseable {

  private static final AtomicLong IDS = new AtomicLong();

  private final long id = IDS.incrementAndGet();
  private final String name;
  private final boolean async;
  private final long startNanos = System.nanoTime();
  /** Null for the requests of flak, which records its own event. */
  private final OperationEvent event;
  private final AtomicBoolean ended = new AtomicBoolean();
  private final Iterable<OperationListener> listeners;

  private volatile String detail;
  private volatile long bytesIn;
  private volatile long bytesOut;
  private volatile boolean failed;
  private volatile String error;
  private volatile long durationNanos = -1;
  private volatile String endThread;

  /** The status of a flak request, 0 for others. */
  int status;
  /** The token of the armed client that sent it, see jfrweb.auto.AutoMode. */
  String armToken;

  /**
   * @param recorded  whether to record its JFR event
   * @param listeners notified when it ends
   */
  Operation(String name, boolean async, boolean recorded, Iterable<OperationListener> listeners) {
    this.name = name;
    this.async = async;
    this.event = recorded ? new OperationEvent() : null;
    this.listeners = listeners;
  }

  /**
   * Starts timing the event, once the listeners have run: one of them may
   * have just started the recording.
   */
  void beginEvent() {
    if (event != null)
      event.begin();
  }

  public long getId() {
    return id;
  }

  /**
   * What operations are grouped by, e.g. "sort", or "GET /api/user/:id" for
   * a request of flak.
   */
  public String getName() {
    return name;
  }

  /**
   * Whether it may end on another thread than the one that began it.
   */
  public boolean isAsync() {
    return async;
  }

  /**
   * What this one worked on, e.g. its argument, or the path of a request.
   * Shown with the slowest operations.
   */
  public Operation detail(String detail) {
    this.detail = detail;
    return this;
  }

  public String getDetail() {
    return detail;
  }

  /**
   * The size of what it received, e.g. a request, in bytes.
   */
  public Operation bytesIn(long bytes) {
    this.bytesIn = bytes;
    return this;
  }

  public long getBytesIn() {
    return bytesIn;
  }

  /**
   * The size of what it sent back, in bytes.
   */
  public Operation bytesOut(long bytes) {
    this.bytesOut = bytes;
    return this;
  }

  public long getBytesOut() {
    return bytesOut;
  }

  /**
   * Marks it as failed: it counts among the errors.
   */
  public Operation failed(Throwable t) {
    return failed(t.getClass().getName() + (t.getMessage() == null ? "" : ": " + t.getMessage()));
  }

  /**
   * Marks it as failed, for a reason.
   */
  public Operation failed(String reason) {
    this.error = reason;
    this.failed = true;
    return this;
  }

  public boolean isFailed() {
    return failed;
  }

  /**
   * Why it failed, or null.
   */
  public String getError() {
    return error;
  }

  /**
   * The HTTP status of a request of flak, 0 for the other operations.
   */
  public int getStatus() {
    return status;
  }

  /**
   * The token of the armed client that sent it, or null.
   */
  public String getArmToken() {
    return armToken;
  }

  /**
   * Its duration once ended, -1 before.
   */
  public long getDurationNanos() {
    return durationNanos;
  }

  /**
   * The name of the thread that ended it, once ended.
   */
  public String getEndThread() {
    return endThread;
  }

  /**
   * Marks the current thread as working for this asynchronous operation,
   * until the segment is closed: the samples it takes then are the
   * operation's. Does nothing for a synchronous operation, whose thread is
   * known.
   * <pre>
   * try (Segment s = op.segment()) {
   *   ...
   * }
   * </pre>
   */
  public Segment segment() {
    if (!async)
      return Segment.NONE;
    SegmentEvent e = new SegmentEvent();
    e.id = id;
    e.begin();
    return new Segment(e);
  }

  /**
   * A task that runs in a {@link #segment()} of this operation, to hand work
   * to another thread, e.g. {@code executor.execute(op.wrap(task))}.
   */
  public Runnable wrap(Runnable task) {
    return () -> {
      try (Segment s = segment()) {
        task.run();
      }
    };
  }

  /**
   * Ends it, on any thread for an asynchronous operation, on the one that
   * began it otherwise. Only the first call counts.
   */
  @Override
  public void close() {
    end(System.nanoTime() - startNanos);
  }

  /**
   * Ends it with a duration measured elsewhere, as flak does.
   *
   * @return whether it was not ended already
   */
  boolean end(long nanos) {
    if (!ended.compareAndSet(false, true))
      return false;
    durationNanos = nanos;
    endThread = Thread.currentThread().getName();
    if (event != null) {
      event.end();
      if (event.shouldCommit()) {
        event.id = id;
        event.name = name;
        event.detail = detail;
        event.async = async;
        event.failed = failed;
        event.error = error;
        event.bytesIn = bytesIn;
        event.bytesOut = bytesOut;
        event.commit();
      }
    }
    for (OperationListener l : listeners)
      Operations.safely(() -> l.onEnd(this));
    return true;
  }

  @Override
  public String toString() {
    return name + (detail != null ? " " + detail : "");
  }
}
