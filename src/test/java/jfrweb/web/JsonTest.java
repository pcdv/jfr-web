package jfrweb.web;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class JsonTest {

  @Test
  public void testShapes() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("n", 1);
    m.put("d", 1.5);
    m.put("nan", Double.NaN);
    m.put("b", true);
    m.put("null", null);
    m.put("list", List.of("a", 2));
    m.put("time", Instant.parse("2026-10-13T10:00:00Z"));
    m.put("state", Thread.State.NEW);
    Assert.assertEquals("{\"n\":1,\"d\":1.5,\"nan\":null,\"b\":true,\"null\":null,\"list\":[\"a\",2]," +
                        "\"time\":\"2026-10-13T10:00:00Z\",\"state\":\"NEW\"}",
                        Json.write(m));
  }

  @Test
  public void testEscaping() {
    Assert.assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", Json.write("a\"b\\c\nd\u0001"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testUnknownType() {
    Json.write(Arrays.asList(new Object()));
  }
}
