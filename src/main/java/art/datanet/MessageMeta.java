package art.datanet;

/** Sender and timing information accompanying a JSON message. */
public record MessageMeta(String channel, String from, long timestamp) {}
