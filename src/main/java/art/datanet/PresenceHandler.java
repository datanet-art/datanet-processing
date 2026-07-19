package art.datanet;

/** Receives a presence lookup result on the Processing animation thread. */
@FunctionalInterface
public interface PresenceHandler {
  void onPresence(PresenceResult presence);
}
