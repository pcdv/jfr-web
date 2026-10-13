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
import java.util.TreeMap;

import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import static jfrweb.analysis.RecordingInfo.EXECUTION_SAMPLE;
import static jfrweb.analysis.RecordingInfo.NATIVE_SAMPLE;
import static jfrweb.analysis.RecordingInfo.REQUEST;

/**
 * A recording read for analysis. Loading it reads the requests and how it was
 * recorded; the samples, which are many, are read again each time they are
 * visited instead of being held in memory.
 */
public final class Analysis {

  private final Path file;
  private final RecordingInfo info = new RecordingInfo();
  private final List<RecordedRequest> requests = new ArrayList<>();
  private final RequestIndex index = new RequestIndex();

  private Analysis(Path file) {
    this.file = file;
  }

  public static Analysis load(Path file) throws IOException {
    Analysis a = new Analysis(file);
    a.readRequestsAndInfo();
    return a;
  }

  public Path getFile() {
    return file;
  }

  public RecordingInfo getInfo() {
    return info;
  }

  /**
   * The requests of the recording, in no particular order.
   */
  public List<RecordedRequest> getRequests() {
    return Collections.unmodifiableList(requests);
  }

  private void readRequestsAndInfo() throws IOException {
    Map<Long, String> eventNames = new HashMap<>();

    try (RecordingFile rf = new RecordingFile(file)) {
      for (EventType t : rf.readEventTypes())
        eventNames.put(t.getId(), t.getName());

      while (rf.hasMoreEvents()) {
        RecordedEvent e = rf.readEvent();
        updateBounds(e);

        switch (e.getEventType().getName()) {
          case REQUEST:
            addRequest(e);
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
            info.os = e.getString("osVersion");
            break;
        }
      }
    }
  }

  private void updateBounds(RecordedEvent e) {
    Instant start = e.getStartTime();
    Instant end = e.getEndTime();
    if (info.start == null || start.isBefore(info.start))
      info.start = start;
    if (info.end == null || end.isAfter(info.end))
      info.end = end;
  }

  private void addRequest(RecordedEvent e) {
    RecordedThread t = e.getThread();
    RecordedRequest r = new RecordedRequest(t == null ? -1 : t.getJavaThreadId(),
                                            t == null ? null : t.getJavaName(),
                                            nanos(e.getStartTime()),
                                            nanos(e.getEndTime()),
                                            e.getString("app"),
                                            e.getString("method"),
                                            e.getString("route"),
                                            e.getString("path"),
                                            e.getInt("status"),
                                            e.getLong("bytesIn"),
                                            e.getLong("bytesOut"));
    requests.add(r);
    index.add(r);
  }

  /**
   * Called for each execution sample of a Java thread, and each sample of a
   * thread in a native method.
   */
  public interface SampleVisitor {

    /**
     * @param sample  the sample event, with its stack trace
     * @param thread  the thread sampled
     * @param request the request the thread was serving, or null
     * @param weight  the time the sample stands for: the sampling period of
     *                its event, in milliseconds
     */
    void visit(RecordedEvent sample, RecordedThread thread, RecordedRequest request, long weight);
  }

  /**
   * Reads the samples of the recording, Java and native: together, they show
   * where the wall-clock time goes, including time blocked in native calls
   * (files, sockets).
   */
  public void visitSamples(SampleVisitor visitor) throws IOException {
    long javaWeight = weight(EXECUTION_SAMPLE);
    long nativeWeight = weight(NATIVE_SAMPLE);

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
        RecordedRequest r = index.find(t.getJavaThreadId(), nanos(e.getStartTime()));
        visitor.visit(e, t, r, java ? javaWeight : nativeWeight);
      }
    }
  }

  private long weight(String event) {
    Duration period = info.getPeriod(event);
    return period == null ? 1 : Math.max(1, period.toMillis());
  }

  static long nanos(Instant i) {
    return i.getEpochSecond() * 1_000_000_000L + i.getNano();
  }
}
