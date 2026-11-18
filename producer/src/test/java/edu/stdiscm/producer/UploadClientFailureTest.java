package edu.stdiscm.producer;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadClientFailureTest {
  @TempDir Path temp;

  @Test
  void unavailableConsumerFailsWithinDeadline() throws Exception {
    int port;
    try (var reservation = new ServerSocket(0)) {
      port = reservation.getLocalPort();
    }
    var channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
    try {
      Path file = Files.writeString(temp.resolve("video.mp4"), "video bytes");
      assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(5),
          () -> {
            var error =
                assertThrows(
                    StatusRuntimeException.class, () -> new UploadClient(channel, 1).upload(file));
            assertTrue(
                java.util.Set.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED)
                    .contains(error.getStatus().getCode()));
          });
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
