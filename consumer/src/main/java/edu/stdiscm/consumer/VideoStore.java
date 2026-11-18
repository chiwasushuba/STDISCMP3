package edu.stdiscm.consumer;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class VideoStore implements AutoCloseable {
  public static final Set<String> EXTENSIONS = Set.of("mp4", "webm", "m4v", "mov", "ogv");
  private final Path root;
  private final Path staging;
  private final FileChannel lockChannel;
  private final FileLock lock;

  public VideoStore(MediaSettings settings) throws IOException {
    root = settings.output().toAbsolutePath().normalize();
    Files.createDirectories(root);
    staging = root.resolve(".partial");
    if (Files.isSymbolicLink(staging)) {
      throw new IOException("Staging directory must not be a symbolic link");
    }
    Files.createDirectories(staging);
    lockChannel =
        FileChannel.open(
            root.resolve(".consumer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = lockChannel.tryLock();
    } catch (RuntimeException | IOException e) {
      lockChannel.close();
      throw e;
    }
    if (acquired == null) {
      lockChannel.close();
      throw new IOException("Another consumer owns this output folder");
    }
    lock = acquired;
    try (var files = Files.list(staging)) {
      for (Path file : files.toList()) {
        if (file.getFileName().toString().endsWith(".part")) {
          Files.delete(file);
        }
      }
    } catch (IOException e) {
      close();
      throw e;
    }
  }

  public static String extension(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
  }

  public static void validateName(String name) {
    if (name.isBlank()
        || name.length() > 160
        || name.equals(".")
        || name.equals("..")
        || name.chars().anyMatch(c -> c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0)
        || !EXTENSIONS.contains(extension(name))) {
      throw new IllegalArgumentException(
          "Use a plain video filename (mp4, webm, m4v, mov, ogv), max 160 characters");
    }
  }

  public Path temporary(String id) {
    return staging.resolve(id + ".part");
  }

  public String publish(Path temporary, String id, String name) throws IOException {
    // The UUID prevents same-name overwrites; one atomic rename publishes only complete files.
    String stored = id + "_" + name;
    Files.move(temporary, root.resolve(stored), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    return stored;
  }

  public List<Video> videos() throws IOException {
    try (var paths = Files.list(root)) {
      return paths
          .filter(this::isVideo)
          .map(
              path -> {
                try {
                  String id = path.getFileName().toString();
                  return new Video(
                      id,
                      id.substring(37),
                      Files.size(path),
                      Files.getLastModifiedTime(path).toInstant());
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              })
          .sorted(Comparator.comparing(Video::uploadedAt).reversed())
          .toList();
    }
  }

  private boolean isVideo(Path path) {
    String name = path.getFileName().toString();
    if (name.length() < 38
        || name.charAt(36) != '_'
        || !EXTENSIONS.contains(extension(name))
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    try {
      return UUID.fromString(name.substring(0, 36)).toString().equals(name.substring(0, 36));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  public Path find(String id) {
    Path path = root.resolve(id).normalize();
    return root.equals(path.getParent()) && isVideo(path) ? path : null;
  }

  @Override
  public void close() throws IOException {
    try {
      lock.release();
    } finally {
      lockChannel.close();
    }
  }

  public record Video(String id, String name, long size, Instant uploadedAt) {}
}
