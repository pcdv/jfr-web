package jfrweb.analysis;

/**
 * An operation found in a recording: a request of flak, read from its
 * flak.Request event, or an operation of the jfrweb.ops API, read from its
 * jfrweb.Operation event.
 *
 * @param threadId   Java id of the thread that ran a synchronous operation,
 *                   or that ended an asynchronous one
 * @param startNanos start, in nanoseconds since the epoch
 * @param endNanos   end, in nanoseconds since the epoch
 * @param name       what operations are grouped by, e.g. "GET /api/user/:id"
 * @param detail     what this one worked on, e.g. "GET /api/user/12"
 * @param status     the HTTP status of a request, 0 for other operations
 * @param error      why it failed, or null
 * @param async      whether its samples are those of its segments, rather
 *                   than those of its thread while it ran
 */
public record RecordedOperation(long threadId,
                                String threadName,
                                long startNanos,
                                long endNanos,
                                String name,
                                String detail,
                                int status,
                                boolean failed,
                                String error,
                                long bytesIn,
                                long bytesOut,
                                boolean async) {

  public long durationNanos() {
    return endNanos - startNanos;
  }
}
