package art.datanet;

import java.util.Base64;
import processing.data.JSONObject;

final class Protocol {
  private Protocol() {}

  static String subscribe(String channel) {
    return new JSONObject().setString("op", "sub").setString("ch", channel).toString();
  }

  static String unsubscribe(String channel) {
    return new JSONObject().setString("op", "unsub").setString("ch", channel).toString();
  }

  static String publish(String channel, Object data) {
    return new JSONObject().setString("op", "pub").setString("ch", channel).put("d", data).toString();
  }

  static String publishBinary(String channel, byte[] data, String contentType, JSONObject metadata) {
    JSONObject envelope = new JSONObject()
        .setString("op", "pub")
        .setString("ch", channel)
        .setBoolean("bin", true)
        .setString("b64", Base64.getEncoder().encodeToString(data))
        .setString("ct", contentType);
    if (metadata != null) envelope.setJSONObject("meta", metadata);
    return envelope.toString();
  }

  static String heartbeat() {
    return new JSONObject().setString("op", "hb").toString();
  }

  static byte[] decodeBinary(String encoded) {
    return Base64.getDecoder().decode(encoded);
  }

  static DataNetError gatewayError(JSONObject message) {
    String code = message.getString("code", message.getString("error", "gateway_error"));
    String channel = message.getString("channel", message.getString("ch", null));
    Long retryMs = numberAsLong(message, "retry_ms");
    String scope = message.getString("scope", null);
    Integer limit = numberAsInteger(message, "limit");
    return new DataNetError(
        code,
        "DataNet: " + message.getString("error", code) + (channel == null ? "" : " (" + channel + ")"),
        channel,
        retryMs,
        scope,
        null,
        limit,
        null);
  }

  static String requireChannel(String channel) {
    if (channel == null || channel.trim().isEmpty()) {
      throw new IllegalArgumentException("channel must not be blank");
    }
    return channel.trim();
  }

  private static Long numberAsLong(JSONObject object, String key) {
    if (!object.hasKey(key) || object.isNull(key)) return null;
    Object value = object.get(key);
    return value instanceof Number number ? number.longValue() : null;
  }

  private static Integer numberAsInteger(JSONObject object, String key) {
    if (!object.hasKey(key) || object.isNull(key)) return null;
    Object value = object.get(key);
    return value instanceof Number number ? number.intValue() : null;
  }
}
