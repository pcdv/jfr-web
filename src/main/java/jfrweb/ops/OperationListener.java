package jfrweb.ops;

/**
 * Notified when operations begin and end: those of the {@link Operations}
 * API, and the requests of the flak apps observed.
 */
public interface OperationListener {

  /**
   * Called on the thread that begins the operation, before it runs.
   */
  default void onBegin(Operation op) {}

  /**
   * Called on the thread that ends the operation, once it has ended.
   */
  void onEnd(Operation op);
}
