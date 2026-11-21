# Frame: Java gRPC media upload

Java producer–consumer app with a Spring Boot browser gallery. Producers
and the consumer are separate processes and can run on different VMs.

## Build

Install a current patched **JDK 17** (or newer). The Maven wrapper downloads Maven
3.9.11 and dependencies on the first build. No Node.js, database, broker, ffmpeg,
or paid service is required to build or run the application.

Windows PowerShell, from this folder:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17' # adjust to your JDK
.\mvnw.cmd clean verify
```

Linux/macOS: set `JAVA_HOME` to your JDK, then `sh ./mvnw clean verify`.
Stop a running consumer before rebuilding: Windows locks the executable JAR while
Java is using it.

Artifacts:

- `consumer/target/consumer-1.0.0.jar`: executable consumer and browser GUI.
- `producer/target/producer-1.0.0.jar`: executable producer.

Dependencies are pinned in the POMs: Spring Boot 3.5.16, gRPC 1.85.1, protobuf
4.36.2. Spring Boot manages its web dependencies; gRPC's BOM manages its transport.
The protobuf compiler generates the shared Java contract during the build.

## Consumer VM

```powershell
java -jar consumer/target/consumer-1.0.0.jar --media.consumers=2 --media.queue-capacity=4 --media.output=uploads --media.grpc-port=9090 --server.port=8080
```

Open **http://localhost:8080** on the consumer, or
`http://CONSUMER_PRIVATE_IP:8080` from another machine.

- `--media.consumers`: **c**, active upload workers, 1–128.
- `--media.queue-capacity`: **q**, waiting uploads, 0–10,000. With q=0, a busy
  consumer immediately drops new uploads.
- `--media.output`: one destination folder for completed videos.
- `--media.max-bytes`: maximum declared video size, default 1 GiB.
- `--media.idle-seconds`: active upload inactivity timeout, default 30 seconds.
- `--media.grpc-port` and `--server.port`: separate gRPC and HTTP listeners.

Counts appear in the gallery and `/api/status`. Session counters reset on restart;
completed videos remain visible. Hover or keyboard focus previews at most ten
seconds. Leaving the tile, reaching the end, opening the full player, or hiding
the tab stops previews. Click or press Enter to open full playback. Escape closes
the player and returns focus to its tile, even when new uploads appear while the
player is open. Touch users can tap directly for full playback.

## Producer VM

Prepare a **distinct folder for every producer worker**, then run:

```powershell
java -jar producer/target/producer-1.0.0.jar --host CONSUMER_PRIVATE_IP --port 9090 --p 2 --folder "C:\videos\producer-1" --folder "C:\videos\producer-2"
```

On Linux use paths such as `/home/student/videos/producer-1`. `--p` must match
the number of `--folder` arguments. Every worker scans its folder once in filename
order and uploads supported regular files sequentially. Workers run concurrently;
subdirectories and symbolic-link files are excluded. Unsupported extensions are
reported as skipped. Empty folders are valid. `--help` prints the syntax.

The default per-upload gRPC deadline is 300 seconds; change it with
`--timeout-seconds` (1–3600). Hashing uses a separate first pass through the source
file. Avoid changing a source file while uploading it; size/checksum validation
rejects a changed transfer.

Results distinguish **STORED**, **DROPPED**, and **FAILED**. There are no automatic
retries. Exit code 0 means no drops/failures, 1 means a configuration/transfer
failure, and 2 means queue drops without other failures. A lost final response
can leave a successfully saved file despite a producer failure; inspect the
gallery before rerunning to avoid duplicate content.

## Queue and synchronization

1. A producer sends filename, size, and SHA-256 metadata over bidirectional gRPC.
2. One synchronized admission operation reserves an active worker when fewer
   than c are active, otherwise a FIFO waiting slot when fewer than q are waiting.
   If both are full, the service returns `RESOURCE_EXHAUSTED` and drops the job.
3. `ADMITTED` acknowledges the reservation, **not** a saved video. Waiting jobs
   hold metadata and stream state; they do not stage complete videos on disk.
4. Each of the c workers requests one chunk at a time using manual inbound flow
   control and writes it to `.partial/<uuid>.part`. Application buffering is one
   chunk per job (64 KiB maximum); transport windows add bounded buffering per
   stream. Producer outbound readiness prevents whole-file buffering.
5. On half-close, the worker checks the exact byte count and SHA-256, closes the
   file, and atomically renames it to `<uuid>_<original-name>` in the output folder.
   Only then does the service return `STORED` and publish it in the gallery.
6. Completion, failure, cancellation, and idle timeout release worker ownership.
   Queued cancellation releases its waiting slot immediately. Workers delete
   partial files in `finally`; cleanup failures are logged. Shutdown cancels
   unfinished work. A folder lock prevents two consumers sharing the destination.
   Restart removes orphan `.part` files under that lock.

The queue bound is **q waiting jobs**, separate from **c active jobs**. Maximum
admitted unfinished jobs is c+q. Active includes receiving, validation, and disk
publication. Network callback threads are not consumer workers. Queue saturation
is deterministic in tests by holding active workers before submitting excess work.

## Video and network compatibility

Browser JavaScript reads gallery data and handles display, search, previews, and
playback. The Java producer and consumer handle gRPC uploads, queueing, validation,
and storage independently of the browser.

Accepted extensions: MP4, WebM, M4V, MOV, OGV. Browser support also depends on the
codecs inside the container. Use MP4 with H.264/AAC or browser-compatible WebM
for demonstrations. Files with unsupported codecs remain downloadable via the
content endpoint and display a playback error. The service checks transfer
integrity; it does not transcode or certify that arbitrary bytes form a video.
Use filenames within 160 characters and 218 UTF-8 bytes, including the extension.
This leaves room for the consumer's generated prefix in the stored filename.
HTTP byte ranges support video seeking and previews without full-file downloads.

Use a reachable private consumer address and permit TCP 9090 from the producer VM
and TCP 8080 from the viewing machine. The consumer binds both listeners to all
interfaces. This configuration uses plaintext gRPC/HTTP without
authentication and is intended for an isolated, trusted VM network.

## Demo

Watch the [recorded demo](docs/demo-local.mp4) or follow the
[demo steps](docs/demonstration.md) to upload the included MP4 from a fresh build.
The Maven build runs the JUnit tests for uploads, queueing, storage, and HTTP
media delivery.

## Source map

```text
protocol/    protobuf messages and generated gRPC service API
producer/    p folder workers, streaming client, CLI validation
consumer/    c upload workers, bounded admission, file storage, HTTP API
  src/main/resources/static/   browser gallery and video player
scripts/     optional demo fixture generator
docs/        demo recording and run instructions
```

Framework references: [Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[gRPC flow control](https://grpc.io/docs/guides/flow-control/),
[manual server requests](https://grpc.github.io/grpc-java/javadoc/io/grpc/stub/ServerCallStreamObserver.html).
