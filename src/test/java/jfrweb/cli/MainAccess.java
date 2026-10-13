package jfrweb.cli;

import java.io.IOException;
import java.io.PrintStream;

/**
 * Runs the command line from tests of other packages.
 */
public final class MainAccess {

  private MainAccess() {}

  public static int run(String[] args, PrintStream out) throws IOException {
    return Main.run(args, out);
  }
}
