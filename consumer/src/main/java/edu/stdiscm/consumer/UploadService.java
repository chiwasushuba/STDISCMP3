package edu.stdiscm.consumer;

import edu.stdiscm.protocol.MediaUploadGrpc;
import edu.stdiscm.protocol.UploadFrame;
import edu.stdiscm.protocol.UploadHeader;
import edu.stdiscm.protocol.UploadReply;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UploadService extends MediaUploadGrpc.MediaUploadImplBase {
  public static final int CHUNK_BYTES = 64 * 1024;
  private static final Logger LOG = LoggerFactory.getLogger(UploadService.class);
  private final UploadScheduler scheduler;
  private final VideoStore store;
  private final MediaSettings settings;

  public UploadService(UploadScheduler scheduler, VideoStore store, MediaSettings settings) {
    this.scheduler = scheduler;
    this.store = store;
    this.settings = settings;
  }

  @Override
  public StreamObserver<UploadFrame> upload(StreamObserver<UploadReply> observer) {
    var output = (ServerCallStreamObserver<UploadReply>) observer;
    var session = new Session(output);
    output.disableAutoRequest();
    output.setOnCancelHandler(session::cancel);
    output.request(1); // Only metadata can arrive before atomic admission.
    return session;
  }

  private final class Session implements StreamObserver<UploadFrame>, UploadScheduler.Job {
    private final ServerCallStreamObserver<UploadReply> output;
    private final ArrayBlockingQueue<UploadFrame> chunks = new ArrayBlockingQueue<>(1);
    private final String id = UUID.randomUUID().toString();
    private UploadHeader header;
    private volatile boolean ended;
    private volatile boolean terminal;
    private boolean admitted;

    Session(ServerCallStreamObserver<UploadReply> output) {
      this.output = output;
    }

    @Override
    public synchronized void onNext(UploadFrame frame) {
      if (terminal) {
        return;
      }
      if (header == null) {
        if (!frame.hasHeader()) {
          fail(Status.INVALID_ARGUMENT.withDescription("First frame must be metadata"));
          return;
        }
        header = frame.getHeader();
        try {
          VideoStore.validateName(header.getFilename());
          if (header.getSize() <= 0
              || header.getSize() > settings.maxBytes()
              || !header.getSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid size or SHA-256 checksum");
          }
        } catch (IllegalArgumentException e) {
          fail(Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
          return;
        }
        admitted = scheduler.admit(this);
        if (!admitted) {
          fail(Status.RESOURCE_EXHAUSTED.withDescription("Queue full; video dropped (no retry)"));
          return;
        }
        output.onNext(
            UploadReply.newBuilder()
                .setState(UploadReply.State.ADMITTED)
                .setId(id)
                .setMessage("Upload admitted; waiting for a consumer worker")
                .build());
        return;
      }
      if (!frame.hasChunk()
          || frame.getChunk().isEmpty()
          || frame.getChunk().size() > CHUNK_BYTES) {
        fail(Status.INVALID_ARGUMENT.withDescription("Expected a video chunk of 1..65536 bytes"));
      } else if (!chunks.offer(frame)) {
        fail(Status.INTERNAL.withDescription("Inbound flow-control violation"));
      }
    }

    @Override
    public synchronized void onCompleted() {
      ended = true;
      if (header == null) {
        fail(Status.INVALID_ARGUMENT.withDescription("Missing metadata"));
      }
    }

    @Override
    public void onError(Throwable error) {
      cancel();
    }

    @Override
    public void cancel() {
      synchronized (this) {
        if (terminal) {
          return;
        }
        terminal = true;
      }
      scheduler.cancel(this);
      if (admitted) {
        scheduler.failed();
      }
    }

    private void fail(Status status) {
      // Caller holds this monitor; serialize terminal replies against completion and cancellation.
      if (terminal) {
        return;
      }
      terminal = true;
      scheduler.cancel(this);
      if (admitted) {
        scheduler.failed();
      }
      if (!output.isCancelled()) {
        output.onError(status.asRuntimeException());
      }
    }

    @Override
    public void run() {
      Path temporary = store.temporary(id);
      try {
        if (terminal) {
          return;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long received = 0;
        long lastChunk = System.nanoTime();
        output.request(1);
        try (var file =
            Files.newOutputStream(temporary, java.nio.file.StandardOpenOption.CREATE_NEW)) {
          while (!terminal) {
            UploadFrame frame = chunks.poll(100, TimeUnit.MILLISECONDS);
            if (frame != null) {
              if (frame.getChunk().size() > header.getSize() - received) {
                synchronized (this) {
                  fail(Status.INVALID_ARGUMENT.withDescription("More bytes than declared"));
                }
                return;
              }
              byte[] bytes = frame.getChunk().toByteArray();
              file.write(bytes);
              digest.update(bytes);
              received += bytes.length;
              lastChunk = System.nanoTime();
              output.request(1);
            } else if (ended) {
              break;
            } else if (System.nanoTime() - lastChunk
                > TimeUnit.SECONDS.toNanos(settings.idleSeconds())) {
              synchronized (this) {
                fail(Status.DEADLINE_EXCEEDED.withDescription("Upload idle timeout"));
              }
              return;
            }
          }
        }
        synchronized (this) {
          if (terminal) {
            return;
          }
          if (output.isCancelled()) {
            cancel();
            return;
          }
          if (received != header.getSize()
              || !HexFormat.of().formatHex(digest.digest()).equals(header.getSha256())) {
            fail(
                Status.DATA_LOSS.withDescription(
                    "Size or SHA-256 mismatch; partial upload removed"));
            return;
          }
          String stored = store.publish(temporary, id, header.getFilename());
          terminal = true;
          scheduler.stored();
          if (!output.isCancelled()) {
            output.onNext(
                UploadReply.newBuilder()
                    .setState(UploadReply.State.STORED)
                    .setId(stored)
                    .setMessage("Verified and stored")
                    .build());
            output.onCompleted();
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        synchronized (this) {
          fail(Status.UNAVAILABLE.withDescription("Consumer shutting down"));
        }
      } catch (IOException e) {
        LOG.warn("Storage failure for upload {}", id, e);
        synchronized (this) {
          fail(
              Status.INTERNAL.withDescription(
                  "Storage failed; check consumer disk and permissions"));
        }
      } catch (NoSuchAlgorithmException e) {
        synchronized (this) {
          fail(Status.INTERNAL.withDescription("SHA-256 unavailable"));
        }
      } catch (RuntimeException e) {
        LOG.warn("Transfer failure for upload {}", id, e);
        synchronized (this) {
          fail(Status.CANCELLED.withDescription("Transfer terminated"));
        }
      } finally {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException e) {
          LOG.error("Could not clean partial upload {}", id, e);
        }
      }
    }
  }
}
