package jfrweb.session;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

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

  /**
   * The names of the events JFR can record where it records, or null if it
   * cannot tell now, e.g. no JVM is attached. An event of the application is
   * known once its class is loaded.
   */
  default Set<String> eventNames() {
    return null;
  }

  /**
   * The number of frames JFR keeps of each stack where it records, 0 if
   * unknown.
   */
  default int stackDepth() {
    return 0;
  }

  /** A recording in progress. */
  interface Running {

    /**
     * Stops it, which writes its file: if it could not, the session is
     * dropped.
     */
    void stop() throws IOException;

    /**
     * An estimate of the size of its file so far, in bytes (see
     * {@link RepositorySize}). 0 if unknown.
     */
    default long size() {
      return 0;
    }
  }
}
