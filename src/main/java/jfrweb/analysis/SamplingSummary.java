package jfrweb.analysis;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

import static jfrweb.analysis.RecordingInfo.EXECUTION_SAMPLE;

/**
 * How many samples a recording holds, where they were taken, and on which
 * threads.
 */
public final class SamplingSummary {

  private long javaSamples;
  private long nativeSamples;
  private long javaInRequests;
  private long nativeInRequests;
  private long nativeWhileRequests;
  private final Map<String, Long> byThread = new TreeMap<>();

  private SamplingSummary() {}

  public static SamplingSummary of(Analysis analysis) throws IOException {
    SamplingSummary s = new SamplingSummary();
    analysis.visitSamples((sample, thread, request, weight) -> {
      boolean java = sample.getEventType().getName().equals(EXECUTION_SAMPLE);
      if (java) {
        s.javaSamples++;
        if (request != null)
          s.javaInRequests++;
      }
      else {
        s.nativeSamples++;
        if (request != null)
          s.nativeInRequests++;
        if (analysis.isRequestRunning(sample.getStartTime()))
          s.nativeWhileRequests++;
      }
      s.byThread.merge(String.valueOf(thread.getJavaName()), 1L, Long::sum);
    });
    return s;
  }

  public long getJavaSamples() {
    return javaSamples;
  }

  public long getNativeSamples() {
    return nativeSamples;
  }

  public long getJavaInRequests() {
    return javaInRequests;
  }

  public long getNativeInRequests() {
    return nativeInRequests;
  }

  /**
   * The share of the native samples that the threads serving requests got,
   * among those taken while some request ran, or null if none was. JFR
   * samples one thread in native code per period: idle threads take the rest.
   */
  public Double getRequestShareOfNative() {
    return nativeWhileRequests == 0 ? null : (double) nativeInRequests / nativeWhileRequests;
  }

  /**
   * The number of samples of each thread, by name.
   */
  public Map<String, Long> getSamplesByThread() {
    return byThread;
  }
}
