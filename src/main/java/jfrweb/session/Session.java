package jfrweb.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.GZIPOutputStream;

import jfrweb.analysis.Analysis;
import jfrweb.analysis.ProfileWriter;

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
  Recorder.Running recording;

  /** The sessions that keep it on disk, null for one kept by others, e.g. a snapshot. */
  Sessions owner;

  /** Loaded on demand, and dropped under memory pressure. */
  private SoftReference<Analysis> analysis = new SoftReference<>(null);

  /** Its profile, gzipped: the same; and that of its allocations. */
  private SoftReference<byte[]> profile = new SoftReference<>(null);
  private SoftReference<byte[]> allocationProfile = new SoftReference<>(null);

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
    Sessions o = owner;
    if (o != null)
      o.save(this);
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
   * The size of the .jfr file; while recording, an estimate of it so far.
   */
  public long getSize() {
    if (state == State.RECORDING) {
      Recorder.Running r = recording;
      return r != null ? r.size() : 0;
    }
    try {
      return Files.size(file);
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
      // a snapshot may hold what came before its start
      a = Analysis.load(file, owner != null ? start : null);
      analysis = new SoftReference<>(a);
    }
    return a;
  }

  /**
   * The samples of the recording as a profile, gzipped (see ProfileWriter):
   * built once, and kept as long as memory allows.
   *
   * @throws IllegalStateException while recording
   */
  public synchronized byte[] getProfile() throws IOException {
    return getProfile(false);
  }

  /**
   * @param allocations that of its allocation samples, weighing bytes
   */
  public synchronized byte[] getProfile(boolean allocations) throws IOException {
    byte[] p = (allocations ? allocationProfile : profile).get();
    if (p == null) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (Writer w = new OutputStreamWriter(new GZIPOutputStream(bytes), StandardCharsets.UTF_8)) {
        ProfileWriter.write(getAnalysis(), w, (thread, operation) -> true, allocations);
      }
      p = bytes.toByteArray();
      if (allocations)
        allocationProfile = new SoftReference<>(p);
      else
        profile = new SoftReference<>(p);
    }
    return p;
  }
}
