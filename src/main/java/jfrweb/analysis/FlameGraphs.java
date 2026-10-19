package jfrweb.analysis;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

/**
 * The samples of a recording as collapsed stacks, the input of flame graph
 * tools: one line per stack, its frames from the root joined by ';', then
 * its weight in milliseconds. Rooted by operation, the first frame being the
 * name of the operation the thread was working for, unless merged.
 * <p>
 * Frames are methods, known by their name only, as in the flame graphs of
 * the interface: a method interpreted, then compiled, is one frame. The
 * interface draws its flame graphs from a profile ({@link ProfileWriter}),
 * with the same filters and the same results.
 */
public final class FlameGraphs {

  /** Root of the samples taken outside any operation. */
  public static final String NO_OPERATION = "(no operation)";

  /** Marks the stacks deeper than the recording kept: their bottom is lost. */
  public static final String TRUNCATED = "(truncated)";

  /**
   * What goes into a flame graph.
   */
  public static final class Options {
    Predicate<RecordedOperation> operations = op -> true;
    Pattern thread;
    boolean outsideOperations;
    boolean merged;
    boolean reversed;
    boolean javaOnly;
    boolean allocations;

    /**
     * Keeps the samples of the operations that match, e.g. those of a name.
     */
    public Options operations(Predicate<RecordedOperation> filter) {
      this.operations = filter;
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
     * Also keeps the samples taken outside any operation, rooted by
     * {@link #NO_OPERATION} then by thread. Most are idle threads blocked in
     * native code (selectors, accept).
     */
    public Options outsideOperations(boolean outside) {
      this.outsideOperations = outside;
      return this;
    }

    /**
     * Merges the samples of every operation kept into one tree, instead of
     * rooting them by the name of their operation: what costs in all of them.
     */
    public Options merged(boolean merged) {
      this.merged = merged;
      return this;
    }

    /**
     * Turns the stacks upside down: the methods that were running at the
     * root, their callers above, and the operation last. Shows the methods
     * that cost the most, whatever the paths they are called from.
     */
    public Options reversed(boolean reversed) {
      this.reversed = reversed;
      return this;
    }

    /**
     * Keeps the samples of threads running Java code only, without those
     * taken in native methods: closer to CPU time.
     */
    public Options javaOnly(boolean javaOnly) {
      this.javaOnly = javaOnly;
      return this;
    }

    /**
     * Takes the allocation samples instead, each weighing the bytes it stands
     * for, the class allocated on top of its stack.
     */
    public Options allocations(boolean allocations) {
      this.allocations = allocations;
      return this;
    }

    public boolean allocations() {
      return allocations;
    }

    /**
     * Whether a sample passes the operation and thread filters, those a page
     * cannot change: its operation, null outside any, matches, and its thread.
     */
    public boolean keeps(RecordedThread thread, RecordedOperation operation) {
      return (operation == null || operations.test(operation))
             && (this.thread == null || this.thread.matcher(String.valueOf(thread.getJavaName())).matches());
    }

    /**
     * @return the other filters, as a page opens with them: see the
     *         interface's flame/profile.js
     */
    public Map<String, Object> pageFilter() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("all", outsideOperations);
      m.put("merged", merged);
      m.put("reversed", reversed);
      m.put("java", javaOnly);
      return m;
    }
  }

  private FlameGraphs() {}

  /**
   * Writes the collapsed stacks, sorted, each weighing the interval at which
   * its samples' events were taken, or the bytes of its allocation samples.
   */
  public static void write(Analysis analysis, Options o, PrintStream out) throws IOException {
    Map<String, Long> stacks = new TreeMap<>();
    List<String> frames = new ArrayList<>();

    analysis.visitSamples(o.allocations, (sample, thread, operation, weight) -> {
      if (o.javaOnly && !o.allocations && !sample.getEventType().getName().equals(RecordingInfo.EXECUTION_SAMPLE))
        return;
      if (!o.keeps(thread, operation))
        return;

      frames.clear();
      if (operation != null) {
        if (!o.merged)
          frames.add(operation.name());
      }
      else {
        if (!o.outsideOperations)
          return;
        frames.add(NO_OPERATION);
        frames.add("[" + thread.getJavaName() + " tid=" + thread.getJavaThreadId() + "]");
      }

      RecordedStackTrace st = sample.getStackTrace();
      if (st != null) {
        if (st.isTruncated())
          frames.add(TRUNCATED);
        // JFR lists the frames from the top of the stack
        List<RecordedFrame> list = st.getFrames();
        for (int i = list.size() - 1; i >= 0; i--)
          frames.add(list.get(i).getMethod().getType().getName() + "." + list.get(i).getMethod().getName());
      }
      if (o.allocations)
        frames.add(Analysis.allocatedClass(sample));
      if (frames.isEmpty())
        return;
      if (o.reversed)
        Collections.reverse(frames);
      stacks.merge(String.join(";", frames), weight, Long::sum);
    });

    // \n whatever the system: tools split on it
    stacks.forEach((stack, weight) -> out.print(stack + " " + weight + "\n"));
    out.flush();
  }
}
