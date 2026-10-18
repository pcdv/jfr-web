package jfrweb;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import flak.App;
import flak.AppFactory;
import flak.Flak;
import flak.annotations.Route;
import jfrweb.export.StaticExport;
import jfrweb.history.OperationHistory;
import jfrweb.session.Session;
import jfrweb.session.Sessions;
import jfrweb.snapshot.Snapshots;

/**
 * A flak app with a few endpoints to profile, and the profiler: to try the
 * web interface. Run with ./gradlew demo, then call the endpoints whose URLs
 * it prints while a session records. With {@code -Pdemo.export=<dir>}, it
 * records a session, runs 3 minutes more for the history
 * ({@code -Pdemo.export.seconds=}), writes both as a static site, and ends.
 */
public class Demo {

  public static class Handlers {
    static volatile long sink;

    @Route("/sort/:n")
    public String sort(int n) {
      int[] a = new Random(n).ints(n * 1000).toArray();
      Arrays.sort(a);
      sink += a[0];
      return "sorted " + a.length + " ints\n";
    }

    @Route("/files")
    public String files() throws IOException {
      // lists a big directory, checking each entry: native time
      Path home = Path.of(System.getProperty("java.home"));
      long n;
      try (var s = Files.walk(home)) {
        n = s.filter(Files::isRegularFile).count();
      }
      return n + " files in " + home + "\n";
    }

    @Route("/hello")
    public String hello() {
      return "hello\n";
    }

    @Route("/load")
    public String load() throws IOException {
      // what a slow request may hide: an exception caught, slow file accesses
      try {
        throw new IllegalStateException("not in cache");
      }
      catch (IllegalStateException e) {
        Path f = Files.createTempFile("demo", ".bin");
        try {
          Files.write(f, new byte[4 << 20]);
          return Files.readAllBytes(f).length + " bytes loaded\n";
        }
        finally {
          Files.delete(f);
        }
      }
    }
  }

  public static void main(String[] args) throws Exception {
    AppFactory factory = Flak.getFactory();
    factory.setLocalAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                                                  Integer.getInteger("demo.port", 8090)));
    App app = factory.createApp();
    app.scan(new Handlers());
    app.start();

    // what production would run: kept across runs of the demo, in intervals
    // of 10 s to see something soon; an export starts its history afresh
    String export = System.getProperty("demo.export");
    Path historyDir = export != null
      ? Files.createTempDirectory("demo").resolve("history")
      : Path.of(System.getProperty("demo.history", "build/demo-history"));
    OperationHistory history = OperationHistory.builder()
                                             .directory(historyDir)
                                             .interval(Duration.ofSeconds(10))
                                             .observe(app)
                                             .start();

    // what production would run too: the last minutes, saved when something
    // goes wrong. Not for an export: recordings running together share their
    // events, and the JDK's settings of the rolling recording record the
    // environment
    Snapshots snapshots = export != null ? null
      : Snapshots.builder()
                 .directory(Path.of(System.getProperty("demo.snapshots", "build/demo-snapshots")))
                 .observe(app)
                 .start();

    // a static site is published: its recording leaves out the environment
    // variables and system properties of the JVM, and the processes of the
    // machine with their command lines
    Map<String, String> settings = new HashMap<>(Sessions.defaultSettings());
    if (export != null) {
      settings.put("jdk.InitialEnvironmentVariable#enabled", "false");
      settings.put("jdk.InitialSystemProperty#enabled", "false");
      settings.put("jdk.SystemProcess#enabled", "false");
    }
    Profiler profiler = Profiler.builder()
                                .port(Integer.getInteger("profiler.port", 0))
                                .settings(settings)
                                .observe(app)
                                .history(history.getDirectory())
                                .snapshots(snapshots)
                                .start();

    System.out.println("Profiler:  " + profiler.getUrl());
    System.out.println("Endpoints: " + app.getRootUrl() + "/sort/500, /files, /hello, /load");
    System.out.println("History:   " + historyDir.toAbsolutePath());

    if (Boolean.getBoolean("demo.load")) {
      // a client calling an endpoint every 300 ms or so, to fill the history
      Thread load = new Thread(() -> {
        String[] paths = {"/sort/1000", "/sort/100", "/files", "/hello", "/hello", "/hello", "/load"};
        Random random = new Random();
        while (true) {
          try {
            call(app.getRootUrl() + paths[random.nextInt(paths.length)]);
            Thread.sleep(random.nextInt(600));
          }
          catch (Exception e) {
            return;
          }
        }
      }, "demo load");
      load.setDaemon(true);
      load.start();
    }
    Session recorded = null;
    if (Boolean.getBoolean("demo.session") || export != null) {
      // records a short session by itself, e.g. to look at the interface
      // its own id: the snapshots of earlier runs are listed first
      int session = profiler.getSessions().start("demo").getId();
      // three clients calling the endpoints at random, for 8 seconds
      clients(app.getRootUrl(), 3, 100, System.currentTimeMillis() + 8000);
      recorded = profiler.getSessions().stop(session);
      System.out.println("Recorded session " + session);
    }
    if (export != null) {
      // a static site of the session and of a history of a few minutes, e.g.
      // to publish a demo
      int seconds = Integer.getInteger("demo.export.seconds", 180);
      clients(app.getRootUrl(), 2, 600, System.currentTimeMillis() + seconds * 1000L);
      // writes the interval in progress
      history.close();
      StaticExport.export(List.of(recorded.getFile()), historyDir, Path.of(export), true, "Demo: an HTTP app");
      System.out.println("Exported to " + Path.of(export).toAbsolutePath());
      System.exit(0);
    }
    Thread.currentThread().join();
  }

  /**
   * Clients calling the endpoints at random, until the time given.
   *
   * @param pause the most each one waits between two calls, in ms
   */
  private static void clients(String root, int count, int pause, long end) throws InterruptedException {
    String[] paths = {"/sort/2000", "/sort/200", "/files", "/hello", "/hello", "/load"};
    Thread[] clients = new Thread[count];
    for (int c = 0; c < clients.length; c++) {
      Random random = new Random(c);
      clients[c] = new Thread(() -> {
        while (System.currentTimeMillis() < end) {
          try {
            call(root + paths[random.nextInt(paths.length)]);
            Thread.sleep(random.nextInt(pause));
          }
          catch (Exception e) {
            throw new IllegalStateException(e);
          }
        }
      }, "client " + c);
      clients[c].start();
    }
    for (Thread c : clients)
      c.join();
  }

  private static void call(String url) throws IOException {
    try (var in = new java.net.URL(url).openStream()) {
      in.readAllBytes();
    }
  }
}
