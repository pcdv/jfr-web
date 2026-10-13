package jfrweb;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import jfrweb.history.OperationHistory;
import jfrweb.ops.Operation;
import jfrweb.ops.Operations;
import jfrweb.ops.Segment;
import jfrweb.snapshot.Snapshots;

/**
 * A server that does not speak HTTP, and the profiler: what jfr-web shows of
 * an application through the jfrweb.ops API. An order server with a line
 * protocol over TCP:
 * <pre>
 *   QUOTE symbol       the price of a symbol, computed on the connection's thread
 *   ORDER symbol qty   an order, checked on the connection's thread, then matched
 *                      by the engine, a single thread, which replies
 *   REPORT             a report of the day, written to a file then read back
 * </pre>
 * QUOTE and REPORT are synchronous operations. ORDER is asynchronous: it waits
 * in the queue of the engine, and its samples are those of the two threads
 * that work for it, in segments.
 * <p>
 * Run with ./gradlew tcpDemo: clients of its own send orders and quotes;
 * {@code telnet localhost 9090} to send some by hand.
 */
public class TcpDemo {

  static volatile long sink;

  /** Matches the orders one at a time, as an exchange would. */
  private final ExecutorService engine = Executors.newSingleThreadExecutor(r -> new Thread(r, "matching engine"));

  private void serve(ServerSocket server) throws IOException {
    for (int n = 1; ; n++) {
      Socket s = server.accept();
      new Thread(() -> handle(s), "connection " + n).start();
    }
  }

  private void handle(Socket socket) {
    try (socket;
         BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
         PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
      for (String line; (line = in.readLine()) != null; ) {
        String[] words = line.trim().split("\\s+");
        switch (words[0].toUpperCase()) {
          case "QUOTE" -> quote(line, words, out);
          case "ORDER" -> order(line, words, out);
          case "REPORT" -> report(out);
          default -> reply(out, "ERR unknown command: " + words[0]);
        }
      }
    }
    catch (IOException e) {
      // the client left
    }
  }

  private void quote(String line, String[] words, PrintWriter out) {
    try (Operation op = Operations.begin("quote").detail(line).bytesIn(line.length())) {
      if (words.length < 2) {
        op.failed("no symbol");
        op.bytesOut(reply(out, "ERR no symbol"));
        return;
      }
      // now and then, a cold cache: much slower than usual, which saves a
      // snapshot when the profiler keeps a rolling recording
      boolean cold = ThreadLocalRandom.current().nextInt(400) == 0;
      long price = compute(words[1], cold ? 3000 : 30);
      op.bytesOut(reply(out, "OK " + words[1] + " " + price));
    }
  }

  private void order(String line, String[] words, PrintWriter out) {
    Operation op = Operations.beginAsync("order").detail(line).bytesIn(line.length());
    try (Segment s = op.segment()) {
      if (words.length < 3 || !words[2].matches("\\d+")) {
        op.failed("bad order").bytesOut(reply(out, "ERR usage: ORDER symbol qty"));
        op.close();
        return;
      }
      compute(words[1], 5);
    }
    engine.execute(op.wrap(() -> {
      compute(words[1], 20 + Integer.parseInt(words[2]) % 40);
      if (ThreadLocalRandom.current().nextInt(25) == 0)
        op.failed("rejected: no liquidity").bytesOut(reply(out, "ERR rejected"));
      else
        op.bytesOut(reply(out, "OK filled " + words[2] + " " + words[1]));
      op.close();
    }));
  }

  private void report(PrintWriter out) {
    try (Operation op = Operations.begin("report")) {
      try {
        Path f = Files.createTempFile("report", ".csv");
        try {
          Files.write(f, new byte[8 << 20]);
          op.bytesOut(reply(out, "OK report of " + Files.readAllBytes(f).length + " bytes"));
        }
        finally {
          Files.delete(f);
        }
      }
      catch (IOException e) {
        op.failed(e).bytesOut(reply(out, "ERR " + e));
      }
    }
  }

  /** Burns CPU for about that many milliseconds, sorting arrays. */
  private static long compute(String symbol, long millis) {
    long end = System.nanoTime() + millis * 1_000_000;
    Random random = new Random(symbol.hashCode());
    while (System.nanoTime() < end) {
      int[] a = random.ints(5_000).toArray();
      Arrays.sort(a);
      sink += a[0];
    }
    return Math.floorMod(symbol.hashCode(), 1000) + 100;
  }

  /**
   * Sends a line; the engine replies on the connection while its thread
   * reads the next command.
   *
   * @return its size
   */
  private static int reply(PrintWriter out, String line) {
    synchronized (out) {
      out.println(line);
    }
    return line.length() + 1;
  }

  /** A client sending commands at random, waiting for each reply. */
  private static void client(int port, long until) {
    String[] symbols = {"ACME", "GLOBEX", "INITECH", "UMBRELLA", "HOOLI"};
    Random random = new Random();
    try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
         BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
         PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
      while (System.currentTimeMillis() < until) {
        String symbol = symbols[random.nextInt(symbols.length)];
        int r = random.nextInt(100);
        out.println(r < 55 ? "QUOTE " + symbol
                    : r < 95 ? "ORDER " + symbol + " " + (1 + random.nextInt(500))
                    : r < 98 ? "REPORT" : "ORDER " + symbol);
        in.readLine();
        Thread.sleep(random.nextInt(200));
      }
    }
    catch (IOException | InterruptedException e) {
      System.err.println("Client stopped: " + e);
    }
  }

  private static Thread clients(int port, int count, long until) {
    Thread t = new Thread(() -> {
      Thread[] all = new Thread[count];
      for (int i = 0; i < count; i++) {
        all[i] = new Thread(() -> client(port, until), "client " + i);
        all[i].start();
      }
      for (Thread c : all) {
        try {
          c.join();
        }
        catch (InterruptedException e) {
          return;
        }
      }
    });
    t.start();
    return t;
  }

  public static void main(String[] args) throws Exception {
    int port = Integer.getInteger("demo.port", 9090);
    ServerSocket server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
    TcpDemo demo = new TcpDemo();
    Thread acceptor = new Thread(() -> {
      try {
        demo.serve(server);
      }
      catch (IOException e) {
        e.printStackTrace();
      }
    }, "acceptor");
    acceptor.start();

    // no app to observe: the history, the snapshots and the profiler hear of
    // the operations of the API by themselves
    Path historyDir = Path.of(System.getProperty("demo.history", "build/tcp-demo-history"));
    OperationHistory history = OperationHistory.builder()
                                               .directory(historyDir)
                                               .interval(Duration.ofSeconds(10))
                                               .start();
    Snapshots snapshots = Snapshots.builder()
                                   .directory(Path.of(System.getProperty("demo.snapshots", "build/tcp-demo-snapshots")))
                                   .start();
    Profiler profiler = Profiler.builder()
                                .port(Integer.getInteger("profiler.port", 0))
                                .history(history.getDirectory())
                                .snapshots(snapshots)
                                .start();

    System.out.println("Profiler: " + profiler.getUrl());
    System.out.println("Server:   telnet localhost " + port + ", then e.g. QUOTE ACME, ORDER ACME 100, REPORT");
    System.out.println("History:  " + historyDir.toAbsolutePath());

    if (Boolean.getBoolean("demo.session")) {
      // a short session recorded by itself, e.g. to look at the interface
      // its own id: the snapshots of earlier runs are listed first
      int session = profiler.getSessions().start("demo").getId();
      clients(port, 4, System.currentTimeMillis() + 10_000).join();
      profiler.getSessions().stop(session);
      System.out.println("Recorded session " + session);
    }
    if (Boolean.getBoolean("demo.load"))
      clients(port, 3, Long.MAX_VALUE);
  }
}
