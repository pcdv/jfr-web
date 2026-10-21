package jfrweb.session;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

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

  @Override
  public void warmUp(Map<String, String> settings) {
    try (Recording r = new Recording(settings)) {
      r.setToDisk(false);
      r.start();
      r.stop();
    }
  }
}
