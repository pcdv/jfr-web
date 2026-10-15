package jfrweb.standalone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import flak.annotations.OutputFormat;
import flak.annotations.Route;
import jfrweb.web.ProfilerRoutes;

/**
 * What the interface of a {@link Target} adds to that of a profiler: which
 * application it is, and which of its JVMs is attached.
 */
public class TargetRoutes {

  private final Target target;

  TargetRoutes(Target target) {
    this.target = target;
  }

  /**
   * The application, its JVM attached if any, and those running.
   */
  @Route("/api/target")
  @OutputFormat(ProfilerRoutes.JSON)
  public Map<String, Object> target() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("app", target.getApp());
    RemoteJvm j = target.getJvm();
    m.put("pid", j == null ? null : j.getPid());
    m.put("name", j == null ? null : j.getName());
    List<Object> running = new ArrayList<>();
    for (Jvms.Jvm r : Jvms.list())
      if (r.app().equals(target.getApp()))
        running.add(Standalone.jvm(r));
    m.put("running", running);
    return m;
  }
}
