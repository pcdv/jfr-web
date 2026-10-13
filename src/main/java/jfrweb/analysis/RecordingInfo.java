package jfrweb.analysis;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How a recording was made: the JVM, its arguments, and the settings of the
 * events, which every report must state.
 */
public final class RecordingInfo {

  public static final String EXECUTION_SAMPLE = "jdk.ExecutionSample";
  public static final String NATIVE_SAMPLE = "jdk.NativeMethodSample";
  public static final String REQUEST = "flak.Request";

  /**
   * What sampling with JFR hides or distorts, stated by every report.
   */
  public static final List<String> KNOWN_BIASES = List.of(
    "JFR samples a few threads per period, not all of them: a short request gets few samples, or none.",
    "Threads blocked on a lock, sleeping or parked are not sampled: only Java code and native calls show.",
    "Idle threads blocked in native code (selectors, accept) take part of the native samples.",
    "Work handed to another thread (executor) is not attributed to the request that started it.",
    "A sample whose stack JFR fails to walk is dropped: tight loops and intrinsics (e.g. hashing) "
    + "get few samples, or none, and are under-represented.");

  String jvmName;
  String jvmVersion;
  String jvmArguments;
  String javaArguments;
  String os;
  Instant start;
  Instant end;

  /** Settings of each event, by event name then setting name. */
  final Map<String, Map<String, String>> settings = new TreeMap<>();

  public String getJvmName() {
    return jvmName;
  }

  public String getJvmVersion() {
    return jvmVersion;
  }

  public String getJvmArguments() {
    return jvmArguments;
  }

  public String getJavaArguments() {
    return javaArguments;
  }

  public String getOs() {
    return os;
  }

  public Instant getStart() {
    return start;
  }

  public Instant getEnd() {
    return end;
  }

  /**
   * The settings of an event, e.g. "enabled" and "period", empty if the
   * recording does not mention it.
   */
  public Map<String, String> getSettings(String event) {
    return settings.getOrDefault(event, Collections.emptyMap());
  }

  /**
   * The events that were enabled, with their settings.
   */
  public Map<String, Map<String, String>> getEnabledEvents() {
    Map<String, Map<String, String>> res = new TreeMap<>();
    settings.forEach((event, s) -> {
      if ("true".equals(s.get("enabled")))
        res.put(event, s);
    });
    return res;
  }

  public boolean isEnabled(String event) {
    return "true".equals(getSettings(event).get("enabled"));
  }

  /**
   * The sampling period of an event, or null if it is not enabled or has no
   * period.
   */
  public Duration getPeriod(String event) {
    return isEnabled(event) ? parseDuration(getSettings(event).get("period")) : null;
  }

  /**
   * Parses a duration as JFR settings write it, e.g. "20 ms".
   *
   * @return the duration, or null if the value is not one, e.g. "everyChunk"
   */
  static Duration parseDuration(String value) {
    if (value == null)
      return null;
    String[] parts = value.trim().split("\\s+");
    if (parts.length != 2)
      return null;
    long n;
    try {
      n = Long.parseLong(parts[0]);
    }
    catch (NumberFormatException e) {
      return null;
    }
    switch (parts[1]) {
      case "ns": return Duration.ofNanos(n);
      case "us": return Duration.ofNanos(n * 1000);
      case "ms": return Duration.ofMillis(n);
      case "s": return Duration.ofSeconds(n);
      case "m": return Duration.ofMinutes(n);
      case "h": return Duration.ofHours(n);
      case "d": return Duration.ofDays(n);
      default: return null;
    }
  }
}
