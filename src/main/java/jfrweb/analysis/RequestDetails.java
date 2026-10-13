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
 * What a recording tells about one request, beyond its samples: the
 * exceptions created and the file and socket accesses on its thread while it
 * ran. Only the accesses longer than the threshold of their event are
 * recorded.
 */
public final class RequestDetails {

  public static final String EXCEPTION = "jdk.JavaExceptionThrow";
  public static final String ERROR = "jdk.JavaErrorThrow";
  public static final String FILE_READ = "jdk.FileRead";
  public static final String FILE_WRITE = "jdk.FileWrite";
  public static final String SOCKET_READ = "jdk.SocketRead";
  public static final String SOCKET_WRITE = "jdk.SocketWrite";

  private static final Set<String> TYPES = Set.of(EXCEPTION, ERROR, FILE_READ, FILE_WRITE, SOCKET_READ, SOCKET_WRITE);

  /**
   * An exception or error created by the request.
   *
   * @param offsetNanos since the start of the request
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

  private final RecordedRequest request;
  private final List<Thrown> thrown = new ArrayList<>();
  private final List<Io> io = new ArrayList<>();
  private long javaSamples;
  private long nativeSamples;

  private RequestDetails(RecordedRequest request) {
    this.request = request;
  }

  public static RequestDetails of(Analysis analysis, RecordedRequest request) throws IOException {
    RequestDetails d = new RequestDetails(request);

    analysis.visitEvents(TYPES, e -> {
      RecordedThread t = e.getThread();
      long time = Analysis.nanos(e.getStartTime());
      if (t == null || t.getJavaThreadId() != request.threadId() ||
          time < request.startNanos() || time > request.endNanos())
        return;
      long offset = time - request.startNanos();
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

    analysis.visitSamples((sample, thread, r, weight) -> {
      if (request.equals(r)) {
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

  public RecordedRequest getRequest() {
    return request;
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
