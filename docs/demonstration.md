# Demonstration guide

## Local recording

[The local demo recording](demo-local.mp4) uses original synthetic videos.
It demonstrates gallery previews, short clips, full playback, searching, keyboard
access, playback errors, concurrent upload updates, mobile layout, and reconnection.
The recording was made on a single Windows host.

## Run locally

1. Build both JARs with the [README instructions](../README.md#build).
2. In one terminal, start the consumer and open [the gallery](http://localhost:8080).

   ```text
   java -jar consumer/target/consumer-1.0.0.jar --media.consumers=2 --media.queue-capacity=2 --media.output=.evidence/demo-uploads
   ```

3. In a second terminal, copy the included recording into a producer folder and
   upload it. In PowerShell, run the following from the repository root.

   ```powershell
   New-Item -ItemType Directory -Force .evidence/demo-input
   Copy-Item docs/demo-local.mp4 .evidence/demo-input/
   java -jar producer/target/producer-1.0.0.jar --p 1 --folder .evidence/demo-input
   ```

   On Linux/macOS, use `mkdir -p .evidence/demo-input` and
   `cp docs/demo-local.mp4 .evidence/demo-input/`, then run the same Java command.
4. Look for `RESULT stored=1 dropped=0 failed=0` in the producer terminal.
   Hover over the new gallery tile to preview it, then select it for full playback.
   Stop the consumer with Ctrl+C. Restart it with the same output folder to
   see the saved upload again.

To generate separate 12-second, 2-second, and deliberately unplayable demo
fixtures, run `python scripts/generate-fixtures.py` with ffmpeg on your PATH.
The script creates three separate producer folders under `.evidence/fixtures/`.

## Run on two VMs

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

Use a trusted private VM network. Keep any unrelated private files out of the
screen recording.
