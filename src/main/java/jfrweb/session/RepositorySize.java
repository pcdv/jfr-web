package jfrweb.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * An estimate of the size of a recording in progress. JFR tells it only once
 * a chunk is complete (Recording.getSize is 0 for the first 12 MB), but it
 * writes the chunk in the repository of the JVM, where it grows: the chunks
 * that appeared since the recording started are its data so far.
 * <p>
 * Starting a recording ends the chunk in progress, so the chunks that existed
 * before it are not its own. The repository is shared by the recordings of the
 * JVM: chunks are not counted twice.
 * <p>
 * The file ends up bigger: stopping writes what the threads still hold, and
 * the constant pools and metadata of the chunk (about 17% on a test
 * recording). The estimate is scaled up to make up for it.
 */
public final class RepositorySize {

  /** What the chunks written so far lack of the file, as a factor. */
  private static final double COMPLETION = 1.2;

  /** The system property of a JVM telling where its JFR repository is. */
  public static final String PROPERTY = "jdk.jfr.repository";

  private final Supplier<Path> repository;
  private final Set<String> before;

  /**
   * To be created just before the recording starts.
   *
   * @param repository the directory of the JFR repository of the JVM, null if
   *                   it does not tell (yet)
   */
  public RepositorySize(Supplier<Path> repository) {
    this.repository = repository;
    this.before = names(repository.get());
  }

  /**
   * The size of the chunks written since, 0 if unknown.
   */
  public long size() {
    Path directory = repository.get();
    if (directory == null)
      return 0;
    try (Stream<Path> files = Files.list(directory)) {
      long written = files.filter(f -> !before.contains(f.getFileName().toString()))
                          .mapToLong(f -> f.toFile().length())
                          .sum();
      return Math.round(written * COMPLETION);
    }
    catch (IOException | RuntimeException e) {
      return 0;
    }
  }

  private static Set<String> names(Path directory) {
    Set<String> names = new HashSet<>();
    if (directory != null) {
      try (Stream<Path> files = Files.list(directory)) {
        files.forEach(f -> names.add(f.getFileName().toString()));
      }
      catch (IOException | RuntimeException e) {
        // nothing known of it
      }
    }
    return names;
  }
}
