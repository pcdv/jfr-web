package jfrweb.ops;

import java.util.List;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import jfrweb.analysis.Analysis;
import jfrweb.analysis.EventMapping;
import jfrweb.analysis.EventMappings;
import jfrweb.analysis.RecordedOperation;
import jfrweb.session.StackDepth;

/**
 * The operations of the events of the application mapped (see
 * {@link EventMappings}), registered rather than built in, heard of in this
 * JVM: while something here listens to operations (live statistics, history,
 * snapshots, auto mode), a stream of these events tells the listeners of each
 * one as it ends, about a second later. They only hear of its end: it is
 * known once over.
 */
public final class MappedOperations {

  private static final System.Logger log = System.getLogger(MappedOperations.class.getName());

  /** What listens to operations in this JVM, see {@link OperationSources}. */
  private static int consumers;
  private static RecordingStream stream;

  private MappedOperations() {}

  /**
   * Whether operations may come from events mapped: some are, and something
   * listens.
   */
  public static synchronized boolean isActive() {
    return stream != null;
  }

  static synchronized void consumerAdded() {
    consumers++;
    update();
  }

  static synchronized void consumerRemoved() {
    consumers = Math.max(0, consumers - 1);
    update();
  }

  /**
   * Streams the events mapped now, if something listens: called as the
   * mappings change.
   */
  public static synchronized void update() {
    if (stream != null) {
      stream.close();
      stream = null;
    }
    List<EventMapping> mappings = EventMappings.registered();
    if (consumers == 0 || mappings.isEmpty())
      return;
    // it may be the first recording of the JVM, which fixes the depth of
    // the stacks of every one after
    StackDepth.raise(StackDepth.DEFAULT);
    RecordingStream s = new RecordingStream();
    for (EventMapping m : mappings) {
      s.enable(m.event()).withoutThreshold().withoutStackTrace();
      s.onEvent(m.event(), MappedOperations::ended);
    }
    s.setReuse(false);
    s.startAsync();
    stream = s;
    log.log(System.Logger.Level.DEBUG, "Operations from the events " + mappings.stream().map(EventMapping::event).toList());
  }

  private static void ended(RecordedEvent e) {
    RecordedOperation r;
    try {
      r = Analysis.operation(e);
    }
    catch (RuntimeException ex) {
      log.log(System.Logger.Level.WARNING, "Could not read an operation from " + e.getEventType().getName(), ex);
      return;
    }
    if (r == null)
      return;
    Operation op = new Operation(r.name(), false, false, Operations.listeners());
    op.mapped = true;
    op.detail(r.detail());
    if (r.failed())
      op.failed(r.error() != null ? r.error() : "failed");
    op.end(r.durationNanos(), r.threadName());
  }
}
