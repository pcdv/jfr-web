package jfrweb.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import flak.HttpException;
import flak.annotations.OutputFormat;
import flak.annotations.Post;
import flak.annotations.Route;
import jfrweb.session.Session;
import jfrweb.session.Sessions;
import jfrweb.snapshot.Snapshots;

/**
 * The REST API of snapshots: the last minutes of the rolling recording, saved
 * on demand. They are listed with the sessions.
 */
public class SnapshotRoutes {

  /** Null when the profiler was given no snapshots. */
  private final Snapshots snapshots;
  private final Sessions sessions;

  public SnapshotRoutes(Snapshots snapshots, Sessions sessions) {
    this.snapshots = snapshots;
    this.sessions = sessions;
  }

  @Route("/api/snapshots")
  @OutputFormat(ProfilerRoutes.JSON)
  public Map<String, Object> state() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("available", snapshots != null);
    if (snapshots != null) {
      m.put("window", snapshots.getWindow().toMillis() / 1000.0);
      m.put("directory", snapshots.getDirectory().toString());
    }
    return m;
  }

  /**
   * Saves the last minutes now, and returns the session that lists them.
   */
  @Route("/api/snapshots")
  @Post
  @OutputFormat(ProfilerRoutes.JSON)
  public Map<String, Object> save() throws IOException {
    if (snapshots == null)
      throw new HttpException(409, "No rolling recording: the profiler was given no snapshots");
    Snapshots.Snapshot s = snapshots.save("saved by hand");
    for (Session session : sessions.list())
      if (session.getFile().equals(s.file()))
        return ProfilerRoutes.session(session);
    throw new IllegalStateException("Snapshot not listed: " + s.file());
  }
}
