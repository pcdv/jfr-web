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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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

  private Main() {}

  public static void main(String[] args) {
    Integer helped = help(args, System.out);
    if (helped != null)
      System.exit(helped);
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
    if (args.length == 0) {
      System.err.print(Help.overview());
      return 2;
    }
    String command = args[0];
    if (Help.of(command) == null) {
      System.err.println("Unknown command: " + command + "\n\n" + Help.overview());
      return 2;
    }
    if (args.length < 2) {
      System.err.print(Help.of(command));
      return 2;
    }
    Path file = Path.of(args[1]);
    List<String> options = new ArrayList<>(List.of(args).subList(2, args.length));

    switch (command) {
      case "report":
        boolean events = options.remove("--events");
        if (!checkNoneLeft(options, command))
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
        // open and standalone run until stopped: see main
        System.err.print(Help.of(command));
        return 2;
    }
  }

  /**
   * Prints the help asked for: the overview, or that of a command, with
   * "help", "help <command>", "<command> --help" or "<command> -h".
   *
   * @return the exit status, or null if no help is asked for
   */
  static Integer help(String[] args, PrintStream stdout) {
    List<String> rest = new ArrayList<>(List.of(args));
    // the option before the command
    if (rest.size() >= 2 && rest.get(0).equals("--events"))
      rest = rest.subList(2, rest.size());
    if (rest.isEmpty()) {
      System.err.print(Help.overview());
      return 2;
    }
    String command;
    if (Help.asks(rest.get(0))) {
      if (rest.size() == 1) {
        stdout.print(Help.overview());
        return 0;
      }
      command = rest.get(1);
    }
    else if (rest.stream().anyMatch(a -> a.equals("--help") || a.equals("-h"))) {
      command = rest.get(0);
    }
    else {
      return null;
    }
    String help = Help.of(command);
    if (help == null) {
      System.err.println("Unknown command: " + command + "\n\n" + Help.overview());
      return 2;
    }
    stdout.print(help);
    return 0;
  }

  /** The events of the application read as operations, by default. */
  static final Path EVENT_MAPPINGS = Standalone.defaultDirectory().resolve("operation-events.properties");

  /**
   * Registers the events mapped to operations: those of the default file if
   * it exists, and of --events, before the command (report has an --events
   * option of its own).
   *
   * @return the other arguments
   */
  static String[] eventMappings(String[] args) throws IOException {
    if (Files.isRegularFile(EVENT_MAPPINGS))
      report(EventMappings.load(EVENT_MAPPINGS), EVENT_MAPPINGS);
    if (args.length == 0 || !args[0].equals("--events"))
      return args;
    if (args.length < 2)
      throw new IllegalArgumentException("Missing value after --events");
    Path file = Path.of(args[1]);
    report(EventMappings.load(file), file);
    return Arrays.copyOfRange(args, 2, args.length);
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
      return usage("open", e.getMessage());
    }
    boolean browser = !options.remove("--no-browser");
    boolean allocations = options.remove("--alloc");
    List<Path> recordings = new ArrayList<>();
    for (String o : List.copyOf(options))
      if (!o.startsWith("-")) {
        recordings.add(Path.of(o));
        options.remove(o);
      }
    if (!checkNoneLeft(options, "open"))
      return 2;
    if (recordings.isEmpty())
      return usage("open", "No recording to open");
    Viewer v;
    try {
      v = Viewer.start(recordings, history, port, allocations);
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
    String attach;
    Standalone.Shared shared;
    boolean follow = options.remove("--follow");
    Pattern followed = null;
    try {
      String v = takeValue(options, "--port");
      port = v == null ? Standalone.PORT : Integer.parseInt(v);
      v = takeValue(options, "--dir");
      dir = v == null ? Standalone.defaultDirectory() : Path.of(v);
      attach = takeValue(options, "--attach");
      shared = new Standalone.Shared(path(takeValue(options, "--sessions")), path(takeValue(options, "--history")),
                                     path(takeValue(options, "--snapshots")));
      if (attach == null && !shared.equals(Standalone.Shared.NONE))
        throw new IllegalArgumentException("--sessions, --history and --snapshots go with --attach");
      if (follow) {
        if (attach == null || attach.matches("\\d+"))
          throw new IllegalArgumentException("--follow goes with --attach <regex>");
        followed = Pattern.compile(attach);
      }
    }
    catch (IllegalArgumentException e) {
      return usage("standalone", e.getMessage());
    }
    boolean browser = !options.remove("--no-browser");
    if (!checkNoneLeft(options, "standalone"))
      return 2;
    Standalone s;
    try {
      s = follow ? Standalone.start(dir, port) : Standalone.start(dir, port, attach, shared);
    }
    catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return 2;
    }
    // a session recording ends with its file written
    Runtime.getRuntime().addShutdownHook(new Thread(s::close, "jfr-web shutdown"));
    if (follow) {
      System.err.println("jfr-web at " + s.getUrl() + ", waiting for the JVMs matching " + attach);
      // the page of the first JVM: it lists the sessions of the next ones too
      AtomicBoolean opened = new AtomicBoolean(!browser);
      s.follow(followed, shared, url -> {
        if (!opened.getAndSet(true))
          browse(url);
      });
      return 0;
    }
    System.err.println("jfr-web at " + s.getAttachedUrl());
    if (browser)
      browse(s.getAttachedUrl());
    return 0;
  }

  private static Path path(String v) {
    return v == null ? null : Path.of(v);
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
    String title;
    try {
      String v = takeValue(options, "-o");
      if (v == null)
        throw new IllegalArgumentException("Missing -o <dir>");
      out = Path.of(v);
      v = takeValue(options, "--history");
      history = v == null ? null : Path.of(v);
      title = takeValue(options, "--title");
    }
    catch (IllegalArgumentException e) {
      return usage("export", e.getMessage());
    }
    boolean withRecordings = !options.remove("--no-recordings");
    List<Path> recordings = new ArrayList<>();
    recordings.add(first);
    for (String o : List.copyOf(options))
      if (!o.startsWith("-")) {
        recordings.add(Path.of(o));
        options.remove(o);
      }
    if (!checkNoneLeft(options, "export"))
      return 2;
    StaticExport.export(recordings, history, out, withRecordings, title);
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
      return usage("history", e.getMessage());
    }
    if (!checkNoneLeft(options, "history"))
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
      return usage("flame", e.getMessage());
    }
    o.outsideOperations(options.remove("--all"));
    o.merged(options.remove("--merged"));
    o.reversed(options.remove("--reversed"));
    if (options.remove("--alloc")) {
      o.allocations(true);
      title = title.replace("Wall clock", "Allocations");
    }
    else if (options.remove("--java")) {
      o.javaOnly(true);
      title = title.replace("Wall clock", "Java code");
    }
    boolean collapsed = options.remove("--collapsed");
    if (!checkNoneLeft(options, "flame"))
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

  private static boolean checkNoneLeft(List<String> options, String command) {
    if (options.isEmpty())
      return true;
    usage(command, "Unknown options: " + options);
    return false;
  }

  /**
   * Tells what is wrong in the arguments of a command, and where its help is.
   *
   * @return the exit status
   */
  private static int usage(String command, String message) {
    System.err.println(message + "\n" + Help.hint(command));
    return 2;
  }
}
