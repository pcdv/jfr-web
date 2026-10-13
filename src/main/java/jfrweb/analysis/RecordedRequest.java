package jfrweb.analysis;

/**
 * A request found in a recording, read from its flak.Request event.
 *
 * @param threadId   Java id of the thread that served the request
 * @param startNanos start, in nanoseconds since the epoch
 * @param endNanos   end, in nanoseconds since the epoch
 * @param route      template of the route, null when no route matched
 */
public record RecordedRequest(long threadId,
                              String threadName,
                              long startNanos,
                              long endNanos,
                              String app,
                              String method,
                              String route,
                              String path,
                              int status,
                              long bytesIn,
                              long bytesOut) {

  public long durationNanos() {
    return endNanos - startNanos;
  }

  /**
   * What the request is grouped by: the method and the route, including the
   * path of the app, e.g. "GET /api/user/:id".
   */
  public String endpoint() {
    return method + " " + (route == null ? "(no route)" : app + route);
  }

  public boolean isError() {
    return status >= 500;
  }
}
