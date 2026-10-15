package jfrweb.standalone;

import java.util.ArrayList;
import java.util.List;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import jfrweb.web.AppName;

/**
 * The JVMs of this machine that can be attached to: those of the same user.
 */
public final class Jvms {

  private Jvms() {}

  /**
   * A JVM running.
   *
   * @param name as it lists itself: its main class or jar, and its arguments
   * @param app  what its sessions are kept by, see {@link AppName#of(String, long)}
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
   * The application of a JVM, see {@link AppName#of(String, long)}.
   */
  public static String app(String displayName, long pid) {
    return AppName.of(displayName, pid);
  }
}
