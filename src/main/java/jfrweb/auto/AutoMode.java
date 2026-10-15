package jfrweb.auto;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import flak.App;
import jfrweb.ops.FlakOperations;
import jfrweb.ops.MappedOperations;
import jfrweb.ops.Operation;
import jfrweb.ops.OperationListener;
import jfrweb.ops.OperationSources;
import jfrweb.session.Session;
import jfrweb.session.Sessions;

/**
 * Starts sessions by themselves: when an operation matching the trigger
 * begins, before it runs (before its handler for a request), so that the
 * whole operation is recorded; and stops them once no matching operation has
 * run for a while, or after a maximum duration. Only the operations of the
 * jfrweb.ops API and the requests of the flak apps observed count, so the
 * profiler's own requests never do.
 */
public final class AutoMode implements AutoCloseable {

  private static final System.Logger log = System.getLogger(AutoMode.class.getName());

  /** The cookie of an armed browser, sent to every path of the host. */
  public static final String COOKIE = FlakOperations.ARM_COOKIE;

  /** How often a session is checked for being idle or too long, in ms. */
  private static final long CHECK_PERIOD = 200;

  private final Sessions sessions;
  private final ScheduledExecutorService scheduler;
  /** Null when fed, see {@link #fed(Sessions)}. */
  private final OperationSources sources;

  private volatile Trigger trigger;
  private boolean warm;

  /** The session started by this, while it records. */
  private Session session;
  private long sessionStart;
  /**
   * When fed: the session started as armed, no matching operation ended yet.
   * It records until one has, then stops as usual.
   */
  private boolean waiting;
  /** Matching operations in flight, and when the last one ended (nanoTime). */
  private int inFlight;
  private long lastEnd;

  /** The operations in flight that matched, set by onBegin. */
  private final Set<Operation> counted = ConcurrentHashMap.newKeySet();

  public AutoMode(Sessions sessions) {
    this(sessions, true);
  }

  /**
   * Sessions triggered by the operations reported to {@link #ended(String)}
   * only, e.g. those of another JVM, read from its events. They are reported
   * once they end, too late to start a session that records them: the
   * session starts as armed, waits for a matching operation to end, then
   * stops as usual. The browser of a request is not known.
   */
  public static AutoMode fed(Sessions sessions) {
    return new AutoMode(sessions, false);
  }

  private AutoMode(Sessions sessions, boolean observe) {
    this.sessions = sessions;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "jfr-web auto mode");
      t.setDaemon(true);
      return t;
    });
    scheduler.scheduleWithFixedDelay(this::check, CHECK_PERIOD, CHECK_PERIOD, TimeUnit.MILLISECONDS);
    sources = !observe ? null : new OperationSources(new OperationListener() {
      @Override
      public void onBegin(Operation op) {
        Trigger t = trigger;
        if (t != null && t.matches(op.getName(), op.getArmToken())) {
          counted.add(op);
          begin(t, op.getName());
        }
      }

      @Override
      public void onEnd(Operation op) {
        if (counted.remove(op))
          end();
        else if (op.isMapped())
          ended(op.getName());
      }
    });
  }

  /**
   * Whether it hears of the operations reported to it rather than those of
   * this JVM.
   */
  public boolean isFed() {
    return sources == null;
  }

  /**
   * Whether operations may be known only once they end: when fed, or when
   * events of the application are mapped to operations. A session then
   * starts as armed, and waits for a matching operation to end.
   */
  private boolean late() {
    return sources == null || MappedOperations.isActive();
  }

  /**
   * An operation known only once it ended: reported when fed, or read from
   * an event mapped. Ends the wait of the session started as armed, or keeps
   * it from stopping.
   */
  public synchronized void ended(String operation) {
    // the session started as armed, by check() if something else recorded
    // then: none to start here, after the operation
    Trigger t = trigger;
    if (session == null || t == null || t.browser() != null || !t.matches(operation, null))
      return;
    lastEnd = System.nanoTime();
    if (waiting) {
      waiting = false;
      // its maximum duration counts from now: waiting may be long
      sessionStart = lastEnd;
    }
  }

  /**
   * Whether the session started records while waiting for a matching
   * operation, when fed.
   */
  public synchronized boolean isWaiting() {
    return session != null && waiting;
  }

  /**
   * When fed: starts the session that waits for a matching operation, if
   * nothing records.
   */
  private synchronized void startWaiting(Trigger t) {
    if (session != null || sessions.getRecording() != null)
      return;
    try {
      session = sessions.start("auto: waiting for "
                               + (t.patterns().isEmpty() ? "any operation" : String.join(", ", t.patterns())));
      session.setName(t.name());
      waiting = true;
      inFlight = 0;
      sessionStart = System.nanoTime();
      lastEnd = sessionStart;
    }
    catch (IOException | RuntimeException e) {
      log.log(System.Logger.Level.WARNING, "Could not start a session", e);
    }
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
   * Starts waiting for operations that match the trigger. The first time,
   * warms JFR up, which takes a moment, so that starting a session at the
   * beginning of an operation then costs a few milliseconds.
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
    if (late() && trigger.browser() == null)
      startWaiting(trigger);
  }

  /**
   * Stops waiting. A session it started keeps recording until idle, unless
   * it was still waiting for an operation, when fed.
   */
  public void disarm() {
    this.trigger = null;
    synchronized (this) {
      if (session != null && waiting) {
        sessions.stop(session.getId());
        sessionEnded();
      }
    }
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
   * Makes the requests of a flak app trigger sessions, until closed. The
   * operations of the jfrweb.ops API do from the start.
   */
  public void observe(App app) {
    if (sources == null)
      throw new IllegalStateException("Fed by the operations reported to it");
    sources.observe(app);
  }

  private synchronized void begin(Trigger t, String operation) {
    inFlight++;
    if (session != null && waiting) {
      // started as armed: this one is recorded whole
      waiting = false;
      sessionStart = System.nanoTime();
    }
    if (session != null || sessions.getRecording() != null)
      return;
    try {
      session = sessions.start(t.describe(operation));
      session.setName(t.name());
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
    if (session == null) {
      // fed: armed again, or what recorded instead has stopped
      Trigger t = trigger;
      if (late() && t != null && t.browser() == null)
        startWaiting(t);
      return;
    }
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
    if (waiting)
      return;
    boolean idle = inFlight == 0 && now - lastEnd >= idleLimit;
    boolean tooLong = now - sessionStart >= maxLimit;
    if (idle || tooLong) {
      // disarmed first, if once: whoever sees the session stopped sees it too
      int id = session.getId();
      sessionEnded();
      sessions.stop(id);
    }
  }

  private void sessionEnded() {
    session = null;
    waiting = false;
    Trigger t = trigger;
    if (t != null && !t.repeat())
      trigger = null;
  }

  @Override
  public synchronized void close() {
    trigger = null;
    if (sources != null)
      sources.close();
    counted.clear();
    scheduler.shutdownNow();
    if (session != null) {
      sessions.stop(session.getId());
      session = null;
    }
  }
}
