package art.datanet;

/** A structured error returned by DataNet or raised by the client. */
public final class DataNetError extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;
  private final String channel;
  private final Long retryMs;
  private final String scope;
  private final Integer status;
  private final Integer limit;

  DataNetError(String code, String message) {
    this(code, message, null, null, null, null, null, null);
  }

  DataNetError(String code, String message, Throwable cause) {
    this(code, message, null, null, null, null, null, cause);
  }

  DataNetError(String code, String message, String channel, Long retryMs,
      String scope, Integer status, Integer limit, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.channel = channel;
    this.retryMs = retryMs;
    this.scope = scope;
    this.status = status;
    this.limit = limit;
  }

  public String code() { return code; }
  public String channel() { return channel; }
  public Long retryMs() { return retryMs; }
  public String scope() { return scope; }
  public Integer status() { return status; }
  public Integer limit() { return limit; }
}
