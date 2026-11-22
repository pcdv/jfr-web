package jfrweb.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HelpTest {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final PrintStream stdout = new PrintStream(out, true, StandardCharsets.UTF_8);

  @Test
  public void testOverview() {
    Assertions.assertEquals(0, Main.help(new String[]{"help"}, stdout));
    Assertions.assertEquals(0, Main.help(new String[]{"--help"}, stdout));
    String text = out.toString(StandardCharsets.UTF_8);
    for (String command : new String[]{"open", "standalone", "report", "flame", "history", "export"}) {
      Assertions.assertTrue(text.contains("\n  " + command + " "), command);
      Assertions.assertNotNull(Help.of(command), command);
    }
  }

  @Test
  public void testCommand() {
    Assertions.assertEquals(0, Main.help(new String[]{"help", "flame"}, stdout));
    Assertions.assertEquals(0, Main.help(new String[]{"flame", "app.jfr", "--help"}, stdout));
    Assertions.assertEquals(0, Main.help(new String[]{"--events", "x.properties", "standalone", "-h"}, stdout));
    String text = out.toString(StandardCharsets.UTF_8);
    Assertions.assertTrue(text.startsWith("Usage: jfr-web flame <file.jfr>"), text);
    Assertions.assertTrue(text.contains("Usage: jfr-web standalone"), text);

    Assertions.assertEquals(2, Main.help(new String[]{"help", "nope"}, stdout));
    Assertions.assertNull(Main.help(new String[]{"report", "app.jfr", "--events"}, stdout), "not a request for help");
  }

  @Test
  public void testEventsOnlyBeforeTheCommand() throws Exception {
    // the option of report, not a file of event mappings
    Assertions.assertArrayEquals(new String[]{"report", "app.jfr", "--events"},
                                 Main.eventMappings(new String[]{"report", "app.jfr", "--events"}));
  }
}
