package jfrweb.json;

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

  @Test
  public void testParse() {
    Object v = Json.parse(" {\"a\": [1, 2.5, -3e2, true, false, null], \"s\": \"x\\\"y\\\\z\\n\\u00e9\", \"o\": {}, \"e\": []} ");
    Assert.assertEquals("{a=[1, 2.5, -300.0, true, false, null], s=x\"y\\z\né, o={}, e=[]}", String.valueOf(v));
    Assert.assertEquals(Long.class, ((List<?>) ((Map<?, ?>) v).get("a")).get(0).getClass());
  }

  @Test
  public void testRoundTrip() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("text", "tab\there \"quoted\" \u0001");
    m.put("list", List.of(1L, 2.5, "x"));
    Assert.assertEquals(m, Json.parse(Json.write(m)));
  }

  @Test
  public void testInvalid() {
    for (String bad : new String[]{"", "{", "[1,]", "{\"a\" 1}", "\"open", "tru", "1 2", "{\"a\":1,}"}) {
      try {
        Json.parse(bad);
        Assert.fail("accepted: " + bad);
      }
      catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testUnknownType() {
    Json.write(Arrays.asList(new Object()));
  }
}
