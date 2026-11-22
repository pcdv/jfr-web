package jfrweb.session;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jfrweb.analysis.EventMappings;
import jfrweb.analysis.RecordingInfo;
import jfrweb.ops.Operations;

/**
 * What the next recording records: a preset, the changes made to it, and JFR
 * settings written as they are given to {@code jcmd JFR.start}. Immutable.
 * <p>
 * The changes are made on {@link #ENTRIES}, the few events and settings worth
 * choosing by hand. Everything else comes from the preset, except the events
 * jfr-web needs to read a recording, which are always on. JFR ignores values
 * it does not understand without telling, so every value is checked here.
 */
public final class RecordingOptions {

  /** The settings a recording starts from. */
  public enum Preset {
    STANDARD("standard", "jfr-web",
             "What this profiler was started with: by default the JDK's profile configuration, native "
             + "calls sampled every 5 ms, and file and socket accesses from 1 ms."),
    LIGHT("light", "Light",
          "The JDK's default configuration: samples every 20 ms, accesses from 20 ms, no exceptions. "
          + "It costs less and shows less."),
    OPERATIONS("operations", "Operations only",
               "No sampling and no other event: only the operations, as the stream of a JVM "
               + "attached to. Flame graphs stay empty.");

    private final String id;
    private final String label;
    private final String description;

    Preset(String id, String label, String description) {
      this.id = id;
      this.label = label;
      this.description = description;
    }

    public String id() {
      return id;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }

    /** @throws IllegalArgumentException if there is none with this id */
    public static Preset of(String id) {
      for (Preset p : values())
        if (p.id.equals(id))
          return p;
      throw new IllegalArgumentException("Unknown preset: " + id);
    }
  }

  /**
   * An event, or a few that go together, that can be switched on or off, and
   * may have one setting to change.
   *
   * @param option      the setting that can be changed: period, threshold or
   *                    throttle, null if there is none
   * @param optionLabel how the interface words it, e.g. "every"
   */
  public record Entry(String id, String label, String hint, List<String> events, String option,
                      String optionLabel) {}

  /** What can be chosen, in the order the interface lists it. */
  public static final List<Entry> ENTRIES = List.of(
    new Entry("java", "Java samples",
              "Where Java code spends its time, the base of the flame graphs. Off: none. On Windows, a period "
              + "under 15.6 ms is only honored while the high-resolution timer is on, as it is for an "
              + "embedded profiler.",
              List.of("jdk.ExecutionSample"), "period", "every"),
    new Entry("native", "Native samples",
              "Threads in native code, e.g. waiting on a socket. JFR samples one per period, and idle threads take most.",
              List.of("jdk.NativeMethodSample"), "period", "every"),
    new Entry("allocations", "Allocation samples",
              "Where memory is allocated, for the allocation flame graph. The throttle caps the samples, e.g. 300/s.",
              List.of("jdk.ObjectAllocationSample"), "throttle", "at most"),
    new Entry("exceptions", "Exceptions created",
              "Each one records its stack: an application creating thousands per second should leave it off.",
              List.of("jdk.JavaExceptionThrow"), null, null),
    new Entry("fileIo", "File reads and writes",
              "Shown in the report of a slow operation.",
              List.of("jdk.FileRead", "jdk.FileWrite"), "threshold", "from"),
    new Entry("socketIo", "Socket reads and writes",
              "Shown in the report of a slow operation.",
              List.of("jdk.SocketRead", "jdk.SocketWrite"), "threshold", "from"),
    new Entry("locks", "Lock and park waits",
              "A thread blocked on a monitor, or parked, e.g. waiting for a lock or a pool.",
              List.of("jdk.JavaMonitorEnter", "jdk.ThreadPark"), "threshold", "from"),
    new Entry("gc", "Garbage collections",
              "Each collection and its pauses.",
              List.of("jdk.GarbageCollection", "jdk.GCPhasePause"), null, null),
    new Entry("cpuLoad", "CPU load",
              "Of the JVM and the machine. On Windows each measure costs about 65 ms of CPU: keep it rare.",
              List.of("jdk.CPULoad"), "period", "every"));

  /** The file next to the sessions that keeps the options. */
  static final String FILE = "recording.properties";

  private static final Pattern DURATION = Pattern.compile("(\\d{1,12})\\s*(ns|us|ms|s|m|h|d)");
  private static final Pattern THROTTLE = Pattern.compile("(\\d{1,9})\\s*/\\s*(ms|s|m|h|d)");
  private static final Pattern LINE = Pattern.compile(
    "\\+?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)(?:#(enabled|threshold|period|stackTrace|cutoff|throttle)=(.+))?");
  private static final Set<String> KEYWORDS = Set.of("everyChunk", "beginChunk", "endChunk");

  private final Preset preset;
  private final Map<String, Boolean> enabled;
  private final Map<String, String> values;
  private final List<String> other;

  private RecordingOptions(Preset preset, Map<String, Boolean> enabled, Map<String, String> values,
                           List<String> other) {
    this.preset = preset;
    this.enabled = Map.copyOf(enabled);
    this.values = Map.copyOf(values);
    this.other = List.copyOf(other);
  }

  /** The preset of this profiler, with no change. */
  public static RecordingOptions standard() {
    return new RecordingOptions(Preset.STANDARD, Map.of(), Map.of(), List.of());
  }

  /** A preset with no change. */
  public static RecordingOptions of(Preset preset) {
    return new RecordingOptions(preset, Map.of(), Map.of(), List.of());
  }

  /**
   * Options checked.
   *
   * @param enabled by id of {@link #ENTRIES}, those switched on or off
   * @param values  by id, the value of their option, none or blank for the preset's
   * @param other   settings as {@code event#option=value}, one per line, or a bare
   *                event name to switch it on; empty lines and lines starting
   *                with # are ignored
   * @throws IllegalArgumentException with what is wrong, and where
   */
  public static RecordingOptions of(Preset preset, Map<String, Boolean> enabled, Map<String, String> values,
                                    String other) {
    Map<String, Boolean> e = new LinkedHashMap<>();
    Map<String, String> v = new LinkedHashMap<>();
    for (Map.Entry<String, Boolean> x : enabled.entrySet()) {
      entry(x.getKey());
      e.put(x.getKey(), x.getValue());
    }
    for (Map.Entry<String, String> x : values.entrySet()) {
      Entry entry = entry(x.getKey());
      String value = x.getValue() == null ? "" : x.getValue().trim();
      if (value.isEmpty())
        continue;
      if (entry.option() == null)
        throw new IllegalArgumentException(entry.label() + " has no value to set");
      try {
        v.put(entry.id(), check(entry.option(), value));
      }
      catch (IllegalArgumentException ex) {
        throw new IllegalArgumentException(entry.label() + ": " + ex.getMessage());
      }
    }
    List<String> lines = new ArrayList<>();
    parse(other, lines);
    return new RecordingOptions(preset, e, v, lines);
  }

  public Preset preset() {
    return preset;
  }

  /** The settings written by hand, one per line. */
  public String other() {
    return String.join("\n", other);
  }

  /** Whether it is the preset of this profiler, unchanged. */
  public boolean isStandard() {
    return preset == Preset.STANDARD && enabled.isEmpty() && values.isEmpty() && other.isEmpty();
  }

  /**
   * Whether an entry is on, with the preset's settings if it is not changed.
   *
   * @param standard the settings of this profiler, see {@link Preset#STANDARD}
   */
  public boolean isEnabled(Entry entry, Map<String, String> standard) {
    Boolean b = enabled.get(entry.id());
    return b != null ? b : baseEnabled(entry, base(preset, standard));
  }

  /** The value of the option of an entry, "" if none is set. */
  public String value(Entry entry, Map<String, String> standard) {
    String v = values.get(entry.id());
    return v != null ? v : baseValue(entry, base(preset, standard));
  }

  /**
   * The same options with what the preset already says dropped, so that a
   * change of the preset's settings, e.g. by the next version, shows.
   */
  RecordingOptions normalized(Map<String, String> standard) {
    Map<String, String> base = base(preset, standard);
    Map<String, Boolean> e = new LinkedHashMap<>(enabled);
    Map<String, String> v = new LinkedHashMap<>(values);
    for (Entry entry : ENTRIES) {
      Boolean b = e.get(entry.id());
      if (b != null && b == baseEnabled(entry, base))
        e.remove(entry.id());
      String s = v.get(entry.id());
      if (s != null && same(s, baseValue(entry, base)))
        v.remove(entry.id());
    }
    return new RecordingOptions(preset, e, v, other);
  }

  /**
   * The JFR settings of a recording: the preset's, the changes, the lines
   * written by hand, then the events jfr-web needs, always on.
   *
   * @param standard the settings of this profiler, see {@link Preset#STANDARD}
   */
  public Map<String, String> settings(Map<String, String> standard) {
    Map<String, String> s = new LinkedHashMap<>(base(preset, standard));
    for (Entry entry : ENTRIES) {
      Boolean b = enabled.get(entry.id());
      String v = values.get(entry.id());
      for (String event : entry.events()) {
        if (b != null)
          s.put(event + "#enabled", String.valueOf(b));
        if (v != null)
          s.put(event + "#" + entry.option(), v);
      }
    }
    s.putAll(parse(other()));
    s.putAll(required());
    return s;
  }

  /**
   * The events jfr-web reads a recording by: flak's requests, the operations
   * and the events mapped to operations.
   */
  public static Set<String> requiredEvents() {
    Set<String> events = new LinkedHashSet<>();
    events.add(RecordingInfo.FLAK_REQUEST);
    events.add(Operations.EVENT);
    events.add(Operations.SEGMENT_EVENT);
    EventMappings.list().forEach(m -> events.add(m.event()));
    return events;
  }

  /** The settings that switch {@link #requiredEvents()} on. */
  static Map<String, String> required() {
    Map<String, String> s = new LinkedHashMap<>();
    for (String event : requiredEvents())
      s.put(event + "#enabled", "true");
    return s;
  }

  /**
   * The events named by the lines written by hand.
   */
  public Set<String> otherEvents() {
    Set<String> events = new LinkedHashSet<>();
    for (String line : other) {
      Matcher m = LINE.matcher(line);
      if (m.matches())
        events.add(m.group(1));
    }
    return events;
  }

  // --- the settings of a preset

  static Map<String, String> base(Preset preset, Map<String, String> standard) {
    return switch (preset) {
      case STANDARD -> standard;
      case LIGHT -> Sessions.lightSettings();
      case OPERATIONS -> required();
    };
  }

  private static boolean baseEnabled(Entry entry, Map<String, String> base) {
    return "true".equals(base.get(entry.events().get(0) + "#enabled"));
  }

  private static String baseValue(Entry entry, Map<String, String> base) {
    return entry.option() == null ? "" : base.getOrDefault(entry.events().get(0) + "#" + entry.option(), "");
  }

  private static Entry entry(String id) {
    for (Entry e : ENTRIES)
      if (e.id().equals(id))
        return e;
    throw new IllegalArgumentException("Unknown event choice: " + id);
  }

  // --- the values

  /**
   * The value of an option, written the way JFR reads it.
   *
   * @throws IllegalArgumentException if JFR would not read it, or would ignore it
   */
  static String check(String option, String value) {
    String v = value.trim();
    switch (option) {
      case "period":
        if (KEYWORDS.contains(v))
          return v;
        return duration(v, 1_000_000L, "a period of 1 ms or more, e.g. 10 ms, or everyChunk");
      case "threshold":
        return duration(v, 0, "a duration, e.g. 10 ms");
      case "cutoff":
        return v.equals("infinity") ? v : duration(v, 0, "a duration, e.g. 10 s, or infinity");
      case "throttle": {
        if (v.equals("off"))
          return v;
        Matcher m = THROTTLE.matcher(v);
        if (!m.matches() || Long.parseLong(m.group(1)) == 0)
          throw new IllegalArgumentException("not a rate: " + v + ", e.g. 300/s, or off");
        return m.group(1) + "/" + m.group(2);
      }
      case "enabled":
      case "stackTrace":
        if (!v.equals("true") && !v.equals("false"))
          throw new IllegalArgumentException("not true or false: " + v);
        return v;
      default:
        throw new IllegalArgumentException("unknown setting: " + option);
    }
  }

  private static String duration(String v, long minNanos, String expected) {
    Matcher m = DURATION.matcher(v);
    if (!m.matches())
      throw new IllegalArgumentException("not " + expected + ": " + v);
    long n;
    try {
      n = nanos(m);
    }
    catch (ArithmeticException e) {
      throw new IllegalArgumentException("too long: " + v);
    }
    if (n < minNanos)
      throw new IllegalArgumentException("not " + expected + ": " + v);
    return m.group(1) + " " + m.group(2);
  }

  private static long nanos(Matcher duration) {
    long unit = switch (duration.group(2)) {
      case "ns" -> 1L;
      case "us" -> 1_000L;
      case "ms" -> 1_000_000L;
      case "s" -> 1_000_000_000L;
      case "m" -> 60_000_000_000L;
      case "h" -> 3_600_000_000_000L;
      default -> 86_400_000_000_000L;
    };
    return Math.multiplyExact(Long.parseLong(duration.group(1)), unit);
  }

  /** Whether two values of a setting say the same: "0 ms" and "0 ns" do, "10 ms" and "10ms" too. */
  private static boolean same(String a, String b) {
    if (a.equals(b))
      return true;
    Matcher x = DURATION.matcher(a.trim()), y = DURATION.matcher(b.trim());
    if (x.matches() && y.matches()) {
      try {
        return nanos(x) == nanos(y);
      }
      catch (ArithmeticException e) {
        return false;
      }
    }
    return a.replaceAll("\\s", "").equals(b.replaceAll("\\s", ""));
  }

  // --- as a recording was made

  /** The settings that are written by hand: the ones of an event that are neither on nor off. */
  private static final List<String> HAND = List.of("threshold", "period", "stackTrace", "cutoff", "throttle");

  /**
   * The options that record as a recording did: the preset that differs least
   * from it, the choices of {@link #ENTRIES}, and what is left written by
   * hand. A setting the recording does not tell, e.g. a default no preset
   * mentions, is left to the preset.
   *
   * @param recorded the settings of each event, by event then setting, as a recording tells them
   * @param standard the settings of this profiler, see {@link Preset#STANDARD}
   */
  public static RecordingOptions like(Map<String, Map<String, String>> recorded, Map<String, String> standard) {
    Map<String, String> flat = new LinkedHashMap<>();
    recorded.forEach((event, settings) -> settings.forEach((option, value) -> flat.put(event + "#" + option, value)));

    Map<String, Boolean> enabled = new LinkedHashMap<>();
    Map<String, String> values = new LinkedHashMap<>();
    for (Entry entry : ENTRIES) {
      String first = entry.events().get(0);
      enabled.put(entry.id(), "true".equals(flat.get(first + "#enabled")));
      String v = entry.option() == null ? null : flat.get(first + "#" + entry.option());
      if (v != null) {
        try {
          values.put(entry.id(), check(entry.option(), v));
        }
        catch (IllegalArgumentException e) {
          // a value the panel would refuse, e.g. a period under 1 ms: left to the preset
        }
      }
    }

    RecordingOptions best = null;
    for (Preset p : Preset.values()) {
      RecordingOptions o = new RecordingOptions(p, enabled, values, difference(p, enabled, values, flat, standard));
      if (best == null || o.other.size() < best.other.size())
        best = o;
    }
    return best.normalized(standard);
  }

  /** The lines that make a preset with some choices record as the settings given. */
  private static List<String> difference(Preset preset, Map<String, Boolean> enabled, Map<String, String> values,
                                         Map<String, String> flat, Map<String, String> standard) {
    Map<String, String> built = new RecordingOptions(preset, enabled, values, List.of()).settings(standard);
    Set<String> events = new LinkedHashSet<>();
    for (String key : built.keySet())
      events.add(key.substring(0, key.indexOf('#')));
    for (String key : flat.keySet())
      events.add(key.substring(0, key.indexOf('#')));

    Set<String> required = requiredEvents();
    List<String> lines = new ArrayList<>();
    for (String event : events) {
      boolean wanted = "true".equals(flat.get(event + "#enabled"));
      boolean builtOn = "true".equals(built.get(event + "#enabled"));
      if (wanted != builtOn && !(required.contains(event) && !wanted))
        add(lines, event + "#enabled=" + wanted);
      if (!wanted)
        continue;
      for (String option : HAND) {
        String recorded = flat.get(event + "#" + option);
        String made = built.get(event + "#" + option);
        // an event the preset leaves off has no setting of its own to compare with
        if (recorded != null && (made == null ? !builtOn : !same(recorded, made)))
          add(lines, event + "#" + option + "=" + recorded);
      }
    }
    return lines;
  }

  /** Adds a line if it is valid, as JFR settings read from a recording may not be. */
  private static void add(List<String> lines, String line) {
    try {
      lines.add(line(line));
    }
    catch (IllegalArgumentException e) {
      // not something the panel would accept: left out
    }
  }

  // --- the lines written by hand

  /** Checks the lines of a text, and adds the useful ones, cleaned, to a list. */
  private static void parse(String text, List<String> lines) {
    if (text == null)
      return;
    String[] all = text.split("\\R", -1);
    for (int i = 0; i < all.length; i++) {
      String line = all[i].trim();
      if (line.isEmpty() || line.startsWith("#"))
        continue;
      try {
        lines.add(line(line));
      }
      catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Line " + (i + 1) + ": " + e.getMessage());
      }
    }
  }

  /** The settings of the lines, already checked. */
  private static Map<String, String> parse(String text) {
    List<String> lines = new ArrayList<>();
    parse(text, lines);
    Map<String, String> s = new LinkedHashMap<>();
    for (String l : lines) {
      int eq = l.indexOf('=');
      s.put(l.substring(0, eq), l.substring(eq + 1));
    }
    return s;
  }

  /** A line as {@code event#option=value}, checked. */
  private static String line(String line) {
    Matcher m = LINE.matcher(line);
    if (!m.matches())
      throw new IllegalArgumentException("not an event or event#setting=value: " + line);
    String event = m.group(1);
    String option = m.group(2) == null ? "enabled" : m.group(2);
    String value = check(option, m.group(2) == null ? "true" : m.group(3));
    if (option.equals("enabled") && value.equals("false") && requiredEvents().contains(event))
      throw new IllegalArgumentException(event + " cannot be switched off: jfr-web reads the operations from it");
    return event + "#" + option + "=" + value;
  }

  // --- kept on disk

  /** Writes the options to a file. */
  void save(Path file) throws IOException {
    Properties p = new Properties();
    writeTo(p, "");
    try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      p.store(w, "What the next session of jfr-web records");
    }
  }

  /**
   * Reads the options of a file.
   *
   * @throws IOException if it cannot be read
   * @throws IllegalArgumentException if what it holds is not valid
   */
  static RecordingOptions load(Path file) throws IOException {
    Properties p = new Properties();
    try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      p.load(r);
    }
    return readFrom(p, "");
  }

  /** Adds the options to properties, their names starting with a prefix. */
  void writeTo(Properties p, String prefix) {
    p.setProperty(prefix + "preset", preset.id());
    enabled.forEach((id, b) -> p.setProperty(prefix + "enabled." + id, String.valueOf(b)));
    values.forEach((id, v) -> p.setProperty(prefix + "value." + id, v));
    if (!other.isEmpty())
      p.setProperty(prefix + "other", other());
  }

  /**
   * Reads the options from properties, their names starting with a prefix.
   *
   * @throws IllegalArgumentException if what they hold is not valid
   */
  static RecordingOptions readFrom(Properties p, String prefix) {
    Map<String, Boolean> e = new LinkedHashMap<>();
    Map<String, String> v = new LinkedHashMap<>();
    for (String k : p.stringPropertyNames()) {
      if (k.startsWith(prefix + "enabled."))
        e.put(k.substring((prefix + "enabled.").length()), Boolean.parseBoolean(p.getProperty(k)));
      else if (k.startsWith(prefix + "value."))
        v.put(k.substring((prefix + "value.").length()), p.getProperty(k));
    }
    return of(Preset.of(p.getProperty(prefix + "preset", Preset.STANDARD.id())), e, v, p.getProperty(prefix + "other"));
  }
}
