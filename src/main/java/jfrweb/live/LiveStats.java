package jfrweb.live;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

import flak.App;
import flak.Request;
import flak.RequestListener;
import flak.RequestSummary;
import jfrweb.analysis.EndpointStats;

/**
 * Statistics of the endpoints of flak apps, kept by counters as requests
 * end, whether a session records or not. Nothing is recorded with JFR: the
 * cost is a few counters per request.
 */
public final class LiveStats implements AutoCloseable {

  /** The windows statistics can be asked for, in minutes. */
  public static final List<Integer> WINDOWS = List.of(1, 5, 15, 60);

  private final Map<String, EndpointCounter> endpoints = new ConcurrentHashMap<>();
  private final List<Observed> observed = new CopyOnWriteArrayList<>();
  private final LongSupplier minuteClock;

  public LiveStats() {
    this(() -> System.currentTimeMillis() / 60_000);
  }

  /**
   * @param minuteClock the current minute, for tests
   */
  LiveStats(LongSupplier minuteClock) {
    this.minuteClock = minuteClock;
  }

  private final class Observed implements RequestListener {
    final App app;

    Observed(App app) {
      this.app = app;
    }

    @Override
    public void onEnd(Request request, RequestSummary s) {
      // grouped as in recordings, see RecordedRequest.endpoint()
      String endpoint = request.getMethod() + " " +
                        (s.getRoute() == null ? "(no route)" : app.getPath() + s.getRoute());
      endpoints.computeIfAbsent(endpoint, EndpointCounter::new)
               .record(minuteClock.getAsLong(), s.getDurationNanos(), s.getStatus(),
                       s.getBytesIn(), s.getBytesOut());
    }
  }

  /**
   * Counts the requests of an app from now on.
   */
  public void observe(App app) {
    Observed o = new Observed(app);
    observed.add(o);
    app.addRequestListener(o);
  }

  public boolean isObserving() {
    return !observed.isEmpty();
  }

  /**
   * The paths of the apps observed, "" for an app at the root of its server.
   */
  public List<String> getAppPaths() {
    List<String> res = new ArrayList<>();
    for (Observed o : observed)
      res.add(o.app.getPath());
    return res;
  }

  /**
   * The endpoints called in the last minutes, the current one included,
   * sorted by total time, the biggest first.
   *
   * @param window a number of minutes, at most 60
   */
  public List<EndpointStats> getEndpoints(int window) {
    if (window < 1 || window > EndpointCounter.MINUTES)
      throw new IllegalArgumentException("Window out of 1.." + EndpointCounter.MINUTES + ": " + window);
    long now = minuteClock.getAsLong();
    List<EndpointStats> res = new ArrayList<>();
    for (EndpointCounter c : endpoints.values()) {
      EndpointStats s = c.over(now, window);
      if (s != null)
        res.add(s);
    }
    res.sort(Comparator.comparingLong(EndpointStats::totalNanos).reversed());
    return res;
  }

  /**
   * Stops observing the apps.
   */
  @Override
  public void close() {
    for (Observed o : observed)
      o.app.removeRequestListener(o);
    observed.clear();
  }
}
