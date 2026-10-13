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
            info.os = e.getString("osVersion");
            break;
        }
      }
    }

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

  /**
   * Whether some request was running at that time, on any thread.
   */
  public boolean isRequestRunning(Instant time) {
    return index.isAnyRunning(nanos(time));
  }

  static long nanos(Instant i) {
    return i.getEpochSecond() * 1_000_000_000L + i.getNano();
  }
}
