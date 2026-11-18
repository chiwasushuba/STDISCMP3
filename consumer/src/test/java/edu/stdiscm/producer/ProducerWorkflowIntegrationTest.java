package edu.stdiscm.producer;

import static org.junit.jupiter.api.Assertions.*;

import edu.stdiscm.consumer.MediaSettings;
import edu.stdiscm.consumer.UploadScheduler;
import edu.stdiscm.consumer.UploadService;
import edu.stdiscm.consumer.VideoStore;
import edu.stdiscm.protocol.MediaUploadGrpc;
import edu.stdiscm.protocol.UploadFrame;
import edu.stdiscm.protocol.UploadHeader;
import edu.stdiscm.protocol.UploadReply;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProducerWorkflowIntegrationTest {
  @TempDir Path temp;

  @Test
  void emptyFoldersUnsupportedFilesAndSubdirectoriesSucceedWithoutUploads() throws Exception {
    Path folder = Files.createDirectory(temp.resolve("input"));
    Files.writeString(folder.resolve("notes.txt"), "not video");
    Files.createDirectory(folder.resolve("nested.mp4"));
    try (var store = new VideoStore(settings());
        var scheduler = new UploadScheduler(1, 0)) {
      var server =
          NettyServerBuilder.forPort(0)
              .addService(new UploadService(scheduler, store, settings()))
              .build()
              .start();
      try {
        assertEquals(0, ProducerMain.run(config(server.getPort(), folder)));
        assertTrue(store.videos().isEmpty());
        assertEquals(0, scheduler.snapshot().completed());
      } finally {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void failedFileDoesNotPreventNextFileAndReturnsFailureExitCode() throws Exception {
    Path folder = Files.createDirectory(temp.resolve("input"));
    Files.write(folder.resolve("a-empty.mp4"), new byte[0]);
    byte[] bytes = {1, 2, 3, 4};
    Files.write(folder.resolve("b-valid.mp4"), bytes);
    try (var store = new VideoStore(settings());
        var scheduler = new UploadScheduler(1, 0)) {
      var server =
          NettyServerBuilder.forPort(0)
              .addService(new UploadService(scheduler, store, settings()))
              .build()
              .start();
      try {
        assertEquals(1, ProducerMain.run(config(server.getPort(), folder)));
        assertEquals(1, store.videos().size());
        assertArrayEquals(bytes, Files.readAllBytes(store.find(store.videos().get(0).id())));
      } finally {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void queueDropReturnsExitCodeTwoAndDoesNotRetry() throws Exception {
    Path folder = Files.createDirectory(temp.resolve("input"));
    Path file = Files.write(folder.resolve("video.mp4"), new byte[] {1, 2, 3, 4});
    try (var store = new VideoStore(settings());
        var scheduler = new UploadScheduler(1, 0)) {
      var server =
          NettyServerBuilder.forPort(0)
              .addService(new UploadService(scheduler, store, settings()))
              .build()
              .start();
      var channel =
          ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
      try {
        var admitted = new CompletableFuture<Void>();
        var requests =
            MediaUploadGrpc.newStub(channel)
                .upload(
                    new StreamObserver<UploadReply>() {
                      public void onNext(UploadReply reply) {
                        admitted.complete(null);
                      }

                      public void onError(Throwable error) {
                        admitted.completeExceptionally(error);
                      }

                      public void onCompleted() {}
                    });
        requests.onNext(
            UploadFrame.newBuilder()
                .setHeader(
                    UploadHeader.newBuilder()
                        .setFilename("held.mp4")
                        .setSize(4)
                        .setSha256(UploadClient.sha256(file)))
                .build());
        admitted.get(5, TimeUnit.SECONDS);
        assertEquals(2, ProducerMain.run(config(server.getPort(), folder)));
        assertEquals(1, scheduler.snapshot().dropped());
        assertTrue(store.videos().isEmpty());
        requests.onError(Status.CANCELLED.asRuntimeException());
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  private MediaSettings settings() {
    return new MediaSettings(9090, 1, 0, temp.resolve("output"), 1024, 30);
  }

  private ProducerMain.Config config(int port, Path folder) {
    return new ProducerMain.Config("127.0.0.1", port, 1, List.of(folder), 5);
  }
}
