package jfrweb.session;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * What records the sessions: JFR in this JVM, or in another one, e.g. over
 * JMX (see jfrweb.standalone).
 */
public interface Recorder {

  /**
   * Starts a recording.
   *
   * @param name     of the recording, as JFR lists it
   * @param settings the JFR settings
   * @param file     where its .jfr file goes once stopped
   * @throws IllegalStateException if it cannot record now, e.g. no JVM to
   *                               record
   */
  Running start(String name, Map<String, String> settings, Path file) throws IOException;

  /**
   * Starts and stops a recording kept nowhere, so that the next one starts
   * fast: the first recording of a JVM sets JFR up. Nothing by default.
   */
  default void warmUp(Map<String, String> settings) {}

  /** A recording in progress. */
  interface Running {

    /**
     * Stops it, which writes its file: if it could not, the session is
     * dropped.
     */
    void stop() throws IOException;
  }
}
