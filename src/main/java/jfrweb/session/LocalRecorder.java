package jfrweb.session;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;

/**
 * Records this JVM.
 */
final class LocalRecorder implements Recorder {

  private final HighResolutionTimer timer;

  /**
   * @param highResolutionTimer see {@link HighResolutionTimer}
   */
  LocalRecorder(boolean highResolutionTimer) {
    this.timer = new HighResolutionTimer(highResolutionTimer);
  }

  @Override
  public Running start(String name, Map<String, String> settings, Path file) throws IOException {
    Recording r = new Recording(settings);
    r.setName(name);
    r.setToDisk(true);
    r.setDestination(file);
    RepositorySize size = new RepositorySize(() -> {
      String p = System.getProperty(RepositorySize.PROPERTY);
      return p == null ? null : Path.of(p);
    });
    timer.acquire();
    try {
      r.start();
    }
    catch (RuntimeException e) {
      timer.release();
      r.close();
      throw e;
    }
    return new Running() {
      @Override
      public void stop() {
        try {
          r.stop();
        }
        finally {
          r.close();
          timer.release();
        }
      }

      @Override
      public long size() {
        return size.size();
      }
    };
  }

  /**
   * Null before JFR recorded in this JVM: asking would start it, and the
   * depth of the stacks can only be set before that, see {@link StackDepth}.
   */
  @Override
  public Set<String> eventNames() {
    if (!FlightRecorder.isInitialized())
      return null;
    Set<String> names = new HashSet<>();
    for (EventType t : FlightRecorder.getFlightRecorder().getEventTypes())
      names.add(t.getName());
    return names;
  }

  @Override
  public int stackDepth() {
    return FlightRecorder.isInitialized() ? StackDepth.current() : 0;
  }

  @Override
  public void warmUp(Map<String, String> settings) {
    try (Recording r = new Recording(settings)) {
      r.setToDisk(false);
      r.start();
      r.stop();
    }
  }
}
