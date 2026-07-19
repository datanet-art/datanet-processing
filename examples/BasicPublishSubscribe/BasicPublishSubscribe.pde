import art.datanet.*;
import processing.data.JSONObject;

// Replace these two values with your DataNet project API key and channel.
String API_KEY = "ak_your_project_key";
String CHANNEL = "project.your-project-id.demo";

DataNet datanet;
float remoteX;
float remoteY;
boolean online;

void setup() {
  size(720, 480);

  datanet = new DataNet(this, DataNetConfig.builder(API_KEY)
    .displayName("DataNet's Processing Sketch")
    .build());

  datanet.onConnect(() -> online = true);
  datanet.onDisconnect(() -> online = false);
  datanet.onError(error -> println(error.code() + ": " + error.getMessage()));

  datanet.subscribe(CHANNEL, (data, meta) -> {
    if (data instanceof JSONObject point) {
      remoteX = point.getFloat("x", remoteX);
      remoteY = point.getFloat("y", remoteY);
    }
  });

  datanet.connect();
}

void draw() {
  background(18);
  fill(online ? color(92, 230, 162) : color(255, 184, 92));
  circle(remoteX, remoteY, 36);
  fill(240);
  text(online ? "Connected — move your mouse" : "Connecting…", 20, 30);
}

void mouseMoved() {
  JSONObject point = new JSONObject();
  point.setFloat("x", mouseX);
  point.setFloat("y", mouseY);
  datanet.publish(CHANNEL, point);
}
