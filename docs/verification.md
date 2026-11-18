# Observed verification

Date: 2026-11-18. One Windows host, Oracle JDK 17.0.9, Maven wrapper 3.9.11,
Chromium 145.0.7632.6 (Playwright 1.58.2), axe-core 4.11.1. Compilation targets
Java 17. Use a current patched JDK for deployment. requirements.txt was preserved.

## Automated results

| Check | Observed outcome |
|---|---|
| `mvnw.cmd -B clean verify` | PASS: 32 tests, zero failures/errors/skips, executable JARs |
| Spotless Java formatting | PASS during Maven validate |
| `npm run format:check` | PASS: HTML, CSS, JS, browser script, package manifest |
| `npm run test:browser` | PASS: 14 scenarios, zero page errors |
| Desktop 1440×1000 and mobile 390×844 | PASS: no horizontal overflow or serious/critical axe findings; screenshots inspected |
| Local p=2 / c=2 / q=2 | PASS: stored=2, dropped=0, failed=0; live gallery updated |
| Local recording | PASS: H.264 MP4 conversion and complete decode |

Java groups: ProducerConfigTest (3), UploadClientFailureTest (1), UploadSchedulerTest
(4), UploadIntegrationTest (18), VideoStoreTest (1), GalleryHttpTest (1),
MediaSettingsTest (1), ProducerWorkflowIntegrationTest (3).

The scheduler test holds workers while submitting 80 concurrent attempts with
c=3/q=5: exactly **8 admitted and 72 dropped**. It verifies q=0 and shutdown.
Real gRPC tests verify a 5 MiB+19-byte transfer twice with byte comparisons,
same-name preservation, active/waiting/dropped jobs, queued/active cancellation,
client deadline, idle timeout, unsafe names, invalid metadata/chunks, truncation,
extra bytes, checksum mismatch, storage failure/recovery, and shutdown. Further
tests cover unavailable servers, folder validation, output locking and restart.
The real Spring HTTP test verifies gallery/status, 206 byte ranges with exact
bytes and content type, unknown paths, and 416 unsatisfiable ranges.

The fourteen browser scenarios exercise actual video: ten-second cutoff, exit/re-entry,
two-second clip end, full playback beyond ten seconds, search, keyboard activation
and focus return, invalid media errors, concurrent CLI upload updates, desktop/mobile
layout and accessibility checks, reduced motion, and offline/reconnection. The
source fixtures are original generated H.264/AAC MP4s. The live-update test uploads
two additional videos each run.

## Expanded adversarial checks

The follow-up verification added 12 Java tests and four browser scenarios. No
application defect was reproduced; changes are tests and evidence only.

- Real simultaneous gRPC saturation: 24 calls with c=1/q=1 yielded exactly two
  reservations and 22 RESOURCE_EXHAUSTED drops. Cancellation released both
  reservations, removed partials, and allowed a subsequent successful upload.
- Queued deadline and interrupted real producer: freed the waiting slot while
  the active upload could still finish. Invalid active content promoted waiting
  work. Scheduler execution preserved FIFO order after cancelling a middle job.
- Empty RPC, unset frame, and metadata followed by no bytes: rejected without
  publication. Correct bytes without a client half-close: idle timeout, cleanup,
  and successful recovery. Exact 64 KiB and split 1/65535-byte chunks: exact bytes.
- Producer workflow: empty/unsupported/directory entries returned exit 0; an
  empty video failed but the next file stored and the run returned exit 1;
  overload returned exit 2 with exactly one drop and no retry.
- Configuration validation: minimum/maximum valid settings, q=0, and ten invalid
  configurations. HTTP suffix/open-ended/clamped ranges returned exact bytes;
  HEAD returned the file length without a body.
- Browser: uploaded and played `clip #50% & '雪'.mp4`; HTTP 500 retained the
  existing gallery and recovered; first-load empty library displayed correctly;
  simulated HTML-like filename metadata rendered literally without creating an
  image element or executing its handler. This last case uses an intercepted
  response to exercise UI defenses; backend filename validation remains intact.

Latest evidence: `.tools/verify-edge-baseline.log` (original 20-test clean build),
`.tools/verify-edge-tests.log` (intermediate expansion),
`.tools/verify-edge-final.log` (32 tests, clean packaging),
`.tools/browser-edge-final.log` (14 browser scenarios), and
`.evidence/browser/results.json` (14 checks, zero page errors). Desktop/mobile
screenshots were inspected again. Browser checks ran against a fresh packaged
consumer using `.evidence/edge-uploads`, with c=2/q=2, and six files were stored
by the fixture, live-update, and special-filename uploads. The temporary consumer
was stopped afterward. No separate VM or instructor evidence was added.

## Requirement evidence

| Requirement | Actual evidence/status |
|---|---|
| R1 gRPC uploads | PASS: streamed RPC and playable local fixtures |
| R2 p workers / separate folders | PASS: validation tests and p=2 CLI |
| R3 c workers | PASS: concurrency and held-worker bounds |
| R4 q bound / drops | PASS: exact concurrent admission, q=0, RPC rejection |
| R5 simultaneous uploads | PASS: overlapping RPCs and CLI workers |
| R6 one output folder | PASS: integrity, safe paths, UUID names, cleanup |
| R7 separate VMs | UNVERIFIED: only local processes ran |
| R8 GUI | PASS: Spring Boot browser gallery |
| R9 hover preview | PASS: actual ten-second cutoff/reset and short clips |
| R10 click playback | PASS: full playback continues beyond ten seconds |
| D1 source/build steps | PASS: source, wrapper, README, clean build |
| D2 demo video | PARTIAL: docs/demo-local.mp4; VM/instructor recording pending |
| D3 slides | PARTIAL: docs/slides-content.md; PowerPoint export pending |
| B1 queue-full feedback | PASS: RESOURCE_EXHAUSTED / DROPPED |
| B2 duplicate detection | OPTIONAL, not implemented |
| B3 compression | OPTIONAL, not implemented |

## Limits and resolved failures

Instructor cases, target VMs/network, other browsers, physical touch devices, and
arbitrary video codecs are unverified. Automated axe checks do not certify
accessibility. Local concurrency tests do not establish separate-VM execution.
Transfer checks establish integrity, not encoded media validity. This coursework
configuration uses plaintext listeners without authentication on a trusted network.

Inherited shade configuration initially prevented packaging; the producer now
overrides the transformer collection. Netty shutdown can report CANCELLED or
UNAVAILABLE; tests accept these failure statuses while requiring no publication
or leaked partials. Axe found secondary-text contrast issues; colors were fixed
and checks rerun. A Windows clean encountered the running JAR lock; after stopping
the consumer, the full clean build passed.

Presentation authoring is blocked by missing load_workspace_dependencies and
@oai/artifact-tool, required by the installed Presentations skill. No deck is
claimed. The eight-slide outline covers the required implementation topics.

Raw evidence: .tools/verify-final.log, .tools/browser-check-final.log,
.evidence/browser/results.json. Inspected screenshots: layout-1440.png,
layout-390.png, player.png, no-results.png, offline.png under .evidence/browser/.

After the clean build, all ten browser checks also passed against the final
executable consumer JAR with its bundled static resources (no source override).
That pass is logged in .tools/browser-packaged.log and recorded in demo-local.mp4.
The temporary verification consumer was stopped after the checks.
