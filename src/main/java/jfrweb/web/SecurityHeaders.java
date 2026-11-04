package jfrweb.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import flak.BeforeHook;
import flak.Request;
import flak.Response;

/**
 * The security headers of every response of the interface: a profiler shows
 * the code and the data of the JVM, and starts recordings, so its pages must
 * not be framed by another site, nor its answers sniffed as another type.
 * <p>
 * The {@link #DEFAULT defaults} suit the interface as it is served: its
 * scripts and styles come from its own origin, its images too or from data:
 * URLs. An application embedding it may change one, e.g. to add
 * Strict-Transport-Security when it serves HTTPS, with
 * {@link #with(String, String)}.
 */
public final class SecurityHeaders implements BeforeHook {

  /**
   * The headers by default. Styles may be inline (some pages have a style
   * element); scripts may not.
   */
  public static final SecurityHeaders DEFAULT = new SecurityHeaders(Map.of())
    .with("Content-Security-Policy",
          "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:"
          + "; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
    .with("X-Frame-Options", "DENY")
    .with("X-Content-Type-Options", "nosniff")
    .with("Referrer-Policy", "same-origin");

  private final Map<String, String> headers;

  private SecurityHeaders(Map<String, String> headers) {
    this.headers = Collections.unmodifiableMap(headers);
  }

  /**
   * These headers with one more, or one changed.
   *
   * @param value null to send no such header
   */
  public SecurityHeaders with(String name, String value) {
    Map<String, String> res = new LinkedHashMap<>(headers);
    if (value == null)
      res.remove(name);
    else
      res.put(name, value);
    return new SecurityHeaders(res);
  }

  /**
   * The headers sent, by name, in the order they were added.
   */
  public Map<String, String> getHeaders() {
    return headers;
  }

  @Override
  public void execute(Request request) {
    Response resp = request.getResponse();
    headers.forEach(resp::addHeader);
  }
}
