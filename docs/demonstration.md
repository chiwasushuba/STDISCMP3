# Demonstration guide

## Local recording

`demo-local.mp4` is a silent browser recording using original synthetic videos.
It demonstrates gallery previews, short clips, full playback, searching, keyboard
access, playback errors, concurrent upload updates, mobile layout, and reconnection.
The automated browser session ran on one Windows host. This recording does not
establish the assignment's separate-VM requirement or replace instructor cases.

## Two-VM recording still required

1. On both VMs, show Java version and build or copy the verified executable JARs.
2. On the consumer VM, start c=2, q=2 with a fresh output folder. Show the gRPC
   address on port 9090 and the gallery on port 8080.
3. On the producer VM, show two distinct folders and run p=2 against the actual
   consumer private IP. Keep the producer terminal and consumer gallery visible.
4. Show stored totals, new gallery entries, and matching SHA-256 values across
   the two VMs. On Windows use `Get-FileHash -Algorithm SHA256`; on Linux use
   `sha256sum`.
5. Hover over a clip longer than ten seconds and show that playback stops/reset
   at the limit. Hover a two-second clip and show its early end. Re-enter and leave
   a tile, then click it and play past ten seconds in the full player.
6. For visible overload, use c=1/q=0 and p>=3 with large supported videos on a
   sufficiently slow network. Show DROPPED outcomes and bounded consumer counts.
   Network timing is variable; the automated held-worker tests provide the exact
   deterministic saturation evidence. Do not claim every short-video run must drop.
7. Interrupt an upload, show no final entry for the interrupted partial transfer,
   then upload another video to establish that capacity was released.
8. Upload two same-name videos from distinct folders and show separate UUID
   filenames with intact content. Show an invalid media file's playback message.
9. Record actual p/c/q, counts, ports, OS/JDK, input sizes, errors, and output hashes.
   Add the instructor's official cases when they become available.

Use a trusted private VM network. Keep any unrelated private files out of the
screen recording.
