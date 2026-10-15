package jfrweb.standalone;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import flak.App;
import flak.AppFactory;
import flak.Flak;
import jfrweb.auto.AutoMode;
import jfrweb.live.LiveStats;
import jfrweb.session.Recorder;
import jfrweb.session.Sessions;
import jfrweb.web.About;
import jfrweb.web.ProfilerApp;

/**
 * The interface of the profiler on recordings made elsewhere, e.g. with
 * {@code jcmd JFR.start}, JDK Mission Control or a snapshot: each one listed
 * as a session, with its report, timeline, operations and flame graphs, and
 * an operation history if given one. There is no JVM behind them: no live
 * operations, auto mode or session to start.
 */
public final class Viewer implements AutoCloseable {

  private static final System.Logger log = System.getLogger(Viewer.class.getName());

  private final App app;
  private final Sessions sessions;
  private final Path empty;

  private Viewer(App app, Sessions sessions, Path empty) {
    this.app = app;
    this.sessions = sessions;
    this.empty = empty;
  }

  /**
   * Serves the interface on loopback.
   *
   * @param recordings .jfr files, or directories whose .jfr files are taken
   * @param history    the directory of an operation history, or null
   * @param port       0 for a free one
   * @throws IllegalArgumentException if there is no recording
   */
  public static Viewer start(List<Path> recordings, Path history, int port) throws IOException {
    List<Path> files = files(recordings);
    if (files.isEmpty())
      throw new IllegalArgumentException("No .jfr file in " + recordings);

    // sessions are only registered: nothing is written there
    Path empty = Files.createTempDirectory("jfr-web-viewer");
    Sessions sessions = new Sessions(empty, Sessions.defaultSettings(), new Recorder() {
      @Override
      public Running start(String name, Map<String, String> settings, Path file) {
        throw new IllegalStateException("No JVM to record: only recordings opened from files");
      }
    });
    for (Path f : files) {
      Instant[] span = span(f);
      String name = f.getFileName().toString();
      sessions.register(span[0], span[1], "file: " + f.toAbsolutePath().normalize(), f)
              .setName(name.substring(0, name.length() - ".jfr".length()));
    }

    AppFactory factory = Flak.getFactory(c -> c.getName().startsWith("flak.backend.jdk."));
    factory.setLocalAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    App app = factory.createApp();
    String title = files.size() == 1 ? files.get(0).getFileName().toString() : files.size() + " recordings";
    ProfilerApp.install(app, null, new About(title, true), sessions, LiveStats.fed(), AutoMode.fed(sessions),
                        history, null);
    app.start();
    Viewer v = new Viewer(app, sessions, empty);
    log.log(System.Logger.Level.INFO, "jfr-web at " + v.getUrl() + ", " + files.size() + " recording(s)");
    return v;
  }

  public String getUrl() {
    return app.getRootUrl() + "/";
  }

  public Sessions getSessions() {
    return sessions;
  }

  /**
   * The .jfr files given, those of the directories given, in order.
   */
  static List<Path> files(List<Path> recordings) throws IOException {
    List<Path> res = new ArrayList<>();
    for (Path p : recordings) {
      if (Files.isDirectory(p)) {
        List<Path> in = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(p, "*.jfr")) {
          files.forEach(in::add);
        }
        in.sort(null);
        res.addAll(in);
      }
      else if (Files.isRegularFile(p)) {
        res.add(p);
      }
      else {
        throw new IllegalArgumentException("No such file: " + p);
      }
    }
    return res;
  }

  /**
   * The start and end of a recording, from the headers of its chunks, so as
   * not to read it whole: the file's last change if they cannot be read.
   */
  static Instant[] span(Path file) throws IOException {
    long start = Long.MAX_VALUE;
    long end = Long.MIN_VALUE;
    try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
      // magic, version, size, offsets of the constant pool and the metadata,
      // start in nanoseconds since the epoch, duration
      for (long pos = 0; pos + 48 <= f.length(); ) {
        f.seek(pos);
        if (f.readInt() != 0x464c5200)
          break;
        f.readInt();
        long size = f.readLong();
        f.readLong();
        f.readLong();
        long startNanos = f.readLong();
        long duration = f.readLong();
        start = Math.min(start, startNanos);
        end = Math.max(end, startNanos + duration);
        if (size <= 0)
          break;
        pos += size;
      }
    }
    if (start == Long.MAX_VALUE) {
      Instant modified = Files.getLastModifiedTime(file).toInstant();
      return new Instant[]{modified, modified};
    }
    return new Instant[]{Instant.ofEpochSecond(0, start), Instant.ofEpochSecond(0, end)};
  }

  @Override
  public void close() {
    app.stop();
    try {
      Files.deleteIfExists(empty);
    }
    catch (IOException e) {
      log.log(System.Logger.Level.DEBUG, "Could not delete " + empty, e);
    }
  }
}
