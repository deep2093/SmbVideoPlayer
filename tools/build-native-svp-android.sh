#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
source_dir=${1:-"$repo/../open-svpflow"}
revision=deadbad2201d77056173c05c2380758d88612019
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK r28c}"
if [[ ! -d "$source_dir" ]]; then
  git clone https://github.com/Z1xus/open-svpflow.git "$source_dir"
  git -C "$source_dir" checkout --detach "$revision"
fi
[[ $(git -C "$source_dir" rev-parse HEAD) == "$revision" ]]
patch="$repo/tools/open-svpflow-android.patch"
if ! git -C "$source_dir" apply --reverse --check "$patch" 2>/dev/null; then
  git -C "$source_dir" apply --check "$patch"
  git -C "$source_dir" apply "$patch"
fi
rustup toolchain install 1.98.1 --profile minimal --target aarch64-linux-android
bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$bin/aarch64-linux-android29-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
cargo +1.98.1 build --manifest-path "$source_dir/Cargo.toml" --target aarch64-linux-android --release --locked -p svpflow1 -p svpflow2 -p svpflow-capi
libraries="$source_dir/target/aarch64-linux-android/release"
output="$repo/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$output"
cp "$libraries/libopen_svpflow.so" "$libraries/libsvpflow1_vs.so" "$libraries/libsvpflow2_vs.so" "$output/"
"$bin/aarch64-linux-android29-clang++" -shared -fPIC -O3 -std=c++17 -static-libstdc++ -Wall -Wextra -Werror \
  -I "$source_dir/crates/svpflow-capi/include" "$repo/app/src/main/cpp/native_svp_stream.cpp" \
  -L "$libraries" -lopen_svpflow -llog -Wl,-z,max-page-size=16384 -o "$output/libskywing_svp_stream.so"
