package jfrweb.analysis;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import one.convert.Arguments;
import one.convert.CallStack;
import one.convert.FlameGraph;
import one.convert.Frame;

/**
 * Builds flame graphs from the samples of a recording, rooted by endpoint:
 * the first frame of each stack is the endpoint of the request the thread was
 * serving. Stacks are assembled here, filtered by request and thread, and
 * rendered by async-profiler's converter.
 */
public final class FlameGraphs {

  /** Root of the samples taken outside any request. */
  public static final String NO_REQUEST = "(no request)";

  /** Marks the stacks deeper than the recording kept: their bottom is lost. */
  public static final String TRUNCATED = "(truncated)";

  /**
   * What goes into a flame graph.
   */
  public static final class Options {
    Predicate<RecordedRequest> requests = r -> true;
    Pattern thread;
    Instant from;
    Instant to;
    boolean outsideRequests;
    boolean collapsed;
    String title = "Wall clock";

    /**
     * Keeps the samples of the requests that match, e.g. one endpoint.
     */
    public Options requests(Predicate<RecordedRequest> filter) {
      this.requests = filter;
      return this;
    }

    /**
     * Keeps the samples of the threads whose name matches.
     */
    public Options thread(Pattern name) {
      this.thread = name;
      return this;
    }

    /**
     * Keeps the samples taken in an interval.
     *
     * @param from inclusive, or null from the start
     * @param to   exclusive, or null to the end
     */
    public Options interval(Instant from, Instant to) {
      this.from = from;
      this.to = to;
      return this;
    }

    /**
     * Also keeps the samples taken outside any request, rooted by
     * {@link #NO_REQUEST} then by thread. Most are idle threads blocked in
     * native code (selectors, accept).
     */
    public Options outsideRequests(boolean outside) {
      this.outsideRequests = outside;
      return this;
    }

    /**
     * Produces collapsed stacks, one line per stack, instead of HTML.
     */
    public Options collapsed(boolean collapsed) {
      this.collapsed = collapsed;
      return this;
    }

    public Options title(String title) {
      this.title = title;
      return this;
    }
  }

  private FlameGraphs() {}

  /**
   * Writes a flame graph, whose widths are in milliseconds: each sample
   * weighs the interval at which its event was sampled.
   */
  public static void write(Analysis analysis, Options o, PrintStream out) throws IOException {
    Arguments args = new Arguments();
    args.title = escapeHtml(o.title);
    if (o.collapsed)
      args.output = "collapsed";

    FlameGraph fg = new FlameGraph(args);
    CallStack stack = new CallStack();

    analysis.visitSamples((sample, thread, request, weight) -> {
      if (o.thread != null && !o.thread.matcher(String.valueOf(thread.getJavaName())).matches())
        return;
      Instant time = sample.getStartTime();
      if ((o.from != null && time.isBefore(o.from)) || (o.to != null && !time.isBefore(o.to)))
        return;

      if (request != null) {
        if (!o.requests.test(request))
          return;
        stack.push(request.endpoint(), Frame.TYPE_CPP);
      }
      else {
        if (!o.outsideRequests)
          return;
        stack.push(NO_REQUEST, Frame.TYPE_CPP);
        stack.push("[" + thread.getJavaName() + " tid=" + thread.getJavaThreadId() + "]",
                   Frame.TYPE_CPP);
      }

      RecordedStackTrace st = sample.getStackTrace();
      if (st != null) {
        if (st.isTruncated())
          stack.push(TRUNCATED, Frame.TYPE_CPP);
        // JFR lists the frames from the top of the stack
        List<RecordedFrame> frames = st.getFrames();
        for (int i = frames.size() - 1; i >= 0; i--) {
          RecordedFrame f = frames.get(i);
          stack.push(name(f.getMethod()), type(f));
        }
      }

      fg.addSample(stack, weight);
      stack.clear();
    });

    fg.dump(out);
    out.flush();
  }

  private static String name(RecordedMethod m) {
    return m.getType().getName() + "." + m.getName();
  }

  private static byte type(RecordedFrame f) {
    switch (f.getType()) {
      case "Interpreted":
        return Frame.TYPE_INTERPRETED;
      case "Inlined":
        return Frame.TYPE_INLINED;
      default:
        // compiled, or a native method of a Java class: shown as Java code
        return Frame.TYPE_JIT_COMPILED;
    }
  }

  private static String escapeHtml(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
