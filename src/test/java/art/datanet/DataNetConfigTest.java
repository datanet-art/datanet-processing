package art.datanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class DataNetConfigTest {
  @Test
  void suppliesProcessingFriendlyDefaults() {
    DataNetConfig config = DataNetConfig.builder("ak_test").build();

    assertEquals("processing", config.clientId);
    assertEquals("DataNet's Processing Sketch", config.displayName);
    assertEquals(5, config.maxReconnectAttempts);
  }

  @Test
  void supportsCustomDeviceIdentity() {
    DataNetConfig config = DataNetConfig.builder("ak_test")
        .deviceId("gallery-screen-1")
        .displayName("DataNet's Gallery Screen")
        .build();

    assertEquals("gallery-screen-1", config.deviceId);
    assertEquals("DataNet's Gallery Screen", config.displayName);
  }

  @Test
  void rejectsMissingApiKeyAndNegativeReconnects() {
    assertThrows(IllegalArgumentException.class, () -> DataNetConfig.builder(" ").build());
    assertThrows(IllegalArgumentException.class,
        () -> DataNetConfig.builder("ak_test").maxReconnectAttempts(-1).build());
  }
}
