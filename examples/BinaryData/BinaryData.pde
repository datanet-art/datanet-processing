import art.datanet.*;

String API_KEY = "ak_your_project_key";
String CHANNEL = "project.your-project-id.lights";

DataNet datanet;
int[] dmx = new int[512];

void setup() {
  size(720, 240);
  datanet = new DataNet(this, API_KEY);
  datanet.onError(error -> println(error.code() + ": " + error.getMessage()));
  datanet.connect();
}

void draw() {
  background(18);
  int level = constrain((int) map(mouseX, 0, width, 0, 255), 0, 255);
  dmx[0] = level;
  dmx[1] = 255 - level;
  dmx[2] = 160;

  fill(dmx[0], dmx[1], dmx[2]);
  rect(0, 0, width, height);
  datanet.publishDmx(CHANNEL, dmx);
}
