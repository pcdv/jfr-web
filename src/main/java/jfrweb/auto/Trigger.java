package jfrweb.auto;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What starts a session by itself, and when it stops.
 *
 * @param patterns operations that trigger a session, e.g. "GET /api/repository*",
 *                 where * matches anything; empty for any operation
 * @param browser  the token of the browser armed, whose requests carry it in
 *                 the {@link AutoMode#COOKIE} cookie; null for any client.
 *                 Only the requests of flak can carry it
 * @param repeat   whether to start a session again after one ended, or only
 *                 once
 * @param idle     the session stops once no matching operation has run for that
 *                 long: long enough for asynchronous work to finish
 * @param max      the session stops after that long whatever happens
 * @param name     the name of the sessions it starts, or null
 */
public record Trigger(List<String> patterns,
                      String browser,
                      boolean repeat,
                      Duration idle,
                      Duration max,
                      String name) {

  public static final Duration DEFAULT_IDLE = Duration.ofSeconds(3);
  public static final Duration DEFAULT_MAX = Duration.ofSeconds(120);

  public Trigger {
    patterns = List.copyOf(patterns);
    name = name == null || name.isBlank() ? null : name.strip();
  }

  /**
   * A trigger of unnamed sessions.
   */
  public Trigger(List<String> patterns, String browser, boolean repeat, Duration idle, Duration max) {
    this(patterns, browser, repeat, idle, max, null);
  }

  /**
   * Whether an operation triggers a session, or keeps it going.
   *
   * @param operation e.g. "GET /api/user/:id"
   * @param token     the token of the armed client that sent it, e.g. the
   *                  value of the {@link AutoMode#COOKIE} cookie of a request,
   *                  or null
   */
  public boolean matches(String operation, String token) {
    if (browser != null && !browser.equals(token))
      return false;
    if (patterns.isEmpty())
      return true;
    for (String p : patterns)
      if (glob(p).matcher(operation).matches())
        return true;
    return false;
  }

  /**
   * A pattern where * matches anything, the rest literally.
   */
  static Pattern glob(String pattern) {
    return Pattern.compile(List.of(pattern.split("\\*", -1)).stream()
                               .map(Pattern::quote)
                               .collect(Collectors.joining(".*")));
  }

  /**
   * How the sessions it starts are labeled.
   */
  String describe(String operation) {
    return "auto: " + operation + (browser != null ? ", armed browser" : "");
  }
}
