package art.datanet;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Connection settings for a {@link DataNet} client. */
public final class DataNetConfig {
  static final URI DEFAULT_API_URL = URI.create("https://api.datanet.art");
  static final URI DEFAULT_WS_URL = URI.create("wss://ws.datanet.art/ws");

  final String apiKey;
  final String deviceId;
  final String clientId;
  final String displayName;
  final URI apiUrl;
  final URI wsUrl;
  final int maxReconnectAttempts;
  final Duration requestTimeout;

  private DataNetConfig(Builder builder) {
    this.apiKey = requireText(builder.apiKey, "apiKey");
    this.deviceId = trimToNull(builder.deviceId);
    this.clientId = trimToNull(builder.clientId);
    this.displayName = trimToNull(builder.displayName);
    this.apiUrl = Objects.requireNonNull(builder.apiUrl, "apiUrl");
    this.wsUrl = Objects.requireNonNull(builder.wsUrl, "wsUrl");
    if (builder.maxReconnectAttempts < 0) {
      throw new IllegalArgumentException("maxReconnectAttempts must be at least 0");
    }
    this.maxReconnectAttempts = builder.maxReconnectAttempts;
    this.requestTimeout = Objects.requireNonNull(builder.requestTimeout, "requestTimeout");
  }

  /** Start configuring a client with a DataNet project API key. */
  public static Builder builder(String apiKey) {
    return new Builder(apiKey);
  }

  /** Fluent builder for optional device identity and endpoint settings. */
  public static final class Builder {
    private final String apiKey;
    private String deviceId;
    private String clientId = "processing";
    private String displayName = "DataNet's Processing Sketch";
    private URI apiUrl = DEFAULT_API_URL;
    private URI wsUrl = DEFAULT_WS_URL;
    private int maxReconnectAttempts = 5;
    private Duration requestTimeout = Duration.ofSeconds(10);

    private Builder(String apiKey) {
      this.apiKey = apiKey;
    }

    public Builder deviceId(String value) {
      this.deviceId = value;
      return this;
    }

    public Builder clientId(String value) {
      this.clientId = value;
      return this;
    }

    /** Set the human-readable device name shown in DataNet dashboards. */
    public Builder displayName(String value) {
      this.displayName = value;
      return this;
    }

    public Builder apiUrl(String value) {
      this.apiUrl = URI.create(requireText(value, "apiUrl"));
      return this;
    }

    public Builder webSocketUrl(String value) {
      this.wsUrl = URI.create(requireText(value, "webSocketUrl"));
      return this;
    }

    public Builder maxReconnectAttempts(int value) {
      this.maxReconnectAttempts = value;
      return this;
    }

    public Builder requestTimeout(Duration value) {
      this.requestTimeout = value;
      return this;
    }

    public DataNetConfig build() {
      return new DataNetConfig(this);
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }

  private static String trimToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}
