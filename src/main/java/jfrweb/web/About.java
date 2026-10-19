package jfrweb.web;

/**
 * What an interface of the profiler shows.
 *
 * @param app            the name of the application, see {@link AppName}: in
 *                       the titles of its pages
 * @param recordingsOnly whether it only browses recordings, with no JVM
 *                       behind them: no live operations, auto mode or
 *                       session to start
 * @param allocations    whether flame graphs show the allocation samples
 *                       first, instead of the wall clock
 */
public record About(String app, boolean recordingsOnly, boolean allocations) {

  public About(String app, boolean recordingsOnly) {
    this(app, recordingsOnly, false);
  }
}
