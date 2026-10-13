package jfrweb.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import jfrweb.history.HistoryReader;

/**
 * A period of an operation history as text: its operations, then the slowest
 * of them.
 */
final class HistoryReport {

  private static final DateTimeFormatter TIME =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

  private HistoryReport() {}

  static void print(Path directory, Instant from, Instant to, String name, PrintStream out) throws IOException {
    HistoryReader r = HistoryReader.read(directory, from, to);
    out.println("History:   " + directory);
    out.println("Period:    " + TIME.format(from) + " to " + TIME.format(to) + " (local time)");
    out.println("Intervals: " + r.getIntervalCount() +
                (r.getIntervalCount() == 0 ? ": the history was not written during this period" : ""));
    if (r.getIntervalCount() == 0)
      return;

    out.println();
    TextReport.printOperations(r.getOperations(), out);

    List<HistoryReader.Slow> slowest = r.getSlowest(name, 20);
    out.println();
    out.println("Slowest operations" + (name != null ? " of " + name : "") + ":");
    if (slowest.isEmpty())
      out.println("  none");
    for (HistoryReader.Slow s : slowest)
      out.printf("  %s  %9s  %-6s  %s  (%s)%n", TIME.format(s.end()), TextReport.ms(s.nanos()), outcome(s),
                 s.detail() != null ? s.detail() : s.name(), s.thread());
  }

  /**
   * The status of a request, or whether another operation failed.
   */
  private static String outcome(HistoryReader.Slow s) {
    if (s.status() != 0)
      return String.valueOf(s.status());
    return s.failed() ? "failed" : "ok";
  }
}
