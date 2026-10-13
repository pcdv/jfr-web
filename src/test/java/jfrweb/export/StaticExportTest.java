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
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static jfrweb.analysis.AnalysisTest.Handlers.burn;

public class StaticExportTest {

  private Path dir;

  @Before
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("jfr-web-export-test");
  }

  @After
  public void tearDown() throws IOException {
    try (Stream<Path> all = Files.walk(dir)) {
      all.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  @Test
  public void testKeySortsParametersAndDoesNotEncode() {
    Assert.assertEquals("api/x", StaticExport.key("api/x", Map.of()));
    Assert.assertEquals("api/x?a=GET /y&b=1", StaticExport.key("api/x", Map.of("b", "1", "a", "GET /y")));
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
    StaticExport.export(List.of(jfr), historyDir, out, false);

    Assert.assertTrue(Files.exists(out.resolve("index.html")));
    Assert.assertTrue(Files.exists(out.resolve("js/api.js")));
    Assert.assertFalse("not for the browser", Files.exists(out.resolve("jsconfig.json")));

    Map<String, Object> manifest = (Map<String, Object>) Json.parse(Files.readString(out.resolve("static.json")));
    Map<String, Object> files = (Map<String, Object>) manifest.get("files");
    for (String key : new String[]{"api/sessions", "api/sessions/1/report", "api/sessions/1/timeline",
                                   "api/sessions/1/instances?limit=20&name=export-test job",
                                   "api/sessions/1/flame", "api/sessions/1/flame?all=true",
                                   "api/sessions/1/flame?name=export-test job"})
      Assert.assertTrue(key + " in " + files.keySet(), files.containsKey(key));
    Assert.assertFalse("recordings left out", files.containsKey("api/sessions/1/recording.jfr"));
    for (Object f : files.values())
      Assert.assertTrue(f + " written", Files.exists(out.resolve((String) f)));

    // the history, over the period it covers, without where it was
    Map<String, Object> period = (Map<String, Object>) manifest.get("history");
    String key = "api/history?from=" + period.get("from") + "&to=" + period.get("to");
    Assert.assertTrue(key + " in " + files.keySet(), files.containsKey(key));
    String history = Files.readString(out.resolve((String) files.get(key)), StandardCharsets.UTF_8);
    Assert.assertTrue(history, history.contains("\"directory\":\"history\""));
    Assert.assertTrue(history, history.contains("\"name\":\"export-test job\",\"count\":3"));
  }
}
