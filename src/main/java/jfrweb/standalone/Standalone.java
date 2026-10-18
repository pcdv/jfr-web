package jfrweb.standalone;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import flak.App;
import flak.AppFactory;
import flak.Flak;
import flak.HttpException;
import flak.annotations.OutputFormat;
import flak.annotations.Post;
import flak.annotations.Route;
import jfrweb.analysis.EventMapping;
import jfrweb.analysis.EventMappings;
import jfrweb.session.Session;
import jfrweb.session.StackDepth;
import jfrweb.web.ProfilerApp;
import jfrweb.web.ProfilerRoutes;

/**
 * jfr-web on its own, profiling the other JVMs of this machine with nothing
 * to install in them: a page lists them, and attaching to one opens the
 * interface of a profiler for it, as if it was embedded (see
 * {@link RemoteJvm} for what differs). One JVM is attached at a time.
 * <p>
 * The sessions are kept by application, in a directory of each under that
 * of jfr-web, e.g. ~/.jfr-web/myapp: they are listed again when the
 * application or jfr-web restarts.
 */
public final class Standalone implements AutoCloseable {

  private static final System.Logger log = System.getLogger(Standalone.class.getName());

  /** The port by default: a fixed one, for bookmarks. */
  public static final int PORT = 8095;

  private final Path directory;
  private final AppFactory factory;
  private final App root;
  private final Map<String, Target> targets = new TreeMap<>();
  private final ScheduledExecutorService watchdog;

  private Standalone(Path directory, AppFactory factory, App root) {
    this.directory = directory;
    this.factory = factory;
    this.root = root;
    this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "jfr-web watchdog");
      t.setDaemon(true);
      return t;
    });
  }

  /**
   * The directory by default: .jfr-web in the home of the user.
   */
  public static Path defaultDirectory() {
    return Path.of(System.getProperty("user.home"), ".jfr-web");
  }

  /**
   * Serves the interface on loopback, with the applications whose sessions
   * are kept in the directory.
   *
   * @param port 0 for a free one
   */
  public static Standalone start(Path directory, int port) throws IOException {
    Files.createDirectories(directory);
    AppFactory factory = Flak.getFactory(c -> c.getName().startsWith("flak.backend.jdk."));
    factory.setLocalAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    App root = factory.createApp();
    Standalone s = new Standalone(directory, factory, root);
    ProfilerApp.base(root, null);
    root.scan(s);
    // the page of the JVMs uses the styles and modules of the profiler's
    ProfilerApp.serveResources(root, "/ui", "jfrweb/ui");
    ProfilerApp.serveResources(root, "/", "jfrweb/standalone");
    root.start();

    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(directory, Files::isDirectory)) {
      for (Path d : dirs)
        s.target(d.getFileName().toString());
    }
    s.watchdog.scheduleWithFixedDelay(s::watch, 1, 1, TimeUnit.SECONDS);
    log.log(System.Logger.Level.INFO, "jfr-web at " + s.getUrl() + ", sessions in " + directory);
    return s;
  }

  public String getUrl() {
    return root.getRootUrl() + "/";
  }

  public Path getDirectory() {
    return directory;
  }

  /**
   * The JVMs of this machine, the one attached, and the applications whose
   * sessions are kept.
   */
  @Route("/api/jvms")
  @OutputFormat(ProfilerRoutes.JSON)
  public synchronized Map<String, Object> jvms() {
    List<Object> jvms = new ArrayList<>();
    for (Jvms.Jvm j : Jvms.list()) {
      Map<String, Object> m = jvm(j);
      Target t = targets.get(j.app());
      m.put("attached", t != null && t.getJvm() != null && t.getJvm().getPid() == j.pid());
      jvms.add(m);
    }
    List<Map<String, Object>> apps = new ArrayList<>();
    for (Target t : targets.values()) {
      List<Session> sessions = t.getSessions().list();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("app", t.getApp());
      m.put("path", t.path().substring(1) + "/");
      m.put("pid", t.getJvm() == null ? null : t.getJvm().getPid());
      m.put("sessions", sessions.size());
      m.put("last", sessions.isEmpty() ? null : sessions.get(0).getStart());
      apps.add(m);
    }
    // the latest session first
    apps.sort(Comparator.comparing((Map<String, Object> m) -> (Instant) m.get("last"),
                                   Comparator.nullsLast(Comparator.reverseOrder())));
    Map<String, Object> res = new LinkedHashMap<>();
    res.put("directory", directory.toString());
    List<Object> events = new ArrayList<>();
    for (EventMapping m : EventMappings.list())
      events.add(Map.of("event", m.event(), "name", m.name()));
    res.put("mappedEvents", events);
    res.put("jvms", jvms);
    res.put("apps", apps);
    return res;
  }

  static Map<String, Object> jvm(Jvms.Jvm j) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("pid", j.pid());
    m.put("name", j.name());
    m.put("app", j.app());
    return m;
  }

  /**
   * Attaches to a JVM, instead of the one attached if any.
   *
   * @return the application of the JVM, and the path of its interface
   */
  @Route("/api/jvms/:pid/attach")
  @Post
  @OutputFormat(ProfilerRoutes.JSON)
  public synchronized Map<String, Object> attach(String pid) throws IOException {
    Jvms.Jvm jvm = null;
    for (Jvms.Jvm j : Jvms.list())
      if (String.valueOf(j.pid()).equals(pid))
        jvm = j;
    if (jvm == null)
      throw new HttpException(404, "No JVM " + pid + " on this machine");

    Target target = target(jvm.app());
    RemoteJvm current = target.getJvm();
    if (current == null || current.getPid() != jvm.pid()) {
      detachAll();
      try {
        target.attach(jvm, StackDepth.DEFAULT);
      }
      catch (IOException | RuntimeException e) {
        log.log(System.Logger.Level.WARNING, "Could not attach to JVM " + pid, e);
        throw new HttpException(502, "Could not attach to JVM " + pid + ": " + e.getMessage());
      }
      log.log(System.Logger.Level.INFO, "Attached to JVM " + pid + " (" + jvm.app() + ")");
    }
    Map<String, Object> res = new LinkedHashMap<>();
    res.put("app", target.getApp());
    res.put("path", target.path().substring(1) + "/");
    return res;
  }

  /**
   * Detaches from the JVM attached, if any, stopping its session if one
   * records.
   */
  @Route("/api/detach")
  @Post
  @OutputFormat(ProfilerRoutes.JSON)
  public synchronized Map<String, Object> detach() {
    detachAll();
    return Map.of();
  }

  private void detachAll() {
    for (Target t : targets.values())
      t.detach();
  }

  /**
   * The target of an application, served from now on: flak cannot remove
   * the paths of an app, so it is kept until this closes.
   */
  private synchronized Target target(String app) throws IOException {
    Target t = targets.get(app);
    if (t == null) {
      t = new Target(app, directory.resolve(app), factory);
      targets.put(app, t);
    }
    return t;
  }

  /**
   * Detaches from a JVM that died.
   */
  private synchronized void watch() {
    for (Target t : targets.values()) {
      RemoteJvm j = t.getJvm();
      if (j != null && !j.isAlive()) {
        log.log(System.Logger.Level.INFO, "JVM " + j.getPid() + " (" + t.getApp() + ") ended");
        t.detach();
      }
    }
  }

  /**
   * Detaches, and stops serving.
   */
  @Override
  public synchronized void close() {
    watchdog.shutdownNow();
    for (Target t : targets.values())
      t.close();
    // the apps of the targets are still there: the server, whole
    factory.getServer().stop();
  }
}
