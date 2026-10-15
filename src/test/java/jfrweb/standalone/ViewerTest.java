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
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

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

  @Before
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

  @After
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

    Assert.assertEquals("{\"app\":\"app.jfr\",\"recordingsOnly\":true,\"history\":false}", get("api/about").body());
    String list = get("api/sessions").body();
    Assert.assertTrue(list, list.startsWith("[{\"id\":1,\"state\":\"STOPPED\",\"name\":\"app\",\"trigger\":\"file: "));
    Assert.assertEquals("one session", 1, list.split("\"id\":").length - 1);

    String report = get("api/sessions/1/report").body();
    Assert.assertTrue(report, report.contains("\"name\":\"work\",\"count\":5"));
    Assert.assertEquals(200, get("api/sessions/1/flame?name=work&from=0&to=1").statusCode());
    Assert.assertEquals("no JVM to record", 409, post("api/sessions").statusCode());
    Assert.assertEquals("no operation to trigger one", 409, post("api/auto?patterns=work").statusCode());
  }

  @Test
  public void testSpanFromChunkHeaders() throws Exception {
    Path f = dir.resolve("app.jfr");
    Instant[] span = Viewer.span(f);
    // that of its chunks, which cover the recording; one may have begun
    // before it, for another recording of this JVM
    Assert.assertFalse(span[0] + " after the start, " + started, span[0].isAfter(started.plusSeconds(1)));
    Assert.assertFalse(span[1] + " before the stop, " + stopped, span[1].isBefore(stopped.minusSeconds(1)));
    Assert.assertTrue("not to the end of the file's day", span[1].isBefore(stopped.plusSeconds(60)));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNoRecording() throws Exception {
    Files.delete(dir.resolve("app.jfr"));
    Viewer.start(List.of(dir), null, 0);
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
