# Implementation and verification checkpoint

Updated 2026-11-18. Joshua authorized application completion and chose Option B.
No work is reserved for the user. There is no Git repository in this folder;
pre-existing requirements.txt and project extensions were preserved.

## Completed application milestones

- [x] Approved protocol / producer / consumer Maven modules.
- [x] Java 17, Maven 3.9.11 wrapper, pinned dependencies, generated protocol types.
- [x] Bidirectional streaming with metadata, <=64 KiB chunks, ADMITTED/STORED replies.
- [x] p distinct folder workers; c active receive/validate/publish consumer workers.
- [x] Atomic admission, q FIFO waiting slots, q=0, immediate drops, no retries.
- [x] Manual inbound flow control and outbound readiness with bounded buffering.
- [x] Size/checksum checks, safe filenames, UUIDs, atomic publication.
- [x] Cancellation, malformed messages, deadlines/idle timeout, storage failure,
  partial cleanup, shutdown, exclusive output lock, restart recovery.
- [x] Browser still frames, ten-second hover/focus previews, full playback, search,
  live updates, errors, offline feedback, responsive layouts.
- [x] Twenty passing Java tests, real gRPC/HTTP, formatting and self-review.
- [x] Ten passing browser scenarios; desktop/mobile screenshots visually inspected.
- [x] Source, executable JARs, build/run instructions, synthetic fixture generator.
- [x] Local browser demo converted to H.264 MP4 and decoded successfully.
- [x] Queue-full feedback bonus. Duplicate detection/compression remain optional.

## Remaining submission evidence

- [ ] Run and record on two separate VMs. VM OS/addresses were not supplied; no
  VMs were accessed. Follow docs/demonstration.md.
- [ ] Add instructor cases when supplied.
- [ ] Export docs/slides-content.md to PowerPoint. Required @oai/artifact-tool and
  load_workspace_dependencies are unavailable; no exported deck is claimed.

## Actual commands and outcomes

- `JAVA_HOME=C:/Program Files/Java/jdk-17; .\mvnw.cmd -B clean verify`: passed,
  20 tests, zero failures/errors/skips; formatting checked and JARs packaged.
- `npm run format:check`: passed.
- `npm run test:browser`: ten scenarios passed, zero page errors; no serious/critical
  axe findings or overflow at 1440x1000 and 390x844. Chromium 145, generated 12s/2s
  H.264/AAC MP4s and invalid media. Built CLI p=2: stored=2 dropped=0 failed=0.
- docs/demo-local.mp4: H.264 conversion and complete ffmpeg decode passed.

Resolved failures: PowerShell property quoting, inherited shade configuration,
valid shutdown status race, secondary-text contrast, Windows JAR lock during clean.
Affected checks passed afterward. Source locations: Maven POMs/wrapper, protocol/,
producer/, consumer/, scripts/, README.md, docs/, package files, AGENTS.md and plans.

Logs: .tools/verify-final.log and .tools/browser-check-final.log. Screenshots and
raw browser recordings: .evidence/browser/. These directories are ignored. Stop
the consumer before a Windows clean build. Resume with the remaining submission
checks above; do not repeat the architecture gate. See docs/verification.md.

Final packaged smoke: all ten browser checks passed again against the rebuilt
consumer JAR and bundled static resources (.tools/browser-packaged.log). The
temporary server was stopped. The final local demo records this packaged run.

Follow-up edge-case verification: 32 Java tests and 14 browser scenarios now pass;
no application defect was reproduced. Added concurrent RPC saturation, FIFO after
cancellation, queued deadlines/interruption, promotion after failure, malformed
and unfinished streams, exact chunk boundaries, producer exit codes, configuration
bounds, HTTP range variants, special filenames, empty-library and HTTP-error UI
states, and literal rendering of untrusted filename text. The final clean build
and packaged browser checks are recorded in `.tools/verify-edge-final.log` and
`.tools/browser-edge-final.log`; see docs/verification.md for detailed outcomes.
