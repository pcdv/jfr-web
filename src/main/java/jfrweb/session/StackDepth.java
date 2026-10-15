package jfrweb.session;

import java.lang.management.ManagementFactory;

import javax.management.ObjectName;

import jdk.jfr.FlightRecorder;

/**
 * The number of frames JFR keeps of each stack: 64 by default, which cuts the
 * bottom of the stacks of deep applications, and with it the operation they
 * belong to in a flame graph. It is set once per JVM, before its first
 * recording: by {@code -XX:FlightRecorderOptions:stackdepth=}, or by the
 * JFR.configure diagnostic command, which this calls from inside the JVM.
 */
public final class StackDepth {

  private static final System.Logger log = System.getLogger(StackDepth.class.getName());

  /** What the profiler and the snapshots ask for: deep enough for most applications. */
  public static final int DEFAULT = 256;

  /** The most JFR accepts. */
  public static final int MAX = 2048;

  private StackDepth() {}

  /**
   * A depth to ask for when stacks were cut at the one given: four times
   * deeper, at least the default.
   */
  public static int suggest(int truncatedAt) {
    return Math.min(MAX, Math.max(DEFAULT, truncatedAt * 4));
  }

  /**
   * Raises the depth of the stacks JFR records, if it can still be changed:
   * JFR has not recorded yet in this JVM, and the command line does not set
   * it.
   *
   * @param depth a number of frames, at most {@link #MAX}
   * @return whether it was set
   */
  public static synchronized boolean raise(int depth) {
    if (depth <= 0 || depth > MAX)
      throw new IllegalArgumentException("Stack depth out of 1.." + MAX + ": " + depth);
    if (FlightRecorder.isInitialized()) {
      // e.g. a tool attached to the JVM recorded first: worth telling, as
      // the stacks may come cut
      int current = current();
      if (current < depth)
        log.log(System.Logger.Level.INFO, "JFR already recorded in this JVM, its stack depth stays at "
                                          + (current > 0 ? current + " frames" : "what it was")
                                          + ": start the JVM with -XX:FlightRecorderOptions:stackdepth="
                                          + depth + " for deeper stacks");
      return false;
    }
    for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments())
      if (arg.startsWith("-XX:FlightRecorderOptions") && arg.contains("stackdepth")) {
        log.log(System.Logger.Level.DEBUG, "The stack depth of JFR is set by " + arg);
        return false;
      }
    try {
      configure("stackdepth=" + depth);
      log.log(System.Logger.Level.DEBUG, "Stack depth of JFR raised to " + depth + " frames");
      return true;
    }
    catch (Exception e) {
      log.log(System.Logger.Level.WARNING, "Could not raise the stack depth of JFR", e);
      return false;
    }
  }

  /**
   * The depth JFR uses, as JFR.configure tells it, or 0 if unknown.
   */
  static int current() {
    try {
      for (String line : configure().lines().toList()) {
        // "Stack depth: 64"
        if (line.trim().startsWith("Stack depth:"))
          return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
      }
    }
    catch (Exception e) {
      log.log(System.Logger.Level.DEBUG, "Could not read the stack depth of JFR", e);
    }
    return 0;
  }

  /** Runs JFR.configure, which only prints the configuration without options. */
  private static String configure(String... options) throws Exception {
    return (String) ManagementFactory.getPlatformMBeanServer().invoke(
      new ObjectName("com.sun.management:type=DiagnosticCommand"), "jfrConfigure",
      new Object[]{options}, new String[]{String[].class.getName()});
  }
}
