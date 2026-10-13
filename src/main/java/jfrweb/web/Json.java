package jfrweb.web;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Writes JSON, with no dependency: jfr-web only produces it, in a few fixed
 * shapes, built as maps (objects, in their iteration order), collections
 * (arrays), strings, numbers, booleans and nulls. An Instant is written as its
 * ISO-8601 string, an enum as its name.
 */
public final class Json {

  private Json() {}

  public static String write(Object value) {
    StringBuilder sb = new StringBuilder();
    write(sb, value);
    return sb.toString();
  }

  private static void write(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("null");
    }
    else if (value instanceof Map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
        if (!first)
          sb.append(',');
        first = false;
        string(sb, String.valueOf(e.getKey()));
        sb.append(':');
        write(sb, e.getValue());
      }
      sb.append('}');
    }
    else if (value instanceof Collection) {
      sb.append('[');
      boolean first = true;
      for (Object o : (Collection<?>) value) {
        if (!first)
          sb.append(',');
        first = false;
        write(sb, o);
      }
      sb.append(']');
    }
    else if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      // JSON has no NaN nor infinity
      sb.append(Double.isFinite(d) ? value.toString() : "null");
    }
    else if (value instanceof Number || value instanceof Boolean) {
      sb.append(value);
    }
    else if (value instanceof CharSequence || value instanceof Instant || value instanceof Enum) {
      string(sb, value.toString());
    }
    else {
      throw new IllegalArgumentException("Cannot write as JSON: " + value.getClass());
    }
  }

  private static void string(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20)
            sb.append(String.format("\\u%04x", (int) c));
          else
            sb.append(c);
      }
    }
    sb.append('"');
  }
}
