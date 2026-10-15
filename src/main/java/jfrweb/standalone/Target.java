package jfrweb.standalone;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import flak.App;
import flak.AppFactory;
import jfrweb.auto.AutoMode;
import jfrweb.live.LiveStats;
import jfrweb.session.Recorder;
import jfrweb.session.Session;
import jfrweb.session.Sessions;
import jfrweb.web.ProfilerApp;

/**
 * An application profiled from outside: the interface of the profiler for its
 * JVM, while one is attached, and its sessions, kept in a directory of its
 * own across runs of the application and of jfr-web.
 */
final class Target implements AutoCloseable {

  private final String app;
  private final Sessions sessions;
  private final LiveStats live = LiveStats.fed();
  private final AutoMode auto;
  private final App web;

  private volatile RemoteJvm jvm;

  /**
   * Serves its interface at /app/{app} of the server of the factory.
   */
  Target(String app, Path directory, AppFactory factory) throws IOException {
    this.app = app;
    this.sessions = new Sessions(directory, Sessions.defaultSettings(), new Recorder() {
      @Override
      public Running start(String name, Map<String, String> settings, Path file) throws IOException {
        RemoteJvm j = jvm;
        if (j == null)
          throw new IllegalStateException("No JVM of " + app + " connected: connect to one from the list of JVMs");
        return j.start(name, settings, file);
      }
    });
    this.auto = AutoMode.fed(sessions);
    this.web = factory.createApp(path());
    ProfilerApp.install(web, null, app, sessions, live, auto, null, null);
    web.scan(new TargetRoutes(this));
    web.start();
  }

  String getApp() {
    return app;
  }

  /**
   * Where its interface is, from the root of the server.
   */
  String path() {
    return "/app/" + app;
  }

  Sessions getSessions() {
    return sessions;
  }

  /**
   * The JVM attached, or null.
   */
  RemoteJvm getJvm() {
    return jvm;
  }

  /**
   * Attaches to a JVM of the application, instead of the one attached.
   */
  synchronized void attach(Jvms.Jvm j, int stackDepth) throws IOException {
    detach();
    jvm = RemoteJvm.attach(j.pid(), j.name(), stackDepth, op -> {
      live.count(op);
      auto.ended(op.name());
    });
  }

  /**
   * Stops the session recording, if any, then the stream of the JVM
   * attached, if any. The session keeps what the JVM recorded, if it could
   * write it.
   */
  synchronized void detach() {
    RemoteJvm j = jvm;
    if (j == null)
      return;
    Session s = sessions.getRecording();
    if (s != null)
      sessions.stop(s.getId());
    jvm = null;
    j.close();
  }

  @Override
  public synchronized void close() {
    detach();
    auto.close();
    live.close();
  }
}
