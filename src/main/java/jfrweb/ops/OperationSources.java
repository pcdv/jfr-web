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
   * Whether operations may come: a flak app is observed, or the application
   * has used the API, and this is not closed.
   */
  public boolean isObserving() {
    return !closed && (!apps.isEmpty() || Operations.isUsed());
  }

  @Override
  public void close() {
    closed = true;
    Operations.removeListener(listener);
    for (Map.Entry<App, Observation> e : apps)
      e.getValue().close();
    apps.clear();
  }
}
