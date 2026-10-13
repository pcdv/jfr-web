package jfrweb.session;

/**
 * On Windows, JFR samples far less often than asked: its sampler waits with
 * the timer of the system, which ticks every 15.6 ms by default. A thread
 * sleeping for a duration that is not a multiple of 10 ms makes the JVM ask
 * for a 1 ms resolution as long as it sleeps, which raised the sampling rate
 * about 2.5 times on JDK 17 and 21. Held only while a session records: a
 * finer timer costs power.
 */
final class HighResolutionTimer {

  private static final boolean WINDOWS =
    System.getProperty("os.name", "").startsWith("Windows");

  private final boolean enabled;
  private Thread thread;

  HighResolutionTimer(boolean enabled) {
    this.enabled = enabled && WINDOWS;
  }

  synchronized void acquire() {
    if (!enabled || thread != null)
      return;
    thread = new Thread(() -> {
      try {
        // Long.MAX_VALUE ends with 7: not a multiple of 10
        Thread.sleep(Long.MAX_VALUE);
      }
      catch (InterruptedException ignored) {
      }
    }, "jfr-web high resolution timer");
    thread.setDaemon(true);
    thread.start();
  }

  synchronized void release() {
    if (thread != null) {
      thread.interrupt();
      thread = null;
    }
  }
}
