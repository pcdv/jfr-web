package jfrweb.cli;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
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
import jfrweb.analysis.EventMapping;
import jfrweb.analysis.EventMappings;
import jfrweb.analysis.FlameGraphs;
import jfrweb.export.FlamePage;
import jfrweb.export.StaticExport;
import jfrweb.standalone.Standalone;
import jfrweb.standalone.Viewer;

/**
 * Analyzes a recording offline.
 */
public final class Main {

  private static final String USAGE = String.join("\n",
    "Usage: jfr-web [--events <file>] <command> ...",
    "  --events: the events of the application to read as operations, see EventMappings;",
    "  ~/.jfr-web/operation-events.properties is read too if it exists",
    "",
    "  jfr-web report <file.jfr> [--events]",
    "      how the file was recorded, and its operations sorted by total time",
    "  jfr-web flame <file.jfr> [-o <file.html>] [--operation <regex>] [--thread <regex>]",
    "                           [--all] [--merged] [--reversed] [--java] [--collapsed]",
    "      wall-clock flame graph of the samples taken in operations, rooted by",
    "      operation (e.g. \"GET /api/user/:id\"); --all adds the other samples,",
    "      --merged merges the operations into one tree, --reversed puts the methods",
    "      running at the root, --java keeps the samples of Java code only. A page that",
    "      works opened from the disk, its filters changeable; --operation and --thread",
    "      keep the samples it holds. --collapsed: collapsed stacks instead, one line each",
    "  jfr-web history <dir> [--from <time>] [--to <time>] [--operation <name>]",
    "      the operations and slowest operations of a period of an operation history,",
    "      the last hour by default; times like 2026-10-13T10:00 (local time) or",
    "      2026-10-13T08:00:00Z",
    "  jfr-web export <file.jfr>... -o <dir> [--history <dir>] [--no-recordings]",
    "      the web interface and the recordings as a static site, with the responses",
    "      computed beforehand: served by any web server, with no Java behind it.",
    "      A recording holds the environment variables and system properties of the",
    "      JVM unless turned off: --no-recordings leaves the .jfr files out",
    "  jfr-web open <file.jfr | dir>... [--history <dir>] [--port <port>] [--no-browser]",
    "      the web interface on recordings made elsewhere, each one as a session: report,",
    "      timeline, operations, flame graphs; the .jfr files of a directory are taken",
    "  jfr-web standalone [--port <port>] [--dir <dir>] [--no-browser]",
    "      the profiler on its own: lists the JVMs of this machine, and profiles the",
    "      one picked as if it was embedded, with nothing to install in it. Sessions",
    "      are kept in <dir>/<application>, ~/.jfr-web by default; port 8095 by default");

  private Main() {}

  public static void main(String[] args) {
    try {
      args = eventMappings(args);
    }
    catch (IOException | IllegalArgumentException e) {
      System.err.println("Error: " + e.getMessage());
      System.exit(2);
    }
    if (args.length > 0 && (args[0].equals("standalone") || args[0].equals("open"))) {
      // runs until stopped
      try {
        List<String> options = new ArrayList<>(List.of(args).subList(1, args.length));
        int status = args[0].equals("open") ? open(options) : standalone(options);
        if (status != 0)
          System.exit(status);
      }
      catch (IOException e) {
        System.err.println("Error: " + e.getMessage());
        System.exit(1);
      }
      return;
    }
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

      case "export":
        return export(file, options);

      default:
        System.err.println("Unknown command: " + command + "\n" + USAGE);
        return 2;
    }
  }

  /** The events of the application read as operations, by default. */
  static final Path EVENT_MAPPINGS = Standalone.defaultDirectory().resolve("operation-events.properties");

  /**
   * Registers the events mapped to operations: those of the default file if
   * it exists, and of --events.
   *
   * @return the other arguments
   */
  static String[] eventMappings(String[] args) throws IOException {
    List<String> rest = new ArrayList<>(List.of(args));
    if (Files.isRegularFile(EVENT_MAPPINGS))
      report(EventMappings.load(EVENT_MAPPINGS), EVENT_MAPPINGS);
    String file = takeValue(rest, "--events");
    if (file != null)
      report(EventMappings.load(Path.of(file)), Path.of(file));
    return rest.toArray(new String[0]);
  }

  private static void report(List<EventMapping> mappings, Path file) {
    for (EventMapping m : mappings)
      System.err.println("Operations from " + m.event() + ", named " + m.name() + " (" + file + ")");
  }

  private static int open(List<String> options) throws IOException {
    int port;
    Path history;
    try {
      String v = takeValue(options, "--port");
      port = v == null ? 0 : Integer.parseInt(v);
      v = takeValue(options, "--history");
      history = v == null ? null : Path.of(v);
    }
    catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return 2;
    }
    boolean browser = !options.remove("--no-browser");
    List<Path> recordings = new ArrayList<>();
    for (String o : List.copyOf(options))
      if (!o.startsWith("-")) {
        recordings.add(Path.of(o));
        options.remove(o);
      }
    if (!checkNoneLeft(options))
      return 2;
    if (recordings.isEmpty()) {
      System.err.println("No recording to open\n" + USAGE);
      return 2;
    }
    Viewer v;
    try {
      v = Viewer.start(recordings, history, port);
    }
    catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return 2;
    }
    Runtime.getRuntime().addShutdownHook(new Thread(v::close, "jfr-web shutdown"));
    System.err.println("jfr-web at " + v.getUrl() + ", " + v.getSessions().list().size() + " recording(s)");
    if (browser)
      browse(v.getUrl());
    return 0;
  }

  private static int standalone(List<String> options) throws IOException {
    int port;
    Path dir;
    try {
      String v = takeValue(options, "--port");
      port = v == null ? Standalone.PORT : Integer.parseInt(v);
      v = takeValue(options, "--dir");
      dir = v == null ? Standalone.defaultDirectory() : Path.of(v);
    }
    catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return 2;
    }
    boolean browser = !options.remove("--no-browser");
    if (!checkNoneLeft(options))
      return 2;
    Standalone s = Standalone.start(dir, port);
    // a session recording ends with its file written
    Runtime.getRuntime().addShutdownHook(new Thread(s::close, "jfr-web shutdown"));
    System.err.println("jfr-web at " + s.getUrl() + ", sessions in " + s.getDirectory());
    if (browser)
      browse(s.getUrl());
    return 0;
  }

  private static void browse(String url) {
    try {
      if (java.awt.Desktop.isDesktopSupported()
          && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
        java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        return;
      }
    }
    catch (IOException | RuntimeException e) {
      // said below
    }
    System.err.println("Open " + url + " in a browser");
  }

  private static int export(Path first, List<String> options) throws IOException {
    Path out;
    Path history;
    try {
      String v = takeValue(options, "-o");
      if (v == null)
        throw new IllegalArgumentException("Missing -o <dir>");
      out = Path.of(v);
      v = takeValue(options, "--history");
      history = v == null ? null : Path.of(v);
    }
    catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return 2;
    }
    boolean withRecordings = !options.remove("--no-recordings");
    List<Path> recordings = new ArrayList<>();
    recordings.add(first);
    for (String o : List.copyOf(options))
      if (!o.startsWith("-")) {
        recordings.add(Path.of(o));
        options.remove(o);
      }
    if (!checkNoneLeft(options))
      return 2;
    StaticExport.export(recordings, history, out, withRecordings);
    System.err.println("Wrote " + out + ": serve it with any web server");
    return 0;
  }

  private static int history(Path dir, List<String> options, PrintStream stdout) throws IOException {
    Instant from;
    Instant to;
    String operation;
    try {
      String v = takeValue(options, "--to");
      to = v == null ? Instant.now() : time(v);
      v = takeValue(options, "--from");
      from = v == null ? to.minus(Duration.ofHours(1)) : time(v);
      operation = takeValue(options, "--operation");
    }
    catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return 2;
    }
    if (!checkNoneLeft(options))
      return 2;
    HistoryReport.print(dir, from, to, operation, stdout);
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
      if ((v = takeValue(options, "--operation")) != null) {
        Pattern p = Pattern.compile(v);
        o.operations(op -> p.matcher(op.name()).matches());
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
    o.outsideOperations(options.remove("--all"));
    o.merged(options.remove("--merged"));
    o.reversed(options.remove("--reversed"));
    if (options.remove("--java")) {
      o.javaOnly(true);
      title = title.replace("Wall clock", "Java code");
    }
    boolean collapsed = options.remove("--collapsed");
    if (!checkNoneLeft(options))
      return 2;

    Analysis analysis = Analysis.load(file);
    String source = file.getFileName().toString();
    if (output == null) {
      flame(analysis, o, collapsed, title, source, stdout);
    }
    else {
      try (PrintStream ps = new PrintStream(new BufferedOutputStream(Files.newOutputStream(output)), false,
                                            StandardCharsets.UTF_8)) {
        flame(analysis, o, collapsed, title, source, ps);
      }
      System.err.println("Wrote " + output);
    }
    return 0;
  }

  /** Collapsed stacks, or a page of their flame graph. */
  private static void flame(Analysis analysis, FlameGraphs.Options o, boolean collapsed, String title, String source,
                            PrintStream out) throws IOException {
    if (collapsed) {
      FlameGraphs.write(analysis, o, out);
      return;
    }
    Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    FlamePage.write(analysis, o, title, source, w);
    w.flush();
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
