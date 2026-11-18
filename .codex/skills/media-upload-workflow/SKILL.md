---
name: media-upload-workflow
description: Implement and verify STDISCMP3 Java gRPC media uploads, bounded producer-consumer queueing, and consumer playback against the assignment.
---

# Media upload workflow

Read the user's `~/.codex/AGENTS.md` forwarding shim and follow its canonical
agreement and `standards/engineering.md`. Apply project `AGENTS.md` and the
shared Java engineering skill. Keep this workflow specific to this assignment.

## Establish the next increment

Read `requirements.txt` and `.codex/plans/01-requirements.md`. Read the chosen
architecture in `.codex/plans/02-architecture.md`; if still pending, prepare
choices and obtain the user's decision before application scaffolding. Follow
`.codex/plans/03-implementation.md` and implement the next authorized milestone.
Inspect actual build manifests before choosing commands or dependency versions.

## Preserve the upload contract

Before transport or queue changes, document what occupies a queue slot, when it
is released, how in-flight streams are bounded, and what each acknowledgement
means. Keep nonblocking capacity admission atomic. A dropped upload must have no
final gallery entry or leaked reservation. Do not hide overload with retries.

Use separate source folders for the p producer workers and a fixed c-worker
processing policy. Stream large files rather than buffering whole videos.
Stage incomplete transfers separately, clean failed transfers, and publish only
completed files under generated safe names in the single final output folder.
Coordinate any shared protocol change before independent component edits.

## Verify at the affected boundary

- Queue changes: deterministic saturation with held workers, concurrent admission,
  exact drop outcomes, slot release on cancellation and failure, graceful shutdown.
- Transfer changes: real gRPC integration, byte-for-byte integrity, large streamed
  input, same-name files, interrupted uploads, invalid metadata, and safe paths.
- GUI changes: actual playable fixtures, ten-second hover limit, hover exit and
  re-entry, short clips, click playback, and responsive updates during uploads.
- Release evidence: repeat the p/c/q scenarios across two VMs; record addresses,
  configuration, observed results, and limitations without secrets.

Update requirement evidence and milestone status only from checks actually run.
Official instructor test cases are pending; label locally designed cases as
provisional. Treat duplicate detection and compression as separate bonus work.

For example, a queue-capacity change needs forced concurrent saturation and a
cleanup assertion; a successful single-file upload does not verify overload.
If a required VM or video codec is unavailable, complete independent checks and
report that precise gap instead of marking the milestone fully verified.
