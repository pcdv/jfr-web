package jfrweb;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import flak.App;
import flak.AppFactory;
import flak.Flak;
import flak.annotations.Route;

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
  }

  public static void main(String[] args) throws Exception {
    AppFactory factory = Flak.getFactory();
    factory.setLocalAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                                                  Integer.getInteger("demo.port", 8090)));
    App app = factory.createApp();
    app.scan(new Handlers());
    app.start();

    Profiler profiler = Profiler.builder().port(Integer.getInteger("profiler.port", 0)).start();

    System.out.println("Profiler:  " + profiler.getUrl());
    System.out.println("Endpoints: " + app.getRootUrl() + "/sort/500, /files, /hello");
    if (Boolean.getBoolean("demo.session")) {
      // records a short session by itself, e.g. to look at the interface
      profiler.getSessions().start("demo");
      for (int i = 0; i < 5; i++) {
        call(app.getRootUrl() + "/sort/2000");
        call(app.getRootUrl() + "/files");
        call(app.getRootUrl() + "/hello");
      }
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
