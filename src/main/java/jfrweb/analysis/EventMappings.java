package jfrweb.analysis;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import jfrweb.ops.MappedOperations;

/**
 * The events of the application read as operations, see
 * {@link EventMapping}, for this JVM: recordings read here take operations
 * from them, recordings made here enable them, and what listens to the
 * operations of this JVM hears of them, see {@link MappedOperations}.
 * <p>
 * In a file, one mapping per event, its keys being the name of the event
 * then "name", "detail" or "failed":
 * <pre>
 * com.acme.RpcCall.name   = {service}.{method}
 * com.acme.RpcCall.detail = {requestId}
 * com.acme.RpcCall.failed = error
 * </pre>
 */
public final class EventMappings {

  private static final Map<String, EventMapping> mappings = new ConcurrentHashMap<>();

  private EventMappings() {}

  /**
   * Reads the events of a name as operations from now on, instead of how
   * they were read before, if they were.
   */
  public static void register(EventMapping m) {
    mappings.put(m.event(), m);
    MappedOperations.update();
  }

  public static void unregister(String event) {
    if (mappings.remove(event) != null)
      MappedOperations.update();
  }

  /**
   * The mapping of the events of a name, or null.
   */
  public static EventMapping get(String event) {
    return mappings.get(event);
  }

  public static List<EventMapping> list() {
    return List.copyOf(mappings.values());
  }

  /**
   * The settings that make a recording get the events mapped, each one of
   * them: enabled, from any duration, with no stack trace.
   */
  public static Map<String, String> settings() {
    Map<String, String> s = new HashMap<>();
    for (String event : mappings.keySet()) {
      s.put(event + "#enabled", "true");
      s.put(event + "#threshold", "0 ms");
      s.put(event + "#stackTrace", "false");
    }
    return s;
  }

  /**
   * Some settings, and those of the events mapped, which win.
   */
  public static Map<String, String> withMapped(Map<String, String> settings) {
    if (mappings.isEmpty())
      return settings;
    Map<String, String> s = new HashMap<>(settings);
    s.putAll(settings());
    return s;
  }

  /**
   * Registers the mappings of a file, see above.
   *
   * @return them
   * @throws IllegalArgumentException if one has no name, or a key is not
   *                                  one of a mapping
   */
  public static List<EventMapping> load(Path file) throws IOException {
    Properties p = new Properties();
    try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      p.load(r);
    }
    Map<String, Map<String, String>> byEvent = new HashMap<>();
    for (String key : p.stringPropertyNames()) {
      int dot = key.lastIndexOf('.');
      String attribute = dot < 0 ? "" : key.substring(dot + 1);
      if (!List.of("name", "detail", "failed").contains(attribute))
        throw new IllegalArgumentException("Not <event>.name, .detail or .failed in " + file + ": " + key);
      byEvent.computeIfAbsent(key.substring(0, dot), k -> new HashMap<>()).put(attribute, p.getProperty(key).strip());
    }
    List<EventMapping> res = byEvent.entrySet().stream()
      .map(e -> new EventMapping(e.getKey(), e.getValue().get("name"), blankToNull(e.getValue().get("detail")),
                                 blankToNull(e.getValue().get("failed"))))
      .toList();
    res.forEach(EventMappings::register);
    return res;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
