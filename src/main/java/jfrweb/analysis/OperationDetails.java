package jfrweb.analysis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

import static jfrweb.analysis.RecordingInfo.EXECUTION_SAMPLE;

/**
 * What a recording tells about one operation, beyond its samples: the
 * exceptions created and the file and socket accesses of the threads that
 * worked for it, while they did. Only the accesses longer than the threshold
 * of their event are recorded.
 */
public final class OperationDetails {

  public static final String EXCEPTION = "jdk.JavaExceptionThrow";
  public static final String ERROR = "jdk.JavaErrorThrow";
  public static final String FILE_READ = "jdk.FileRead";
  public static final String FILE_WRITE = "jdk.FileWrite";
  public static final String SOCKET_READ = "jdk.SocketRead";
  public static final String SOCKET_WRITE = "jdk.SocketWrite";

  private static final Set<String> TYPES = Set.of(EXCEPTION, ERROR, FILE_READ, FILE_WRITE, SOCKET_READ, SOCKET_WRITE);

  /**
   * An exception or error created by the operation.
   *
   * @param offsetNanos since the start of the operation
   * @param frame       the method that created it, e.g. "com.acme.Dao.load"
   */
  public record Thrown(long offsetNanos, String type, String message, String frame) {}

  /**
   * A file or socket access longer than the threshold of its event.
   *
   * @param kind   "file read", "file write", "socket read" or "socket write"
   * @param target the path, or host:port
   */
  public record Io(long offsetNanos, String kind, String target, long bytes, long durationNanos) {}

  private final RecordedOperation operation;
  private final List<OperationIndex.Segment> segments;
  private final List<Thrown> thrown = new ArrayList<>();
  private final List<Io> io = new ArrayList<>();
  private long javaSamples;
  private long nativeSamples;

  private OperationDetails(RecordedOperation operation, List<OperationIndex.Segment> segments) {
    this.operation = operation;
    this.segments = segments;
  }

  public static OperationDetails of(Analysis analysis, RecordedOperation operation) throws IOException {
    OperationDetails d = new OperationDetails(operation, analysis.getSegments(operation));

    analysis.visitEvents(TYPES, e -> {
      RecordedThread t = e.getThread();
      long time = Analysis.nanos(e.getStartTime());
      if (t == null || !d.workedFor(t.getJavaThreadId(), time))
        return;
      long offset = time - operation.startNanos();
      String type = e.getEventType().getName();
      switch (type) {
        case EXCEPTION:
        case ERROR:
          RecordedClass c = e.getClass("thrownClass");
          d.thrown.add(new Thrown(offset, c == null ? "?" : c.getName(), e.getString("message"), creator(e)));
          break;
        case FILE_READ:
          d.io.add(new Io(offset, "file read", e.getString("path"), e.getLong("bytesRead"), e.getDuration().toNanos()));
          break;
        case FILE_WRITE:
          d.io.add(new Io(offset, "file write", e.getString("path"), e.getLong("bytesWritten"), e.getDuration().toNanos()));
          break;
        case SOCKET_READ:
          d.io.add(new Io(offset, "socket read", e.getString("host") + ":" + e.getInt("port"),
                          e.getLong("bytesRead"), e.getDuration().toNanos()));
          break;
        case SOCKET_WRITE:
          d.io.add(new Io(offset, "socket write", e.getString("host") + ":" + e.getInt("port"),
                          e.getLong("bytesWritten"), e.getDuration().toNanos()));
          break;
      }
    });
    d.thrown.sort(Comparator.comparingLong(Thrown::offsetNanos));
    d.io.sort(Comparator.comparingLong(Io::offsetNanos));

    analysis.visitSamples((sample, thread, op, weight) -> {
      if (operation.equals(op)) {
        if (sample.getEventType().getName().equals(EXECUTION_SAMPLE))
          d.javaSamples++;
        else
          d.nativeSamples++;
      }
    });
    return d;
  }

  /**
   * The method that created the exception: the first frame that is not a
   * constructor of the exception, or of its parents.
   */
  private static String creator(RecordedEvent e) {
    RecordedStackTrace st = e.getStackTrace();
    if (st == null)
      return null;
    for (RecordedFrame f : st.getFrames()) {
      if (!f.getMethod().getName().equals("<init>"))
        return f.getMethod().getType().getName() + "." + f.getMethod().getName();
    }
    return null;
  }

  private boolean workedFor(long threadId, long timeNanos) {
    for (OperationIndex.Segment s : segments)
      if (s.threadId() == threadId && timeNanos >= s.startNanos() && timeNanos <= s.endNanos())
        return true;
    return false;
  }

  public RecordedOperation getOperation() {
    return operation;
  }

  /**
   * The threads that worked for it, and when, the earliest first.
   */
  public List<OperationIndex.Segment> getSegments() {
    return segments;
  }

  public List<Thrown> getThrown() {
    return thrown;
  }

  public List<Io> getIo() {
    return io;
  }

  public long getJavaSamples() {
    return javaSamples;
  }

  public long getNativeSamples() {
    return nativeSamples;
  }
}
