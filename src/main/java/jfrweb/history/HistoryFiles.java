package jfrweb.history;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The layout of a history: {@code <directory>/<yyyy-MM-dd>/<HH>.jsonl}, gzipped
 * as {@code <HH>.jsonl.gz} once the hour is over, dates and hours in UTC.
 */
final class HistoryFiles {

  private HistoryFiles() {}

  /**
   * The file holding the intervals that start in the hour of a time, before
   * it is gzipped.
   */
  static Path hourFile(Path directory, Instant time) {
    String iso = time.atOffset(ZoneOffset.UTC).toLocalDateTime().toString();
    // e.g. 2026-10-13T10:41:00 -> 2026-10-13/10.jsonl
    return directory.resolve(iso.substring(0, 10)).resolve(iso.substring(11, 13) + ".jsonl");
  }

  /**
   * The files of the history, plain or gzipped, in no particular order.
   */
  static List<Path> list(Path directory) throws IOException {
    List<Path> res = new ArrayList<>();
    if (!Files.isDirectory(directory))
      return res;
    try (Stream<Path> days = Files.list(directory)) {
      for (Path day : (Iterable<Path>) days::iterator) {
        if (dateOf(day) == null || !Files.isDirectory(day))
          continue;
        try (Stream<Path> hours = Files.list(day)) {
          hours.filter(f -> hourOf(f) != null).forEach(res::add);
        }
      }
    }
    return res;
  }

  /**
   * The date of a directory of the history, or null if it is not one.
   */
  static LocalDate dateOf(Path day) {
    try {
      return LocalDate.parse(day.getFileName().toString());
    }
    catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * The hour a file of the history holds, or null if it is not one.
   */
  static Instant hourOf(Path file) {
    String name = file.getFileName().toString();
    String hour = name.endsWith(".jsonl") ? name.substring(0, name.length() - 6)
                  : name.endsWith(".jsonl.gz") ? name.substring(0, name.length() - 9)
                  : null;
    LocalDate date = file.getParent() == null ? null : dateOf(file.getParent());
    if (hour == null || date == null || !hour.matches("\\d\\d"))
      return null;
    int h = Integer.parseInt(hour);
    return h < 24 ? date.atTime(h, 0).toInstant(ZoneOffset.UTC) : null;
  }
}
