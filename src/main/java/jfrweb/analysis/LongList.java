package jfrweb.analysis;

import java.util.Arrays;

/**
 * A growable list of longs, without boxing: a recording can hold millions of
 * samples.
 */
final class LongList {

  private long[] values = new long[1024];
  private int size;

  void add(long v) {
    if (size == values.length)
      values = Arrays.copyOf(values, size * 2);
    values[size++] = v;
  }

  int size() {
    return size;
  }

  long[] sorted() {
    long[] res = Arrays.copyOf(values, size);
    Arrays.sort(res);
    return res;
  }
}
