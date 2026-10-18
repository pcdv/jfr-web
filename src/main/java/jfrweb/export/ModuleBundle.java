package jfrweb.export;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Joins JavaScript modules of the interface into one script, for a page that
 * must work on its own, opened from a file: browsers refuse modules loaded
 * from {@code file://}, and a module inlined as it is cannot resolve its
 * relative imports.
 * <p>
 * Each module becomes a function returning its exports, called once, after
 * those it imports from; its imports become lookups of those exports. Only
 * the forms the interface uses are understood, and anything else is refused:
 * {@code import {a, b as c} from './x.js';}, {@code import * as x from
 * './x.js';}, {@code export function}, {@code export async function} and
 * {@code export const a = 1, b = 2;}.
 */
final class ModuleBundle {

  private static final Pattern IMPORT = Pattern.compile(
    "^import\\s+(?:\\{([^}]*)\\}|\\*\\s+as\\s+([\\w$]+))\\s+from\\s+'([^']+)';[ \\t]*$", Pattern.MULTILINE);
  private static final Pattern EXPORT_FUNCTION = Pattern.compile(
    "^export\\s+((?:async\\s+)?function\\s*\\*?\\s*([\\w$]+))", Pattern.MULTILINE);
  private static final Pattern EXPORT_CONST = Pattern.compile("^export\\s+(const\\s+([^;]*);)", Pattern.MULTILINE);
  private static final Pattern CONST_NAME = Pattern.compile("(?:^|,)\\s*([\\w$]+)\\s*=");
  /** Whatever else would export or import. */
  private static final Pattern OTHER = Pattern.compile("^\\s*(export|import)\\b(?!\\()", Pattern.MULTILINE);

  /** Where the modules are read from, e.g. "/jfrweb/ui/". */
  private final String root;
  private final Map<String, String> bundled = new LinkedHashMap<>();
  private final Set<String> visiting = new HashSet<>();

  private ModuleBundle(String root) {
    this.root = root;
  }

  /**
   * @param root  the classpath directory of the modules, e.g. "/jfrweb/ui/"
   * @param entry the module run, relative to it, e.g. "js/flamePage.js"
   * @return the script: the entry and the modules it needs, before it
   */
  static String of(String root, String entry) throws IOException {
    ModuleBundle b = new ModuleBundle(root);
    b.add(entry);
    StringBuilder out = new StringBuilder("const modules = {};\n");
    b.bundled.values().forEach(out::append);
    return out.toString();
  }

  /** Adds a module after the ones it imports from. */
  private void add(String path) throws IOException {
    if (bundled.containsKey(path))
      return;
    if (!visiting.add(path))
      throw new IOException("Modules importing each other, through " + path);
    String source = read(path);

    StringBuilder body = new StringBuilder();
    Matcher m = IMPORT.matcher(source);
    int last = 0;
    while (m.find()) {
      String from = resolve(path, m.group(3));
      add(from);
      body.append(source, last, m.start());
      String target = "modules[" + quote(from) + "]";
      if (m.group(2) != null)
        body.append("const ").append(m.group(2)).append(" = ").append(target).append(';');
      else
        body.append("const {").append(m.group(1).replaceAll("\\s+as\\s+", ": ").trim()).append("} = ").append(target).append(';');
      last = m.end();
    }
    body.append(source.substring(last));

    List<String> exports = new ArrayList<>();
    String code = replace(EXPORT_FUNCTION, body.toString(), e -> {
      exports.add(e.group(2));
      return e.group(1);
    });
    code = replace(EXPORT_CONST, code, e -> {
      Matcher names = CONST_NAME.matcher(topLevel(e.group(2)));
      while (names.find())
        exports.add(names.group(1));
      return e.group(1);
    });
    Matcher other = OTHER.matcher(code);
    if (other.find())
      throw new IOException("Not understood in " + path + ": " + code.substring(other.start(), code.indexOf('\n', other.start())));

    bundled.put(path, "\n// " + path + "\nmodules[" + quote(path) + "] = (() => {\n" + code
                      + "\nreturn {" + String.join(", ", exports) + "};\n})();\n");
    visiting.remove(path);
  }

  private String read(String path) throws IOException {
    try (InputStream in = ModuleBundle.class.getResourceAsStream(root + path)) {
      if (in == null)
        throw new IOException("No module " + root + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** The path of an import, from the module importing it: "js/a/b.js" and "../c.js" give "js/c.js". */
  static String resolve(String from, String specifier) {
    if (!specifier.startsWith("./") && !specifier.startsWith("../"))
      throw new IllegalArgumentException("Not a relative import in " + from + ": " + specifier);
    return URI.create("x:/" + from).resolve(specifier).getPath().substring(1);
  }

  /** The declarations of a const statement, what nested brackets hold left out: "a = f(b = 1), c = 2" gives "a = f(), c = 2". */
  private static String topLevel(String declarations) {
    StringBuilder s = new StringBuilder();
    int depth = 0;
    for (char c : declarations.toCharArray()) {
      if (c == '(' || c == '[' || c == '{')
        depth++;
      if (depth == 0)
        s.append(c);
      if (c == ')' || c == ']' || c == '}')
        depth--;
    }
    return s.toString();
  }

  private static String replace(Pattern p, String s, java.util.function.Function<Matcher, String> f) {
    Matcher m = p.matcher(s);
    StringBuilder out = new StringBuilder();
    while (m.find())
      m.appendReplacement(out, Matcher.quoteReplacement(f.apply(m)));
    m.appendTail(out);
    return out.toString();
  }

  private static String quote(String s) {
    return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
  }
}
