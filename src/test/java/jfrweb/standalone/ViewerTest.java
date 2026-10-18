package jfrweb.standalone;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import jdk.jfr.Recording;
import jfrweb.ops.Operation;
import jfrweb.ops.Operations;
import jfrweb.session.Sessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static jfrweb.analysis.AnalysisTest.Handlers.burn;

/**
 * Browses recordings made elsewhere, opened from files.
 */
public class ViewerTest {

  private final HttpClient http = HttpClient.newHttpClient();
  private Path dir;
  private Viewer viewer;
  /** When the recording was started and stopped. */
  private Instant started;
  private Instant stopped;

  @BeforeEach
  public void setUp() throws Exception {
    dir = Files.createTempDirectory("jfr-web-viewer-test");
    try (Recording r = new Recording(Sessions.defaultSettings())) {
      started = Instant.now();
      r.start();
      for (int i = 0; i < 5; i++)
        try (Operation op = Operations.begin("work").detail("item " + i)) {
          burn(30);
        }
      r.stop();
      stopped = Instant.now();
      r.dump(dir.resolve("app.jfr"));
    }
    Files.writeString(dir.resolve("notes.txt"), "not a recording");
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (viewer != null)
      viewer.close();
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  @Test
  public void testBrowsesRecordings() throws Exception {
    viewer = Viewer.start(List.of(dir), null, 0);

    Assertions.assertEquals("{\"app\":\"app.jfr\",\"recordingsOnly\":true,\"history\":false}", get("api/about").body());
    String list = get("api/sessions").body();
    Assertions.assertTrue(list.startsWith("[{\"id\":1,\"state\":\"STOPPED\",\"name\":\"app\",\"trigger\":\"file: "), list);
    Assertions.assertEquals(1, list.split("\"id\":").length - 1, "one session");

    String report = get("api/sessions/1/report").body();
    Assertions.assertTrue(report.contains("\"name\":\"work\",\"count\":5"), report);
    Assertions.assertEquals(200, get("api/sessions/1/profile").statusCode());
    Assertions.assertEquals(409, post("api/sessions").statusCode(), "no JVM to record");
    Assertions.assertEquals(409, post("api/auto?patterns=work").statusCode(), "no operation to trigger one");
  }

  @Test
  public void testNumberedOldestFirst() throws Exception {
    // recorded after app.jfr, but before it by name
    try (Recording r = new Recording(Sessions.defaultSettings())) {
      r.start();
      burn(30);
      r.stop();
      r.dump(dir.resolve("a-later.jfr"));
    }
    viewer = Viewer.start(List.of(dir), null, 0);

    String list = get("api/sessions").body();
    Assertions.assertTrue(list.contains("{\"id\":1,\"state\":\"STOPPED\",\"name\":\"app\""), list);
    Assertions.assertTrue(list.contains("{\"id\":2,\"state\":\"STOPPED\",\"name\":\"a-later\""), list);
  }

  @Test
  public void testSpanFromChunkHeaders() throws Exception {
    Path f = dir.resolve("app.jfr");
    Instant[] span = Viewer.span(f);
    // that of its chunks, which cover the recording; one may have begun
    // before it, for another recording of this JVM
    Assertions.assertFalse(span[0].isAfter(started.plusSeconds(1)), span[0] + " after the start, " + started);
    Assertions.assertFalse(span[1].isBefore(stopped.minusSeconds(1)), span[1] + " before the stop, " + stopped);
    Assertions.assertTrue(span[1].isBefore(stopped.plusSeconds(60)), "not to the end of the file's day");
  }

  @Test
  public void testNoRecording() throws Exception {
    Files.delete(dir.resolve("app.jfr"));
    Assertions.assertThrows(IllegalArgumentException.class, () -> Viewer.start(List.of(dir), null, 0));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(viewer.getUrl() + path)).build(),
                     HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(viewer.getUrl() + path))
                                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                     HttpResponse.BodyHandlers.ofString());
  }
}
