package jfrweb.json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes and reads JSON, with no dependency: jfr-web uses it in a few fixed
 * shapes, built as maps (objects, in their iteration order), collections
 * (arrays), strings, numbers, booleans and nulls. An Instant is written as its
 * ISO-8601 string, an enum as its name.
 */
public final class Json {

  private Json() {}

  /**
   * Reads a JSON document: objects as maps keeping the order of their
   * members, arrays as lists, numbers as Long when integral, Double otherwise.
   *
   * @throws IllegalArgumentException if it is not valid JSON
   */
  public static Object parse(String json) {
    Parser p = new Parser(json);
    Object v = p.value();
    p.skipSpaces();
    if (p.pos != json.length())
      throw p.error("Unexpected content");
    return v;
  }

  private static final class Parser {
    final String s;
    int pos;

    Parser(String s) {
      this.s = s;
    }

    Object value() {
      skipSpaces();
      if (pos >= s.length())
        throw error("Unexpected end");
      char c = s.charAt(pos);
      switch (c) {
        case '{': return object();
        case '[': return array();
        case '"': return string();
        case 't': return literal("true", Boolean.TRUE);
        case 'f': return literal("false", Boolean.FALSE);
        case 'n': return literal("null", null);
        default:
          if (c == '-' || (c >= '0' && c <= '9'))
            return number();
          throw error("Unexpected character '" + c + "'");
      }
    }

    Map<String, Object> object() {
      Map<String, Object> m = new LinkedHashMap<>();
      pos++;
      skipSpaces();
      if (peek() == '}') {
        pos++;
        return m;
      }
      while (true) {
        skipSpaces();
        if (peek() != '"')
          throw error("Expected a member name");
        String name = string();
        skipSpaces();
        expect(':');
        m.put(name, value());
        skipSpaces();
        if (peek() == ',') {
          pos++;
          continue;
        }
        expect('}');
        return m;
      }
    }

    List<Object> array() {
      List<Object> l = new ArrayList<>();
      pos++;
      skipSpaces();
      if (peek() == ']') {
        pos++;
        return l;
      }
      while (true) {
        l.add(value());
        skipSpaces();
        if (peek() == ',') {
          pos++;
          continue;
        }
        expect(']');
        return l;
      }
    }

    String string() {
      StringBuilder sb = new StringBuilder();
      pos++;
      while (true) {
        if (pos >= s.length())
          throw error("Unterminated string");
        char c = s.charAt(pos++);
        if (c == '"')
          return sb.toString();
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        if (pos >= s.length())
          throw error("Unterminated escape");
        char e = s.charAt(pos++);
        switch (e) {
          case '"': case '\\': case '/': sb.append(e); break;
          case 'b': sb.append('\b'); break;
          case 'f': sb.append('\f'); break;
          case 'n': sb.append('\n'); break;
          case 'r': sb.append('\r'); break;
          case 't': sb.append('\t'); break;
          case 'u':
            if (pos + 4 > s.length())
              throw error("Truncated \\u escape");
            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
            pos += 4;
            break;
          default:
            throw error("Unknown escape \\" + e);
        }
      }
    }

    Object number() {
      int start = pos;
      while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0)
        pos++;
      String n = s.substring(start, pos);
      try {
        if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0)
          return Long.parseLong(n);
        return Double.parseDouble(n);
      }
      catch (NumberFormatException e) {
        throw error("Not a number: " + n);
      }
    }

    Object literal(String word, Object value) {
      if (!s.startsWith(word, pos))
        throw error("Unexpected token");
      pos += word.length();
      return value;
    }

    void skipSpaces() {
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos)))
        pos++;
    }

    char peek() {
      return pos < s.length() ? s.charAt(pos) : '\0';
    }

    void expect(char c) {
      if (peek() != c)
        throw error("Expected '" + c + "'");
      pos++;
    }

    IllegalArgumentException error(String message) {
      return new IllegalArgumentException(message + " at " + pos);
    }
  }

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
