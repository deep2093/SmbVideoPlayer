"""Stage the PE import closure and provenance for the experimental mpv preview."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def sha256(file):
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def command(*arguments):
    return subprocess.check_output(arguments, encoding="utf-8", errors="replace")


def main():
    dll, bin_dir, output = map(Path, sys.argv[1:4])
    revision = sys.argv[4]
    system32 = Path(os.environ["SystemRoot"]) / "System32"
    available = {file.name.lower(): file for file in bin_dir.glob("*.dll")}
    dlls = {dll.name.lower(): dll}
    pending = [dll]
    while pending:
        imports = command(bin_dir / "objdump.exe", "-p", pending.pop())
        for name in re.findall(r"^\s*DLL Name: (\S+)", imports, re.MULTILINE):
            key = name.lower()
            if key in dlls:
                continue
            if key in available:
                dlls[key] = available[key]
                pending.append(available[key])
            elif not key.startswith(("api-ms-win-", "ext-ms-win-")) and not (system32 / name).is_file():
                raise RuntimeError(f"Unresolved native dependency: {name}")

    output.mkdir(parents=True, exist_ok=True)
    notices = output / "mpv-preview-notices"
    notices.mkdir()
    owners = {}
    packages = {}
    for file in sorted(dlls.values()):
        shutil.copy2(file, output / file.name)
        if file == dll:
            continue
        package = command("pacman", "-Qoq", f"/ucrt64/bin/{file.name}").strip()
        owners[file.name] = package
        if package in packages:
            continue
        package_info = command("pacman", "-Qi", package)
        (notices / f"{package}.txt").write_text(package_info, encoding="utf-8")
        source = bin_dir.parent / "share" / "licenses" / package.removeprefix("mingw-w64-ucrt-x86_64-")
        if source.is_dir():
            shutil.copytree(source, notices / source.name)
        packages[package] = command("pacman", "-Q", package).strip().split(maxsplit=1)[1]

    patch = Path(__file__).with_name("mpv-vapoursynth-output-preview.patch")
    manifest = {
        "mpvRevision": revision,
        "vapourSynthHeaders": "R73",
        "patchSha256": hashlib.sha256(patch.read_bytes().replace(b"\r\n", b"\n")).hexdigest(),
        "files": {file.name: sha256(file) for file in sorted(dlls.values())},
        "dllPackages": owners,
        "packages": packages,
    }
    (output / "MPV-PREVIEW-MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Staged {len(dlls)} native libraries from mpv and {len(packages)} MSYS2 packages")


if __name__ == "__main__":
    main()
