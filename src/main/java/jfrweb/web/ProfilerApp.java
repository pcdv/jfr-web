package jfrweb.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import flak.App;
import flak.BeforeHook;
import flak.ResourceOptions;
import flak.util.CrossOriginProtection;
import jfrweb.auto.AutoMode;
import jfrweb.json.Json;
import jfrweb.live.LiveStats;
import jfrweb.session.Sessions;
import jfrweb.snapshot.Snapshots;

/**
 * The web interface of a profiler, set up on a flak app: its guard, its REST
 * API and its pages.
 */
public final class ProfilerApp {

  private ProfilerApp() {}

  /**
   * Sets up the interface on an app, not started yet.
   *
   * @param guard     who may use it, null to refuse the clients that are not
   *                  local
   * @param about     what is profiled
   * @param history   the directory of an operation history to browse, or null
   * @param snapshots listed with the sessions and taken from the interface,
   *                  or null
   */
  public static void install(App app, BeforeHook guard, About about, Sessions sessions, LiveStats live,
                             AutoMode auto, Path history, Snapshots snapshots) {
    base(app, guard);
    app.scan(new ProfilerRoutes(sessions, live, auto, about, history != null));
    app.scan(new HistoryRoutes(history));
    if (snapshots != null) {
      for (Snapshots.Snapshot s : snapshots.list())
        register(sessions, s);
      snapshots.addListener(s -> register(sessions, s));
    }
    app.scan(new SnapshotRoutes(snapshots, sessions));
    serveResources(app, "/", "jfrweb/ui");
  }

  /**
   * Serves a directory of the resources of jfr-web: from the classpath, or
   * from the directory named by the system property {@value #RESOURCES}
   * (e.g. src/main/resources), where an edit shows on the next reload.
   *
   * @param resource its path among the resources, e.g. "jfrweb/ui"
   */
  public static void serveResources(App app, String path, String resource) {
    String dir = System.getProperty(RESOURCES);
    if (dir != null)
      app.serveDir(path, Path.of(dir, resource).toFile());
    else
      app.serveClasspath(path, "/" + resource,
                         new ResourceOptions().classLoader(ProfilerApp.class.getClassLoader()));
  }

  /** The system property naming a directory to serve the pages from, in place of the classpath. */
  public static final String RESOURCES = "jfrweb.resources";

  /**
   * What any app of the interface needs: its guard, the protection against
   * cross-origin requests, and the output format of its REST API,
   * {@link ProfilerRoutes#JSON}.
   *
   * @param guard who may use it, null to refuse the clients that are not
   *              local
   */
  public static void base(App app, BeforeHook guard) {
    app.addBeforeAllHook(guard != null ? guard : new LoopbackOnly());
    app.addBeforeAllHook(new CrossOriginProtection());
    app.addOutputFormatter(ProfilerRoutes.JSON, (data, resp) -> {
      resp.addHeader("Content-Type", "application/json");
      resp.getOutputStream().write(Json.write(data).getBytes(StandardCharsets.UTF_8));
    });
  }

  private static void register(Sessions sessions, Snapshots.Snapshot s) {
    sessions.register(s.start(), s.end(), "snapshot: " + s.reason(), s.file());
  }
}
