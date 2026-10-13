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
 * A period of an endpoint history as text: its endpoints, then its slowest
 * requests.
 */
final class HistoryReport {

  private static final DateTimeFormatter TIME =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

  private HistoryReport() {}

  static void print(Path directory, Instant from, Instant to, String endpoint, PrintStream out) throws IOException {
    HistoryReader r = HistoryReader.read(directory, from, to);
    out.println("History:   " + directory);
    out.println("Period:    " + TIME.format(from) + " to " + TIME.format(to) + " (local time)");
    out.println("Intervals: " + r.getIntervalCount() +
                (r.getIntervalCount() == 0 ? ": the history was not written during this period" : ""));
    if (r.getIntervalCount() == 0)
      return;

    out.println();
    TextReport.printEndpoints(r.getEndpoints(), out);

    List<HistoryReader.Slow> slowest = r.getSlowest(endpoint, 20);
    out.println();
    out.println("Slowest requests" + (endpoint != null ? " of " + endpoint : "") + ":");
    if (slowest.isEmpty())
      out.println("  none");
    for (HistoryReader.Slow s : slowest)
      out.printf("  %s  %9s  %3d  %s  (%s)%n", TIME.format(s.end()), TextReport.ms(s.nanos()), s.status(),
                 s.path(), s.thread());
  }
}
