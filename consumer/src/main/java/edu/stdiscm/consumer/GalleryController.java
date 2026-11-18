package edu.stdiscm.consumer;

import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class GalleryController {
  private final VideoStore store;
  private final UploadScheduler scheduler;

  public GalleryController(VideoStore store, UploadScheduler scheduler) {
    this.store = store;
    this.scheduler = scheduler;
  }

  @GetMapping("/api/videos")
  public List<VideoStore.Video> videos() throws IOException {
    return store.videos();
  }

  @GetMapping("/api/status")
  public UploadScheduler.Snapshot status() {
    return scheduler.snapshot();
  }

  @GetMapping("/api/videos/{id}/content")
  public ResponseEntity<Resource> content(@PathVariable String id) {
    Path path = store.find(id);
    if (path == null) {
      throw new ResponseStatusException(NOT_FOUND);
    }
    String type =
        switch (VideoStore.extension(id)) {
          case "webm" -> "video/webm";
          case "ogv" -> "video/ogg";
          case "mov" -> "video/quicktime";
          default -> "video/mp4";
        };
    // Spring MVC supplies byte-range responses for seeking via ResourceHttpMessageConverter.
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noCache())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(type))
        .body(new FileSystemResource(path));
  }
}
