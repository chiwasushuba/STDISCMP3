package edu.stdiscm.consumer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UploadSchedulerTest {
  @Test
  void concurrentAdmissionHasExactBoundsAndDrops() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    var callers = Executors.newFixedThreadPool(16);
    try (var scheduler = new UploadScheduler(3, 5)) {
      var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 80; i++) {
        results.add(
            callers.submit(
                () ->
                    scheduler.admit(
                        new UploadScheduler.Job() {
                          public void run() {
                            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                            try {
                              release.await();
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            } finally {
                              active.decrementAndGet();
                            }
                          }

                          public void cancel() {
                            release.countDown();
                          }
                        })));
      }
      int admitted = 0;
      for (var result : results) {
        if (result.get(3, TimeUnit.SECONDS)) admitted++;
      }
      assertEquals(8, admitted);
      assertEquals(3, scheduler.snapshot().active());
      assertEquals(5, scheduler.snapshot().queued());
      assertEquals(72, scheduler.snapshot().dropped());
      release.countDown();
      await(() -> scheduler.snapshot().active() == 0);
      assertTrue(peak.get() <= 3);
      assertEquals(0, scheduler.snapshot().queued());
    } finally {
      release.countDown();
      callers.shutdownNow();
    }
  }

  @Test
  void zeroQueueDropsWhileWorkerBusyAndShutdownCancels() throws Exception {
    var gate = new CountDownLatch(1);
    var cancelled = new AtomicInteger();
    var scheduler = new UploadScheduler(1, 0);
    var job =
        new UploadScheduler.Job() {
          public void run() {
            try {
              gate.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }

          public void cancel() {
            cancelled.incrementAndGet();
            gate.countDown();
          }
        };
    assertTrue(scheduler.admit(job));
    assertFalse(scheduler.admit(job));
    scheduler.close();
    assertEquals(1, cancelled.get());
    assertFalse(scheduler.admit(job));
    assertEquals(0, scheduler.snapshot().active());
  }

  @Test
  void invalidCapacityRejected() {
    assertThrows(IllegalArgumentException.class, () -> new UploadScheduler(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new UploadScheduler(1, -1));
    assertThrows(IllegalArgumentException.class, () -> new UploadScheduler(129, 0));
    assertThrows(IllegalArgumentException.class, () -> new UploadScheduler(1, 10001));
  }

  @Test
  void waitingJobsRunInFifoOrderAfterCancellation() throws Exception {
    var gate = new CountDownLatch(1);
    var order = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
    try (var scheduler = new UploadScheduler(1, 4)) {
      UploadScheduler.Job first =
          new UploadScheduler.Job() {
            public void run() {
              try {
                gate.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }

            public void cancel() {
              gate.countDown();
            }
          };
      assertTrue(scheduler.admit(first));
      var jobs = new java.util.ArrayList<UploadScheduler.Job>();
      for (int i = 0; i < 4; i++) {
        int index = i;
        UploadScheduler.Job job =
            new UploadScheduler.Job() {
              public void run() {
                order.add(index);
              }

              public void cancel() {}
            };
        jobs.add(job);
        assertTrue(scheduler.admit(job));
      }
      scheduler.cancel(jobs.get(1));
      assertEquals(3, scheduler.snapshot().queued());
      gate.countDown();
      await(() -> scheduler.snapshot().active() == 0);
      assertEquals(java.util.List.of(0, 2, 3), order);
    } finally {
      gate.countDown();
    }
  }

  static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (!condition.getAsBoolean() && System.nanoTime() < until) {
      Thread.sleep(10);
    }
    assertTrue(condition.getAsBoolean(), "Condition did not become true within 8 seconds");
  }
}
