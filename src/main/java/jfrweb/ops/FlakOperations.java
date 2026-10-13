package jfrweb.ops;

import java.util.List;

import flak.App;
import flak.Request;
import flak.RequestListener;
import flak.RequestSummary;

/**
 * The requests of a flak app, as operations: synchronous, named after their
 * method and route (e.g. "GET /api/user/:id"), as in recordings, where they
 * come from flak's own JFR event.
 */
public final class FlakOperations {

  /** The cookie of an armed browser, sent to every path of the host. */
  public static final String ARM_COOKIE = "jfrweb-armed";

  private FlakOperations() {}

  /**
   * The name of the operations of a route: the HTTP method, then the path of
   * the app and the template of the route, or "(no route)".
   */
  public static String name(String appPath, String method, String route) {
    return method + " " + (route == null ? "(no route)" : appPath + route);
  }

  /**
   * Notifies a listener of the requests of an app, until the result is
   * closed.
   */
  public static Observation observe(App app, OperationListener listener) {
    List<OperationListener> listeners = List.of(listener);
    RequestListener l = new RequestListener() {
      /** flak serves a request on one thread, from onBegin to onEnd. */
      private final ThreadLocal<Operation> current = new ThreadLocal<>();

      @Override
      public void onBegin(Request request) {
        Operation op = new Operation(name(app.getPath(), request.getMethod(), request.getRoute()),
                                     false, false, listeners);
        op.armToken = request.getCookie(ARM_COOKIE);
        current.set(op);
        Operations.safely(() -> listener.onBegin(op));
      }

      @Override
      public void onEnd(Request request, RequestSummary s) {
        Operation op = current.get();
        current.remove();
        // a request that matched no route did not begin
        if (op == null)
          op = new Operation(name(app.getPath(), request.getMethod(), s.getRoute()), false, false, listeners);
        op.detail(request.getMethod() + " " + request.getPath())
          .bytesIn(s.getBytesIn())
          .bytesOut(s.getBytesOut());
        op.status = s.getStatus();
        if (s.getStatus() >= 500)
          op.failed("HTTP " + s.getStatus());
        op.end(s.getDurationNanos());
      }
    };
    app.addRequestListener(l);
    return () -> app.removeRequestListener(l);
  }
}
