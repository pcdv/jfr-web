package jfrweb.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the next session records: the values are checked, since JFR ignores
 * what it does not understand, and the sessions record with the result.
 */
public class RecordingOptionsTest {

  @TempDir
  Path dir;

  private static final Map<String, String> STANDARD = Sessions.defaultSettings();

  private static RecordingOptions options(Map<String, Boolean> enabled, Map<String, String> values, String other) {
    return RecordingOptions.of(RecordingOptions.Preset.STANDARD, enabled, values, other);
  }

  private static String message(Runnable r) {
    return assertThrows(IllegalArgumentException.class, r::run).getMessage();
  }

  @Test
  public void periodsAreWrittenTheWayJfrReadsThem() {
    assertEquals("10 ms", RecordingOptions.check("period", "10ms"));
    assertEquals("2 s", RecordingOptions.check("period", " 2  s "));
    assertEquals("everyChunk", RecordingOptions.check("period", "everyChunk"));
    // JFR accepts these and does what it likes with them
    assertTrue(message(() -> RecordingOptions.check("period", "0 ms")).contains("1 ms or more"));
    assertTrue(message(() -> RecordingOptions.check("period", "500 us")).contains("1 ms or more"));
    assertTrue(message(() -> RecordingOptions.check("period", "abc")).contains("abc"));
    assertTrue(message(() -> RecordingOptions.check("period", "10")).contains("10"));
  }

  @Test
  public void thresholdsThrottlesAndFlags() {
    assertEquals("0 ms", RecordingOptions.check("threshold", "0 ms"));
    assertEquals("300/s", RecordingOptions.check("throttle", "300 / s"));
    assertEquals("off", RecordingOptions.check("throttle", "off"));
    assertEquals("infinity", RecordingOptions.check("cutoff", "infinity"));
    assertThrows(IllegalArgumentException.class, () -> RecordingOptions.check("throttle", "0/s"));
    assertThrows(IllegalArgumentException.class, () -> RecordingOptions.check("throttle", "fast"));
    assertThrows(IllegalArgumentException.class, () -> RecordingOptions.check("enabled", "yes"));
    assertThrows(IllegalArgumentException.class, () -> RecordingOptions.check("threshold", "999999999999999 d"));
  }

  @Test
  public void changesAreOnTopOfThePreset() {
    RecordingOptions o = options(Map.of("exceptions", false, "gc", false), Map.of("java", "5 ms", "fileIo", "50 ms"), "");
    Map<String, String> s = o.settings(STANDARD);

    assertEquals("5 ms", s.get("jdk.ExecutionSample#period"));
    assertEquals("50 ms", s.get("jdk.FileRead#threshold"));
    assertEquals("50 ms", s.get("jdk.FileWrite#threshold"));
    assertEquals("false", s.get("jdk.JavaExceptionThrow#enabled"));
    assertEquals("false", s.get("jdk.GCPhasePause#enabled"));
    // not changed
    assertEquals(STANDARD.get("jdk.NativeMethodSample#period"), s.get("jdk.NativeMethodSample#period"));
    assertEquals("5 ms", o.value(RecordingOptions.ENTRIES.get(0), STANDARD));
    assertFalse(o.isEnabled(RecordingOptions.ENTRIES.get(3), STANDARD));
  }

  @Test
  public void presets() {
    Map<String, String> light = RecordingOptions.of(RecordingOptions.Preset.LIGHT).settings(STANDARD);
    assertEquals("20 ms", light.get("jdk.ExecutionSample#period"));
    assertEquals("true", light.get("flak.Request#enabled"));

    Map<String, String> operations = RecordingOptions.of(RecordingOptions.Preset.OPERATIONS).settings(STANDARD);
    assertEquals("true", operations.get("flak.Request#enabled"));
    assertNull(operations.get("jdk.ExecutionSample#enabled"));
    assertFalse(RecordingOptions.of(RecordingOptions.Preset.OPERATIONS)
                                .isEnabled(RecordingOptions.ENTRIES.get(0), STANDARD));
    assertThrows(IllegalArgumentException.class, () -> RecordingOptions.Preset.of("fast"));
  }

  @Test
  public void linesWrittenByHand() {
    RecordingOptions o = options(Map.of(), Map.of(),
                                 "# class loading\n\njdk.ClassLoad#threshold=5ms\n+jdk.Compilation\n com.acme.RpcCall#stackTrace=false ");
    Map<String, String> s = o.settings(STANDARD);
    assertEquals("5 ms", s.get("jdk.ClassLoad#threshold"));
    assertEquals("true", s.get("jdk.Compilation#enabled"));
    assertEquals("false", s.get("com.acme.RpcCall#stackTrace"));
    assertEquals("jdk.ClassLoad#threshold=5 ms\njdk.Compilation#enabled=true\ncom.acme.RpcCall#stackTrace=false", o.other());
    assertEquals(java.util.Set.of("jdk.ClassLoad", "jdk.Compilation", "com.acme.RpcCall"), o.otherEvents());
  }

  @Test
  public void aBadLineNamesItsNumber() {
    assertTrue(message(() -> options(Map.of(), Map.of(), "jdk.ClassLoad\njdk.ClassLoad#threshold=soon"))
                 .startsWith("Line 2: "));
    assertTrue(message(() -> options(Map.of(), Map.of(), "classload")).startsWith("Line 1: "));
    assertTrue(message(() -> options(Map.of(), Map.of(), "jdk.ClassLoad#color=red")).startsWith("Line 1: "));
  }

  @Test
  public void theEventsJfrWebNeedsStayOn() {
    assertTrue(message(() -> options(Map.of(), Map.of(), "flak.Request#enabled=false")).contains("flak.Request"));
    assertTrue(message(() -> options(Map.of(), Map.of(), "jdk.ClassLoad\njfrweb.Operation#enabled=false")).startsWith("Line 2"));
    // other settings of them are fine
    assertEquals("false", options(Map.of(), Map.of(), "flak.Request#stackTrace=false")
                            .settings(STANDARD).get("flak.Request#stackTrace"));
    assertEquals("true", options(Map.of(), Map.of(), "").settings(STANDARD).get("flak.Request#enabled"));
  }

  @Test
  public void aWrongChoiceIsRefused() {
    assertTrue(message(() -> options(Map.of("fast", true), Map.of(), "")).contains("fast"));
    assertTrue(message(() -> options(Map.of(), Map.of("exceptions", "5 ms"), "")).contains("no value"));
    assertTrue(message(() -> options(Map.of(), Map.of("java", "often"), "")).startsWith("Java samples: "));
    // blank is the preset's
    assertTrue(options(Map.of(), Map.of("java", " "), "").isStandard());
  }

  @Test
  public void whatThePresetSaysIsNotKept() {
    String profile = STANDARD.get("jdk.ExecutionSample#period");
    RecordingOptions same = options(Map.of("java", true, "exceptions", true), Map.of("java", profile), "");
    assertFalse(same.isStandard());
    assertTrue(same.normalized(STANDARD).isStandard());
    assertFalse(options(Map.of("exceptions", false), Map.of(), "").normalized(STANDARD).isStandard());
  }

  // --- as a recording was made

  /** The settings of the enabled events, as RecordingInfo tells them. */
  private static Map<String, Map<String, String>> recorded(Map<String, String> settings) {
    Map<String, Map<String, String>> res = new TreeMap<>();
    settings.forEach((key, value) -> {
      int i = key.indexOf('#');
      res.computeIfAbsent(key.substring(0, i), e -> new TreeMap<>()).put(key.substring(i + 1), value);
    });
    res.values().removeIf(s -> !"true".equals(s.get("enabled")));
    return res;
  }

  /** What was recorded is what the options would record: the events on, with their settings. */
  private static void assertSameRecording(Map<String, String> expected, Map<String, String> actual) {
    Map<String, Map<String, String>> want = recorded(expected), got = recorded(actual);
    assertEquals(want.keySet(), got.keySet());
    want.forEach((event, settings) -> settings.forEach((option, value) -> {
      // the settings that are set by the options: those the recording gives of an event
      if (got.get(event).containsKey(option))
        assertEquals(value, got.get(event).get(option), event + "#" + option);
    }));
  }

  private static void assertLike(RecordingOptions original) {
    Map<String, String> settings = original.settings(STANDARD);
    RecordingOptions like = RecordingOptions.like(recorded(settings), STANDARD);
    assertSameRecording(settings, like.settings(STANDARD));
  }

  @Test
  public void recordedAgainAsItWas() {
    assertLike(RecordingOptions.standard());
    assertLike(RecordingOptions.of(RecordingOptions.Preset.LIGHT));
    assertLike(RecordingOptions.of(RecordingOptions.Preset.OPERATIONS));
    assertLike(options(Map.of("exceptions", false, "gc", false, "allocations", false),
                       Map.of("java", "3 ms", "fileIo", "50 ms", "socketIo", "2 s"),
                       "jdk.ClassLoad#threshold=5 ms\njdk.Compilation"));
    assertLike(RecordingOptions.of(RecordingOptions.Preset.OPERATIONS, Map.of("java", true), Map.of("java", "7 ms"),
                                   "jdk.OldObjectSample#cutoff=infinity"));
  }

  @Test
  public void thePresetClosestIsTheStart() {
    assertEquals(RecordingOptions.Preset.STANDARD,
                 RecordingOptions.like(recorded(STANDARD), STANDARD).preset());
    assertEquals(RecordingOptions.Preset.LIGHT,
                 RecordingOptions.like(recorded(Sessions.lightSettings()), STANDARD).preset());
    assertEquals(RecordingOptions.Preset.OPERATIONS,
                 RecordingOptions.like(recorded(RecordingOptions.required()), STANDARD).preset());
    // a recording of the profile as it is asks for nothing by hand
    assertTrue(RecordingOptions.like(recorded(STANDARD), STANDARD).isStandard());
  }

  @Test
  public void aRecordingOfOtherToolsGivesWhatCanBeUsed() {
    Map<String, Map<String, String>> other = new TreeMap<>(recorded(Sessions.lightSettings()));
    // JFR tells a zero threshold in ns, and a value the panel refuses
    other.put("jdk.GarbageCollection", Map.of("enabled", "true", "threshold", "0 ns"));
    other.put("jdk.ExecutionSample", Map.of("enabled", "true", "period", "100 us"));
    other.put("jdk.ClassLoad", Map.of("enabled", "true", "threshold", "5 ms", "stackTrace", "true"));
    RecordingOptions o = RecordingOptions.like(other, STANDARD);

    assertEquals(RecordingOptions.Preset.LIGHT, o.preset());
    assertFalse(o.other().contains("GarbageCollection"), o.other());
    assertTrue(o.other().contains("jdk.ClassLoad#enabled=true"), o.other());
    assertTrue(o.other().contains("jdk.ClassLoad#threshold=5 ms"), o.other());
    // under 1 ms: left to the preset
    assertEquals("20 ms", o.value(RecordingOptions.ENTRIES.get(0), STANDARD));
    assertFalse(o.other().contains("ExecutionSample"), o.other());
  }

  @Test
  public void whatAnotherRecordingChangedIsTold() {
    RecordingOptions asked = options(Map.of("exceptions", false, "gc", false), Map.of("java", "50 ms", "fileIo", "0 ms"), "");
    Map<String, Map<String, String>> same = recorded(asked.settings(STANDARD));
    // what JFR tells: an event of its own rolling recording
    assertTrue(asked.merged(same, STANDARD).isEmpty());

    Map<String, Map<String, String>> merged = new TreeMap<>(same);
    merged.put("jdk.ExecutionSample", Map.of("enabled", "true", "period", "20 ms"));
    merged.put("jdk.GarbageCollection", Map.of("enabled", "true", "threshold", "0 ns"));
    // 0 ms is 0 ns
    merged.put("jdk.FileRead", Map.of("enabled", "true", "threshold", "0 ns"));
    java.util.List<RecordingOptions.Merged> told = asked.merged(merged, STANDARD);

    assertEquals(2, told.size(), told.toString());
    assertEquals("Java samples", told.get(0).entry().label());
    assertEquals("50 ms", told.get(0).asked());
    assertEquals("20 ms", told.get(0).recorded());
    assertEquals("Garbage collections", told.get(1).entry().label());
    assertEquals("off", told.get(1).asked());
    // a choice with no value to set is on or off
    assertEquals("on", told.get(1).recorded());
  }

  // --- the sessions

  @Test
  public void sessionsRecordWithThem() throws IOException {
    AtomicReference<Map<String, String>> used = new AtomicReference<>();
    Recorder recorder = (name, settings, file) -> {
      used.set(settings);
      return () -> Files.writeString(file, "jfr");
    };
    Sessions sessions = new Sessions(dir, STANDARD, recorder);
    sessions.setOptions(options(Map.of(), Map.of("java", "3 ms"), "jdk.ClassLoad"));

    sessions.stop(sessions.start("manual").getId());
    assertEquals("3 ms", used.get().get("jdk.ExecutionSample#period"));
    assertEquals("true", used.get().get("jdk.ClassLoad#enabled"));
    assertEquals("true", used.get().get("flak.Request#enabled"));
  }

  @Test
  public void keptAcrossRestarts() throws IOException {
    Recorder recorder = (name, settings, file) -> () -> Files.writeString(file, "jfr");
    Sessions sessions = new Sessions(dir, STANDARD, recorder);
    assertTrue(sessions.getOptions().isStandard());
    sessions.setOptions(RecordingOptions.of(RecordingOptions.Preset.LIGHT, Map.of("exceptions", true),
                                            Map.of("java", "7 ms"), "jdk.ClassLoad#threshold=5 ms"));
    assertTrue(Files.exists(dir.resolve("recording.properties")));

    RecordingOptions again = new Sessions(dir, STANDARD, recorder).getOptions();
    assertEquals(RecordingOptions.Preset.LIGHT, again.preset());
    assertEquals("7 ms", again.value(RecordingOptions.ENTRIES.get(0), STANDARD));
    assertTrue(again.isEnabled(RecordingOptions.ENTRIES.get(3), STANDARD));
    assertEquals("jdk.ClassLoad#threshold=5 ms", again.other());

    // back to the settings of the profiler: nothing left to keep
    sessions.setOptions(RecordingOptions.standard());
    assertFalse(Files.exists(dir.resolve("recording.properties")));
    assertTrue(new Sessions(dir, STANDARD, recorder).getOptions().isStandard());
  }

  @Test
  public void aSessionKnowsWhatItWasAskedToRecord() throws IOException {
    Recorder recorder = (name, settings, file) -> () -> Files.writeString(file, "jfr");
    Sessions sessions = new Sessions(dir.resolve("sessions"), STANDARD, recorder);
    RecordingOptions asked = options(Map.of("exceptions", false), Map.of("java", "3 ms"), "jdk.ClassLoad");
    sessions.setOptions(asked);
    Session s = sessions.start("manual");
    sessions.stop(s.getId());
    // changed since: the next one is another matter
    sessions.setOptions(RecordingOptions.of(RecordingOptions.Preset.LIGHT));
    Session other = sessions.start("manual");
    sessions.stop(other.getId());

    assertEquals(asked.settings(STANDARD), s.getOptions().settings(STANDARD));
    assertEquals(RecordingOptions.Preset.LIGHT, other.getOptions().preset());

    // after a restart
    Sessions again = new Sessions(dir.resolve("sessions"), STANDARD, recorder);
    assertEquals(asked.settings(STANDARD), again.get(s.getId()).getOptions().settings(STANDARD));
    assertEquals(RecordingOptions.Preset.LIGHT, again.get(other.getId()).getOptions().preset());

    // a recording made elsewhere does not tell
    Path snapshot = Files.writeString(dir.resolve("snapshot.jfr"), "jfr");
    java.time.Instant t = java.time.Instant.parse("2026-11-22T08:00:00Z");
    assertNull(again.register(t, t.plusSeconds(60), "snapshot", snapshot).getOptions());
  }

  @Test
  public void aSpoiledFileIsIgnored() throws IOException {
    Files.writeString(dir.resolve("recording.properties"), "preset=standard\nvalue.java=often\n");
    Sessions sessions = new Sessions(dir, STANDARD, (name, settings, file) -> () -> {});
    assertTrue(sessions.getOptions().isStandard());
  }

  @Test
  public void aSessionKeepsWhatItStartedWith() throws IOException {
    Sessions sessions = new Sessions(dir, STANDARD, (name, settings, file) -> () -> Files.writeString(file, "jfr"));
    Session s = sessions.start("manual");
    assertThrows(IllegalStateException.class, () -> sessions.setOptions(RecordingOptions.of(RecordingOptions.Preset.LIGHT)));
    sessions.stop(s.getId());
    sessions.setOptions(RecordingOptions.of(RecordingOptions.Preset.LIGHT));
  }
}
