package jfrweb.session;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import jdk.jfr.Recording;
import jfrweb.analysis.Analysis;

/**
 * One profiling recording, kept on disk as one .jfr file.
 */
public final class Session {

  public enum State {
    RECORDING,
    STOPPED
  }

  private final int id;
  private final Instant start;
  private final String trigger;
  private final Path file;

  private volatile Instant end;
  private volatile State state = State.RECORDING;
  private volatile String name;

  /** While recording. */
  Recording recording;

  /** Recorded elsewhere, e.g. a snapshot: its file may be purged. */
  boolean external;

  /** Loaded on demand, and dropped under memory pressure. */
  private SoftReference<Analysis> analysis = new SoftReference<>(null);

  Session(int id, Instant start, String trigger, Path file) {
    this.id = id;
    this.start = start;
    this.trigger = trigger;
    this.file = file;
  }

  public int getId() {
    return id;
  }

  public Instant getStart() {
    return start;
  }

  /**
   * The end, or null while recording.
   */
  public Instant getEnd() {
    return end;
  }

  /**
   * How the session was started, e.g. "manual".
   */
  public String getTrigger() {
    return trigger;
  }

  /**
   * What the user called it, or null.
   */
  public String getName() {
    return name;
  }

  /**
   * Names it; blank or null removes the name.
   */
  public void setName(String name) {
    this.name = name == null || name.isBlank() ? null : name.strip();
  }

  public State getState() {
    return state;
  }

  public Path getFile() {
    return file;
  }

  public Duration getDuration() {
    Instant e = end;
    return Duration.between(start, e != null ? e : Instant.now());
  }

  /**
   * The size of the .jfr file, 0 while recording.
   */
  public long getSize() {
    try {
      return state == State.STOPPED ? Files.size(file) : 0;
    }
    catch (IOException e) {
      return 0;
    }
  }

  void stopped(Instant end) {
    this.end = end;
    this.recording = null;
    this.state = State.STOPPED;
  }

  /**
   * The recording, read for analysis. The result is kept as long as memory
   * allows.
   *
   * @throws IllegalStateException while recording
   */
  public synchronized Analysis getAnalysis() throws IOException {
    if (state != State.STOPPED)
      throw new IllegalStateException("Session " + id + " is still recording");
    Analysis a = analysis.get();
    if (a == null) {
      a = Analysis.load(file);
      analysis = new SoftReference<>(a);
    }
    return a;
  }
}
