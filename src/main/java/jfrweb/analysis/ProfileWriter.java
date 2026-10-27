package jfrweb.analysis;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
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
 * thread and operation. The format is Flamepack, specified in
 * https://github.com/pcdv/flamepack; how jfr-web uses it is in
 * docs/flame-graphs.md.
 * <p>
 * Names are written once, sorted and front-coded: each string starts with a
 * character whose code minus 32 is the length of the prefix it shares with
 * the previous one, as async-profiler's pages do. Stacks are a tree, written
 * depth first as columns of depth, name and type: a node's parent is the last
 * node written one level up. A column that holds only what a reader assumes
 * is left out.
 */
public final class ProfileWriter {

  /**
   * Raised with any change of the format: a page loaded before the server
   * changed refuses what it cannot read, instead of reading it wrong. 2 added
   * the fields of the operations to their names, 3 the start and end of the
   * recording, for the page to draw its timeline in any buckets, 4 the unit
   * of the weights, and the weight of each allocation sample, 5 Flamepack's
   * version 5, with the stacks as columns, and the columns of the default left out.
   */
  public static final int VERSION = 5;

  /** Types of frames. */
  public static final int COMPILED = 0, INLINED = 1, INTERPRETED = 2, NATIVE = 3, SYNTHETIC = 4;

  /** The type of the class an allocation sample allocated: the top of its stack. */
  public static final int ALLOCATED = 5;

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
  /** Of allocation samples only: the bytes each stands for. */
  private final LongList sampleWeight = new LongList();

  /** Allocation samples, weighing bytes, instead of Java and native ones, weighing ms. */
  private final boolean allocations;

  private final Map<Long, Integer> threads = new HashMap<>();
  /** Name and id of each thread. */
  private final List<long[]> threadList = new ArrayList<>();

  /** The operations, in the order of the analysis, and their strings: -1 for none. */
  private List<RecordedOperation> ops;
  private int[] operationNames, operationThreads, operationDetails, operationErrors;

  /** The start of the recording, which times count from, and its end; null if unknown. */
  private long start;
  private Instant recordingStart, recordingEnd;

  private final long[] weights = new long[2];

  private ProfileWriter(boolean allocations) {
    this.allocations = allocations;
  }

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
    write(analysis, out, keep, false);
  }

  /**
   * @param allocations the allocation samples, weighing the bytes they stand
   *                    for, instead of the Java and native ones
   */
  public static void write(Analysis analysis, Writer out, BiPredicate<RecordedThread, RecordedOperation> keep,
                           boolean allocations) throws IOException {
    ProfileWriter w = new ProfileWriter(allocations);
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
      operationThreads[i] = thread(op.threadId(), String.valueOf(op.threadName()));
      operationDetails[i] = op.detail() == null ? -1 : name(op.detail());
      operationErrors[i] = op.error() == null ? -1 : name(op.error());
    }
    start = Analysis.nanos(analysis.getInfo().getStart());
    recordingStart = analysis.getInfo().getStart();
    recordingEnd = analysis.getInfo().getEnd();
    int truncated = frame(FlameGraphs.TRUNCATED, SYNTHETIC);

    analysis.visitSamples(allocations, (sample, thread, operation, weight) -> {
      if (!keep.test(thread, operation))
        return;
      int kind = allocations || sample.getEventType().getName().equals(RecordingInfo.EXECUTION_SAMPLE)
                 ? JAVA : NATIVE_SAMPLE;
      if (allocations)
        sampleWeight.add(weight);
      else
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
      // what was allocated, on top of where
      if (allocations)
        node = node(node, frame(Analysis.allocatedClass(sample), ALLOCATED));

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
    Integer i = threads.get(t.getJavaThreadId());
    return i != null ? i : thread(t.getJavaThreadId(), String.valueOf(t.getJavaName()));
  }

  /** @return the index of the thread in the profile's threads, added when new */
  private int thread(long id, String name) {
    Integer i = threads.get(id);
    if (i == null) {
      i = threadList.size();
      threads.put(id, i);
      threadList.add(new long[]{name(name), id});
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
    out.write(",\"unit\":" + (allocations ? "\"bytes\"" : "\"ms\""));
    // allocation samples carry their own weight
    if (!allocations)
      out.write(",\"weights\":[" + weights[JAVA] + "," + weights[NATIVE_SAMPLE] + "]");
    out.write(",\"meta\":{\"kind\":\"" + (allocations ? "alloc" : "wall") + "\"}");
    // the span of the recording: the end in ns since its start
    out.write(",\"start\":" + Json.write(String.valueOf(recordingStart)));
    out.write(",\"end\":" + (recordingEnd == null ? 0 : Analysis.nanos(recordingEnd) - start));

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
    out.write("]");

    // the stacks as columns; the type, when any frame is not compiled
    out.write(",\"stacks\":{");
    out.write("\"depth\":[");
    for (int i = 0; i < n; i++) {
      separate(out, i);
      out.write(Integer.toString(depth[order[i]]));
    }
    out.write("],\"name\":[");
    for (int i = 0; i < n; i++) {
      separate(out, i);
      out.write(Integer.toString(rank[nodeFrame.get(order[i]) >>> 3]));
    }
    out.write("]");
    optional(out, "type", n, i -> nodeFrame.get(order[i]) & 7, 0);
    out.write("}");

    // the samples: each stack as the node plus 1, as the difference with the last
    out.write(",\"samples\":{\"stack\":[");
    long lastNode = 0;
    long[] times = new long[s];
    long lastTime = 0;
    for (int i = 0; i < s; i++) {
      int node = sampleStack.get(samples[i]);
      long at = node < 0 ? 0 : position[node] + 1;
      separate(out, i);
      out.write(Long.toString(at - lastNode));
      lastNode = at;
      long t = sampleTime.get(samples[i]);
      times[i] = t - lastTime;
      lastTime = t;
    }
    out.write("]");
    optional(out, "time", s, i -> times[i], 0);
    optional(out, "thread", s, i -> sampleThread.get(samples[i]), 0);
    optional(out, "operation", s, i -> sampleOperation.get(samples[i]), -1);
    optional(out, "kind", s, i -> sampleKind.get(samples[i]), 0);
    if (allocations)
      column(out, "weight", s, i -> sampleWeight.get(samples[i]));
    out.write("}");

    if (!threadList.isEmpty()) {
      out.write(",\"threads\":[");
      for (int i = 0; i < threadList.size(); i++) {
        long[] t = threadList.get(i);
        separate(out, i);
        out.write("[" + rank[(int) t[0]] + "," + t[1] + "]");
      }
      out.write("]");
    }

    // the operations: their times in ns, for the same durations and totals
    // as the server's; each start as the difference with the previous one
    int operations = ops.size();
    if (operations > 0) {
      long[] starts = new long[operations];
      long previousStart = 0;
      for (int i = 0; i < operations; i++) {
        long t = ops.get(i).startNanos() - start;
        starts[i] = t - previousStart;
        previousStart = t;
      }
      out.write(",\"operations\":{");
      out.write("\"name\":[");
      for (int i = 0; i < operations; i++) {
        separate(out, i);
        out.write(Integer.toString(rank[operationNames[i]]));
      }
      out.write("]");
      column(out, "start", operations, i -> starts[i]);
      column(out, "duration", operations, i -> ops.get(i).durationNanos());
      optional(out, "thread", operations, i -> operationThreads[i], -1);
      optional(out, "detail", operations, i -> operationDetails[i] < 0 ? -1 : rank[operationDetails[i]], -1);
      optional(out, "status", operations, i -> ops.get(i).status(), 0);
      optional(out, "failed", operations, i -> ops.get(i).failed() ? 1 : 0, 0);
      optional(out, "error", operations, i -> operationErrors[i] < 0 ? -1 : rank[operationErrors[i]], -1);
      optional(out, "async", operations, i -> ops.get(i).async() ? 1 : 0, 0);
      optional(out, "bytesIn", operations, i -> ops.get(i).bytesIn(), 0);
      optional(out, "bytesOut", operations, i -> ops.get(i).bytesOut(), 0);
      out.write("}");
    }
    out.write("}");
  }

  /** Writes {@code ,"name":[v0,v1...]}, the values of 0 to n - 1. */
  private static void column(Writer out, String name, int n, IntToLongFunction value) throws IOException {
    out.write(",\"" + name + "\":[");
    for (int i = 0; i < n; i++) {
      separate(out, i);
      out.write(Long.toString(value.applyAsLong(i)));
    }
    out.write("]");
  }

  /** As {@link #column}, left out when all its values are the default of a reader. */
  private static void optional(Writer out, String name, int n, IntToLongFunction value, long dflt)
    throws IOException {
    for (int i = 0; i < n; i++)
      if (value.applyAsLong(i) != dflt) {
        column(out, name, n, value);
        return;
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
