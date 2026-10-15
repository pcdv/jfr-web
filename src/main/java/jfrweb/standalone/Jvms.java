package jfrweb.standalone;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

/**
 * The JVMs of this machine that can be attached to: those of the same user.
 */
public final class Jvms {

  /** A version at the end of a name, e.g. "-7.40.0-5-e71cad50-all". */
  private static final Pattern VERSION = Pattern.compile("[-_]v?\\d.*$");

  private Jvms() {}

  /**
   * A JVM running.
   *
   * @param name as it lists itself: its main class or jar, and its arguments
   * @param app  what its sessions are kept by, see {@link #app(String, long)}
   */
  public record Jvm(long pid, String name, String app) {}

  /**
   * The other JVMs of this machine.
   */
  public static List<Jvm> list() {
    long self = ProcessHandle.current().pid();
    List<Jvm> res = new ArrayList<>();
    for (VirtualMachineDescriptor d : VirtualMachine.list()) {
      long pid;
      try {
        pid = Long.parseLong(d.id());
      }
      catch (NumberFormatException e) {
        continue;
      }
      if (pid != self)
        res.add(new Jvm(pid, d.displayName(), app(d.displayName(), pid)));
    }
    return res;
  }

  /**
   * The application of a JVM, the same from one run to the next: the name
   * of its main class, or of its jar without its version, e.g. "myapp" for
   * "/opt/myapp-1.2.3-all.jar --port 80". Only letters, digits, dots,
   * dashes and underscores: it names a directory, and a path of the
   * interface.
   *
   * @param pid for a JVM that does not tell its main class
   */
  public static String app(String displayName, long pid) {
    String main = displayName == null ? "" : displayName.strip().split("\\s+", 2)[0];
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
