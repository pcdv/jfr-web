package jfrweb.cli;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jfrweb.analysis.Analysis;
import jfrweb.analysis.FlameGraphs;

/**
 * Analyzes a recording offline.
 */
public final class Main {

  private static final String USAGE = String.join("\n",
    "Usage:",
    "  jfr-web report <file.jfr> [--events]",
    "      how the file was recorded, and its endpoints sorted by total time",
    "  jfr-web flame <file.jfr> [-o <file.html>] [--endpoint <regex>] [--thread <regex>]",
    "                           [--all] [--collapsed]",
    "      wall-clock flame graph of the samples taken in requests, rooted by",
    "      endpoint (e.g. \"GET /api/user/:id\"); --all adds the other samples",
    "  jfr-web history <dir> [--from <time>] [--to <time>] [--endpoint <endpoint>]",
    "      the endpoints and slowest requests of a period of an endpoint history,",
    "      the last hour by default; times like 2026-10-13T10:00 (local time) or",
    "      2026-10-13T08:00:00Z");

  private Main() {}

  public static void main(String[] args) {
    try {
      System.exit(run(args, System.out));
    }
    catch (IOException e) {
      System.err.println("Error: " + e.getMessage());
      System.exit(1);
    }
  }

  /**
   * @return the exit status
   */
  static int run(String[] args, PrintStream stdout) throws IOException {
    if (args.length < 2) {
      System.err.println(USAGE);
      return 2;
    }

    String command = args[0];
    Path file = Path.of(args[1]);
    List<String> options = new ArrayList<>(List.of(args).subList(2, args.length));

    switch (command) {
      case "report":
        boolean events = options.remove("--events");
        if (!checkNoneLeft(options))
          return 2;
        new TextReport(Analysis.load(file), stdout).print(events);
        return 0;

      case "flame":
        return flame(file, options, stdout);

      case "history":
        return history(file, options, stdout);

      default:
        System.err.println("Unknown command: " + command + "\n" + USAGE);
        return 2;
    }
  }

  private static int history(Path dir, List<String> options, PrintStream stdout) throws IOException {
    Instant from;
    Instant to;
    String endpoint;
    try {
      String v = takeValue(options, "--to");
      to = v == null ? Instant.now() : time(v);
      v = takeValue(options, "--from");
      from = v == null ? to.minus(Duration.ofHours(1)) : time(v);
      endpoint = takeValue(options, "--endpoint");
    }
    catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return 2;
    }
    if (!checkNoneLeft(options))
      return 2;
    HistoryReport.print(dir, from, to, endpoint, stdout);
    return 0;
  }

  /**
   * An ISO-8601 instant, or a date and time in the local time zone.
   */
  private static Instant time(String s) {
    try {
      return Instant.parse(s);
    }
    catch (DateTimeParseException e) {
      try {
        return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant();
      }
      catch (DateTimeParseException e2) {
        throw new IllegalArgumentException("Not a time: " + s + " (e.g. 2026-10-13T10:00 or 2026-10-13T08:00:00Z)");
      }
    }
  }

  private static int flame(Path file, List<String> options, PrintStream stdout) throws IOException {
    FlameGraphs.Options o = new FlameGraphs.Options();
    String title = "Wall clock";
    Path output = null;

    try {
      String v;
      if ((v = takeValue(options, "-o")) != null)
        output = Path.of(v);
      if ((v = takeValue(options, "--endpoint")) != null) {
        Pattern p = Pattern.compile(v);
        o.requests(r -> p.matcher(r.endpoint()).matches());
        title += ", " + v;
      }
      if ((v = takeValue(options, "--thread")) != null) {
        o.thread(Pattern.compile(v));
        title += ", threads " + v;
      }
    }
    catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return 2;
    }
    o.outsideRequests(options.remove("--all"));
    o.collapsed(options.remove("--collapsed"));
    if (!checkNoneLeft(options))
      return 2;

    o.title(title + " (ms)");
    Analysis analysis = Analysis.load(file);
    if (output == null) {
      FlameGraphs.write(analysis, o, stdout);
    }
    else {
      try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(output));
           PrintStream ps = new PrintStream(os, false, StandardCharsets.UTF_8)) {
        FlameGraphs.write(analysis, o, ps);
      }
      System.err.println("Wrote " + output);
    }
    return 0;
  }

  /**
   * Removes an option and its value.
   *
   * @return the value, or null if the option is absent
   */
  private static String takeValue(List<String> options, String name) {
    int i = options.indexOf(name);
    if (i < 0)
      return null;
    if (i + 1 >= options.size())
      throw new IllegalArgumentException("Missing value after " + name);
    options.remove(i);
    return options.remove(i);
  }

  private static boolean checkNoneLeft(List<String> options) {
    if (options.isEmpty())
      return true;
    System.err.println("Unknown options: " + options + "\n" + USAGE);
    return false;
  }
}
