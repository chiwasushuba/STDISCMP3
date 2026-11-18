package edu.stdiscm.producer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProducerConfigTest {
  @TempDir Path temp;

  @Test
  void acceptsExactlyOneDistinctFolderPerWorker() throws Exception {
    Path second = Files.createDirectory(temp.resolve("other"));
    var result =
        ProducerMain.Config.parse(
            new String[] {
              "--p",
              "2",
              "--folder",
              temp.toString(),
              "--folder",
              second.toString(),
              "--host",
              "10.0.0.2"
            });
    assertEquals(2, result.p());
    assertEquals("10.0.0.2", result.host());
  }

  @Test
  void rejectsInvalidCountsOptionsAndFolderAliases() {
    String folder = temp.toString();
    String[][] invalid = {
      {},
      {"--p", "0"},
      {"--p", "129"},
      {"--p", "one"},
      {"--p"},
      {"--p", "2", "--folder", folder},
      {"--p", "2", "--folder", folder, "--folder", temp.resolve(".").toString()},
      {"--p", "1", "--folder", folder, "--port", "65536"},
      {"--p", "1", "--folder", folder, "--host", ""},
      {"--p", "1", "--folder", folder, "--timeout-seconds", "0"},
      {"--p", "1", "--folder", folder, "--p", "1"},
      {"--nope", "1"}
    };
    for (String[] args : invalid) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ProducerMain.Config.parse(args),
          java.util.Arrays.toString(args));
    }
  }

  @Test
  void missingAndFileFoldersFail() throws Exception {
    Path file = Files.writeString(temp.resolve("file"), "x");
    assertThrows(
        IllegalArgumentException.class,
        () -> ProducerMain.Config.parse(new String[] {"--p", "1", "--folder", file.toString()}));
    assertThrows(
        java.io.IOException.class,
        () ->
            ProducerMain.Config.parse(
                new String[] {"--p", "1", "--folder", temp.resolve("missing").toString()}));
  }
}
