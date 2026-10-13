package jfrweb.ops;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * The JFR event recorded for each {@link Operation}, once it ends. Its start
 * time and duration are those of the operation. A synchronous operation is
 * committed by its thread, which ran it from start to end: its samples are
 * those of that thread in that time. An asynchronous one is committed by
 * whichever thread ends it: its samples are those of its
 * {@link SegmentEvent segments}.
 * <p>
 * Disabled by default, like flak's request event, so that a recording made
 * with the settings of the JDK does not get one event per operation.
 */
@Name(Operations.EVENT)
@Label("Operation")
@Category("jfr-web")
@Description("A unit of work of the application: a request served, a job run")
@Enabled(false)
@StackTrace(false)
final class OperationEvent extends Event {

  @Label("Id")
  @Description("Unique in the JVM, shared with the segments of the operation")
  long id;

  @Label("Name")
  @Description("What operations are grouped by, e.g. \"sort\"")
  String name;

  @Label("Detail")
  @Description("What this one worked on, e.g. its argument")
  String detail;

  @Label("Asynchronous")
  @Description("Whether it ran on several threads, its samples being those of its segments")
  boolean async;

  @Label("Failed")
  boolean failed;

  @Label("Error")
  String error;

  @Label("Bytes In")
  @DataAmount
  long bytesIn;

  @Label("Bytes Out")
  @DataAmount
  long bytesOut;
}
