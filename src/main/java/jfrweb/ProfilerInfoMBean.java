package jfrweb;

/**
 * What a profiler embedded in a JVM tells of itself over JMX, as
 * {@code jfrweb:type=Profiler,*}: a jfr-web profiling the JVM from outside
 * (see {@code jfr-web standalone}) reads it to show the same sessions,
 * history and snapshots, with nothing to tell it.
 */
public interface ProfilerInfoMBean {

  /** The URL of its web interface. */
  String getUrl();

  /** Where its sessions are kept. */
  String getSessionsDirectory();

  /** Where the operation history it browses is, or null. */
  String getHistoryDirectory();

  /** Where the snapshots it lists are, or null. */
  String getSnapshotsDirectory();
}
