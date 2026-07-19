package art.datanet;

/** Receives a JSON-compatible value and its message metadata. */
@FunctionalInterface
public interface MessageHandler {
  void onMessage(Object data, MessageMeta meta);
}
