package jfrweb.ops;

/**
 * A thread working for an asynchronous operation, from
 * {@link Operation#segment()} until closed, on the same thread.
 */
public final class Segment implements AutoCloseable {

  /** The segment of a synchronous operation, which needs none. */
  static final Segment NONE = new Segment(null);

  private final SegmentEvent event;

  Segment(SegmentEvent event) {
    this.event = event;
  }

  @Override
  public void close() {
    if (event != null && event.shouldCommit())
      event.commit();
  }
}
