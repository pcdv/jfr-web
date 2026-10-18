package jfrweb.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jfrweb.analysis.Analysis;
import jfrweb.analysis.FlameGraphs;
import jfrweb.analysis.ProfileWriter;
import jfrweb.json.Json;

/**
 * A flame graph as one HTML file, which works opened from the disk: the
 * recording's profile, and the flame graph panel of the interface with its
 * modules, styles and filters inlined. What {@code jfr-web flame} writes.
 * <p>
 * The operation and thread filters keep the samples written, the others are
 * the filters the page opens with, which it can change.
 */
public final class FlamePage {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)\\}\\}");

  private FlamePage() {}

  /**
   * @param title  e.g. "Wall clock, GET /x"
   * @param source what it was made from, e.g. the name of the recording
   */
  public static void write(Analysis analysis, FlameGraphs.Options o, String title, String source, Writer out)
    throws IOException {
    StringWriter profile = new StringWriter();
    ProfileWriter.write(analysis, profile, o::keeps);

    Map<String, String> values = Map.of(
      "title", html(title),
      "source", html(source),
      "style", resource("/jfrweb/ui/css/style.css"),
      "filter", inScript(Json.write(o.pageFilter())),
      "profile", inScript(profile.toString()),
      "script", inScript(ModuleBundle.of("/jfrweb/ui/", "js/flamePage.js")));
    // in one pass: what is inserted is not looked into
    Matcher m = PLACEHOLDER.matcher(resource("/jfrweb/export/flame-page.html"));
    StringBuilder page = new StringBuilder();
    while (m.find())
      m.appendReplacement(page, Matcher.quoteReplacement(values.get(m.group(1))));
    m.appendTail(page);
    out.write(page.toString());
    out.flush();
  }

  private static String resource(String path) throws IOException {
    try (InputStream in = FlamePage.class.getResourceAsStream(path)) {
      if (in == null)
        throw new IOException("No " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * In a script element, "&lt;/" would end it: written "&lt;\/" instead, the same in JSON
   * strings and in JavaScript strings.
   */
  private static String inScript(String s) {
    return s.replace("</", "<\\/");
  }

  private static String html(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
}
