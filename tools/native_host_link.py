"""Link Rust's Windows-GNU build helpers using its bundled MinGW import libraries.

The Android NDK supplies LLD; no machine-wide Visual Studio install is required.
Only used by the opt-in native SVP feasibility build.
"""
import os
import pathlib
import subprocess
import sys

root = pathlib.Path(os.environ["SKYWING_TOOLCHAIN_ROOT"])
libs = root / "rustup/toolchains/1.98.1-x86_64-pc-windows-gnu/lib/rustlib/x86_64-pc-windows-gnu/lib/self-contained"
arguments = []
for argument in sys.argv[1:]:
    if argument in ("-fno-use-linker-plugin", "-nodefaultlibs", "-m64"):
        continue
    arguments.extend(argument[4:].split(",") if argument.startswith("-Wl,") else [argument])
linker = root / "android-ndk-r28c/toolchains/llvm/prebuilt/windows-x86_64/bin/ld.lld.exe"
sys.exit(subprocess.call([str(linker), "-m", "i386pep", "-L" + str(libs), str(libs / "crt2.o")] + arguments))
