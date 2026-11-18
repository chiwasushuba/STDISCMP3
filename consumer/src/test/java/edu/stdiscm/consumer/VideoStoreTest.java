package edu.stdiscm.consumer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoStoreTest {
  @TempDir Path root;

  @Test
  void restartRetainsCompletedFilesCleansPartialsAndLocksOutput() throws Exception {
    var settings = new MediaSettings(9090, 1, 1, root, 1024, 30);
    String id = UUID.randomUUID().toString();
    String stored;
    try (var store = new VideoStore(settings)) {
      assertThrows(Exception.class, () -> new VideoStore(settings));
      Path pending = Files.writeString(store.temporary(id), "complete video");
      stored = store.publish(pending, id, "video.mp4");
      Files.writeString(store.temporary(UUID.randomUUID().toString()), "interrupted");
      assertNull(store.find("../video.mp4"));
      assertNull(store.find(".consumer.lock"));
      assertNull(store.find("/"));
    }
    try (var store = new VideoStore(settings)) {
      assertEquals(1, store.videos().size());
      assertEquals("complete video", Files.readString(store.find(stored)));
      try (var files = Files.list(root.resolve(".partial"))) {
        assertEquals(0, files.count());
      }
    }
  }
}
