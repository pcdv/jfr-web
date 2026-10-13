package jfrweb;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import flak.App;
import flak.AppFactory;
import flak.BeforeHook;
import flak.Flak;
import flak.ResourceOptions;
import flak.util.CrossOriginProtection;
import jfrweb.auto.AutoMode;
import jfrweb.live.LiveStats;
import jfrweb.session.Sessions;
import jfrweb.web.Json;
import jfrweb.web.LoopbackOnly;
import jfrweb.web.ProfilerRoutes;

/**
 * A profiler embedded in the JVM, with a web interface. Installing it records
 * nothing: sessions are started from the interface or from
 * {@link #getSessions()}.
 * <pre>
 * Profiler.start();   // serves the interface on loopback, logs its URL
 * </pre>
 * Everything else is set with a {@link #builder()}.
 */
public final class Profiler implements AutoCloseable {

  private static final System.Logger log = System.getLogger(Profiler.class.getName());

  private final App app;
  private final Sessions sessions;
  private final LiveStats live;
  private final AutoMode auto;

  private Profiler(App app, Sessions sessions, LiveStats live, AutoMode auto) {
    this.app = app;
    this.sessions = sessions;
    this.live = live;
    this.auto = auto;
  }

  /**
   * Serves the interface on loopback, on a free port, and logs its URL.
   */
  public static Profiler start() throws IOException {
    return builder().start();
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * The URL of the web interface.
   */
  public String getUrl() {
    return app.getRootUrl() + "/";
  }

  public Sessions getSessions() {
    return sessions;
  }

  /**
   * The statistics of the endpoints of the apps observed, see
   * {@link Builder#observe(App...)}.
   */
  public LiveStats getLiveStats() {
    return live;
  }

  /**
   * The automatic start of sessions, triggered by the requests of the apps
   * observed.
   */
  public AutoMode getAutoMode() {
    return auto;
  }

  /**
   * Stops the session recording, if any, observing the apps, and the web
   * interface. The .jfr files stay.
   */
  @Override
  public void close() {
    auto.close();
    sessions.close();
    live.close();
    app.stop();
  }

  public static final class Builder {

    private InetAddress address = InetAddress.getLoopbackAddress();
    private int port;
    private AppFactory factory;
    private String path;
    private BeforeHook guard;
    private Path directory;
    private Map<String, String> settings;
    private boolean highResolutionTimer = true;
    private final List<App> observed = new ArrayList<>();

    private Builder() {}

    /**
     * Flak apps whose endpoints get live statistics, kept by counters as
     * requests end, without recording, and whose requests can start sessions
     * by themselves (auto mode). Manual sessions do not need it: recordings get
     * the route of each request from flak's request event.
     */
    public Builder observe(App... apps) {
      observed.addAll(List.of(apps));
      return this;
    }

    /**
     * The address to serve the interface on, loopback by default. Clients
     * that are not local are refused all the same unless a
     * {@link #guard(BeforeHook) guard} is set.
     */
    public Builder address(InetAddress address) {
      this.address = address;
      return this;
    }

    /**
     * The port, a free one by default.
     */
    public Builder port(int port) {
      this.port = port;
      return this;
    }

    /**
     * Serves the interface as one more app of an existing flak server, at a
     * path such as "/profiler", instead of a server of its own.
     */
    public Builder mount(AppFactory factory, String path) {
      this.factory = factory;
      this.path = path;
      return this;
    }

    /**
     * Decides who may use the interface, e.g. a login check of flak-login.
     * It replaces the default guard, which refuses the clients that are not
     * local.
     */
    public Builder guard(BeforeHook guard) {
      this.guard = guard;
      return this;
    }

    /**
     * Where the .jfr files go, a new temporary directory by default.
     */
    public Builder directory(Path directory) {
      this.directory = directory;
      return this;
    }

    /**
     * The JFR settings of the recordings, e.g. "jdk.ExecutionSample#period"
     * to "20 ms". See {@link Sessions#defaultSettings()}.
     */
    public Builder settings(Map<String, String> settings) {
      this.settings = settings;
      return this;
    }

    /**
     * On Windows, whether to raise the resolution of the system timer while a
     * session records, so that JFR samples about as often as asked. On by
     * default.
     */
    public Builder highResolutionTimer(boolean enabled) {
      this.highResolutionTimer = enabled;
      return this;
    }

    public Profiler start() throws IOException {
      Sessions sessions = new Sessions(directory != null ? directory : Files.createTempDirectory("jfr-web"),
                                       settings != null ? settings : Sessions.defaultSettings(),
                                       highResolutionTimer);
      App app;
      if (factory != null) {
        app = factory.createApp(path);
      }
      else {
        AppFactory f = backend();
        f.setLocalAddress(new InetSocketAddress(address, port));
        app = f.createApp();
      }

      app.addBeforeAllHook(guard != null ? guard : new LoopbackOnly());
      app.addBeforeAllHook(new CrossOriginProtection());
      app.addOutputFormatter(ProfilerRoutes.JSON, (data, resp) -> {
        resp.addHeader("Content-Type", "application/json");
        resp.getOutputStream().write(Json.write(data).getBytes(StandardCharsets.UTF_8));
      });
      LiveStats live = new LiveStats();
      AutoMode auto = new AutoMode(sessions);
      for (App a : observed) {
        live.observe(a);
        auto.observe(a);
      }
      app.scan(new ProfilerRoutes(sessions, live, auto));
      app.serveClasspath("/", "/jfrweb/ui",
                         new ResourceOptions().classLoader(Profiler.class.getClassLoader()));
      app.start();

      Profiler p = new Profiler(app, sessions, live, auto);
      log.log(System.Logger.Level.INFO, "jfr-web profiler at " + p.getUrl() +
                                        ", recordings in " + sessions.getDirectory());
      return p;
    }

    /**
     * The JDK backend, which needs nothing more, or whichever is there if it
     * was excluded from the dependencies.
     */
    private static AppFactory backend() {
      try {
        return Flak.getFactory(c -> c.getName().startsWith("flak.backend.jdk."));
      }
      catch (IllegalStateException e) {
        return Flak.getFactory();
      }
    }
  }
}
