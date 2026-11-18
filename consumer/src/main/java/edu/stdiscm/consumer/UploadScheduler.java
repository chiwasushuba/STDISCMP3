package edu.stdiscm.consumer;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Admission and worker ownership share one monitor, making capacity checks atomic. */
public final class UploadScheduler implements AutoCloseable {
  public interface Job extends Runnable {
    void cancel();
  }

  private final int consumers;
  private final int capacity;
  private final ExecutorService workers;
  private final ArrayDeque<Job> waiting = new ArrayDeque<>();
  private final Set<Job> running = new HashSet<>();
  private boolean closed;
  private long completed;
  private long dropped;
  private long failed;

  public UploadScheduler(int consumers, int capacity) {
    if (consumers < 1 || consumers > 128 || capacity < 0 || capacity > 10000) {
      throw new IllegalArgumentException("c must be 1..128 and q must be 0..10000");
    }
    this.consumers = consumers;
    this.capacity = capacity;
    workers = Executors.newFixedThreadPool(consumers);
  }

  public synchronized boolean admit(Job job) {
    if (closed || (running.size() == consumers && waiting.size() == capacity)) {
      dropped++;
      return false;
    }
    if (running.size() < consumers) {
      start(job);
    } else {
      waiting.addLast(job);
    }
    return true;
  }

  private void start(Job job) {
    running.add(job);
    workers.execute(
        () -> {
          try {
            job.run();
          } finally {
            finished(job);
          }
        });
  }

  private synchronized void finished(Job job) {
    running.remove(job);
    if (!closed && !waiting.isEmpty()) {
      start(waiting.removeFirst());
    }
  }

  public synchronized void cancel(Job job) {
    if (waiting.remove(job)) {
      job.cancel();
    }
  }

  public synchronized void stored() {
    completed++;
  }

  public synchronized void failed() {
    failed++;
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(
        consumers, capacity, running.size(), waiting.size(), completed, dropped, failed);
  }

  @Override
  public void close() {
    java.util.List<Job> cancelled;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      cancelled = new java.util.ArrayList<>(waiting);
      cancelled.addAll(running);
      waiting.clear();
      workers.shutdownNow();
    }
    cancelled.forEach(Job::cancel);
    try {
      if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Upload workers did not terminate");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public record Snapshot(
      int consumers,
      int capacity,
      int active,
      int queued,
      long completed,
      long dropped,
      long failed) {}
}
