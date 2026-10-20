package jfrweb;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.JMException;
import javax.management.ObjectName;

/**
 * The {@link ProfilerInfoMBean} of a {@link Profiler}, registered while it runs.
 */
public final class ProfilerInfo implements ProfilerInfoMBean {

  private static final System.Logger log = System.getLogger(ProfilerInfo.class.getName());

  private static final AtomicInteger COUNT = new AtomicInteger();

  private final String url;
  private final Path sessions;
  private final Path history;
  private final Path snapshots;

  ProfilerInfo(String url, Path sessions, Path history, Path snapshots) {
    this.url = url;
    this.sessions = sessions;
    this.history = history;
    this.snapshots = snapshots;
  }

  /**
   * @return its name, to {@link #unregister}, or null if it could not be registered
   */
  ObjectName register() {
    try {
      ObjectName name = new ObjectName("jfrweb:type=Profiler,id=" + COUNT.incrementAndGet());
      ManagementFactory.getPlatformMBeanServer().registerMBean(this, name);
      return name;
    }
    catch (JMException | RuntimeException e) {
      log.log(System.Logger.Level.WARNING, "Could not register the MBean of the profiler", e);
      return null;
    }
  }

  static void unregister(ObjectName name) {
    if (name == null)
      return;
    try {
      ManagementFactory.getPlatformMBeanServer().unregisterMBean(name);
    }
    catch (JMException | RuntimeException e) {
      log.log(System.Logger.Level.DEBUG, "Could not unregister " + name, e);
    }
  }

  @Override
  public String getUrl() {
    return url;
  }

  @Override
  public String getSessionsDirectory() {
    return sessions.toAbsolutePath().toString();
  }

  @Override
  public String getHistoryDirectory() {
    return history == null ? null : history.toAbsolutePath().toString();
  }

  @Override
  public String getSnapshotsDirectory() {
    return snapshots == null ? null : snapshots.toAbsolutePath().toString();
  }
}
