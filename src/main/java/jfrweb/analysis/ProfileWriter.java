package jfrweb.analysis;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.IntToLongFunction;

import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jfrweb.json.Json;

/**
 * Writes the samples of a recording as a profile, from which the page builds
 * the flame graph of any filter by itself: each sample with its time, stack,
 * thread and operation. The format is described in docs/flame-graphs.md.
 * <p>
 * Names are written once, sorted and front-coded: each string starts with a
 * character whose code minus 32 is the length of the prefix it shares with
 * the previous one, as async-profiler's pages do. Stacks are a tree, written
 * depth first as pairs of depth and frame: a node's parent is the last node
 * written one level up. A frame is the index of its name, shifted left by 3,
 * with its type in the low bits.
 */
public final class ProfileWriter {

  /**
   * Raised with any change of the format: a page loaded before the server
   * changed refuses what it cannot read, instead of reading it wrong. 2 added
   * the fields of the operations to their names.
   */
  public static final int VERSION = 2;

  /** Types of frames. */
  public static final int COMPILED = 0, INLINED = 1, INTERPRETED = 2, NATIVE = 3, SYNTHETIC = 4;

  /** Kinds of samples, and the index of their weight. */
  public static final int JAVA = 0, NATIVE_SAMPLE = 1;

  /** Names, numbered as met; sorted once all are known. */
  private final Map<String, Integer> names = new HashMap<>();
  private final List<String> nameList = new ArrayList<>();

  /** The tree of stacks: a node per parent and frame, the roots under -1. */
  private final Map<Long, Integer> nodes = new HashMap<>();
  private final IntList nodeParent = new IntList();
  private final IntList nodeFrame = new IntList();

  /** The samples, in the order read. */
  private final LongList sampleTime = new LongList();
  private final IntList sampleStack = new IntList();
  private final IntList sampleThread = new IntList();
  private final IntList sampleOperation = new IntList();
  private final IntList sampleKind = new IntList();

  private final Map<Long, Integer> threads = new HashMap<>();
  /** Name and id of each thread. */
  private final List<long[]> threadList = new ArrayList<>();

  /** The operations, in the order of the analysis, and their strings: -1 for none. */
  private List<RecordedOperation> ops;
  private int[] operationNames, operationThreads, operationDetails, operationErrors;

  /** The start of the recording, which times count from. */
  private long start;

  private final long[] weights = new long[2];

  private ProfileWriter() {}

  /**
   * @param out written to, not closed
   */
  public static void write(Analysis analysis, Writer out) throws IOException {
    write(analysis, out, (thread, operation) -> true);
  }

  /**
   * @param keep the samples written, by thread and operation (null outside
   *             any): e.g. those of some operations only
   * @param out  written to, not closed
   */
  public static void write(Analysis analysis, Writer out, BiPredicate<RecordedThread, RecordedOperation> keep)
    throws IOException {
    ProfileWriter w = new ProfileWriter();
    w.read(analysis, keep);
    w.writeJson(out);
  }

  private void read(Analysis analysis, BiPredicate<RecordedThread, RecordedOperation> keep) throws IOException {
    // an operation is known by its index, the id the API gives it
    ops = analysis.getOperations();
    Map<RecordedOperation, Integer> operations = new IdentityHashMap<>();
    operationNames = new int[ops.size()];
    operationThreads = new int[ops.size()];
    operationDetails = new int[ops.size()];
    operationErrors = new int[ops.size()];
    for (int i = 0; i < ops.size(); i++) {
      RecordedOperation op = ops.get(i);
      operations.put(op, i);
      operationNames[i] = name(op.name());
      operationThreads[i] = name(String.valueOf(op.threadName()));
      operationDetails[i] = op.detail() == null ? -1 : name(op.detail());
      operationErrors[i] = op.error() == null ? -1 : name(op.error());
    }
    start = Analysis.nanos(analysis.getInfo().getStart());
    int truncated = frame(FlameGraphs.TRUNCATED, SYNTHETIC);

    analysis.visitSamples((sample, thread, operation, weight) -> {
      if (!keep.test(thread, operation))
        return;
      int kind = sample.getEventType().getName().equals(RecordingInfo.EXECUTION_SAMPLE) ? JAVA : NATIVE_SAMPLE;
      weights[kind] = weight;

      // none when JFR kept no stack: the sample still counts for its operation
      int node = -1;
      RecordedStackTrace st = sample.getStackTrace();
      if (st != null) {
        if (st.isTruncated())
          node = node(node, truncated);
        // JFR lists the frames from the top of the stack
        List<RecordedFrame> frames = st.getFrames();
        for (int i = frames.size() - 1; i >= 0; i--) {
          RecordedFrame f = frames.get(i);
          node = node(node, frame(f.getMethod().getType().getName() + "." + f.getMethod().getName(), type(f)));
        }
      }

      sampleTime.add((Analysis.nanos(sample.getStartTime()) - start) / 1_000_000);
      sampleStack.add(node);
      sampleThread.add(thread(thread));
      Integer op = operation == null ? null : operations.get(operation);
      sampleOperation.add(op == null ? -1 : op);
      sampleKind.add(kind);
    });
  }

  private int name(String s) {
    Integer i = names.get(s);
    if (i == null) {
      i = nameList.size();
      names.put(s, i);
      nameList.add(s);
    }
    return i;
  }

  private int frame(String name, int type) {
    return name(name) << 3 | type;
  }

  private int node(int parent, int frame) {
    long key = (long) parent << 32 | (frame & 0xffffffffL);
    Integer n = nodes.get(key);
    if (n == null) {
      n = nodeParent.size();
      nodes.put(key, n);
      nodeParent.add(parent);
      nodeFrame.add(frame);
    }
    return n;
  }

  private int thread(RecordedThread t) {
    long id = t.getJavaThreadId();
    Integer i = threads.get(id);
    if (i == null) {
      i = threadList.size();
      threads.put(id, i);
      threadList.add(new long[]{name(String.valueOf(t.getJavaName())), id});
    }
    return i;
  }

  private static int type(RecordedFrame f) {
    switch (f.getType()) {
      case "Inlined":
        return INLINED;
      case "Interpreted":
        return INTERPRETED;
      case "Native":
        return NATIVE;
      default:
        return COMPILED;
    }
  }

  private void writeJson(Writer out) throws IOException {
    // names sorted, for their front coding: each renumbered by its rank
    Integer[] sorted = new Integer[nameList.size()];
    for (int i = 0; i < sorted.length; i++)
      sorted[i] = i;
    Arrays.sort(sorted, Comparator.comparing(nameList::get));
    int[] rank = new int[sorted.length];
    for (int i = 0; i < sorted.length; i++)
      rank[sorted[i]] = i;

    // nodes depth first, each renumbered by its position
    int n = nodeParent.size();
    int[][] children = children(n);
    int[] position = new int[n];
    int[] depth = new int[n];
    int[] order = new int[n];
    int count = 0;
    int[] stack = new int[n + 1];
    int top = 0;
    // pushed in reverse, to come out in order
    for (int c = children[0].length - 1; c >= 0; c--)
      stack[top++] = children[0][c];
    while (top > 0) {
      int node = stack[--top];
      int parent = nodeParent.get(node);
      depth[node] = parent < 0 ? 0 : depth[parent] + 1;
      position[node] = count;
      order[count++] = node;
      int[] cs = children[node + 1];
      for (int c = cs.length - 1; c >= 0; c--)
        stack[top++] = cs[c];
    }

    // samples by time; within 49 days of the start, 2^31 samples at most
    int s = sampleTime.size();
    long[] byTime = new long[s];
    for (int i = 0; i < s; i++)
      byTime[i] = sampleTime.get(i) << 31 | i;
    Arrays.sort(byTime);
    int[] samples = new int[s];
    for (int i = 0; i < s; i++)
      samples[i] = (int) (byTime[i] & Integer.MAX_VALUE);

    out.write("{\"version\":" + VERSION);
    out.write(",\"weights\":[" + weights[JAVA] + "," + weights[NATIVE_SAMPLE] + "]");

    out.write(",\"strings\":[");
    String previous = "";
    for (int i = 0; i < sorted.length; i++) {
      String name = nameList.get(sorted[i]);
      // 95 at most: the character stays printable ASCII
      int max = Math.min(Math.min(previous.length(), name.length()), 95);
      int shared = 0;
      while (shared < max && previous.charAt(shared) == name.charAt(shared))
        shared++;
      separate(out, i);
      out.write(Json.write((char) (shared + 32) + name.substring(shared)));
      previous = name;
    }

    out.write("],\"stacks\":[");
    for (int i = 0; i < n; i++) {
      int frame = nodeFrame.get(order[i]);
      separate(out, i);
      out.write(depth[order[i]] + "," + (rank[frame >>> 3] << 3 | frame & 7));
    }

    out.write("],\"samples\":{\"time\":[");
    long last = 0;
    for (int i = 0; i < s; i++) {
      long t = sampleTime.get(samples[i]);
      separate(out, i);
      out.write(Long.toString(t - last));
      last = t;
    }
    out.write("],\"stack\":[");
    for (int i = 0; i < s; i++) {
      int node = sampleStack.get(samples[i]);
      separate(out, i);
      out.write(Integer.toString(node < 0 ? -1 : position[node]));
    }
    writeColumn(out, "thread", sampleThread, samples);
    writeColumn(out, "operation", sampleOperation, samples);
    writeColumn(out, "kind", sampleKind, samples);

    out.write("]},\"threads\":[");
    for (int i = 0; i < threadList.size(); i++) {
      long[] t = threadList.get(i);
      separate(out, i);
      out.write("[" + rank[(int) t[0]] + "," + t[1] + "]");
    }
    // the operations: their times in ns, for the same durations and totals
    // as the server's; each start as the difference with the previous one
    int operations = ops.size();
    long[] starts = new long[operations];
    long previousStart = 0;
    for (int i = 0; i < operations; i++) {
      long t = ops.get(i).startNanos() - start;
      starts[i] = t - previousStart;
      previousStart = t;
    }
    out.write("],\"operations\":{");
    writeColumn(out, "name", operations, i -> rank[operationNames[i]], false);
    writeColumn(out, "start", operations, i -> starts[i], true);
    writeColumn(out, "duration", operations, i -> ops.get(i).durationNanos(), true);
    writeColumn(out, "thread", operations, i -> rank[operationThreads[i]], true);
    writeColumn(out, "detail", operations, i -> operationDetails[i] < 0 ? -1 : rank[operationDetails[i]], true);
    writeColumn(out, "status", operations, i -> ops.get(i).status(), true);
    writeColumn(out, "failed", operations, i -> ops.get(i).failed() ? 1 : 0, true);
    writeColumn(out, "error", operations, i -> operationErrors[i] < 0 ? -1 : rank[operationErrors[i]], true);
    writeColumn(out, "async", operations, i -> ops.get(i).async() ? 1 : 0, true);
    writeColumn(out, "bytesIn", operations, i -> ops.get(i).bytesIn(), true);
    writeColumn(out, "bytesOut", operations, i -> ops.get(i).bytesOut(), true);
    out.write("]}}");
  }

  /**
   * Writes {@code "name":[v0,v1...}, the values of 0 to n - 1.
   *
   * @param after whether a column comes before, closed here
   */
  private static void writeColumn(Writer out, String name, int n, IntToLongFunction value, boolean after)
    throws IOException {
    out.write((after ? "]," : "") + "\"" + name + "\":[");
    for (int i = 0; i < n; i++) {
      separate(out, i);
      out.write(Long.toString(value.applyAsLong(i)));
    }
  }

  /**
   * @return the children of each node, at its index + 1; those of the roots
   *         at 0
   */
  private int[][] children(int n) {
    int[] count = new int[n + 1];
    for (int i = 0; i < n; i++)
      count[nodeParent.get(i) + 1]++;
    int[][] children = new int[n + 1][];
    for (int i = 0; i <= n; i++)
      children[i] = new int[count[i]];
    int[] filled = new int[n + 1];
    for (int i = 0; i < n; i++) {
      int p = nodeParent.get(i) + 1;
      children[p][filled[p]++] = i;
    }
    return children;
  }

  private static void writeColumn(Writer out, String name, IntList column, int[] samples) throws IOException {
    out.write("],\"" + name + "\":[");
    for (int i = 0; i < samples.length; i++) {
      separate(out, i);
      out.write(Integer.toString(column.get(samples[i])));
    }
  }

  private static void separate(Writer out, int i) throws IOException {
    if (i > 0)
      out.write(',');
  }

  /** A growable array of ints. */
  private static final class IntList {
    private int[] values = new int[1024];
    private int size;

    void add(int v) {
      if (size == values.length)
        values = Arrays.copyOf(values, size * 2);
      values[size++] = v;
    }

    int get(int i) {
      return values[i];
    }

    int size() {
      return size;
    }
  }
}
