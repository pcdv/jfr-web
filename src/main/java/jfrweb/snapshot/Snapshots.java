package jfrweb.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import flak.App;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jfrweb.json.Json;
import jfrweb.ops.Operations;
import jfrweb.session.StackDepth;

/**
 * Records all the time, and saves the last minutes when something goes
 * wrong: so that there is something to investigate after a problem, with no
 * need to have started a session beforehand.
 * <p>
 * A rolling recording, with the light settings of the JDK, keeps the last
 * minutes on disk. A snapshot writes them to a .jfr file, with a .json file
 * telling why and when. Snapshots are taken by hand ({@link #save}), or by
 * themselves when an operation is much slower than usual for its name, or
 * when errors burst (see {@link Triggers}): with no threshold to configure.
 * Automatic snapshots are spaced, and the directory is purged by number of
 * snapshots and total size.
 * <pre>
 * Snapshots snapshots = Snapshots.builder()
 *                                .directory(Path.of("logs/snapshots"))
 *                                .observe(app)
 *                                .start();
 * </pre>
 */
public final class Snapshots implements AutoCloseable {

  private static final System.Logger log = System.getLogger(Snapshots.class.getName());

  private static final DateTimeFormatter FILE_TIME =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  /**
   * A saved snapshot.
   *
   * @param reason why it was taken, e.g. "GET /api/x took 9.1 s, 6x its usual p99"
   */
  public record Snapshot(Instant start, Instant end, String reason, Path file) {}

  private final Path directory;
  private final Duration window;
  private final Duration minGap;
  private final Duration aftermath;
  private final int maxCount;
  private final long maxSize;
  private final Map<String, String> settings;
  private final Clock clock;

  private Recording rolling;
  private final List<Snapshot> snapshots = new ArrayList<>();
  private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
  private final Triggers triggers;
  private final ScheduledExecutorService scheduler;

  /** When the last automatic snapshot was taken, or is due. */
  private Instant lastAutomatic = Instant.MIN;

  private Snapshots(Builder b) {
    this.directory = b.directory;
    this.window = b.window;
    this.minGap = b.minGap;
    this.aftermath = b.aftermath;
    this.maxCount = b.maxCount;
    this.maxSize = b.maxSize;
    this.settings = b.settings != null ? b.settings : defaultSettings();
    this.clock = b.clock;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "jfr-web snapshots");
      t.setDaemon(true);
      return t;
    });
    this.triggers = new Triggers(clock, this::trigger);
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * The JDK's "default" settings, light enough for production, plus flak's
   * request event, jfr-web's operation events, and the load of the CPU
   * measured every minute instead of every second: on Windows, a measure
   * costs about 65 ms of CPU (performance counters), and every second it was
   * 95% of the cost of the recording, about 7% of a core on an idle application server.
   */
  public static Map<String, String> defaultSettings() {
    try {
      Map<String, String> s = new HashMap<>(Configuration.getConfiguration("default").getSettings());
      s.put("flak.Request#enabled", "true");
      s.put(Operations.EVENT + "#enabled", "true");
      s.put(Operations.SEGMENT_EVENT + "#enabled", "true");
      s.put("jdk.CPULoad#period", "60 s");
      return s;
    }
    catch (IOException | ParseException e) {
      throw new IllegalStateException("Cannot read the default configuration of the JDK", e);
    }
  }

  public Path getDirectory() {
    return directory;
  }

  /**
   * How much a snapshot holds: the last minutes before it is taken.
   */
  public Duration getWindow() {
    return window;
  }

  /**
   * Makes the requests of a flak app trigger snapshots, until closed. The
   * operations of the jfrweb.ops API do from the start.
   */
  public void observe(App app) {
    triggers.observe(app);
  }

  /**
   * Called with each new snapshot, e.g. to list it.
   */
  public void addListener(Consumer<Snapshot> listener) {
    listeners.add(listener);
  }

  /**
   * The snapshots of the directory, the oldest first, those taken before a
   * restart included.
   */
  public synchronized List<Snapshot> list() {
    return new ArrayList<>(snapshots);
  }

  private void start() throws IOException {
    Files.createDirectories(directory);
    loadExisting();
    rolling = new Recording(settings);
    rolling.setName("jfr-web rolling recording");
    rolling.setToDisk(true);
    rolling.setMaxAge(window);
    rolling.start();
    scheduler.scheduleWithFixedDelay(triggers::refreshBaselines, 1, 1, TimeUnit.MINUTES);
  }

  /**
   * Saves the last minutes now.
   *
   * @param reason why, e.g. "saved by hand"
   */
  public synchronized Snapshot save(String reason) throws IOException {
    Instant end = clock.instant();
    String name = "snapshot-" + FILE_TIME.format(end);
    Path file = directory.resolve(name + ".jfr");
    for (int i = 2; Files.exists(file); i++)
      file = directory.resolve(name + "-" + i + ".jfr");

    rolling.dump(file);
    Snapshot s = new Snapshot(end.minus(window), end, reason, file);
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("start", s.start());
    meta.put("end", s.end());
    meta.put("reason", reason);
    Files.writeString(metaFile(file), Json.write(meta), StandardCharsets.UTF_8);
    snapshots.add(s);
    log.log(System.Logger.Level.INFO, "Snapshot " + file.getFileName() + ": " + reason);

    purge();
    for (Consumer<Snapshot> l : listeners)
      l.accept(s);
    return s;
  }

  /**
   * An automatic snapshot, taken a little later so that it holds the
   * aftermath too, and spaced from the previous one.
   */
  synchronized void trigger(String reason) {
    Instant now = clock.instant();
    if (now.isBefore(lastAutomatic.plus(minGap)))
      return;
    lastAutomatic = now;
    scheduler.schedule(() -> {
      try {
        save(reason);
      }
      catch (IOException | RuntimeException e) {
        log.log(System.Logger.Level.WARNING, "Could not save a snapshot", e);
      }
    }, aftermath.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void loadExisting() throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      for (Path f : (Iterable<Path>) files::iterator) {
        if (!f.getFileName().toString().endsWith(".jfr") || !Files.exists(metaFile(f)))
          continue;
        try {
          @SuppressWarnings("unchecked")
          Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(metaFile(f), StandardCharsets.UTF_8));
          snapshots.add(new Snapshot(Instant.parse((String) m.get("start")), Instant.parse((String) m.get("end")),
                                     (String) m.get("reason"), f));
        }
        catch (RuntimeException e) {
          log.log(System.Logger.Level.WARNING, "Ignored snapshot " + f + ": " + e);
        }
      }
    }
    snapshots.sort(Comparator.comparing(Snapshot::end));
  }

  /**
   * Deletes the oldest snapshots beyond the maximum number, then while they
   * take more than the maximum size. The latest is always kept.
   */
  private void purge() throws IOException {
    long total = 0;
    for (Snapshot s : snapshots)
      total += Files.size(s.file());
    while (snapshots.size() > 1 && (snapshots.size() > maxCount || total > maxSize)) {
      Snapshot oldest = snapshots.remove(0);
      total -= Files.size(oldest.file());
      Files.deleteIfExists(oldest.file());
      Files.deleteIfExists(metaFile(oldest.file()));
    }
  }

  private static Path metaFile(Path jfr) {
    String name = jfr.getFileName().toString();
    return jfr.resolveSibling(name.substring(0, name.length() - 4) + ".json");
  }

  /**
   * Stops recording and observing. The snapshots stay.
   */
  @Override
  public synchronized void close() {
    triggers.close();
    scheduler.shutdownNow();
    if (rolling != null)
      rolling.close();
  }

  public static final class Builder {
    private Path directory;
    private Duration window = Duration.ofMinutes(5);
    private Duration minGap = Duration.ofMinutes(15);
    private Duration aftermath = Duration.ofSeconds(10);
    private int maxCount = 20;
    private long maxSize = 1L << 30;
    private Map<String, String> settings;
    private int stackDepth = StackDepth.DEFAULT;
    private final List<App> apps = new ArrayList<>();
    private Clock clock = Clock.systemUTC();

    private Builder() {}

    /**
     * The directory of the snapshots, dedicated to them: what it holds may be
     * deleted. Required.
     */
    public Builder directory(Path directory) {
      this.directory = directory;
      return this;
    }

    /**
     * How much a snapshot holds: the last 5 minutes by default.
     */
    public Builder window(Duration window) {
      this.window = window;
      return this;
    }

    /**
     * The least time between two automatic snapshots, 15 minutes by default:
     * an incident that lasts gives one snapshot, not one per slow operation.
     */
    public Builder minGap(Duration minGap) {
      this.minGap = minGap;
      return this;
    }

    /**
     * How long an automatic snapshot waits after its trigger, so that it
     * holds what followed too: 10 seconds by default.
     */
    public Builder aftermath(Duration aftermath) {
      this.aftermath = aftermath;
      return this;
    }

    /**
     * How many snapshots are kept, 20 by default: the oldest are deleted.
     */
    public Builder maxCount(int maxCount) {
      this.maxCount = maxCount;
      return this;
    }

    /**
     * The most the snapshots may take on disk, in bytes, 1 GiB by default.
     */
    public Builder maxSize(long bytes) {
      this.maxSize = bytes;
      return this;
    }

    /**
     * The number of frames JFR keeps of each stack, {@link StackDepth#DEFAULT}
     * by default, instead of the 64 of the JVM: set as this starts, if nothing
     * recorded with JFR in this JVM yet, and the command line does not set it.
     * 0 leaves it as it is.
     */
    public Builder stackDepth(int frames) {
      if (frames < 0 || frames > StackDepth.MAX)
        throw new IllegalArgumentException("Stack depth out of 0.." + StackDepth.MAX + ": " + frames);
      this.stackDepth = frames;
      return this;
    }

    /**
     * The JFR settings of the rolling recording, see {@link #defaultSettings()}.
     */
    public Builder settings(Map<String, String> settings) {
      this.settings = settings;
      return this;
    }

    public Builder observe(App... apps) {
      this.apps.addAll(List.of(apps));
      return this;
    }

    /** For tests. */
    Builder clock(Clock clock) {
      this.clock = clock;
      return this;
    }

    public Snapshots start() throws IOException {
      if (directory == null)
        throw new IllegalStateException("No directory");
      // before its rolling recording, which would fix it
      if (stackDepth > 0)
        StackDepth.raise(stackDepth);
      Snapshots s = new Snapshots(this);
      s.start();
      apps.forEach(s::observe);
      return s;
    }
  }
}
