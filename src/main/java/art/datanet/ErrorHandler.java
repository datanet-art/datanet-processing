package art.datanet;

/** Receives structured connection, protocol, and API errors. */
@FunctionalInterface
public interface ErrorHandler {
  void onError(DataNetError error);
}
