package edu.stdiscm.consumer;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MediaSettingsTest {
  @Test
  void configurationBoundsAreValidatedIncludingZeroQueue() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      Path output = Path.of("uploads");
      assertTrue(validator.validate(new MediaSettings(1, 1, 0, output, 1, 1)).isEmpty());
      assertTrue(
          validator
              .validate(new MediaSettings(65535, 128, 10000, output, Long.MAX_VALUE, 3600))
              .isEmpty());
      MediaSettings[] invalid = {
        new MediaSettings(0, 1, 0, output, 1, 1),
        new MediaSettings(65536, 1, 0, output, 1, 1),
        new MediaSettings(9090, 0, 0, output, 1, 1),
        new MediaSettings(9090, 129, 0, output, 1, 1),
        new MediaSettings(9090, 1, -1, output, 1, 1),
        new MediaSettings(9090, 1, 10001, output, 1, 1),
        new MediaSettings(9090, 1, 0, null, 1, 1),
        new MediaSettings(9090, 1, 0, output, 0, 1),
        new MediaSettings(9090, 1, 0, output, 1, 0),
        new MediaSettings(9090, 1, 0, output, 1, 3601)
      };
      for (var settings : invalid)
        assertFalse(validator.validate(settings).isEmpty(), settings.toString());
    }
  }
}
