package jfrweb.cli;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The help of the command line: an overview of the commands, and the options
 * and examples of each one.
 */
final class Help {

  private static final Map<String, String> COMMANDS = new LinkedHashMap<>();

  static {
    COMMANDS.put("open", """
      Usage: jfr-web open <file.jfr | dir>... [options]

      Opens recordings made elsewhere in the web interface, each one as a session:
      report, timeline, operations and flame graphs. A directory gives all its .jfr files.

      Options:
        --history <dir>   an operation history, shown next to the recordings
        --port <port>     the port of the interface, a free one by default
        --no-browser      print the address of the interface, without opening a browser
        --alloc           open the flame graphs on the allocations, not the wall clock

      Examples:
        jfr-web open app.jfr
        jfr-web open logs/snapshots --history logs/operations
      """);

    COMMANDS.put("standalone", """
      Usage: jfr-web standalone [options]

      Profiles the other JVMs of this machine, with nothing to install in them. A page
      lists the JVMs, and the one picked is profiled as if jfr-web was embedded in it.
      Its sessions are kept in <dir>/<application>.

      Options:
        --port <port>            the port of the interface, 8095 by default
        --dir <dir>              where the sessions are kept, ~/.jfr-web by default
        --no-browser             print the address of the interface, without opening a browser
        --attach <pid | regex>   attach to a JVM at once: its pid, or a regex found in its
                                 name (its main class or jar, then its arguments)
        --follow                 with --attach <regex>: wait for each new JVM that matches,
                                 and record a session of it until it exits

      With --attach, for a JVM that embeds jfr-web but does not tell its directories
      over JMX (recent versions do):
        --sessions <dir>         the sessions of the profiler embedded
        --history <dir>          its operation history, only read
        --snapshots <dir>        its snapshots, only read
      Start sessions from one of the two profilers at a time.

      Examples:
        jfr-web standalone
        jfr-web standalone --attach myapp
        jfr-web standalone --attach 12345 --port 8096
        jfr-web standalone --attach "Gradle Test Executor" --follow    (then: ./gradlew test)
      """);

    COMMANDS.put("report", """
      Usage: jfr-web report <file.jfr> [--events]

      Prints how a recording was made (time, JVM, sampling, known biases, events), then
      its operations sorted by total time.

      Options:
        --events   list every enabled event, with its settings

      Example:
        jfr-web report app.jfr
      """);

    COMMANDS.put("flame", """
      Usage: jfr-web flame <file.jfr> [options]

      Writes a wall-clock flame graph of the samples taken in operations, rooted by
      operation (e.g. "GET /api/user/:id"). The page works opened from the disk, and its
      filters stay changeable. Without -o, it is written to the standard output.

      Options:
        -o <file.html>        the file to write
        --operation <regex>   keep the samples of the operations whose name matches
        --thread <regex>      keep the samples of the threads whose name matches
        --all                 add the samples taken outside operations
        --merged              merge the operations into one tree
        --reversed            put the methods running at the root (hot methods)
        --java                keep the samples of Java code only
        --alloc               take the allocation samples instead, weighing bytes, the
                              class allocated on top
        --collapsed           write collapsed stacks instead of a page, one line per
                              stack: the input of other flame graph tools

      Examples:
        jfr-web flame app.jfr -o flame.html
        jfr-web flame app.jfr -o api.html --operation 'GET /api/.*'
        jfr-web flame app.jfr --alloc --collapsed > alloc.txt
      """);

    COMMANDS.put("history", """
      Usage: jfr-web history <dir> [options]

      Prints the operations of a period of an operation history, then the slowest ones.
      The period is the last hour by default.

      Options:
        --from <time>        the start of the period, an hour before its end by default
        --to <time>          the end of the period, now by default
        --operation <name>   the slowest calls of this operation only

      A time is local, like 2026-10-13T10:00, or in UTC, like 2026-10-13T08:00:00Z.

      Examples:
        jfr-web history logs/operations
        jfr-web history logs/operations --from 2026-10-13T10:00 --to 2026-10-13T11:00
      """);

    COMMANDS.put("export", """
      Usage: jfr-web export <file.jfr>... -o <dir> [options]

      Writes the web interface and the recordings as a static site, with the responses
      computed beforehand: any web server can host it, with no Java behind it.

      Options:
        -o <dir>          the directory to write
        --history <dir>   an operation history, shown next to the recordings
        --title <text>    shown in the top bar, in place of jfr-web
        --no-recordings   leave the .jfr files out: a recording holds the environment
                          variables and system properties of the JVM, unless turned off

      Examples:
        jfr-web export app.jfr -o site/
        jfr-web export app.jfr other.jfr -o site/ --history logs/operations --title "Checkout"
      """);
  }

  private static final String OVERVIEW = """
    Usage: jfr-web [--events <file>] <command> [options]

    Commands:
      open <file.jfr | dir>...        show recordings in the web interface
      standalone                      profile the other JVMs of this machine
      report <file.jfr>               how a recording was made, and its operations
      flame <file.jfr>                a flame graph of a recording, as a page
      history <dir>                   the operations of a period of an operation history
      export <file.jfr>... -o <dir>   the web interface and recordings, as a static site

    Before the command:
      --events <file>   the JFR events of the application to read as operations; the
                        file ~/.jfr-web/operation-events.properties is read too, if it exists

    Examples:
      jfr-web open app.jfr
      jfr-web standalone
      jfr-web standalone --attach "Gradle Test Executor" --follow
      jfr-web flame app.jfr -o flame.html

    Run "jfr-web help <command>" for the options of a command, and more examples.
    """;

  private Help() {}

  static String overview() {
    return OVERVIEW;
  }

  /**
   * The help of a command, or null if there is no such command.
   */
  static String of(String command) {
    return COMMANDS.get(command);
  }

  /**
   * Whether an argument asks for help.
   */
  static boolean asks(String arg) {
    return arg.equals("help") || arg.equals("--help") || arg.equals("-h");
  }

  /**
   * Where to read more, after an error in the arguments of a command.
   */
  static String hint(String command) {
    return "Run \"jfr-web help " + command + "\" for its options and examples.";
  }
}
