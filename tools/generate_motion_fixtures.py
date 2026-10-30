"""Generate synthetic device-test inputs; requires ffmpeg on PATH."""
from pathlib import Path
import subprocess

output = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/motion-test"
output.mkdir(parents=True, exist_ok=True)
for rate in (24, 28, 30):
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi",
        "-i", f"testsrc2=size=640x360:rate={rate}", "-t", "12",
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-an",
        "-y", str(output / f"{rate}.mp4"),
    ], check=True)
