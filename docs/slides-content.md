# Media upload presentation content

This is an editable slide outline, not an exported PowerPoint deck.

## 1. Java media upload service

Configurable producer workers upload videos through gRPC. The consumer saves
verified files and serves a browser gallery with previews and full playback.

Java 17 / Maven / Spring Boot / gRPC / browser HTML video.

## 2. Components and data flow

- `protocol`: shared protobuf header, chunks, admission, and storage replies.
- `producer`: p workers, one distinct source folder each, sequential files per worker.
- `consumer`: c active receive/validate/publish workers, q waiting uploads.
- Browser: HTTP gallery and byte-range playback on port 8080.
- Producers communicate with the consumer over gRPC on port 9090.

Source: protocol/src/main/proto/media.proto; ProducerMain; GrpcServer; GalleryController.

## 3. Bounded queue and overload

Waiting queue capacity is q. Active capacity is c. At most c+q jobs are admitted.
Admission reserves an active slot or FIFO waiting slot atomically. New jobs are
dropped when both are full. q=0 supports immediate rejection while workers are busy.

The server returns RESOURCE_EXHAUSTED for queue-full drops. Producers report
DROPPED and do not retry. ADMITTED reserves capacity. STORED confirms saved bytes.

Source: UploadScheduler.admit; UploadService.Session.onNext.

## 4. Producer and consumer synchronization

One monitor protects admission, the FIFO waiting deque, and running ownership.
A fixed thread pool executes c consumer workers. Each upload has a synchronized
terminal transition so cancellation and completion cannot both publish replies.

Manual gRPC flow control requests one chunk at a time. A bounded one-element
queue transfers chunks from network callbacks to workers. Browser polling runs
asynchronously and does not execute Java disk work on the UI thread.

Source: UploadScheduler; UploadService.Session; static/app.js.

## 5. Safe storage lifecycle

Validate metadata. Stream <=64 KiB chunks to a temporary file. Enforce declared
and maximum size. Check SHA-256 and exact length after half-close. Close the file.
Publish through one atomic rename under a generated UUID filename. Show it in the
gallery only after publication.

Failures and cancellations delete partial files. Idle streams time out. Restart
cleans orphan partials under an exclusive output-folder lock and retains videos.
UUID filenames preserve same-name uploads. Content deduplication is optional and
not implemented.

Source: UploadService.Session.run; VideoStore.

## 6. Browser interactions

Hover or keyboard focus starts a muted preview. Ten seconds or clip end stops and
resets it. Exit, blur, modal opening, and hidden tabs stop previews. Click or Enter
opens full playback. Escape closes it. Search filters saved filenames. The gallery
updates automatically. Unsupported media displays a recovery message.

Source: consumer/src/main/resources/static/app.js.

## 7. Verification evidence

The Java suite covers bounded concurrency, drops, q=0, large real gRPC transfer,
same-name preservation, malformed inputs, cancellation/deadlines, truncation,
checksums, storage failure, shutdown/restart, configuration, and HTTP byte ranges.

The browser suite covers hover limits, short clips, full playback, search,
keyboard controls, invalid media, live upload updates, narrow layouts, accessibility
checks, reduced motion, and reconnecting. See verification.md for actual totals.

## 8. Demonstration and remaining submission checks

Play demo-local.mp4 for the observed local browser behavior. Repeat with the
producer and consumer on separate VMs and record their addresses and file hashes.
Instructor test cases remain pending. The implementation includes queue-full
feedback, while duplicate-content detection and compression remain optional.

Source: docs/demonstration.md; docs/verification.md; requirements.txt.
