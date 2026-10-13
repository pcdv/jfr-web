package jfrweb.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jfrweb.analysis.Analysis;
import jfrweb.analysis.EndpointStats;
import jfrweb.analysis.RecordingInfo;
import jfrweb.analysis.SamplingSummary;

import static jfrweb.analysis.RecordingInfo.EXECUTION_SAMPLE;
import static jfrweb.analysis.RecordingInfo.NATIVE_SAMPLE;
import static jfrweb.analysis.RecordingInfo.REQUEST;

/**
 * The report of a recording as text: how it was recorded, then its
 * endpoints.
 */
final class TextReport {

  private final Analysis analysis;
  private final PrintStream out;

  TextReport(Analysis analysis, PrintStream out) {
    this.analysis = analysis;
    this.out = out;
  }

  void print(boolean allEvents) throws IOException {
    printHeader(allEvents);
    out.println();
    printEndpoints();
  }

  private void printHeader(boolean allEvents) throws IOException {
    RecordingInfo info = analysis.getInfo();
    out.println("Recording:      " + analysis.getFile());
    if (info.getStart() != null)
      out.println("Time:           " + info.getStart().truncatedTo(ChronoUnit.MILLIS) + ", " +
                  ms(Duration.between(info.getStart(), info.getEnd()).toNanos()));
    // the version starts with the name of the JVM
    out.println("JVM:            " + info.getJvmVersion());
    out.println("JVM arguments:  " + info.getJvmArguments());
    out.println("Java arguments: " + info.getJavaArguments());
    out.println("OS:             " + os(info.getOs()));

    SamplingSummary s = SamplingSummary.of(analysis);

    out.println();
    out.println("Sampling:");
    out.println("  Java code      " + period(EXECUTION_SAMPLE) + ", " + s.getJavaSamples() +
                " samples, " + s.getJavaInRequests() + " in requests");
    out.println("  native calls   " + period(NATIVE_SAMPLE) + ", " + s.getNativeSamples() +
                " samples, " + s.getNativeInRequests() + " in requests");
    if (s.getRequestShareOfNative() != null)
      out.println(String.format(Locale.ROOT,
                                "                 one thread sampled per period: while requests ran, " +
                                "their threads got %.0f%% of the native samples, idle threads the rest." +
                                "%n                 Read native time in requests as proportions, " +
                                "not as absolute time.",
                                100 * s.getRequestShareOfNative()));
    out.println("  requests       " + (info.isEnabled(REQUEST)
                                       ? analysis.getRequests().size() + " (" + REQUEST + ")"
                                       : REQUEST + " not enabled: no route context"));

    out.println();
    out.println("Known biases:");
    for (String b : RecordingInfo.KNOWN_BIASES)
      out.println("  - " + b);

    Map<String, Map<String, String>> enabled = info.getEnabledEvents();
    out.println();
    if (allEvents) {
      out.println("Enabled events:");
      enabled.forEach((event, settings) -> out.println("  " + event + " " + settings));
    }
    else {
      out.println("Enabled events: " + enabled.size() + " (--events to list them)");
    }
  }

  private String period(String event) {
    Duration p = analysis.getInfo().getPeriod(event);
    if (p == null)
      return "off";
    Duration observed = analysis.getInfo().getObservedInterval(event);
    if (observed == null || observed.compareTo(p.multipliedBy(5).dividedBy(4)) <= 0)
      return "every " + ms(p.toNanos());
    return "every " + ms(p.toNanos()) + " asked, " + ms(observed.toNanos()) + " observed";
  }

  private void printEndpoints() {
    List<EndpointStats> stats = EndpointStats.of(analysis.getRequests());
    if (stats.isEmpty()) {
      out.println("No requests.");
      return;
    }

    int width = Math.max("Endpoint".length(),
                         stats.stream().mapToInt(s -> s.endpoint().length()).max().orElse(0));
    String format = "%-" + width + "s %7s %10s %9s %9s %9s %6s %9s %9s%n";
    out.printf(format, "Endpoint", "Count", "Total", "p50", "p99", "Max", "5xx", "In", "Out");
    for (EndpointStats s : stats) {
      out.printf(format,
                 s.endpoint(),
                 s.count(),
                 ms(s.totalNanos()),
                 ms(s.p50Nanos()),
                 ms(s.p99Nanos()),
                 ms(s.maxNanos()),
                 s.errors(),
                 bytes(s.bytesIn()),
                 bytes(s.bytesOut()));
    }
  }

  // Locale.ROOT: the same output on every machine

  static String ms(long nanos) {
    double ms = nanos / 1e6;
    if (ms >= 10_000)
      return String.format(Locale.ROOT, "%.1f s", ms / 1000);
    if (ms >= 100)
      return String.format(Locale.ROOT, "%.0f ms", ms);
    return String.format(Locale.ROOT, "%.1f ms", ms);
  }

  static String bytes(long n) {
    if (n < 1024)
      return n + " B";
    if (n < 1024 * 1024)
      return String.format(Locale.ROOT, "%.1f KiB", n / 1024.0);
    return String.format(Locale.ROOT, "%.1f MiB", n / (1024.0 * 1024));
  }

  /**
   * The OS on one line, without the "OS:" that starts it on Windows.
   */
  private static String os(String s) {
    return s == null ? null : s.trim().replaceFirst("^OS:\\s*", "").replaceAll("\\s*\\R\\s*", " ");
  }
}
