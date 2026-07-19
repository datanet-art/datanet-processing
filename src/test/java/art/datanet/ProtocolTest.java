package art.datanet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import processing.data.JSONObject;

final class ProtocolTest {
  @Test
  void buildsJsonPublishEnvelope() {
    JSONObject data = new JSONObject().setInt("value", 42);
    JSONObject envelope = JSONObject.parse(Protocol.publish("project.demo.sensor", data));

    assertEquals("pub", envelope.getString("op"));
    assertEquals("project.demo.sensor", envelope.getString("ch"));
    assertEquals(42, envelope.getJSONObject("d").getInt("value"));
  }

  @Test
  void roundTripsBinaryEnvelopeAndMetadata() {
    byte[] payload = new byte[] {0, 1, (byte) 255};
    JSONObject metadata = new JSONObject().setInt("universe", 1);
    JSONObject envelope = JSONObject.parse(
        Protocol.publishBinary("project.demo.lights", payload, "binary/dmx", metadata));

    assertTrue(envelope.getBoolean("bin"));
    assertEquals("binary/dmx", envelope.getString("ct"));
    assertEquals(1, envelope.getJSONObject("meta").getInt("universe"));
    assertArrayEquals(payload, Protocol.decodeBinary(envelope.getString("b64")));
  }

  @Test
  void omitsMetadataWhenItWasNotProvided() {
    JSONObject envelope = JSONObject.parse(
        Protocol.publishBinary("project.demo.bytes", new byte[] {1}, "application/octet-stream", null));

    assertFalse(envelope.hasKey("meta"));
  }

  @Test
  void convertsGatewayErrorsToStructuredErrors() {
    JSONObject envelope = new JSONObject()
        .setString("type", "error")
        .setString("error", "rate_limited")
        .setString("channel", "project.demo.sensor")
        .setLong("retry_ms", 2500)
        .setString("scope", "connection")
        .setInt("limit", 20);

    DataNetError error = Protocol.gatewayError(envelope);

    assertEquals("rate_limited", error.code());
    assertEquals("project.demo.sensor", error.channel());
    assertEquals(2500L, error.retryMs());
    assertEquals("connection", error.scope());
    assertEquals(20, error.limit());
  }

  @Test
  void rejectsBlankChannels() {
    assertThrows(IllegalArgumentException.class, () -> Protocol.requireChannel("  "));
  }
}
