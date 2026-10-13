package jfrweb.ops;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * The JFR event recorded for each {@link Segment} of an asynchronous
 * operation, committed by the thread that worked for it: the samples of that
 * thread in that time are the operation's.
 */
@Name(Operations.SEGMENT_EVENT)
@Label("Operation Segment")
@Category("jfr-web")
@Description("A thread working for an asynchronous operation")
@Enabled(false)
@StackTrace(false)
final class SegmentEvent extends Event {

  @Label("Operation Id")
  long id;
}
