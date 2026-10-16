package jfrweb.analysis;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;

/**
 * An event of the application read as an operation: for an application that
 * records its own JFR event for each unit of work, e.g. an RPC framework, so
 * that jfr-web does not need to know it. Its start, duration and thread are
 * those of the operation, a synchronous one.
 * <p>
 * The name and the detail are templates, where {@code {field}} stands for
 * the value of a field of the event, e.g. {@code {service}.{method}}; a
 * field of a field is written {@code {request.path}}, and a class stands
 * for its name.
 *
 * @param event  the name of the event, e.g. "com.acme.RpcCall"
 * @param name   what operations are grouped by: a few distinct values, not
 *               one per call
 * @param detail what one call worked on, or null
 * @param failed a field telling that it failed, or null: a boolean, or any
 *               other value, which is the error unless null or blank
 */
public record EventMapping(String event, String name, String detail, String failed) {

  private static final Pattern FIELD = Pattern.compile("\\{([^}]+)}");

  public EventMapping {
    if (event == null || event.isBlank())
      throw new IllegalArgumentException("No event to map");
    if (name == null || name.isBlank())
      throw new IllegalArgumentException("No name for the operations of " + event);
  }

  /**
   * The operation of one of its events.
   */
  RecordedOperation operation(RecordedEvent e) {
    RecordedThread t = e.getThread();
    boolean isFailed = false;
    String error = null;
    if (failed != null) {
      Object v = value(e, failed);
      if (v instanceof Boolean b) {
        isFailed = b;
      }
      else if (v != null && !v.toString().isBlank()) {
        isFailed = true;
        error = v.toString();
      }
    }
    return new RecordedOperation(t == null ? -1 : t.getJavaThreadId(),
                                 t == null ? null : t.getJavaName(),
                                 Analysis.nanos(e.getStartTime()),
                                 Analysis.nanos(e.getEndTime()),
                                 format(e, name),
                                 detail == null ? null : format(e, detail),
                                 0,
                                 isFailed,
                                 error,
                                 0,
                                 0,
                                 false);
  }

  private static String format(RecordedEvent e, String template) {
    Matcher m = FIELD.matcher(template);
    StringBuilder sb = new StringBuilder();
    while (m.find())
      m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value(e, m.group(1).trim()))));
    m.appendTail(sb);
    return sb.toString();
  }

  /**
   * The value of a field, null if the event has none of that name, or if a
   * field it is nested in is null; the name of a class.
   */
  private static Object value(RecordedEvent e, String field) {
    try {
      Object v = e.getValue(field);
      return v instanceof RecordedClass c ? c.getName() : v;
    }
    catch (IllegalArgumentException | NullPointerException ex) {
      return null;
    }
  }
}
