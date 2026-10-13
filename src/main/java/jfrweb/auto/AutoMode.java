package jfrweb.auto;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import flak.App;
import flak.Request;
import flak.RequestListener;
import flak.RequestSummary;
import jfrweb.session.Session;
import jfrweb.session.Sessions;

/**
 * Starts sessions by themselves: when a request matching the trigger begins,
 * before its handler runs, so that the whole request is recorded; and stops
 * them once no matching request has run for a while, or after a maximum
 * duration. Only the requests of the apps observed count, so the profiler's
 * own never do.
 */
public final class AutoMode implements AutoCloseable {

  private static final System.Logger log = System.getLogger(AutoMode.class.getName());

  /** The cookie of an armed browser, sent to every path of the host. */
  public static final String COOKIE = "jfrweb-armed";

  /** How often a session is checked for being idle or too long, in ms. */
  private static final long CHECK_PERIOD = 200;

  private final Sessions sessions;
  private final ScheduledExecutorService scheduler;
  private final List<Map.Entry<App, RequestListener>> observed = new CopyOnWriteArrayList<>();

  private volatile Trigger trigger;
  private boolean warm;

  /** The session started by this, while it records. */
  private Session session;
  private long sessionStart;
  /** Matching requests in flight, and when the last one ended (nanoTime). */
  private int inFlight;
  private long lastEnd;

  /** Whether the request served by this thread matched, set by onBegin. */
  private final ThreadLocal<Boolean> counted = new ThreadLocal<>();

  public AutoMode(Sessions sessions) {
    this.sessions = sessions;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "jfr-web auto mode");
      t.setDaemon(true);
      return t;
    });
    scheduler.scheduleWithFixedDelay(this::check, CHECK_PERIOD, CHECK_PERIOD, TimeUnit.MILLISECONDS);
  }

  /**
   * A new token for an armed browser.
   */
  public static String newBrowserToken() {
    byte[] b = new byte[16];
    new SecureRandom().nextBytes(b);
    return HexFormat.of().formatHex(b);
  }

  /**
   * Starts waiting for requests that match the trigger. The first time, warms
   * JFR up, which takes a moment, so that starting a session in the middle of
   * a request then costs a few milliseconds.
   */
  public void arm(Trigger trigger) {
    synchronized (this) {
      if (!warm) {
        long t0 = System.nanoTime();
        sessions.warmUp();
        warm = true;
        log.log(System.Logger.Level.DEBUG, "JFR warmed up in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
      }
    }
    this.trigger = trigger;
  }

  /**
   * Stops waiting. A session it started keeps recording until idle.
   */
  public void disarm() {
    this.trigger = null;
  }

  /**
   * The trigger armed, or null.
   */
  public Trigger getTrigger() {
    return trigger;
  }

  /**
   * The session it started, while it records, or null.
   */
  public synchronized Session getSession() {
    return session;
  }

  /**
   * Makes the requests of an app trigger sessions, until closed.
   */
  public void observe(App app) {
    RequestListener l = listenerFor(app);
    observed.add(Map.entry(app, l));
    app.addRequestListener(l);
  }

  private RequestListener listenerFor(App app) {
    return new RequestListener() {
      @Override
      public void onBegin(Request request) {
        Trigger t = trigger;
        if (t == null)
          return;
        String endpoint = request.getMethod() + " " + app.getPath() + request.getRoute();
        if (t.matches(endpoint, request.getCookie(COOKIE))) {
          counted.set(Boolean.TRUE);
          begin(t, endpoint);
        }
      }

      @Override
      public void onEnd(Request request, RequestSummary summary) {
        if (counted.get() != null) {
          counted.remove();
          end();
        }
      }
    };
  }

  private synchronized void begin(Trigger t, String endpoint) {
    inFlight++;
    if (session != null || sessions.getRecording() != null)
      return;
    try {
      session = sessions.start(t.describe(endpoint));
      sessionStart = System.nanoTime();
      lastEnd = sessionStart;
    }
    catch (IOException | RuntimeException e) {
      log.log(System.Logger.Level.WARNING, "Could not start a session", e);
    }
  }

  private synchronized void end() {
    inFlight = Math.max(0, inFlight - 1);
    lastEnd = System.nanoTime();
  }

  /**
   * Stops the session once idle or too long.
   */
  synchronized void check() {
    if (session == null)
      return;
    if (session.getState() == Session.State.STOPPED) {
      // stopped by hand
      sessionEnded();
      return;
    }
    // disarmed while the session records: it ends with the defaults
    Trigger t = trigger;
    long idleLimit = (t != null ? t.idle() : Trigger.DEFAULT_IDLE).toNanos();
    long maxLimit = (t != null ? t.max() : Trigger.DEFAULT_MAX).toNanos();
    long now = System.nanoTime();
    boolean idle = inFlight == 0 && now - lastEnd >= idleLimit;
    boolean tooLong = now - sessionStart >= maxLimit;
    if (idle || tooLong) {
      sessions.stop(session.getId());
      sessionEnded();
    }
  }

  private void sessionEnded() {
    session = null;
    Trigger t = trigger;
    if (t != null && !t.repeat())
      trigger = null;
  }

  @Override
  public synchronized void close() {
    trigger = null;
    for (Map.Entry<App, RequestListener> e : observed)
      e.getKey().removeRequestListener(e.getValue());
    observed.clear();
    scheduler.shutdownNow();
    if (session != null) {
      sessions.stop(session.getId());
      session = null;
    }
  }
}
