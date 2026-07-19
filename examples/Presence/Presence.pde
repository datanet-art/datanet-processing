import art.datanet.*;

String API_KEY = "ak_your_project_key";
String CHANNEL = "project.your-project-id.demo";

DataNet datanet;
int occupancy;
int lastCheck;

void setup() {
  size(600, 320);
  textAlign(CENTER, CENTER);
  datanet = new DataNet(this, API_KEY);
  datanet.onError(error -> println(error.code() + ": " + error.getMessage()));
  datanet.onConnect(() -> refreshPresence());
  datanet.connect();
}

void draw() {
  background(18);
  fill(240);
  textSize(22);
  text(occupancy + " connected device" + (occupancy == 1 ? "" : "s"), width / 2, height / 2);

  if (datanet.isConnected() && millis() - lastCheck > 3000) refreshPresence();
}

void refreshPresence() {
  lastCheck = millis();
  datanet.getPresence(CHANNEL, presence -> occupancy = presence.occupancy());
}
