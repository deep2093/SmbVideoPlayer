"""Run one opt-in native probe with a watchdog. Requires installed debug/test APKs.

On timeout, stops only the SkyWing preview and its instrumentation package.
The normal APK and app data are not replaced or cleared by this command.
"""
import argparse
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb") or "adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--name", default="native-probe")
    parser.add_argument("--profile", choices=["baseline", "pel1", "captured"], default="baseline")
    parser.add_argument("--gpu", choices=["true", "false"], required=True)
    parser.add_argument("--half", choices=["true", "false"], default="false")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--frames", type=int, default=720)
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--guard", type=int, default=0)
    parser.add_argument("--timeout", type=int, default=60)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.name):
        parser.error("name must contain only letters, digits, underscores or hyphens")
    if not (2 <= args.frames <= 7200 and 1 <= args.fps <= 60
            and 1 <= args.threads <= 32 and 0 <= args.guard <= 16
            and 10 <= args.timeout <= 600):
        parser.error("invalid frame count, fps, worker count, guard or timeout")
    args.out.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, "-s", args.serial]
    command = adb + ["shell", "am", "instrument", "-w", "-r"]
    options = {
        "class": "com.deepak.videolibrary.player.NativeSvpProbeTest",
        "nativeSvp": "true", "probeProfile": args.profile,
        "probeFrames": args.frames, "probeFps": args.fps,
        "probeGuardFrames": args.guard, "probeHalf": args.half,
        "probeGpu": args.gpu, "probeThreads": args.threads,
    }
    for key, value in options.items():
        command.extend(["-e", key, str(value)])
    command.append("com.deepak.videolibrary.preview.test/androidx.test.runner.AndroidJUnitRunner")
    log = args.out / f"{args.name}.log"
    start = time.monotonic()
    expired = False
    with log.open("w", encoding="utf-8") as output:
        output.write(" ".join(command) + "\n")
        output.flush()
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
        try:
            process.wait(timeout=args.timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            expired = True
            process.kill()
            process.wait(timeout=10)
            output.write(f"\nHOST_WATCHDOG_TIMEOUT {args.timeout}s\n")
        finally:
            if expired:
                for package in ("com.deepak.videolibrary.preview", "com.deepak.videolibrary.preview.test"):
                    subprocess.run(adb + ["shell", "am", "force-stop", package], check=True, timeout=15)
        output.write(f"\nHOST_DURATION {time.monotonic()-start:.2f}s exit={process.returncode}\n")
    native = subprocess.run(adb + ["logcat", "-d", "-v", "threadtime", "-s", "SvpProbe"],
                            capture_output=True, check=True, timeout=15)
    (args.out / f"{args.name}-native.log").write_bytes(native.stdout)
    result = log.read_text(encoding="utf-8")
    print(result)
    if expired:
        print("Watchdog stopped the diagnostic processes.")
    return 0 if (not expired and process.returncode == 0
                 and "OK (1 test)" in result and "Native SVP gpu=" in result) else 1


if __name__ == "__main__":
    sys.exit(main())
