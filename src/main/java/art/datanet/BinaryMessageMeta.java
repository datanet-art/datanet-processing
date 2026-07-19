package art.datanet;

import processing.data.JSONObject;

/** Sender, format, and timing information accompanying a binary message. */
public record BinaryMessageMeta(
    String channel,
    String from,
    long timestamp,
    String contentType,
    int bytes,
    JSONObject metadata) {}
