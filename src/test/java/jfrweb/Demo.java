package jfrweb;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;

import flak.App;
import flak.AppFactory;
import flak.Flak;
import flak.annotations.Route;
import jfrweb.history.EndpointHistory;

/**
 * A flak app with a few endpoints to profile, and the profiler: to try the
 * web interface. Run with ./gradlew demo, then call the endpoints whose URLs
 * it prints while a session records.
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
    // of 10 s to see something soon
    Path historyDir = Path.of(System.getProperty("demo.history", "build/demo-history"));
    EndpointHistory history = EndpointHistory.builder()
                                             .directory(historyDir)
                                             .interval(Duration.ofSeconds(10))
                                             .observe(app)
                                             .start();

    Profiler profiler = Profiler.builder()
                                .port(Integer.getInteger("profiler.port", 0))
                                .observe(app)
                                .history(history.getDirectory())
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
    if (Boolean.getBoolean("demo.session")) {
      // records a short session by itself, e.g. to look at the interface
      profiler.getSessions().start("demo");
      // three clients calling the endpoints at random, for 8 seconds
      String[] paths = {"/sort/2000", "/sort/200", "/files", "/hello", "/hello", "/load"};
      long end = System.currentTimeMillis() + 8000;
      Thread[] clients = new Thread[3];
      for (int c = 0; c < clients.length; c++) {
        Random random = new Random(c);
        clients[c] = new Thread(() -> {
          while (System.currentTimeMillis() < end) {
            try {
              call(app.getRootUrl() + paths[random.nextInt(paths.length)]);
              Thread.sleep(random.nextInt(100));
            }
            catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
        });
        clients[c].start();
      }
      for (Thread c : clients)
        c.join();
      profiler.getSessions().stop(1);
      System.out.println("Recorded session 1");
    }
    Thread.currentThread().join();
  }

  private static void call(String url) throws IOException {
    try (var in = new java.net.URL(url).openStream()) {
      in.readAllBytes();
    }
  }
}
