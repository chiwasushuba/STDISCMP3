"""Generate original, synthetic videos for demonstration; requires ffmpeg on PATH.

Usage: python scripts/generate-fixtures.py [ffmpeg-executable]
No media decoder or ffmpeg is needed by the Java application itself.
"""
import pathlib
import subprocess
import sys

ffmpeg = sys.argv[1] if len(sys.argv) > 1 else "ffmpeg"
base = pathlib.Path(".evidence/fixtures")
for worker, name, seconds, pattern in [
    ("producer-1", "twelve-seconds.mp4", 12, "testsrc2"),
    ("producer-2", "two-seconds.mp4", 2, "testsrc"),
]:
    target = base / worker / name
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", f"{pattern}=size=640x360:rate=24",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
        "-t", str(seconds), "-c:v", "libx264", "-pix_fmt", "yuv420p",
        "-preset", "fast", "-crf", "28", "-c:a", "aac",
        "-movflags", "+faststart", str(target),
    ], check=True)
    print(target)

invalid = base / "producer-3" / "unplayable.mp4"
invalid.parent.mkdir(parents=True, exist_ok=True)
invalid.write_bytes(b"Deliberately invalid video bytes for the playback error case.")
print(invalid)
