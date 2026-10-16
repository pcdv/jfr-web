package jfrweb.session;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import jdk.jfr.Configuration;
import jfrweb.analysis.EventMappings;
import jfrweb.ops.Operations;

/**
 * The profiling sessions of a profiler. One session records at a time.
 * <p>
 * Each one is kept in its directory: its .jfr file, and next to it a
 * .properties file of what the list shows (name, trigger, start, end). The
 * sessions found there are listed again by the next profiler using the same
 * directory, e.g. after a restart.
 */
public final class Sessions implements AutoCloseable {

  private static final System.Logger log = System.getLogger(Sessions.class.getName());

  private static final DateTimeFormatter FILE_TIME =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

  private final Path directory;
  private final Map<String, String> settings;
  private final Recorder recorder;

  private final List<Session> sessions = new ArrayList<>();
  private Session recording;
  private int lastId;
  /**
   * The ids of the recordings registered, by file: a snapshot keeps its id
   * across restarts, so that a link to it keeps working. Kept with the last
   * id given, never given again, in {@link #IDS}.
   */
  private final Map<String, Integer> registered = new HashMap<>();

  /** Not session-*.properties: those are the sessions of {@link #load()}. */
  private static final String IDS = "ids.properties";

  /**
   * Sessions recording this JVM.
   *
   * @param directory           where the .jfr files go
   * @param settings            the JFR settings of the recordings, see
   *                            {@link #defaultSettings()}
   * @param highResolutionTimer on Windows, whether to raise the resolution of
   *                            the timer while recording, to sample as often
   *                            as asked
   */
  public Sessions(Path directory, Map<String, String> settings, boolean highResolutionTimer) {
    this(directory, settings, new LocalRecorder(highResolutionTimer));
  }

  /**
   * @param recorder what records them, e.g. another JVM
   */
  public Sessions(Path directory, Map<String, String> settings, Recorder recorder) {
    this.directory = directory;
    this.settings = Map.copyOf(settings);
    this.recorder = recorder;
    load();
  }

  /**
   * The settings of the JDK's "profile" configuration, plus:
   * <ul>
   *   <li>flak's request event, and jfr-web's operation events
   *   <li>native calls sampled every 5 ms instead of 20: JFR samples one
   *   thread in native code per period, and idle threads (selectors,
   *   acceptors) take most periods
   *   <li>the exceptions created, to show those of a slow request. Each one
   *   records its stack trace: an application creating thousands per second
   *   should turn them off ("jdk.JavaExceptionThrow#enabled" to "false")
   *   <li>file and socket accesses from 1 ms instead of 10 or 20, to show
   *   those of a slow request
   *   <li>the load of the CPU every minute instead of every second, see
   *   {@link jfrweb.snapshot.Snapshots#defaultSettings()}
   * </ul>
   */
  public static Map<String, String> defaultSettings() {
    try {
      Map<String, String> s = new HashMap<>(Configuration.getConfiguration("profile").getSettings());
      s.put("flak.Request#enabled", "true");
      s.put(Operations.EVENT + "#enabled", "true");
      s.put(Operations.SEGMENT_EVENT + "#enabled", "true");
      s.put("jdk.NativeMethodSample#period", "5 ms");
      s.put("jdk.JavaExceptionThrow#enabled", "true");
      // every second, its 65 ms of CPU on Windows would show among the samples
      // taken outside requests; see Snapshots.defaultSettings()
      s.put("jdk.CPULoad#period", "60 s");
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
   * @throws IllegalStateException if a session is already recording, or the
   *                               recorder cannot record now
   */
  public synchronized Session start(String trigger) throws IOException {
    if (recording != null)
      throw new IllegalStateException("Session " + recording.getId() + " is already recording");

    Files.createDirectories(directory);
    int id = lastId + 1;
    Instant now = Instant.now();
    Path file = directory.resolve("session-" + id + "-" + FILE_TIME.format(now) + ".jfr");

    // the events of the application mapped to operations, mapped now
    Recorder.Running r = recorder.start("jfr-web-" + id, EventMappings.withMapped(settings), file);
    lastId = id;
    Session s = new Session(id, now, trigger, file);
    s.recording = r;
    s.owner = this;
    sessions.add(s);
    recording = s;
    save(s);
    saveIds();
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
    recorder.warmUp(settings);
  }

  /**
   * Stops a session, which writes its .jfr file. If it could not be written,
   * e.g. the JVM recorded is gone, the session is dropped.
   *
   * @return the session, or null if there is none with this id
   */
  public synchronized Session stop(int id) {
    Session s = get(id);
    if (s == null || s.getState() == Session.State.STOPPED)
      return s;

    try {
      s.recording.stop();
    }
    catch (IOException | RuntimeException e) {
      log.log(System.Logger.Level.WARNING, "Could not stop session " + id, e);
    }
    finally {
      s.stopped(Instant.now());
      recording = null;
    }
    if (Files.exists(s.getFile())) {
      save(s);
    }
    else {
      log.log(System.Logger.Level.WARNING, "Session " + id + " dropped: its recording was not written");
      sessions.remove(s);
      deleteProperties(s);
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
   * Adds a recording made elsewhere, e.g. a snapshot of a rolling recording,
   * as a session already stopped, kept by whoever made it.
   *
   * @param trigger why it was recorded, e.g. "snapshot: GET /x took 9 s"
   */
  public synchronized Session register(Instant start, Instant end, String trigger, Path file) {
    String key = file.toAbsolutePath().normalize().toString();
    Integer id = registered.get(key);
    if (id == null || get(id) != null) {
      id = ++lastId;
      registered.put(key, id);
      saveIds();
    }
    Session s = new Session(id, start, trigger, file);
    s.stopped(end);
    sessions.add(s);
    return s;
  }

  /**
   * All the sessions, the latest first. Those whose file was deleted, e.g.
   * a snapshot purged, are dropped.
   */
  public synchronized List<Session> list() {
    sessions.removeIf(s -> s.getState() == Session.State.STOPPED && !Files.exists(s.getFile()));
    List<Session> res = new ArrayList<>(sessions);
    res.sort(Comparator.comparing(Session::getStart).reversed());
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

  // --- kept on disk

  private static Path properties(Path jfr) {
    String f = jfr.getFileName().toString();
    return jfr.resolveSibling(f.substring(0, f.length() - ".jfr".length()) + ".properties");
  }

  /**
   * Writes what the list shows of a session next to its file.
   */
  synchronized void save(Session s) {
    if (s.owner != this)
      return;
    Properties p = new Properties();
    p.setProperty("id", String.valueOf(s.getId()));
    p.setProperty("start", s.getStart().toString());
    if (s.getEnd() != null)
      p.setProperty("end", s.getEnd().toString());
    p.setProperty("trigger", s.getTrigger());
    if (s.getName() != null)
      p.setProperty("name", s.getName());
    try (Writer w = Files.newBufferedWriter(properties(s.getFile()), StandardCharsets.UTF_8)) {
      p.store(w, "A session of jfr-web");
    }
    catch (IOException e) {
      log.log(System.Logger.Level.WARNING, "Could not save session " + s.getId(), e);
    }
  }

  /**
   * Writes the ids of the recordings registered, those whose file is gone
   * left out, and the last id given.
   */
  private void saveIds() {
    registered.keySet().removeIf(f -> !Files.exists(Path.of(f)));
    Properties p = new Properties();
    p.setProperty("last", String.valueOf(lastId));
    registered.forEach((f, id) -> p.setProperty(f, String.valueOf(id)));
    try {
      Files.createDirectories(directory);
      try (Writer w = Files.newBufferedWriter(directory.resolve(IDS), StandardCharsets.UTF_8)) {
        p.store(w, "The ids of jfr-web's sessions: the last one given, those of the recordings registered");
      }
    }
    catch (IOException e) {
      log.log(System.Logger.Level.WARNING, "Could not save the ids of the sessions", e);
    }
  }

  private void loadIds() {
    Path f = directory.resolve(IDS);
    if (!Files.exists(f))
      return;
    try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
      Properties p = new Properties();
      p.load(r);
      for (String k : p.stringPropertyNames()) {
        int id = Integer.parseInt(p.getProperty(k));
        if (!k.equals("last"))
          registered.put(k, id);
        lastId = Math.max(lastId, id);
      }
    }
    catch (IOException | RuntimeException e) {
      log.log(System.Logger.Level.WARNING, "Could not read the ids of the sessions in " + f, e);
    }
  }

  private static void deleteProperties(Session s) {
    try {
      Files.deleteIfExists(properties(s.getFile()));
    }
    catch (IOException e) {
      log.log(System.Logger.Level.DEBUG, "Could not delete the properties of session " + s.getId(), e);
    }
  }

  /**
   * Lists the sessions of an earlier profiler. One that was recording when it
   * ended ends when its file was last written.
   */
  private void load() {
    if (!Files.isDirectory(directory))
      return;
    loadIds();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "session-*.properties")) {
      for (Path f : files) {
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
          Properties p = new Properties();
          p.load(r);
          String name = f.getFileName().toString();
          Path jfr = f.resolveSibling(name.substring(0, name.length() - ".properties".length()) + ".jfr");
          if (!Files.exists(jfr))
            continue;
          int id = Integer.parseInt(p.getProperty("id"));
          Session s = new Session(id, Instant.parse(p.getProperty("start")), p.getProperty("trigger", "manual"), jfr);
          String end = p.getProperty("end");
          s.stopped(end != null ? Instant.parse(end) : Files.getLastModifiedTime(jfr).toInstant());
          s.setName(p.getProperty("name"));
          s.owner = this;
          sessions.add(s);
          lastId = Math.max(lastId, id);
        }
        catch (IOException | RuntimeException e) {
          log.log(System.Logger.Level.WARNING, "Could not read session " + f, e);
        }
      }
    }
    catch (IOException e) {
      log.log(System.Logger.Level.WARNING, "Could not list the sessions of " + directory, e);
    }
  }
}
