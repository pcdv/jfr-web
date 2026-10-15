package jfrweb.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import flak.App;

/**
 * Where a listener hears of operations, until closed: those of the
 * {@link Operations} API from the start, and the requests of the flak apps
 * observed.
 */
public final class OperationSources implements AutoCloseable {

  private final OperationListener listener;
  private final List<Map.Entry<App, Observation>> apps = new CopyOnWriteArrayList<>();
  private volatile boolean closed;

  public OperationSources(OperationListener listener) {
    this.listener = listener;
    Operations.addListener(listener);
    // the events of the application mapped to operations, if any
    MappedOperations.consumerAdded();
  }

  /**
   * Adds the requests of a flak app.
   */
  public void observe(App app) {
    apps.add(Map.entry(app, FlakOperations.observe(app, listener)));
  }

  /**
   * The flak apps observed.
   */
  public List<App> getApps() {
    List<App> res = new ArrayList<>();
    for (Map.Entry<App, Observation> e : apps)
      res.add(e.getKey());
    return res;
  }

  /**
   * Whether operations may come: a flak app is observed, the application
   * has used the API, or events of the application are mapped, and this is
   * not closed.
   */
  public boolean isObserving() {
    return !closed && (!apps.isEmpty() || Operations.isUsed() || MappedOperations.isActive());
  }

  @Override
  public void close() {
    if (!closed)
      MappedOperations.consumerRemoved();
    closed = true;
    Operations.removeListener(listener);
    for (Map.Entry<App, Observation> e : apps)
      e.getValue().close();
    apps.clear();
  }
}
