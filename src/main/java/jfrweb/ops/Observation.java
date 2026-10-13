package jfrweb.ops;

/**
 * Notifications of operations, until closed.
 */
public interface Observation extends AutoCloseable {

  @Override
  void close();
}
