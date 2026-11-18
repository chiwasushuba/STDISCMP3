package edu.stdiscm.producer;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProducerMain {
  private static final Set<String> EXTENSIONS = Set.of("mp4", "webm", "m4v", "mov", "ogv");

  public static void main(String[] args) {
    if (List.of(args).contains("--help")) {
      System.out.println(
          "Usage: java -jar producer-1.0.0.jar --host HOST --port 9090 --p N --folder DIR [--folder DIR ...] [--timeout-seconds 300]");
      return;
    }
    try {
      System.exit(run(Config.parse(args)));
    } catch (IllegalArgumentException | IOException e) {
      System.err.println("Configuration error: " + e.getMessage());
      System.exit(1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.err.println("Producer interrupted");
      System.exit(1);
    }
  }

  static int run(Config config) throws InterruptedException {
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress(config.host(), config.port()).usePlaintext().build();
    var workers = Executors.newFixedThreadPool(config.p());
    var stored = new AtomicInteger();
    var dropped = new AtomicInteger();
    var failed = new AtomicInteger();
    try {
      var client = new UploadClient(channel, config.timeoutSeconds());
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (Path folder : config.folders()) {
        futures.add(
            workers.submit(
                () -> {
                  try (var paths = Files.list(folder)) {
                    for (Path file :
                        paths
                            .filter(
                                p ->
                                    Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                            .sorted()
                            .toList()) {
                      String name = file.getFileName().toString();
                      int dot = name.lastIndexOf('.');
                      if (dot < 0
                          || !EXTENSIONS.contains(
                              name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                        System.out.println("SKIPPED " + file + " (unsupported extension)");
                        continue;
                      }
                      if (Thread.currentThread().isInterrupted()) {
                        return;
                      }
                      try {
                        String id = client.upload(file);
                        stored.incrementAndGet();
                        System.out.println("STORED " + file + " -> " + id);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failed.incrementAndGet();
                        return;
                      } catch (IOException | RuntimeException e) {
                        if (Status.fromThrowable(e).getCode() == Status.Code.RESOURCE_EXHAUSTED) {
                          dropped.incrementAndGet();
                          System.out.println("DROPPED " + file + " (queue full)");
                        } else {
                          failed.incrementAndGet();
                          System.err.println("FAILED " + file + ": " + e.getMessage());
                        }
                      }
                    }
                  } catch (IOException e) {
                    failed.incrementAndGet();
                    System.err.println("FAILED folder " + folder + ": " + e.getMessage());
                  }
                }));
      }
      for (var future : futures) {
        try {
          future.get();
        } catch (java.util.concurrent.ExecutionException e) {
          failed.incrementAndGet();
          System.err.println("Worker failed: " + e.getCause());
        }
      }
      System.out.printf(
          "RESULT stored=%d dropped=%d failed=%d%n", stored.get(), dropped.get(), failed.get());
      return failed.get() > 0 ? 1 : dropped.get() > 0 ? 2 : 0;
    } finally {
      workers.shutdownNow();
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
      workers.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  public record Config(String host, int port, int p, List<Path> folders, int timeoutSeconds) {
    public static Config parse(String[] args) throws IOException {
      String host = "127.0.0.1";
      int port = 9090;
      int count = 0;
      int timeout = 300;
      List<Path> folders = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < args.length; i += 2) {
        String key = args[i];
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("Missing value for " + key);
        }
        if (!key.equals("--folder") && !seen.add(key)) {
          throw new IllegalArgumentException("Repeated option " + key);
        }
        String value = args[i + 1];
        switch (key) {
          case "--host" -> host = value;
          case "--port" -> port = Integer.parseInt(value);
          case "--p" -> count = Integer.parseInt(value);
          case "--folder" -> folders.add(Path.of(value).toRealPath());
          case "--timeout-seconds" -> timeout = Integer.parseInt(value);
          default -> throw new IllegalArgumentException("Unknown option " + key);
        }
      }
      if (host.isBlank()
          || port < 1
          || port > 65535
          || count < 1
          || count > 128
          || timeout < 1
          || timeout > 3600
          || count != folders.size()) {
        throw new IllegalArgumentException(
            "Require --p 1..128, exactly p --folder values, valid host/port, timeout 1..3600");
      }
      if (new HashSet<>(folders).size() != count
          || folders.stream().anyMatch(p -> !Files.isDirectory(p) || !Files.isReadable(p))) {
        throw new IllegalArgumentException("Each producer needs a distinct readable folder");
      }
      return new Config(host, port, count, List.copyOf(folders), timeout);
    }
  }
}
