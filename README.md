# DataNet for Processing

Realtime pub/sub for networked sketches, visualizations, and installations. The library connects Processing 4 sketches to DataNet channels using the same protocol as the JavaScript, Python, and Arduino SDKs.

## Features

- JSON publish and subscribe with Processing `JSONObject` and `JSONArray`
- Binary messages plus DMX frame helpers
- Automatic authentication, heartbeat, reconnect, and subscription replay
- Structured gateway errors
- Authoritative channel presence
- Message and event callbacks delivered on Processing's animation thread
- No runtime dependencies beyond Processing 4 and Java 17

## Install

Once the library is accepted into Processing Contributions, install it from **Sketch → Import Library… → Manage Libraries…** and search for **DataNet**.

Until then, download `DataNet.zip` from the latest GitHub release, unzip it into your Processing sketchbook's `libraries` directory, and restart Processing. The final path should be:

```text
<sketchbook>/libraries/DataNet/library/DataNet.jar
```

## Quick start

```java
import art.datanet.*;
import processing.data.JSONObject;

String API_KEY = "ak_your_project_key";
String CHANNEL = "project.your-project-id.demo";
DataNet datanet;

void setup() {
  size(720, 480);

  datanet = new DataNet(this, DataNetConfig.builder(API_KEY)
    .displayName("DataNet's Processing Sketch")
    .build());

  datanet.onError(error -> println(error.code() + ": " + error.getMessage()));
  datanet.subscribe(CHANNEL, (data, meta) -> println(data));
  datanet.connect();
}

void mousePressed() {
  JSONObject message = new JSONObject();
  message.setFloat("x", mouseX);
  message.setFloat("y", mouseY);
  datanet.publish(CHANNEL, message);
}
```

Open **File → Examples → Contributed Libraries → DataNet** for complete JSON, binary/DMX, and presence examples.

## API overview

```java
datanet.connect();
datanet.disconnect();
datanet.isConnected();

datanet.publish(channel, jsonValue);
datanet.subscribe(channel, (data, meta) -> { });
datanet.unsubscribe(channel);

datanet.publishBinary(channel, bytes);
datanet.publishBinary(channel, bytes, "binary/custom", metadata);
datanet.subscribeBinary(channel, (bytes, meta) -> { });
datanet.publishDmx(channel, values);

datanet.getPresence(channel, presence -> println(presence.occupancy()));
```

`MessageMeta` includes `channel`, `from`, and `timestamp`. `BinaryMessageMeta` also includes `contentType`, byte count, and optional protocol metadata.

## Build and test

Processing 4 ships with Java 17. If Java 17 is available in your shell:

```sh
./gradlew clean test
./gradlew buildReleaseArtifacts
```

On macOS, you can use Processing's bundled JDK:

```sh
JAVA_HOME=/Applications/Processing.app/Contents/app/resources/jdk ./gradlew clean buildReleaseArtifacts
```

Release artifacts are written to `release/DataNet.zip`, `release/DataNet.txt`, and `release/DataNet.pdex`. To install a development build directly into the local sketchbook, run:

```sh
./gradlew deployToProcessingSketchbook
```

## Releasing

1. Update `prettyVersion` and increment the integer `version` in `release.properties`.
2. Run `./gradlew clean buildReleaseArtifacts`.
3. Tag the merged commit, for example `v0.1.0`, and push the tag.
4. GitHub Actions creates the release and attaches the `.zip`, `.txt`, and `.pdex` files required by Processing Contributions.
5. For the first release, submit the stable `DataNet.txt` URL to the Processing Contributions repository. Later releases are discovered through the incremented metadata.

## Compatibility

- Processing 4
- macOS, Windows, and Linux
- DataNet protocol: JSON and base64 binary WebSocket envelopes

## About

DataNet is developed and supported by [Studio Jordan Shaw](https://www.jordanshaw.com), a creative technology studio building tools for realtime, networked, and physical-digital work.

- [DataNet](https://datanet.art)
- [GitHub organization](https://github.com/datanet-art)
- [Cross-platform examples](https://github.com/datanet-art/datanet-examples)

## License

[MIT](LICENSE)
