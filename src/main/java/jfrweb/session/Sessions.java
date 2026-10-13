package jfrweb.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/**
 * The profiling sessions of a profiler. One session records at a time.
 */
public final class Sessions implements AutoCloseable {

  private static final DateTimeFormatter FILE_TIME =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

  private final Path directory;
  private final Map<String, String> settings;
  private final HighResolutionTimer timer;

  private final List<Session> sessions = new ArrayList<>();
  private Session recording;
  private int lastId;

  /**
   * @param directory           where the .jfr files go
   * @param settings            the JFR settings of the recordings, see
   *                            {@link #defaultSettings()}
   * @param highResolutionTimer on Windows, whether to raise the resolution of
   *                            the timer while recording, to sample as often
   *                            as asked
   */
  public Sessions(Path directory, Map<String, String> settings, boolean highResolutionTimer) {
    this.directory = directory;
    this.settings = Map.copyOf(settings);
    this.timer = new HighResolutionTimer(highResolutionTimer);
  }

  /**
   * The settings of the JDK's "profile" configuration, plus:
   * <ul>
   *   <li>flak's request event
   *   <li>native calls sampled every 5 ms instead of 20: JFR samples one
   *   thread in native code per period, and idle threads (selectors,
   *   acceptors) take most periods
   *   <li>the exceptions created, to show those of a slow request. Each one
   *   records its stack trace: an application creating thousands per second
   *   should turn them off ("jdk.JavaExceptionThrow#enabled" to "false")
   *   <li>file and socket accesses from 1 ms instead of 10 or 20, to show
   *   those of a slow request
   * </ul>
   */
  public static Map<String, String> defaultSettings() {
    try {
      Map<String, String> s = new HashMap<>(Configuration.getConfiguration("profile").getSettings());
      s.put("flak.Request#enabled", "true");
      s.put("jdk.NativeMethodSample#period", "5 ms");
      s.put("jdk.JavaExceptionThrow#enabled", "true");
      for (String io : new String[]{"jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite"})
        s.put(io + "#threshold", "1 ms");
      return s;
    }
    catch (IOException | ParseException e) {
      throw new IllegalStateException("Cannot read the profile configuration of the JDK", e);
    }
  }

  /**
   * Starts recording.
   *
   * @param trigger how the session is started, e.g. "manual"
   * @throws IllegalStateException if a session is already recording
   */
  public synchronized Session start(String trigger) throws IOException {
    if (recording != null)
      throw new IllegalStateException("Session " + recording.getId() + " is already recording");

    Files.createDirectories(directory);
    int id = ++lastId;
    Instant now = Instant.now();
    Path file = directory.resolve("session-" + id + "-" + FILE_TIME.format(now) + ".jfr");

    Recording r = new Recording(settings);
    r.setName("jfr-web-" + id);
    r.setToDisk(true);
    r.setDestination(file);
    timer.acquire();
    try {
      r.start();
    }
    catch (RuntimeException e) {
      timer.release();
      r.close();
      throw e;
    }

    Session s = new Session(id, now, trigger, file);
    s.recording = r;
    sessions.add(s);
    recording = s;
    return s;
  }

  /**
   * Starts and stops a recording with the settings of the sessions, kept
   * nowhere: the first recording of a JVM costs much more than the next ones
   * (classes to load, the JFR runtime to set up). Done before a session must
   * start in the middle of a request.
   */
  public synchronized void warmUp() {
    if (recording != null)
      return;
    try (Recording r = new Recording(settings)) {
      r.setToDisk(false);
      r.start();
      r.stop();
    }
  }

  /**
   * Stops a session, which writes its .jfr file.
   *
   * @return the session, or null if there is none with this id
   */
  public synchronized Session stop(int id) {
    Session s = get(id);
    if (s == null || s.getState() == Session.State.STOPPED)
      return s;

    Recording r = s.recording;
    try {
      r.stop();
    }
    finally {
      r.close();
      timer.release();
      s.stopped(Instant.now());
      recording = null;
    }
    return s;
  }

  /**
   * The session recording now, if any.
   */
  public synchronized Session getRecording() {
    return recording;
  }

  public synchronized Session get(int id) {
    for (Session s : sessions)
      if (s.getId() == id)
        return s;
    return null;
  }

  /**
   * All the sessions, the latest first.
   */
  public synchronized List<Session> list() {
    List<Session> res = new ArrayList<>(sessions);
    Collections.reverse(res);
    return res;
  }

  public Path getDirectory() {
    return directory;
  }

  /**
   * Stops the session recording, if any.
   */
  @Override
  public synchronized void close() {
    if (recording != null)
      stop(recording.getId());
  }
}
