package art.datanet;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import processing.core.PApplet;
import processing.data.JSONArray;
import processing.data.JSONObject;

/**
 * Realtime DataNet client for Processing 4.
 *
 * <p>Network work runs in the background. Message, connection, error, and convenience presence
 * callbacks run during Processing's {@code pre()} phase, immediately before {@code draw()}.</p>
 */
public final class DataNet implements AutoCloseable {
  public static final String VERSION = "0.1.0";
  public static final String CONTENT_TYPE_BINARY = "application/octet-stream";
  public static final String CONTENT_TYPE_DMX = "binary/dmx";
  public static final String CONTENT_TYPE_ARTNET = "binary/artnet";

  private final PApplet parent;
  private final DataNetConfig config;
  private final HttpClient httpClient;
  private final ScheduledExecutorService scheduler;
  private final ConcurrentLinkedQueue<Runnable> callbackQueue = new ConcurrentLinkedQueue<>();
  private final Map<String, Set<MessageHandler>> messageHandlers = new ConcurrentHashMap<>();
  private final Map<String, Set<BinaryMessageHandler>> binaryHandlers = new ConcurrentHashMap<>();
  private final Object connectionLock = new Object();

  private volatile WebSocket webSocket;
  private volatile String jwt;
  private volatile boolean connected;
  private volatile boolean connecting;
  private volatile boolean explicitlyDisconnected = true;
  private volatile boolean fatalError;
  private volatile int reconnectAttempts;
  private volatile long activeGeneration;
  private volatile long handledDisconnectGeneration = -1;
  private volatile CompletableFuture<DataNet> connectFuture;
  private volatile ScheduledFuture<?> heartbeatTask;
  private volatile ScheduledFuture<?> reconnectTask;
  private volatile Runnable connectHandler;
  private volatile Runnable disconnectHandler;
  private volatile ErrorHandler errorHandler;

  /** Create a client with default endpoints and identity settings. */
  public DataNet(PApplet parent, String apiKey) {
    this(parent, DataNetConfig.builder(apiKey).build());
  }

  /** Create a client with custom identity, endpoint, or reconnect settings. */
  public DataNet(PApplet parent, DataNetConfig config) {
    this.parent = parent;
    this.config = Objects.requireNonNull(config, "config");
    ThreadFactory threads = runnable -> {
      Thread thread = new Thread(runnable, "datanet-processing");
      thread.setDaemon(true);
      return thread;
    };
    this.scheduler = Executors.newSingleThreadScheduledExecutor(threads);
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(config.requestTimeout)
        .executor(scheduler)
        .build();
    if (parent != null) {
      parent.registerMethod("pre", this);
      parent.registerMethod("dispose", this);
    }
  }

  /** Connect asynchronously. Calling this again while connected is harmless. */
  public CompletableFuture<DataNet> connect() {
    synchronized (connectionLock) {
      if (connected) return CompletableFuture.completedFuture(this);
      if (connecting && connectFuture != null) return connectFuture;
      explicitlyDisconnected = false;
      fatalError = false;
      reconnectAttempts = 0;
      connecting = true;
      connectFuture = new CompletableFuture<>();
      authenticateAndOpen();
      return connectFuture;
    }
  }

  /** Disconnect and disable automatic reconnect until {@link #connect()} is called again. */
  public void disconnect() {
    WebSocket socket;
    boolean notify;
    synchronized (connectionLock) {
      explicitlyDisconnected = true;
      connecting = false;
      activeGeneration++;
      socket = webSocket;
      webSocket = null;
      notify = connected;
      connected = false;
      cancel(reconnectTask);
      reconnectTask = null;
      stopHeartbeat();
      if (connectFuture != null && !connectFuture.isDone()) {
        connectFuture.completeExceptionally(new DataNetError("disconnected", "DataNet: connection cancelled"));
      }
    }
    if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "client disconnect");
    if (notify) dispatch(this::notifyDisconnected);
  }

  public boolean isConnected() {
    return connected;
  }

  /** Run queued callbacks on Processing's animation thread. Called automatically by Processing. */
  public void pre() {
    Runnable callback;
    while ((callback = callbackQueue.poll()) != null) {
      try {
        callback.run();
      } catch (RuntimeException exception) {
        System.err.println("DataNet callback failed: " + exception.getMessage());
        exception.printStackTrace();
      }
    }
  }

  /** Release network resources. Called automatically when the sketch exits. */
  public void dispose() {
    disconnect();
    scheduler.shutdownNow();
    if (parent != null) {
      parent.unregisterMethod("pre", this);
      parent.unregisterMethod("dispose", this);
    }
  }

  @Override
  public void close() {
    dispose();
  }

  public DataNet onConnect(Runnable handler) {
    this.connectHandler = handler;
    return this;
  }

  public DataNet onDisconnect(Runnable handler) {
    this.disconnectHandler = handler;
    return this;
  }

  public DataNet onError(ErrorHandler handler) {
    this.errorHandler = handler;
    return this;
  }

  /** Subscribe to JSON-compatible messages on a channel. */
  public DataNet subscribe(String channel, MessageHandler handler) {
    channel = Protocol.requireChannel(channel);
    Objects.requireNonNull(handler, "handler");
    boolean first = !hasHandlers(channel);
    messageHandlers.computeIfAbsent(channel, ignored -> new CopyOnWriteArraySet<>()).add(handler);
    if (first) send(Protocol.subscribe(channel));
    return this;
  }

  /** Remove one JSON handler. The network subscription remains while another handler is present. */
  public DataNet unsubscribe(String channel, MessageHandler handler) {
    channel = Protocol.requireChannel(channel);
    Set<MessageHandler> handlers = messageHandlers.get(channel);
    if (handlers != null) {
      handlers.remove(handler);
      if (handlers.isEmpty()) messageHandlers.remove(channel, handlers);
    }
    unsubscribeIfUnused(channel);
    return this;
  }

  /** Remove every JSON handler for a channel. */
  public DataNet unsubscribe(String channel) {
    channel = Protocol.requireChannel(channel);
    messageHandlers.remove(channel);
    unsubscribeIfUnused(channel);
    return this;
  }

  /** Subscribe to binary messages on a channel. */
  public DataNet subscribeBinary(String channel, BinaryMessageHandler handler) {
    channel = Protocol.requireChannel(channel);
    Objects.requireNonNull(handler, "handler");
    boolean first = !hasHandlers(channel);
    binaryHandlers.computeIfAbsent(channel, ignored -> new CopyOnWriteArraySet<>()).add(handler);
    if (first) send(Protocol.subscribe(channel));
    return this;
  }

  public DataNet unsubscribeBinary(String channel, BinaryMessageHandler handler) {
    channel = Protocol.requireChannel(channel);
    Set<BinaryMessageHandler> handlers = binaryHandlers.get(channel);
    if (handlers != null) {
      handlers.remove(handler);
      if (handlers.isEmpty()) binaryHandlers.remove(channel, handlers);
    }
    unsubscribeIfUnused(channel);
    return this;
  }

  public DataNet unsubscribeBinary(String channel) {
    channel = Protocol.requireChannel(channel);
    binaryHandlers.remove(channel);
    unsubscribeIfUnused(channel);
    return this;
  }

  /** Publish any Processing JSON object, array, string, number, boolean, or null value. */
  public DataNet publish(String channel, Object data) {
    send(Protocol.publish(Protocol.requireChannel(channel), data));
    return this;
  }

  /** Publish raw bytes with the default application/octet-stream content type. */
  public DataNet publishBinary(String channel, byte[] data) {
    return publishBinary(channel, data, CONTENT_TYPE_BINARY, null);
  }

  /** Publish raw bytes with format and optional protocol metadata. */
  public DataNet publishBinary(String channel, byte[] data, String contentType, JSONObject metadata) {
    Objects.requireNonNull(data, "data");
    if (contentType == null || contentType.isBlank()) contentType = CONTENT_TYPE_BINARY;
    send(Protocol.publishBinary(Protocol.requireChannel(channel), data, contentType, metadata));
    return this;
  }

  /** Build and publish a zero-padded DMX frame of 1 to 512 channels. */
  public DataNet publishDmx(String channel, int[] values) {
    Objects.requireNonNull(values, "values");
    if (values.length > 512) throw new IllegalArgumentException("DMX data cannot exceed 512 channels");
    byte[] frame = new byte[512];
    for (int index = 0; index < values.length; index++) {
      frame[index] = (byte) Math.max(0, Math.min(255, values[index]));
    }
    return publishBinary(channel, frame, CONTENT_TYPE_DMX, null);
  }

  /** Query authoritative channel occupancy. The returned future completes off the animation thread. */
  public CompletableFuture<PresenceResult> getPresence(String channel) {
    channel = Protocol.requireChannel(channel);
    String token = jwt;
    if (token == null) {
      return CompletableFuture.failedFuture(
          new DataNetError("not_connected", "DataNet: connect before requesting presence"));
    }
    String projectId = projectIdFromJwt(token);
    String query = "channel=" + encode(channel)
        + (projectId == null ? "" : "&projectId=" + encode(projectId));
    URI endpoint = config.apiUrl.resolve("/presence?" + query);
    HttpRequest request = HttpRequest.newBuilder(endpoint)
        .timeout(Duration.ofSeconds(8))
        .header("Authorization", "Bearer " + token)
        .GET()
        .build();
    String requestedChannel = channel;
    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .handle((response, failure) -> {
          if (failure != null) {
            throw new DataNetError("presence_failed", "DataNet: presence request failed", unwrap(failure));
          }
          if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = response.body();
            try {
              JSONObject body = JSONObject.parse(response.body());
              detail = body.getString("error", detail);
            } catch (RuntimeException ignored) {
              // Preserve the response body when it is not JSON.
            }
            String code = response.statusCode() == 403 ? "presence_forbidden" : "presence_failed";
            throw new DataNetError(code,
                "DataNet: presence request failed (" + response.statusCode() + " " + detail + ")",
                requestedChannel, null, null, response.statusCode(), null, null);
          }
          JSONObject body = JSONObject.parse(response.body());
          int occupancy = body.getInt("occupancy", body.getInt("count", 0));
          List<String> members = new ArrayList<>();
          if (body.hasKey("members") && !body.isNull("members")) {
            JSONArray list = body.getJSONArray("members");
            for (int index = 0; index < list.size(); index++) {
              Object member = list.get(index);
              if (member instanceof String value) members.add(value);
            }
          }
          return new PresenceResult(occupancy, members);
        });
  }

  /** Query presence and invoke handlers on Processing's animation thread. */
  public DataNet getPresence(String channel, PresenceHandler handler) {
    Objects.requireNonNull(handler, "handler");
    getPresence(channel).whenComplete((presence, failure) -> {
      if (failure != null) dispatch(() -> notifyError(asDataNetError(failure, "presence_failed")));
      else dispatch(() -> handler.onPresence(presence));
    });
    return this;
  }

  private void authenticateAndOpen() {
    JSONObject body = new JSONObject().setString("apiKey", config.apiKey);
    if (config.deviceId != null) body.setString("deviceId", config.deviceId);
    if (config.clientId != null) body.setString("clientId", config.clientId);
    if (config.displayName != null) body.setString("deviceName", config.displayName);
    HttpRequest request = HttpRequest.newBuilder(config.apiUrl.resolve("/auth/token"))
        .timeout(config.requestTimeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build();
    httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .whenComplete((response, failure) -> {
          if (failure != null) {
            failConnect(new DataNetError("auth_failed", "DataNet: authentication failed", unwrap(failure)));
            return;
          }
          if (response.statusCode() < 200 || response.statusCode() >= 300) {
            failConnect(new DataNetError("auth_failed",
                "DataNet: authentication failed (HTTP " + response.statusCode() + ")",
                null, null, null, response.statusCode(), null, null));
            return;
          }
          try {
            String token = JSONObject.parse(response.body()).getString("token");
            if (token == null || token.isBlank()) throw new IllegalArgumentException("missing token");
            jwt = token;
            openSocket(token);
          } catch (RuntimeException exception) {
            failConnect(new DataNetError("auth_failed", "DataNet: authentication returned an invalid token", exception));
          }
        });
  }

  private void openSocket(String token) {
    final long generation;
    synchronized (connectionLock) {
      if (explicitlyDisconnected || fatalError) return;
      generation = ++activeGeneration;
      handledDisconnectGeneration = -1;
    }
    SocketListener listener = new SocketListener(generation);
    httpClient.newWebSocketBuilder()
        .connectTimeout(config.requestTimeout)
        .subprotocols("bearer", token)
        .buildAsync(config.wsUrl, listener)
        .whenComplete((socket, failure) -> {
          if (failure != null) handleSocketEnded(generation,
              new DataNetError("connection_failed", "DataNet: WebSocket connection failed", unwrap(failure)));
        });
  }

  private void handleText(long generation, String text) {
    if (generation != activeGeneration) return;
    final JSONObject message;
    try {
      message = JSONObject.parse(text);
    } catch (RuntimeException exception) {
      dispatch(() -> notifyError(new DataNetError("invalid_message", "DataNet: invalid JSON message", exception)));
      return;
    }
    String type = message.getString("type", "");
    String operation = message.getString("op", "");
    if ("connected".equals(type)) {
      connected = true;
      connecting = false;
      reconnectAttempts = 0;
      startHeartbeat();
      replaySubscriptions();
      CompletableFuture<DataNet> pending = connectFuture;
      if (pending != null && !pending.isDone()) pending.complete(this);
      dispatch(this::notifyConnected);
      return;
    }
    if ("error".equals(type)) {
      DataNetError error = Protocol.gatewayError(message);
      dispatch(() -> notifyError(error));
      if ("device_limit_reached".equals(error.code())) {
        fatalError = true;
        CompletableFuture<DataNet> pending = connectFuture;
        if (pending != null && !pending.isDone()) pending.completeExceptionally(error);
        WebSocket socket = webSocket;
        if (socket != null) socket.abort();
        handleSocketEnded(generation, null);
      }
      return;
    }
    if (!"message".equals(type) && !"pub".equals(operation)) return;
    String channel = message.getString("ch", "");
    String from = message.getString("from", "");
    long timestamp = message.getLong("ts", System.currentTimeMillis());
    if (message.getBoolean("bin", false)) {
      try {
        byte[] bytes = Protocol.decodeBinary(message.getString("b64", ""));
        JSONObject metadata = message.hasKey("meta") && !message.isNull("meta")
            ? message.getJSONObject("meta") : null;
        BinaryMessageMeta meta = new BinaryMessageMeta(
            channel,
            from,
            timestamp,
            message.getString("ct", CONTENT_TYPE_BINARY),
            message.getInt("bytes", bytes.length),
            metadata);
        Set<BinaryMessageHandler> handlers = binaryHandlers.get(channel);
        if (handlers != null) {
          for (BinaryMessageHandler handler : handlers) {
            dispatch(() -> handler.onMessage(bytes.clone(), meta));
          }
        }
      } catch (RuntimeException exception) {
        dispatch(() -> notifyError(new DataNetError("invalid_binary", "DataNet: invalid binary message", exception)));
      }
      return;
    }
    Object data = message.hasKey("d") ? message.get("d") : null;
    MessageMeta meta = new MessageMeta(channel, from, timestamp);
    Set<MessageHandler> handlers = messageHandlers.get(channel);
    if (handlers != null) {
      for (MessageHandler handler : handlers) dispatch(() -> handler.onMessage(data, meta));
    }
  }

  private void handleSocketEnded(long generation, DataNetError error) {
    boolean wasConnected;
    synchronized (connectionLock) {
      if (generation != activeGeneration || handledDisconnectGeneration == generation) return;
      handledDisconnectGeneration = generation;
      wasConnected = connected;
      connected = false;
      connecting = false;
      webSocket = null;
      stopHeartbeat();
    }
    if (error != null) dispatch(() -> notifyError(error));
    if (wasConnected) dispatch(this::notifyDisconnected);
    if (!explicitlyDisconnected && !fatalError) scheduleReconnect();
  }

  private void scheduleReconnect() {
    synchronized (connectionLock) {
      if (reconnectAttempts >= config.maxReconnectAttempts) {
        DataNetError error = new DataNetError("reconnect_failed", "DataNet: maximum reconnect attempts reached");
        if (connectFuture != null && !connectFuture.isDone()) connectFuture.completeExceptionally(error);
        dispatch(() -> notifyError(error));
        return;
      }
      reconnectAttempts++;
      long delay = Math.min(30_000L, 1_000L << Math.min(reconnectAttempts - 1, 5));
      connecting = true;
      reconnectTask = scheduler.schedule(this::authenticateAndOpen, delay, TimeUnit.MILLISECONDS);
    }
  }

  private void failConnect(DataNetError error) {
    connecting = false;
    CompletableFuture<DataNet> pending = connectFuture;
    if (pending != null && !pending.isDone()) pending.completeExceptionally(error);
    dispatch(() -> notifyError(error));
  }

  private void replaySubscriptions() {
    Set<String> channels = ConcurrentHashMap.newKeySet();
    channels.addAll(messageHandlers.keySet());
    channels.addAll(binaryHandlers.keySet());
    for (String channel : channels) send(Protocol.subscribe(channel));
  }

  private boolean hasHandlers(String channel) {
    Set<MessageHandler> json = messageHandlers.get(channel);
    Set<BinaryMessageHandler> binary = binaryHandlers.get(channel);
    return (json != null && !json.isEmpty()) || (binary != null && !binary.isEmpty());
  }

  private void unsubscribeIfUnused(String channel) {
    if (!hasHandlers(channel)) send(Protocol.unsubscribe(channel));
  }

  private void send(String text) {
    WebSocket socket = webSocket;
    if (socket != null && connected) socket.sendText(text, true);
  }

  private void startHeartbeat() {
    stopHeartbeat();
    heartbeatTask = scheduler.scheduleAtFixedRate(
        () -> send(Protocol.heartbeat()), 30, 30, TimeUnit.SECONDS);
  }

  private void stopHeartbeat() {
    cancel(heartbeatTask);
    heartbeatTask = null;
  }

  private void dispatch(Runnable callback) {
    if (parent == null) callback.run();
    else callbackQueue.add(callback);
  }

  private void notifyConnected() {
    Runnable handler = connectHandler;
    if (handler != null) handler.run();
  }

  private void notifyDisconnected() {
    Runnable handler = disconnectHandler;
    if (handler != null) handler.run();
  }

  private void notifyError(DataNetError error) {
    ErrorHandler handler = errorHandler;
    if (handler != null) handler.onError(error);
    else System.err.println(error.getMessage());
  }

  private static void cancel(ScheduledFuture<?> task) {
    if (task != null) task.cancel(false);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String projectIdFromJwt(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length < 2) return null;
      byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
      return JSONObject.parse(new String(decoded, StandardCharsets.UTF_8)).getString("pid", null);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Throwable unwrap(Throwable failure) {
    return failure.getCause() == null ? failure : failure.getCause();
  }

  private static DataNetError asDataNetError(Throwable failure, String fallbackCode) {
    Throwable cause = unwrap(failure);
    if (cause instanceof DataNetError error) return error;
    return new DataNetError(fallbackCode, "DataNet: " + cause.getMessage(), cause);
  }

  private final class SocketListener implements WebSocket.Listener {
    private final long generation;
    private final StringBuilder textBuffer = new StringBuilder();

    private SocketListener(long generation) {
      this.generation = generation;
    }

    @Override
    public void onOpen(WebSocket socket) {
      if (generation != activeGeneration || explicitlyDisconnected) {
        socket.abort();
        return;
      }
      webSocket = socket;
      socket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      synchronized (textBuffer) {
        textBuffer.append(data);
        if (last) {
          String complete = textBuffer.toString();
          textBuffer.setLength(0);
          handleText(generation, complete);
        }
      }
      socket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
      socket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
      handleSocketEnded(generation,
          explicitlyDisconnected ? null : new DataNetError("connection_closed",
              "DataNet: connection closed (" + statusCode + (reason.isBlank() ? "" : " " + reason) + ")"));
      return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable failure) {
      handleSocketEnded(generation,
          new DataNetError("connection_error", "DataNet: WebSocket error", failure));
    }
  }
}
