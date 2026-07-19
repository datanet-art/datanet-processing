package art.datanet;

/** Receives raw bytes and their message metadata. */
@FunctionalInterface
public interface BinaryMessageHandler {
  void onMessage(byte[] data, BinaryMessageMeta meta);
}
