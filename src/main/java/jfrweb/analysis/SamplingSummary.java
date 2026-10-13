package jfrweb.analysis;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

import jdk.jfr.consumer.RecordedStackTrace;

import static jfrweb.analysis.RecordingInfo.EXECUTION_SAMPLE;

/**
 * How many samples a recording holds, where they were taken, and on which
 * threads.
 */
public final class SamplingSummary {

  private long javaSamples;
  private long nativeSamples;
  private long javaInOperations;
  private long nativeInOperations;
  private long nativeWhileOperations;
  private long truncated;
  private int stackDepth;
  private final Map<String, Long> byThread = new TreeMap<>();

  private SamplingSummary() {}

  public static SamplingSummary of(Analysis analysis) throws IOException {
    SamplingSummary s = new SamplingSummary();
    analysis.visitSamples((sample, thread, operation, weight) -> {
      boolean java = sample.getEventType().getName().equals(EXECUTION_SAMPLE);
      if (java) {
        s.javaSamples++;
        if (operation != null)
          s.javaInOperations++;
      }
      else {
        s.nativeSamples++;
        if (operation != null)
          s.nativeInOperations++;
        if (analysis.isOperationRunning(sample.getStartTime()))
          s.nativeWhileOperations++;
      }
      RecordedStackTrace st = sample.getStackTrace();
      if (st != null && st.isTruncated()) {
        s.truncated++;
        // JFR keeps that many frames of a stack it cuts
        s.stackDepth = Math.max(s.stackDepth, st.getFrames().size());
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

  public long getJavaInOperations() {
    return javaInOperations;
  }

  public long getNativeInOperations() {
    return nativeInOperations;
  }

  /**
   * The share of the native samples that the threads working for operations
   * got, among those taken while some operation ran, or null if none was. JFR
   * samples one thread in native code per period: idle threads take the rest.
   */
  public Double getOperationShareOfNative() {
    return nativeWhileOperations == 0 ? null : (double) nativeInOperations / nativeWhileOperations;
  }

  /**
   * The number of samples, Java and native, whose stack JFR cut: their bottom
   * frames are lost, and flame graphs show their top under "(truncated)".
   */
  public long getTruncated() {
    return truncated;
  }

  /**
   * The number of frames JFR kept of each stack, as the truncated ones show,
   * or 0 if none was.
   */
  public int getStackDepth() {
    return stackDepth;
  }

  /**
   * The number of samples of each thread, by name.
   */
  public Map<String, Long> getSamplesByThread() {
    return byThread;
  }
}
