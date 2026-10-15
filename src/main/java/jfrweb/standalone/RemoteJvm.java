package jfrweb.standalone;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import jdk.jfr.consumer.RecordedEvent;
import jdk.management.jfr.FlightRecorderMXBean;
import jdk.management.jfr.RecordingInfo;
import jdk.management.jfr.RemoteRecordingStream;
import jfrweb.analysis.Analysis;
import jfrweb.analysis.EventMapping;
import jfrweb.analysis.EventMappings;
import jfrweb.analysis.RecordedOperation;
import jfrweb.ops.Operations;
import jfrweb.session.Recorder;
import jfrweb.session.StackDepth;

/**
 * Another JVM of this machine, attached to with nothing to install in it: the
 * attach API starts its local JMX agent, over which JFR records and streams
 * (JDK 17 or later on both sides). Attaching needs the same user, and fails
 * when the JVM was started with {@code -XX:+DisableAttachMechanism}.
 * <p>
 * Its operations come from their events, streamed as they end: flak's
 * requests (flak 3.4.0 or later) and the operations of the jfrweb.ops API.
 * The stream is a recording of these events only, with no stack trace: it
 * costs about what the counters of an embedded profiler do. The JVM keeps a
 * minute of it.
 */
public final class RemoteJvm implements Recorder, AutoCloseable {

  private static final System.Logger log = System.getLogger(RemoteJvm.class.getName());

  /** The name of the recording of the stream, in the JVM. */
  static final String STREAM = "jfr-web operations";

  private final long pid;
  private final String name;
  private final JMXConnector jmx;
  private final FlightRecorderMXBean jfr;
  private final RemoteRecordingStream stream;

  private RemoteJvm(long pid, String name, JMXConnector jmx, FlightRecorderMXBean jfr, RemoteRecordingStream stream) {
    this.pid = pid;
    this.name = name;
    this.jmx = jmx;
    this.jfr = jfr;
    this.stream = stream;
  }

  /**
   * Attaches to a JVM, raises the depth of the stacks JFR records there if
   * it still can (see {@link StackDepth}), and streams its operations.
   *
   * @param stackDepth  0 to leave it
   * @param operations  told of each operation once it ends, on the thread of
   *                    the stream
   */
  public static RemoteJvm attach(long pid, String name, int stackDepth, Consumer<RecordedOperation> operations)
      throws IOException {
    String url;
    try {
      VirtualMachine vm = VirtualMachine.attach(String.valueOf(pid));
      try {
        url = vm.startLocalManagementAgent();
      }
      finally {
        vm.detach();
      }
    }
    catch (AttachNotSupportedException e) {
      throw new IOException("Cannot attach to JVM " + pid + ": " + e.getMessage(), e);
    }
    JMXConnector jmx = JMXConnectorFactory.connect(new JMXServiceURL(url));
    try {
      MBeanServerConnection server = jmx.getMBeanServerConnection();
      // before the stream, which may be the first recording of the JVM
      if (stackDepth > 0)
        StackDepth.raise(server, stackDepth);
      FlightRecorderMXBean jfr = ManagementFactory.newPlatformMXBeanProxy(
        server, "jdk.management.jfr:type=FlightRecorder", FlightRecorderMXBean.class);

      Set<Long> before = new HashSet<>();
      for (RecordingInfo r : jfr.getRecordings())
        before.add(r.getId());
      RemoteRecordingStream stream = new RemoteRecordingStream(server);
      Map<String, String> settings = new HashMap<>();
      for (String event : new String[]{jfrweb.analysis.RecordingInfo.FLAK_REQUEST, Operations.EVENT}) {
        settings.put(event + "#enabled", "true");
        settings.put(event + "#threshold", "0 ms");
        settings.put(event + "#stackTrace", "false");
        stream.onEvent(event, e -> report(e, operations));
      }
      // the events of the application mapped to operations
      for (EventMapping m : EventMappings.list())
        stream.onEvent(m.event(), e -> report(e, operations));
      settings.putAll(EventMappings.settings());
      stream.setSettings(settings);
      // named, and bounded: a jfr-web that dies without closing it leaves it
      // running until the JVM ends
      for (RecordingInfo r : jfr.getRecordings())
        if (!before.contains(r.getId()))
          jfr.setRecordingOptions(r.getId(), Map.of("name", STREAM, "maxAge", "60 s"));
      stream.startAsync();
      return new RemoteJvm(pid, name, jmx, jfr, stream);
    }
    catch (IOException | RuntimeException e) {
      jmx.close();
      throw e;
    }
  }

  private static void report(RecordedEvent e, Consumer<RecordedOperation> operations) {
    try {
      RecordedOperation op = Analysis.operation(e);
      if (op != null)
        operations.accept(op);
    }
    catch (RuntimeException ex) {
      log.log(System.Logger.Level.WARNING, "Could not count an operation", ex);
    }
  }

  public long getPid() {
    return pid;
  }

  /**
   * As the JVM lists itself, e.g. its main class and arguments.
   */
  public String getName() {
    return name;
  }

  /**
   * Whether its process still runs: over JMX, the stream is not closed when
   * the JVM dies, it only goes quiet.
   */
  public boolean isAlive() {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  /**
   * Records the JVM: its file is written by the JVM itself, which runs on
   * this machine, as it stops, or as it exits, before it is stopped.
   */
  @Override
  public Running start(String recordingName, Map<String, String> settings, Path file) throws IOException {
    Path destination = file.toAbsolutePath();
    long id = jfr.newRecording();
    try {
      jfr.setRecordingSettings(id, settings);
      jfr.setRecordingOptions(id, Map.of("name", recordingName,
                                         "disk", "true",
                                         "destination", destination.toString(),
                                         "dumpOnExit", "true"));
      jfr.startRecording(id);
    }
    catch (RuntimeException e) {
      close(id);
      throw e;
    }
    return () -> {
      try {
        jfr.stopRecording(id);
        // e.g. the JVM could not write there: through JMX instead
        if (!Files.exists(destination))
          copy(id, destination);
      }
      finally {
        close(id);
      }
    };
  }

  private void copy(long id, Path file) throws IOException {
    long s = jfr.openStream(id, null);
    try (OutputStream out = Files.newOutputStream(file)) {
      for (byte[] b; (b = jfr.readStream(s)) != null; )
        out.write(b);
    }
    finally {
      jfr.closeStream(s);
    }
  }

  private void close(long id) {
    try {
      jfr.closeRecording(id);
    }
    catch (IOException | RuntimeException e) {
      log.log(System.Logger.Level.DEBUG, "Could not close recording " + id + " of JVM " + pid, e);
    }
  }

  /**
   * Stops streaming, which stops its recording in the JVM, and disconnects.
   * A session recording should be stopped first.
   */
  @Override
  public void close() {
    try {
      stream.close();
    }
    catch (RuntimeException e) {
      log.log(System.Logger.Level.DEBUG, "Could not close the stream of JVM " + pid, e);
    }
    try {
      jmx.close();
    }
    catch (IOException e) {
      log.log(System.Logger.Level.DEBUG, "Could not disconnect from JVM " + pid, e);
    }
  }
}
