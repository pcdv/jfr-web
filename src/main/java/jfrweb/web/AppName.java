package jfrweb.web;

import java.util.regex.Pattern;

/**
 * The name of an application, the same from one run to the next, e.g. to
 * tell its pages apart in a browser, or keep its sessions by.
 */
public final class AppName {

  /** A version at the end of a name, e.g. "-7.40.0-5-e71cad50-all". */
  private static final Pattern VERSION = Pattern.compile("[-_]v?\\d.*$");

  private AppName() {}

  /**
   * The name of this JVM's application.
   */
  public static String self() {
    return of(System.getProperty("sun.java.command"), ProcessHandle.current().pid());
  }

  /**
   * The name of the application of a JVM: that of its main class, or of its
   * jar without its version, e.g. "myapp" for "/opt/myapp-1.2.3-all.jar
   * --port 80". Only letters, digits, dots, dashes and underscores: it names
   * a directory, and a path of the interface.
   *
   * @param command its main class or jar, and its arguments, as the JVM
   *                lists itself
   * @param pid     for a JVM that does not tell its main class
   */
  public static String of(String command, long pid) {
    String main = command == null ? "" : command.strip().split("\s+", 2)[0];
    main = main.substring(Math.max(main.lastIndexOf('/'), main.lastIndexOf('\\')) + 1);
    if (main.endsWith(".jar")) {
      main = main.substring(0, main.length() - ".jar".length());
      main = VERSION.matcher(main).replaceFirst("");
    }
    main = main.replaceAll("[^A-Za-z0-9._-]", "_");
    if (main.isEmpty() || main.startsWith("."))
      return "pid-" + pid;
    return main;
  }
}
