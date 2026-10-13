package jfrweb.auto;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What starts a session by itself, and when it stops.
 *
 * @param patterns endpoints that trigger a session, e.g. "GET /api/repository*",
 *                 where * matches anything; empty for any endpoint
 * @param browser  the token of the browser armed, whose requests carry it in
 *                 the {@link AutoMode#COOKIE} cookie; null for any client
 * @param repeat   whether to start a session again after one ended, or only
 *                 once
 * @param idle     the session stops once no matching request has run for that
 *                 long: long enough for asynchronous work to finish
 * @param max      the session stops after that long whatever happens
 */
public record Trigger(List<String> patterns,
                      String browser,
                      boolean repeat,
                      Duration idle,
                      Duration max) {

  public static final Duration DEFAULT_IDLE = Duration.ofSeconds(3);
  public static final Duration DEFAULT_MAX = Duration.ofSeconds(120);

  public Trigger {
    patterns = List.copyOf(patterns);
  }

  /**
   * Whether a request triggers a session, or keeps it going.
   *
   * @param endpoint e.g. "GET /api/user/:id"
   * @param cookie   the value of the {@link AutoMode#COOKIE} cookie of the
   *                 request, or null
   */
  public boolean matches(String endpoint, String cookie) {
    if (browser != null && !browser.equals(cookie))
      return false;
    if (patterns.isEmpty())
      return true;
    for (String p : patterns)
      if (glob(p).matcher(endpoint).matches())
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
  String describe(String endpoint) {
    return "auto: " + endpoint + (browser != null ? ", armed browser" : "");
  }
}
