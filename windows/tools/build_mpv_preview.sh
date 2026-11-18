#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
work=$(cygpath -u "$1")
output=$(cygpath -u "$2")
revision=e471496c56f0f49c133bd88b50aed62b845f1909
archive="$work/mpv.tar.gz"
source="$work/mpv-$revision"
curl_exe="$(cygpath -u "${SYSTEMROOT:-${SystemRoot:-C:/Windows}}")/System32/curl.exe"

download() {
    "$curl_exe" --fail --silent --show-error --location --retry 3 "$1" --output "$2"
    printf '%s  %s\n' "$3" "$2" | sha256sum --check
}

mkdir -p "$work/headers" "$work/pkgconfig"
download "https://codeload.github.com/mpv-player/mpv/tar.gz/$revision" "$archive" \
    562674ce7a0f6ac0681291037abc4c8595e0d1202d86d27eb341113eb7d984ba
tar -xf "$archive" -C "$work"
headers=https://raw.githubusercontent.com/vapoursynth/vapoursynth/R73/include
download "$headers/VapourSynth4.h" "$work/headers/VapourSynth4.h" \
    a73a23aa9ea8395f475644344cf7b60fbc9de29a571b9af131361265c09c8530
download "$headers/VSScript4.h" "$work/headers/VSScript4.h" \
    2c9497111c23784f9a2c3cf94007a1ee9abbfba47a1bb33a3e26eaf38c689b36

# libmpv dynamically loads the separately packaged VapourSynth R73 runtime.
cat > "$work/pkgconfig/vapoursynth-script.pc" <<EOF
Name: VapourSynth Script
Description: Headers for the dynamically loaded script library
Version: 73
Cflags: -I$(cygpath -m "$work/headers")
EOF
export PKG_CONFIG_PATH=$(cygpath -m "$work/pkgconfig")
sed 's/\r$//' "$repo/windows/tools/mpv-vapoursynth-output-preview.patch" > "$work/output.patch"
git -C "$source" apply --check "$work/output.patch"
git -C "$source" apply "$work/output.patch"

meson setup "$work/build" "$source" --buildtype=release \
    -Dlibmpv=true -Dcplayer=false -Dvapoursynth=enabled -Dd3d11=enabled -Dlua=enabled
meson compile -C "$work/build"
python "$repo/windows/tools/stage_mpv_preview.py" "$work/build/libmpv-2.dll" /ucrt64/bin "$output" "$revision"
