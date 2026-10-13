package jfrweb.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import jfrweb.ops.FlakOperations;

import static jfrweb.analysis.RecordingInfo.EXECUTION_SAMPLE;
import static jfrweb.analysis.RecordingInfo.FLAK_REQUEST;
import static jfrweb.analysis.RecordingInfo.NATIVE_SAMPLE;
import static jfrweb.analysis.RecordingInfo.OPERATION;
import static jfrweb.analysis.RecordingInfo.OPERATION_SEGMENT;

/**
 * A recording read for analysis. Loading it reads the operations and how it
 * was recorded; the samples, which are many, are read again each time they
 * are visited instead of being held in memory.
 */
public final class Analysis {

  private final Path file;
  private final RecordingInfo info = new RecordingInfo();
  private final List<RecordedOperation> operations = new ArrayList<>();
  private final OperationIndex index = new OperationIndex();

  /** The asynchronous operations by id, and the segments read, until linked. */
  private final Map<Long, RecordedOperation> asyncById = new HashMap<>();
  private final List<RawSegment> rawSegments = new ArrayList<>();

  private record RawSegment(long id, long threadId, String threadName, long startNanos, long endNanos) {}

  /** Times of the samples, to measure how often they were really taken. */
  private final LongList javaSampleTimes = new LongList();
  private final LongList nativeSampleTimes = new LongList();

  private long javaWeight;
  private long nativeWeight;

  private Analysis(Path file) {
    this.file = file;
  }

  public static Analysis load(Path file) throws IOException {
    Analysis a = new Analysis(file);
    a.readOperationsAndInfo();
    return a;
  }

  public Path getFile() {
    return file;
  }

  public RecordingInfo getInfo() {
    return info;
  }

  /**
   * The operations of the recording, in no particular order.
   */
  public List<RecordedOperation> getOperations() {
    return Collections.unmodifiableList(operations);
  }

  private void readOperationsAndInfo() throws IOException {
    Map<Long, String> eventNames = new HashMap<>();

    try (RecordingFile rf = new RecordingFile(file)) {
      for (EventType t : rf.readEventTypes())
        eventNames.put(t.getId(), t.getName());

      while (rf.hasMoreEvents()) {
        RecordedEvent e = rf.readEvent();
        updateBounds(e);

        switch (e.getEventType().getName()) {
          case FLAK_REQUEST:
            addFlakRequest(e);
            break;
          case OPERATION:
            addOperation(e);
            break;
          case OPERATION_SEGMENT:
            RecordedThread t = e.getThread();
            if (t != null)
              rawSegments.add(new RawSegment(e.getLong("id"), t.getJavaThreadId(), t.getJavaName(),
                                             nanos(e.getStartTime()), nanos(e.getEndTime())));
            break;
          case EXECUTION_SAMPLE:
            javaSampleTimes.add(nanos(e.getStartTime()));
            break;
          case NATIVE_SAMPLE:
            nativeSampleTimes.add(nanos(e.getStartTime()));
            break;
          case "jdk.ActiveSetting":
            String event = eventNames.get(e.getLong("id"));
            if (event != null)
              info.settings.computeIfAbsent(event, k -> new TreeMap<>())
                           .put(e.getString("name"), e.getString("value"));
            break;
          case "jdk.JVMInformation":
            info.jvmName = e.getString("jvmName");
            info.jvmVersion = e.getString("jvmVersion");
            info.jvmArguments = e.getString("jvmArguments");
            info.javaArguments = e.getString("javaArguments");
            break;
          case "jdk.OSInformation":
            info.os = oneLine(e.getString("osVersion"));
            break;
        }
      }
    }

    linkSegments();
    index.seal();
    info.javaInterval = observedRoundInterval(javaSampleTimes);
    info.nativeInterval = observedSampleInterval(nativeSampleTimes);
    javaWeight = weight(info.getPeriod(EXECUTION_SAMPLE), info.javaInterval);
    nativeWeight = weight(info.getPeriod(NATIVE_SAMPLE), info.nativeInterval);
  }

  /**
   * The recording spans from its first to its last committed event. A
   * duration event may have started before the recording (e.g. a thread
   * parked earlier), but is committed when it ends.
   */
  private void updateBounds(RecordedEvent e) {
    Instant end = e.getEndTime();
    if (info.start == null || end.isBefore(info.start))
      info.start = end;
    if (info.end == null || end.isAfter(info.end))
      info.end = end;
  }

  /**
   * The mean interval between native samples. JFR samples one thread in
   * native code per period, and a server always has one (an idle selector, an
   * acceptor): each period yields one sample.
   */
  static Duration observedSampleInterval(LongList times) {
    if (times.size() < 2)
      return null;
    long[] t = times.sorted();
    return Duration.ofNanos((t[t.length - 1] - t[0]) / (t.length - 1));
  }

  /**
   * The interval between rounds of Java samples. A round samples the threads
   * running Java code at that moment, so the rounds where none is are missing:
   * the shortest gaps between rounds are the period. The samples of a round
   * are taken within a millisecond.
   */
  static Duration observedRoundInterval(LongList times) {
    long[] t = times.sorted();
    LongList gaps = new LongList();
    long roundStart = t.length == 0 ? 0 : t[0];
    for (int i = 1; i < t.length; i++)
      if (t[i] - t[i - 1] > 1_000_000) {
        gaps.add(t[i] - roundStart);
        roundStart = t[i];
      }
    if (gaps.size() < 20)
      return null;
    long[] g = gaps.sorted();
    return Duration.ofNanos(g[g.length / 10]);
  }

  /**
   * The time a sample stands for, in milliseconds: the period actually
   * observed, which on Windows can be much longer than the period asked.
   */
  private static long weight(Duration period, Duration observed) {
    long ms = period == null ? 1 : period.toMillis();
    if (observed != null)
      ms = Math.max(ms, Math.round(observed.toNanos() / 1e6));
    return Math.max(1, ms);
  }

  private void addFlakRequest(RecordedEvent e) {
    RecordedThread t = e.getThread();
    String method = e.getString("method");
    int status = e.getInt("status");
    add(new RecordedOperation(t == null ? -1 : t.getJavaThreadId(),
                              t == null ? null : t.getJavaName(),
                              nanos(e.getStartTime()),
                              nanos(e.getEndTime()),
                              FlakOperations.name(e.getString("app"), method, e.getString("route")),
                              method + " " + e.getString("path"),
                              status,
                              status >= 500,
                              status >= 500 ? "HTTP " + status : null,
                              e.getLong("bytesIn"),
                              e.getLong("bytesOut"),
                              false));
  }

  private void addOperation(RecordedEvent e) {
    RecordedThread t = e.getThread();
    RecordedOperation op = new RecordedOperation(t == null ? -1 : t.getJavaThreadId(),
                                                 t == null ? null : t.getJavaName(),
                                                 nanos(e.getStartTime()),
                                                 nanos(e.getEndTime()),
                                                 e.getString("name"),
                                                 e.getString("detail"),
                                                 0,
                                                 e.getBoolean("failed"),
                                                 e.getString("error"),
                                                 e.getLong("bytesIn"),
                                                 e.getLong("bytesOut"),
                                                 e.getBoolean("async"));
    if (op.async()) {
      operations.add(op);
      asyncById.put(e.getLong("id"), op);
    }
    else {
      add(op);
    }
  }

  /**
   * A synchronous operation: its thread worked for it from start to end.
   */
  private void add(RecordedOperation op) {
    operations.add(op);
    index.add(new OperationIndex.Segment(op.threadId(), op.threadName(), op.startNanos(), op.endNanos(), op));
  }

  /**
   * Gives the asynchronous operations their segments, once all are read. The
   * segments of an operation that had not ended when the recording stopped
   * belong to none.
   */
  private void linkSegments() {
    for (RawSegment s : rawSegments) {
      RecordedOperation op = asyncById.get(s.id());
      if (op != null)
        index.add(new OperationIndex.Segment(s.threadId(), s.threadName(), s.startNanos(), s.endNanos(), op));
    }
    rawSegments.clear();
    asyncById.clear();
  }

  /**
   * Called for each execution sample of a Java thread, and each sample of a
   * thread in a native method.
   */
  public interface SampleVisitor {

    /**
     * @param sample    the sample event, with its stack trace
     * @param thread    the thread sampled
     * @param operation the operation the thread was working for, or null
     * @param weight    the time the sample stands for: the sampling period of
     *                  its event, in milliseconds
     */
    void visit(RecordedEvent sample, RecordedThread thread, RecordedOperation operation, long weight);
  }

  /**
   * Reads the samples of the recording, Java and native: together, they show
   * where the wall-clock time goes, including time blocked in native calls
   * (files, sockets).
   */
  public void visitSamples(SampleVisitor visitor) throws IOException {
    try (RecordingFile rf = new RecordingFile(file)) {
      while (rf.hasMoreEvents()) {
        RecordedEvent e = rf.readEvent();
        String name = e.getEventType().getName();
        boolean java = name.equals(EXECUTION_SAMPLE);
        if (!java && !name.equals(NATIVE_SAMPLE))
          continue;

        RecordedThread t = e.getThread("sampledThread");
        if (t == null)
          continue;
        RecordedOperation op = index.find(t.getJavaThreadId(), nanos(e.getStartTime()));
        visitor.visit(e, t, op, java ? javaWeight : nativeWeight);
      }
    }
  }

  /**
   * Reads the events of some types, in no particular order.
   */
  public void visitEvents(Set<String> types, Consumer<RecordedEvent> visitor) throws IOException {
    try (RecordingFile rf = new RecordingFile(file)) {
      while (rf.hasMoreEvents()) {
        RecordedEvent e = rf.readEvent();
        if (types.contains(e.getEventType().getName()))
          visitor.accept(e);
      }
    }
  }

  /**
   * Whether some thread was working for an operation at that time.
   */
  public boolean isOperationRunning(Instant time) {
    return index.isAnyRunning(nanos(time));
  }

  /**
   * The threads that worked for an operation, and when, the earliest first:
   * the whole operation if synchronous, its segments otherwise.
   */
  public List<OperationIndex.Segment> getSegments(RecordedOperation op) {
    return index.segmentsOf(op);
  }

  /**
   * The OS on one line, without the "OS:" that starts it on Windows.
   */
  private static String oneLine(String os) {
    return os == null ? null : os.trim().replaceFirst("^OS:\\s*", "").replaceAll("\\s*\\R\\s*", " ");
  }

  /**
   * A time in nanoseconds since the epoch, as {@link RecordedOperation} holds
   * them.
   */
  public static long nanos(Instant i) {
    return i.getEpochSecond() * 1_000_000_000L + i.getNano();
  }
}
