package jfrweb.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jfrweb.history.OperationHistory;
import jfrweb.json.Json;
import jfrweb.ops.Operation;
import jfrweb.ops.Operations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static jfrweb.analysis.AnalysisTest.Handlers.burn;

public class StaticExportTest {

  private Path dir;

  @BeforeEach
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("jfr-web-export-test");
  }

  @AfterEach
  public void tearDown() throws IOException {
    try (Stream<Path> all = Files.walk(dir)) {
      all.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  @Test
  public void testKeySortsParametersAndDoesNotEncode() {
    Assertions.assertEquals("api/x", StaticExport.key("api/x", Map.of()));
    Assertions.assertEquals("api/x?a=GET /y&b=1", StaticExport.key("api/x", Map.of("b", "1", "a", "GET /y")));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testExport() throws Exception {
    Path jfr = dir.resolve("app.jfr");
    Path historyDir = dir.resolve("history");
    try (OperationHistory history = OperationHistory.builder().directory(historyDir).start();
         Recording rec = new Recording(Configuration.getConfiguration("profile"))) {
      rec.enable(Operations.EVENT);
      rec.start();
      for (int i = 0; i < 3; i++)
        try (Operation op = Operations.begin("export-test job").detail("job " + i)) {
          burn(50);
        }
      rec.stop();
      rec.dump(jfr);
    }

    Path out = dir.resolve("site");
    StaticExport.export(List.of(jfr), historyDir, out, false, "Export test");

    Assertions.assertTrue(Files.exists(out.resolve("index.html")));
    Assertions.assertTrue(Files.exists(out.resolve("js/api.js")));
    Assertions.assertFalse(Files.exists(out.resolve("jsconfig.json")), "not for the browser");

    Map<String, Object> manifest = (Map<String, Object>) Json.parse(Files.readString(out.resolve("static.json")));
    Map<String, Object> files = (Map<String, Object>) manifest.get("files");
    Assertions.assertEquals("Export test", manifest.get("title"));
    for (String key : new String[]{"api/sessions", "api/sessions/1/report", "api/sessions/1/timeline",
                                   "api/sessions/1/profile", "api/sessions/1/profile?samples=alloc"})
      Assertions.assertTrue(files.containsKey(key), key + " in " + files.keySet());
    // the page computes flame graphs and lists from the profile: only the
    // details of the slowest operations are asked for
    Assertions.assertTrue(files.keySet().stream().anyMatch(k -> k.matches("api/sessions/1/instances/\\d+")), files.keySet().toString());
    Assertions.assertTrue(files.keySet().stream().noneMatch(k -> k.contains("/flame") || k.contains("/instances?")),
                          files.keySet().toString());
    Assertions.assertTrue(Files.readString(out.resolve((String) files.get("api/sessions/1/profile"))).startsWith("{\"version\":5,"));
    Assertions.assertFalse(files.containsKey("api/sessions/1/recording.jfr"), "recordings left out");
    for (Object f : files.values())
      Assertions.assertTrue(Files.exists(out.resolve((String) f)), f + " written");

    // the history, over the period it covers, without where it was
    Map<String, Object> period = (Map<String, Object>) manifest.get("history");
    String key = "api/history?from=" + period.get("from") + "&to=" + period.get("to");
    Assertions.assertTrue(files.containsKey(key), key + " in " + files.keySet());
    String history = Files.readString(out.resolve((String) files.get(key)), StandardCharsets.UTF_8);
    Assertions.assertTrue(history.contains("\"directory\":\"history\""), history);
    Assertions.assertTrue(history.contains("\"name\":\"export-test job\",\"count\":3"), history);
  }
}
