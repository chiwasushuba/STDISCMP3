package edu.stdiscm.consumer;

import static edu.stdiscm.consumer.UploadSchedulerTest.await;
import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import edu.stdiscm.producer.UploadClient;
import edu.stdiscm.protocol.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class UploadIntegrationTest {
  @TempDir Path temp;
  VideoStore store;
  UploadScheduler scheduler;
  Server server;
  ManagedChannel channel;
  Path input;
  String hash;

  @BeforeEach
  void start() throws Exception {
    var settings = new MediaSettings(9090, 1, 1, temp.resolve("output"), 20 * 1024 * 1024, 1);
    store = new VideoStore(settings);
    scheduler = new UploadScheduler(1, 1);
    server =
        NettyServerBuilder.forPort(0)
            .maxInboundMessageSize(UploadService.CHUNK_BYTES + 1024)
            .addService(new UploadService(scheduler, store, settings))
            .build()
            .start();
    channel =
        ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
    input = Files.write(temp.resolve("sample.mp4"), new byte[] {1, 2, 3, 4});
    hash = UploadClient.sha256(input);
  }

  @AfterEach
  void stop() throws Exception {
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    scheduler.close();
    store.close();
  }

  Raw begin(String name, long size, String sha) {
    Raw raw = new Raw();
    raw.requests =
        MediaUploadGrpc.newStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS).upload(raw);
    raw.requests.onNext(
        UploadFrame.newBuilder()
            .setHeader(UploadHeader.newBuilder().setFilename(name).setSize(size).setSha256(sha))
            .build());
    return raw;
  }

  @Test
  void realClientStreamsLargeFileAndPreservesSameNames() throws Exception {
    byte[] bytes = new byte[5 * 1024 * 1024 + 19];
    new java.util.Random(31).nextBytes(bytes);
    Files.write(input, bytes);
    var client = new UploadClient(channel, 10);
    String first = client.upload(input);
    String second = client.upload(input);
    assertNotEquals(first, second);
    assertArrayEquals(bytes, Files.readAllBytes(store.find(first)));
    assertArrayEquals(bytes, Files.readAllBytes(store.find(second)));
    assertEquals(2, store.videos().size());
    assertEquals(2, scheduler.snapshot().completed());
    assertClean();
  }

  @Test
  void queueFullIsDroppedAndQueuedCancellationReleasesSlot() throws Exception {
    Raw active = begin("active.mp4", 4, hash);
    active.admitted();
    Raw queued = begin("queued.mp4", 4, hash);
    queued.admitted();
    assertEquals(1, scheduler.snapshot().queued());
    Raw excess = begin("drop.mp4", 4, hash);
    assertEquals(Status.Code.RESOURCE_EXHAUSTED, excess.code());
    queued.requests.onError(Status.CANCELLED.asRuntimeException());
    await(() -> scheduler.snapshot().queued() == 0);
    active.chunk(new byte[] {1, 2, 3, 4});
    active.requests.onCompleted();
    assertNotNull(active.done.get(5, TimeUnit.SECONDS));
    assertEquals(1, store.videos().size());
    assertEquals(1, scheduler.snapshot().dropped());
    assertClean();
  }

  @Test
  void interruptedActiveTransferCleansPartialAndNextUploadWorks() throws Exception {
    Raw raw = begin("cancel.mp4", 100000, hash);
    raw.admitted();
    raw.chunk(new byte[60000]);
    raw.requests.onError(Status.CANCELLED.asRuntimeException());
    await(() -> scheduler.snapshot().active() == 0);
    assertTrue(store.videos().isEmpty());
    assertClean();
    assertNotNull(new UploadClient(channel, 5).upload(input));
  }

  @Test
  void rejectsUnsafeNamesInvalidSizesAndChecksums() throws Exception {
    for (String name :
        new String[] {"../escape.mp4", "a/b.mp4", "a\\b.mp4", "x.txt", "", "x\n.mp4"}) {
      assertEquals(Status.Code.INVALID_ARGUMENT, begin(name, 4, hash).code(), name);
    }
    assertEquals(Status.Code.INVALID_ARGUMENT, begin("x.mp4", 0, hash).code());
    assertEquals(Status.Code.INVALID_ARGUMENT, begin("x.mp4", -1, hash).code());
    assertEquals(Status.Code.INVALID_ARGUMENT, begin("x.mp4", 21 * 1024 * 1024, hash).code());
    assertEquals(Status.Code.INVALID_ARGUMENT, begin("x.mp4", 4, "bad").code());
    assertTrue(store.videos().isEmpty());
    assertClean();
  }

  @Test
  void rejectsTruncationAndWrongChecksum() throws Exception {
    Raw shortFile = begin("short.mp4", 4, hash);
    shortFile.admitted();
    shortFile.chunk(new byte[] {1});
    shortFile.requests.onCompleted();
    assertEquals(Status.Code.DATA_LOSS, shortFile.code());
    Raw corrupt = begin("corrupt.mp4", 4, "0".repeat(64));
    corrupt.admitted();
    corrupt.chunk(new byte[] {1, 2, 3, 4});
    corrupt.requests.onCompleted();
    assertEquals(Status.Code.DATA_LOSS, corrupt.code());
    assertTrue(store.videos().isEmpty());
    assertClean();
  }

  @Test
  void rejectsExtraBytesRepeatedHeadersAndOversizedChunks() throws Exception {
    Raw extra = begin("extra.mp4", 1, hash);
    extra.admitted();
    extra.chunk(new byte[] {1, 2});
    extra.requests.onCompleted();
    assertEquals(Status.Code.INVALID_ARGUMENT, extra.code());
    Raw repeated = begin("repeat.mp4", 4, hash);
    repeated.admitted();
    repeated.requests.onNext(
        UploadFrame.newBuilder().setHeader(UploadHeader.newBuilder().setFilename("x.mp4")).build());
    assertEquals(Status.Code.INVALID_ARGUMENT, repeated.code());
    Raw large = begin("large.mp4", 100000, hash);
    large.admitted();
    large.chunk(new byte[65537]);
    assertEquals(Status.Code.INVALID_ARGUMENT, large.code());
    assertClean();
  }

  @Test
  void missingMetadataAndEmptyChunksFail() throws Exception {
    Raw missing = new Raw();
    missing.requests = MediaUploadGrpc.newStub(channel).upload(missing);
    missing.chunk(new byte[] {1});
    assertEquals(Status.Code.INVALID_ARGUMENT, missing.code());
    Raw empty = begin("empty.mp4", 4, hash);
    empty.admitted();
    empty.chunk(new byte[0]);
    assertEquals(Status.Code.INVALID_ARGUMENT, empty.code());
    assertClean();
  }

  @Test
  void idleUploadTimesOutAndReleasesWorker() throws Exception {
    Raw raw = begin("idle.mp4", 4, hash);
    raw.admitted();
    assertEquals(Status.Code.DEADLINE_EXCEEDED, raw.code());
    assertClean();
    assertNotNull(new UploadClient(channel, 5).upload(input));
  }

  @Test
  void clientDeadlineCleansPartialAndReleasesWorker() throws Exception {
    Raw raw = new Raw();
    raw.requests =
        MediaUploadGrpc.newStub(channel).withDeadlineAfter(300, TimeUnit.MILLISECONDS).upload(raw);
    raw.requests.onNext(
        UploadFrame.newBuilder()
            .setHeader(
                UploadHeader.newBuilder().setFilename("deadline.mp4").setSize(4).setSha256(hash))
            .build());
    raw.admitted();
    raw.chunk(new byte[] {1});
    assertEquals(Status.Code.DEADLINE_EXCEEDED, raw.code());
    assertClean();
    assertTrue(store.videos().isEmpty());
  }

  @Test
  void storageFailureIsReportedAndLeavesNoGalleryEntry() throws Exception {
    Path staging = temp.resolve("output/.partial");
    Files.delete(staging);
    Files.writeString(staging, "disk path unavailable");
    var error =
        assertThrows(
            io.grpc.StatusRuntimeException.class, () -> new UploadClient(channel, 5).upload(input));
    assertEquals(Status.Code.INTERNAL, error.getStatus().getCode());
    await(() -> scheduler.snapshot().active() == 0);
    assertTrue(store.videos().isEmpty());
    assertEquals(1, scheduler.snapshot().failed());
    Files.delete(staging);
    Files.createDirectory(staging);
    assertNotNull(new UploadClient(channel, 5).upload(input));
  }

  @Test
  void consumerShutdownCancelsActiveAndQueuedWithoutPublishing() throws Exception {
    Raw active = begin("active.mp4", 4, hash);
    active.admitted();
    Raw queued = begin("queued.mp4", 4, hash);
    queued.admitted();
    server.shutdownNow();
    // Netty may deliver CANCELLED or UNAVAILABLE depending on stream/connection teardown order.
    var shutdownCodes = java.util.Set.of(Status.Code.CANCELLED, Status.Code.UNAVAILABLE);
    assertTrue(shutdownCodes.contains(active.code()));
    assertTrue(shutdownCodes.contains(queued.code()));
    scheduler.close();
    assertTrue(store.videos().isEmpty());
    assertClean();
  }

  @Test
  void concurrentRpcAdmissionDropsExcessWithoutLeakingReservations() throws Exception {
    var callers = Executors.newFixedThreadPool(12);
    var gate = new CountDownLatch(1);
    var streams = new java.util.ArrayList<Raw>();
    try {
      var futures = new java.util.ArrayList<Future<Raw>>();
      for (int i = 0; i < 24; i++) {
        futures.add(
            callers.submit(
                () -> {
                  gate.await();
                  Raw raw = begin("race.mp4", 4, hash);
                  try {
                    raw.admitted();
                  } catch (ExecutionException e) {
                    assertEquals(
                        Status.Code.RESOURCE_EXHAUSTED,
                        Status.fromThrowable(e.getCause()).getCode());
                  }
                  return raw;
                }));
      }
      gate.countDown();
      for (var future : futures) streams.add(future.get(5, TimeUnit.SECONDS));
      assertEquals(1, scheduler.snapshot().active());
      assertEquals(1, scheduler.snapshot().queued());
      assertEquals(22, scheduler.snapshot().dropped());
      assertEquals(2, streams.stream().filter(raw -> !raw.done.isDone()).count());
      assertTrue(store.videos().isEmpty());
    } finally {
      streams.stream()
          .filter(raw -> !raw.done.isDone())
          .forEach(raw -> raw.requests.onError(Status.CANCELLED.asRuntimeException()));
      callers.shutdownNow();
    }
    assertClean();
    assertEquals(2, scheduler.snapshot().failed());
    assertNotNull(new UploadClient(channel, 5).upload(input));
  }

  @Test
  void queuedDeadlineReleasesCapacityWhileActiveUploadContinues() throws Exception {
    Raw active = begin("active.mp4", 4, hash);
    active.admitted();
    Raw queued = new Raw();
    queued.requests =
        MediaUploadGrpc.newStub(channel)
            .withDeadlineAfter(300, TimeUnit.MILLISECONDS)
            .upload(queued);
    queued.requests.onNext(
        UploadFrame.newBuilder()
            .setHeader(
                UploadHeader.newBuilder().setFilename("queued.mp4").setSize(4).setSha256(hash))
            .build());
    queued.admitted();
    assertEquals(Status.Code.DEADLINE_EXCEEDED, queued.code());
    await(() -> scheduler.snapshot().queued() == 0);
    Raw replacement = begin("replacement.mp4", 4, hash);
    replacement.admitted();
    active.chunk(new byte[] {1, 2, 3, 4});
    active.requests.onCompleted();
    assertNotNull(active.done.get(5, TimeUnit.SECONDS));
    replacement.chunk(new byte[] {1, 2, 3, 4});
    replacement.requests.onCompleted();
    assertNotNull(replacement.done.get(5, TimeUnit.SECONDS));
    assertClean();
    assertEquals(2, store.videos().size());
    assertEquals(1, scheduler.snapshot().failed());
    assertEquals(0, scheduler.snapshot().dropped());
  }

  @Test
  void invalidActiveUploadPromotesWaitingUpload() throws Exception {
    Raw active = begin("bad.mp4", 4, hash);
    active.admitted();
    Raw queued = begin("good.mp4", 4, hash);
    queued.admitted();
    active.chunk(new byte[] {9});
    active.requests.onCompleted();
    assertEquals(Status.Code.DATA_LOSS, active.code());
    queued.chunk(new byte[] {1, 2, 3, 4});
    queued.requests.onCompleted();
    assertNotNull(queued.done.get(5, TimeUnit.SECONDS));
    assertClean();
    assertEquals(1, store.videos().size());
    assertEquals(1, scheduler.snapshot().failed());
    assertEquals(1, scheduler.snapshot().completed());
  }

  @Test
  void emptyStreamAndUnsetFramesFailWithoutPublication() throws Exception {
    Raw empty = new Raw();
    empty.requests = MediaUploadGrpc.newStub(channel).upload(empty);
    empty.requests.onCompleted();
    assertEquals(Status.Code.INVALID_ARGUMENT, empty.code());
    Raw unset = new Raw();
    unset.requests = MediaUploadGrpc.newStub(channel).upload(unset);
    unset.requests.onNext(UploadFrame.getDefaultInstance());
    assertEquals(Status.Code.INVALID_ARGUMENT, unset.code());
    Raw noBytes = begin("no-bytes.mp4", 4, hash);
    noBytes.admitted();
    noBytes.requests.onCompleted();
    assertEquals(Status.Code.DATA_LOSS, noBytes.code());
    assertClean();
    assertTrue(store.videos().isEmpty());
    assertEquals(1, scheduler.snapshot().failed());
    assertNotNull(new UploadClient(channel, 5).upload(input));
  }

  @Test
  void exactChunkBoundaryAndSingleByteFramesPreserveBytes() throws Exception {
    byte[] bytes = new byte[UploadService.CHUNK_BYTES];
    new java.util.Random(42).nextBytes(bytes);
    Files.write(input, bytes);
    Raw exact = begin("UPPER.MP4", bytes.length, UploadClient.sha256(input));
    exact.admitted();
    exact.chunk(bytes);
    exact.requests.onCompleted();
    assertArrayEquals(bytes, Files.readAllBytes(store.find(exact.done.get(5, TimeUnit.SECONDS))));
    Raw tiny = begin("single-byte.mp4", bytes.length, UploadClient.sha256(input));
    tiny.admitted();
    tiny.chunk(new byte[] {bytes[0]});
    tiny.chunk(java.util.Arrays.copyOfRange(bytes, 1, bytes.length));
    tiny.requests.onCompleted();
    assertArrayEquals(bytes, Files.readAllBytes(store.find(tiny.done.get(5, TimeUnit.SECONDS))));
    assertClean();
  }

  @Test
  void completeBytesWithoutHalfCloseTimeOutInsteadOfPublishing() throws Exception {
    Raw stalled = begin("never-finished.mp4", 4, hash);
    stalled.admitted();
    stalled.chunk(new byte[] {1, 2, 3, 4});
    assertEquals(Status.Code.DEADLINE_EXCEEDED, stalled.code());
    assertClean();
    assertTrue(store.videos().isEmpty());
    assertEquals(1, scheduler.snapshot().failed());
    assertNotNull(new UploadClient(channel, 5).upload(input));
  }

  @Test
  void interruptingRealProducerWhileQueuedReleasesReservation() throws Exception {
    Raw active = begin("held.mp4", 4, hash);
    active.admitted();
    var caller = Executors.newSingleThreadExecutor();
    try {
      var uploading = caller.submit(() -> new UploadClient(channel, 5).upload(input));
      await(() -> scheduler.snapshot().queued() == 1);
      assertTrue(uploading.cancel(true));
      await(() -> scheduler.snapshot().queued() == 0);
      active.chunk(new byte[] {1, 2, 3, 4});
      active.requests.onCompleted();
      assertNotNull(active.done.get(5, TimeUnit.SECONDS));
      assertClean();
      assertEquals(1, scheduler.snapshot().failed());
      assertEquals(1, store.videos().size());
    } finally {
      caller.shutdownNow();
      assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  void assertClean() throws Exception {
    await(() -> scheduler.snapshot().active() == 0 && scheduler.snapshot().queued() == 0);
    try (var files = Files.list(temp.resolve("output/.partial"))) {
      assertEquals(0, files.count(), "Partial uploads must be removed");
    }
  }

  static class Raw implements StreamObserver<UploadReply> {
    StreamObserver<UploadFrame> requests;
    final CompletableFuture<Void> admission = new CompletableFuture<>();
    final CompletableFuture<String> done = new CompletableFuture<>();
    String stored;

    public void onNext(UploadReply reply) {
      if (reply.getState() == UploadReply.State.ADMITTED) {
        admission.complete(null);
      } else {
        stored = reply.getId();
      }
    }

    public void onError(Throwable error) {
      admission.completeExceptionally(error);
      done.completeExceptionally(error);
    }

    public void onCompleted() {
      done.complete(stored);
    }

    void admitted() throws Exception {
      admission.get(5, TimeUnit.SECONDS);
    }

    void chunk(byte[] bytes) {
      requests.onNext(UploadFrame.newBuilder().setChunk(ByteString.copyFrom(bytes)).build());
    }

    Status.Code code() throws Exception {
      ExecutionException error =
          assertThrows(ExecutionException.class, () -> done.get(5, TimeUnit.SECONDS));
      return Status.fromThrowable(error.getCause()).getCode();
    }
  }
}
