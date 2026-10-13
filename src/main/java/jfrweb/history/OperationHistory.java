package jfrweb.history;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import flak.App;
import jfrweb.json.Json;
import jfrweb.ops.Operation;
import jfrweb.ops.OperationListener;
import jfrweb.ops.OperationSources;

/**
 * Keeps a history of the operations of the application on disk, for
 * production: no profiling, only counters fed by the operations as they end,
 * those of the jfrweb.ops API and the requests of the flak apps observed,
 * written at a regular interval. What an interval holds: per operation name,
 * the number of operations, their durations (total, p50, p99, max, and the
 * histogram they come from, so that intervals add up) and sizes; and its
 * slowest operations.
 * <p>
 * Each interval is one JSON line of an hourly file, {@code
 * <directory>/<yyyy-MM-dd>/<HH>.jsonl}, dates and hours in UTC. The file of an
 * hour is gzipped once the hour is over. Days older than the maximum age are
 * deleted, then the oldest hours while the whole exceeds the maximum size.
 * <pre>
 * OperationHistory history = OperationHistory.builder()
 *                                            .directory(Path.of("logs/operations"))
 *                                            .observe(app)
 *                                            .start();
 * </pre>
 */
public final class OperationHistory implements AutoCloseable {

  private static final System.Logger log = System.getLogger(OperationHistory.class.getName());

  private final Path directory;
  private final Duration interval;
  private final Duration maxAge;
  private final long maxSize;
  private final int slowest;
  private final Clock clock;

  private final ReadWriteLock swap = new ReentrantReadWriteLock();
  private IntervalStats current;

  private final OperationSources sources;
  private final ScheduledExecutorService scheduler;
  private long lastHousekeepingHour = Long.MIN_VALUE;

  private OperationHistory(Builder b) {
    this.directory = b.directory;
    this.interval = b.interval;
    this.maxAge = b.maxAge;
    this.maxSize = b.maxSize;
    this.slowest = b.slowest;
    this.clock = b.clock;
    this.current = new IntervalStats(clock.instant(), slowest);
    this.scheduler = b.scheduled
      ? Executors.newSingleThreadScheduledExecutor(r -> {
          Thread t = new Thread(r, "jfr-web operation history");
          t.setDaemon(true);
          return t;
        })
      : null;
    this.sources = new OperationSources(new OperationListener() {
      @Override
      public void onEnd(Operation op) {
        swap.readLock().lock();
        try {
          current.record(op.getName(), op.getDetail(), op.getStatus(), op.isFailed(), op.getDurationNanos(),
                         op.getBytesIn(), op.getBytesOut(), clock.instant(), op.getEndThread());
        }
        finally {
          swap.readLock().unlock();
        }
      }
    });
  }

  public static Builder builder() {
    return new Builder();
  }

  public Path getDirectory() {
    return directory;
  }

  public Duration getInterval() {
    return interval;
  }

  /**
   * Counts the requests of a flak app, until closed. The operations of the
   * jfrweb.ops API are counted from the start.
   */
  public void observe(App app) {
    sources.observe(app);
  }

  private void start() throws IOException {
    Files.createDirectories(directory);
    housekeeping(clock.instant());
    if (scheduler != null)
      scheduleNext();
  }

  /**
   * At the next round multiple of the interval: an interval of a minute ends
   * on the minute.
   */
  private void scheduleNext() {
    long now = clock.millis();
    long step = interval.toMillis();
    long boundary = (now / step + 1) * step;
    scheduler.schedule(() -> {
      try {
        flush(Instant.ofEpochMilli(boundary));
      }
      catch (RuntimeException e) {
        log.log(System.Logger.Level.WARNING, "Could not write the operation history", e);
      }
      scheduleNext();
    }, boundary - now, TimeUnit.MILLISECONDS);
  }

  /**
   * The number of operations of the interval in progress, for tests.
   */
  int pending() {
    swap.readLock().lock();
    try {
      return current.count();
    }
    finally {
      swap.readLock().unlock();
    }
  }

  /**
   * Ends the current interval and writes it. By the scheduler, or once more
   * when closing.
   */
  synchronized void flush(Instant end) {
    IntervalStats ended;
    swap.writeLock().lock();
    try {
      ended = current;
      current = new IntervalStats(end, slowest);
    }
    finally {
      swap.writeLock().unlock();
    }
    write(ended, end);
    housekeeping(end);
  }

  private void write(IntervalStats stats, Instant end) {
    Path file = HistoryFiles.hourFile(directory, stats.getStart());
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, Json.write(stats.toJson(end)) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    catch (IOException e) {
      log.log(System.Logger.Level.WARNING, "Could not write the operation history to " + file, e);
    }
  }

  /**
   * Once an hour: gzips the files of the hours that are over, then deletes
   * what is too old or too much.
   */
  private void housekeeping(Instant now) {
    long hour = now.getEpochSecond() / 3600;
    if (hour == lastHousekeepingHour)
      return;
    lastHousekeepingHour = hour;
    try {
      compressPastHours(now);
      purge(now);
    }
    catch (IOException | RuntimeException e) {
      log.log(System.Logger.Level.WARNING, "Could not tidy the operation history in " + directory, e);
    }
  }

  private void compressPastHours(Instant now) throws IOException {
    Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
    for (Path f : HistoryFiles.list(directory)) {
      if (!f.getFileName().toString().endsWith(".jsonl"))
        continue;
      Instant hour = HistoryFiles.hourOf(f);
      if (hour != null && hour.isBefore(currentHour))
        gzip(f);
    }
  }

  private static void gzip(Path f) throws IOException {
    Path gz = f.resolveSibling(f.getFileName() + ".gz");
    Path tmp = f.resolveSibling(f.getFileName() + ".gz.tmp");
    try (InputStream in = Files.newInputStream(f);
         OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
      in.transferTo(out);
    }
    Files.move(tmp, gz, StandardCopyOption.REPLACE_EXISTING);
    Files.delete(f);
  }

  private void purge(Instant now) throws IOException {
    LocalDate oldestKept = LocalDate.ofInstant(now.minus(maxAge), ZoneOffset.UTC);
    try (Stream<Path> days = Files.list(directory)) {
      for (Path day : (Iterable<Path>) days::iterator) {
        LocalDate date = HistoryFiles.dateOf(day);
        if (date != null && date.isBefore(oldestKept))
          deleteTree(day);
      }
    }

    List<Path> files = new ArrayList<>(HistoryFiles.list(directory));
    files.sort(Comparator.comparing(HistoryFiles::hourOf));
    long total = 0;
    for (Path f : files)
      total += Files.size(f);
    Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
    for (Path f : files) {
      if (total <= maxSize || !HistoryFiles.hourOf(f).isBefore(currentHour))
        break;
      total -= Files.size(f);
      Files.delete(f);
      try (Stream<Path> rest = Files.list(f.getParent())) {
        if (rest.findAny().isEmpty())
          Files.delete(f.getParent());
      }
    }
  }

  private static void deleteTree(Path dir) throws IOException {
    try (Stream<Path> all = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) all.sorted(Comparator.reverseOrder())::iterator)
        Files.delete(p);
    }
  }

  /**
   * Stops observing, and writes the interval in progress.
   */
  @Override
  public void close() {
    sources.close();
    if (scheduler != null)
      scheduler.shutdownNow();
    flush(clock.instant());
  }

  public static final class Builder {
    private Path directory;
    private Duration interval = Duration.ofMinutes(1);
    private Duration maxAge = Duration.ofDays(7);
    private long maxSize = 100L << 20;
    private int slowest = 10;
    private final List<App> apps = new ArrayList<>();
    private Clock clock = Clock.systemUTC();
    private boolean scheduled = true;

    private Builder() {}

    /**
     * The directory of the history, dedicated to it: what it holds may be
     * deleted. Required.
     */
    public Builder directory(Path directory) {
      this.directory = directory;
      return this;
    }

    /**
     * How often the counters are written: the finest period a diagnosis can
     * look at. One minute by default, at least one second.
     */
    public Builder interval(Duration interval) {
      if (interval.compareTo(Duration.ofSeconds(1)) < 0)
        throw new IllegalArgumentException("Interval under 1 s: " + interval);
      this.interval = interval;
      return this;
    }

    /**
     * How long the history is kept, 7 days by default.
     */
    public Builder maxAge(Duration maxAge) {
      this.maxAge = maxAge;
      return this;
    }

    /**
     * The most the history may take on disk, in bytes, 100 MiB by default: the
     * oldest hours are deleted beyond it.
     */
    public Builder maxSize(long bytes) {
      this.maxSize = bytes;
      return this;
    }

    /**
     * How many of the slowest operations each interval keeps, 10 by default.
     */
    public Builder slowest(int n) {
      this.slowest = n;
      return this;
    }

    public Builder observe(App... apps) {
      this.apps.addAll(List.of(apps));
      return this;
    }

    /** For tests: the time, and intervals ended by hand with flush(). */
    Builder clock(Clock clock, boolean scheduled) {
      this.clock = clock;
      this.scheduled = scheduled;
      return this;
    }

    public OperationHistory start() throws IOException {
      if (directory == null)
        throw new IllegalStateException("No directory");
      OperationHistory h = new OperationHistory(this);
      h.start();
      apps.forEach(h::observe);
      return h;
    }
  }
}
