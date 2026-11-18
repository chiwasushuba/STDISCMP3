package edu.stdiscm.producer;

import com.google.protobuf.ByteString;
import edu.stdiscm.protocol.MediaUploadGrpc;
import edu.stdiscm.protocol.UploadFrame;
import edu.stdiscm.protocol.UploadHeader;
import edu.stdiscm.protocol.UploadReply;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One caller thread per upload; readiness bounds gRPC outbound buffering. */
public final class UploadClient {
  private final ManagedChannel channel;
  private final int timeoutSeconds;

  public UploadClient(ManagedChannel channel, int timeoutSeconds) {
    if (timeoutSeconds < 1) {
      throw new IllegalArgumentException("Timeout must be positive");
    }
    this.channel = channel;
    this.timeoutSeconds = timeoutSeconds;
  }

  public String upload(Path file) throws IOException, InterruptedException {
    long size = Files.size(file);
    String checksum = sha256(file);
    Exchange exchange = new Exchange();
    var requests =
        MediaUploadGrpc.newStub(channel)
            .withDeadlineAfter(timeoutSeconds, TimeUnit.SECONDS)
            .upload(exchange);
    try {
      requests.onNext(
          UploadFrame.newBuilder()
              .setHeader(
                  UploadHeader.newBuilder()
                      .setFilename(file.getFileName().toString())
                      .setSize(size)
                      .setSha256(checksum))
              .build());
      exchange.admission.get(timeoutSeconds, TimeUnit.SECONDS);
      byte[] buffer = new byte[64 * 1024];
      try (var input = Files.newInputStream(file)) {
        int count;
        while ((count = input.read(buffer)) != -1) {
          exchange.awaitReady();
          requests.onNext(
              UploadFrame.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, count)).build());
        }
      }
      if (!exchange.result.isDone()) {
        requests.onCompleted();
      }
      return exchange.result.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      exchange.stream.cancel("Upload failed", e.getCause());
      throw Status.fromThrowable(e.getCause()).asRuntimeException();
    } catch (TimeoutException e) {
      exchange.stream.cancel("Upload timed out", e);
      throw Status.DEADLINE_EXCEEDED.withDescription("Upload timed out").asRuntimeException();
    } catch (IOException | InterruptedException | RuntimeException e) {
      exchange.stream.cancel("Producer stopped upload", e);
      throw e;
    }
  }

  public static String sha256(Path file) throws IOException {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(file)) {
        byte[] bytes = new byte[64 * 1024];
        int count;
        while ((count = input.read(bytes)) != -1) {
          if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Hashing interrupted");
          }
          digest.update(bytes, 0, count);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class Exchange implements ClientResponseObserver<UploadFrame, UploadReply> {
    private final CompletableFuture<Void> admission = new CompletableFuture<>();
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private final Object ready = new Object();
    private ClientCallStreamObserver<UploadFrame> stream;
    private String stored;

    @Override
    public void beforeStart(ClientCallStreamObserver<UploadFrame> requestStream) {
      stream = requestStream;
      stream.setOnReadyHandler(this::wake);
    }

    private void wake() {
      synchronized (ready) {
        ready.notifyAll();
      }
    }

    void awaitReady() throws InterruptedException, ExecutionException {
      synchronized (ready) {
        while (!stream.isReady() && !result.isDone()) {
          ready.wait(1000);
        }
        if (result.isDone()) {
          result.get();
        }
      }
    }

    @Override
    public void onNext(UploadReply reply) {
      if (reply.getState() == UploadReply.State.ADMITTED) {
        admission.complete(null);
      } else if (reply.getState() == UploadReply.State.STORED) {
        stored = reply.getId();
      }
    }

    @Override
    public void onError(Throwable error) {
      admission.completeExceptionally(error);
      result.completeExceptionally(error);
      wake();
    }

    @Override
    public void onCompleted() {
      if (stored == null) {
        onError(
            Status.DATA_LOSS.withDescription("No storage acknowledgement").asRuntimeException());
      } else {
        result.complete(stored);
        wake();
      }
    }
  }
}
