#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK r28c}"
build="$repo/app/build/vulkan-motion-probe"
output="$repo/app/src/androidTest/jniLibs/arm64-v8a"
mkdir -p "$build" "$output"
shaders="$ANDROID_NDK_HOME/shader-tools/linux-x86_64"
"$shaders/glslc" --target-env=vulkan1.1 -O -fshader-stage=compute "$repo/tools/vulkan-motion-probe.comp" -o "$build/motion.spv"
"$shaders/spirv-val" --target-env vulkan1.1 "$build/motion.spv"
python3 "$repo/tools/embed_spirv.py" "$build/motion.spv" "$build/vulkan_motion_spv.h"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang++" \
  -shared -fPIC -O3 -std=c++17 -static-libstdc++ -Wall -Wextra -Werror -Wno-missing-field-initializers \
  -I "$build" "$repo/tools/native_vulkan_probe.cpp" -lvulkan -Wl,-z,max-page-size=16384 \
  -o "$output/libskywing_vulkan_probe.so"
