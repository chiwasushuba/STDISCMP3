package edu.stdiscm.consumer;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class GalleryHttpTest {
  @Autowired TestRestTemplate http;
  @Autowired VideoStore store;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    int port;
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    registry.add("media.grpc-port", () -> port);
    registry.add(
        "media.output",
        () -> Path.of("target", "http-test-" + UUID.randomUUID()).toAbsolutePath().toString());
  }

  @Test
  void servesGalleryRangesAndRejectsUnknownFiles() throws Exception {
    String id = UUID.randomUUID().toString();
    byte[] bytes = new byte[1024];
    new java.util.Random(12).nextBytes(bytes);
    Files.write(store.temporary(id), bytes);
    String saved = store.publish(store.temporary(id), id, "example.mp4");
    var page = http.getForEntity("/", String.class);
    assertEquals(HttpStatus.OK, page.getStatusCode());
    assertTrue(page.getBody().contains("Media library"));
    var videos = http.getForEntity("/api/videos", VideoStore.Video[].class);
    assertEquals(1, videos.getBody().length);
    assertEquals(saved, videos.getBody()[0].id());
    var status = http.getForEntity("/api/status", UploadScheduler.Snapshot.class);
    assertEquals(2, status.getBody().consumers());
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RANGE, "bytes=10-29");
    var range =
        http.exchange(
            "/api/videos/" + saved + "/content",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            byte[].class);
    assertEquals(HttpStatus.PARTIAL_CONTENT, range.getStatusCode());
    assertEquals("bytes 10-29/1024", range.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 10, 30), range.getBody());
    assertEquals("video/mp4", range.getHeaders().getContentType().toString());
    for (String spec : new String[] {"bytes=-5", "bytes=1019-", "bytes=1019-9999"}) {
      headers.set(HttpHeaders.RANGE, spec);
      var tail =
          http.exchange(
              "/api/videos/" + saved + "/content",
              HttpMethod.GET,
              new HttpEntity<>(headers),
              byte[].class);
      assertEquals(HttpStatus.PARTIAL_CONTENT, tail.getStatusCode(), spec);
      assertEquals("bytes 1019-1023/1024", tail.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
      assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 1019, 1024), tail.getBody());
    }
    var head =
        http.exchange(
            "/api/videos/" + saved + "/content", HttpMethod.HEAD, HttpEntity.EMPTY, byte[].class);
    assertEquals(HttpStatus.OK, head.getStatusCode());
    assertEquals(1024, head.getHeaders().getContentLength());
    assertNull(head.getBody());
    assertEquals(
        HttpStatus.NOT_FOUND,
        http.getForEntity("/api/videos/missing.mp4/content", String.class).getStatusCode());
    assertEquals(
        HttpStatus.NOT_FOUND,
        http.getForEntity("/api/videos/.consumer.lock/content", String.class).getStatusCode());
    headers.set(HttpHeaders.RANGE, "bytes=2000-3000");
    assertEquals(
        HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
        http.exchange(
                "/api/videos/" + saved + "/content",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class)
            .getStatusCode());
  }
}
