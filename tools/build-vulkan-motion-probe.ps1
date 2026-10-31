param([string]$Ndk = "$PSScriptRoot/../../toolchains/android-ndk-r28c")
$ErrorActionPreference = 'Stop'
$Ndk = (Resolve-Path $Ndk).Path
$build = Join-Path $PSScriptRoot '../app/build/vulkan-motion-probe'
$output = Join-Path $PSScriptRoot '../app/src/androidTest/jniLibs/arm64-v8a'
New-Item -ItemType Directory -Force $build, $output | Out-Null
& "$Ndk/shader-tools/windows-x86_64/glslc.exe" --target-env=vulkan1.1 -O -fshader-stage=compute "$PSScriptRoot/vulkan-motion-probe.comp" -o "$build/motion.spv"
if ($LASTEXITCODE -ne 0) { throw 'Shader compilation failed' }
& "$Ndk/shader-tools/windows-x86_64/spirv-val.exe" --target-env vulkan1.1 "$build/motion.spv"
if ($LASTEXITCODE -ne 0) { throw 'Shader validation failed' }
python "$PSScriptRoot/embed_spirv.py" "$build/motion.spv" "$build/vulkan_motion_spv.h"
if ($LASTEXITCODE -ne 0) { throw 'Shader embedding failed' }
& "$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe" --target=aarch64-linux-android29 -shared -fPIC -O3 -std=c++17 -static-libstdc++ -Wall -Wextra -Werror -Wno-missing-field-initializers -I $build "$PSScriptRoot/native_vulkan_probe.cpp" -lvulkan '-Wl,-z,max-page-size=16384' -o "$output/libskywing_vulkan_probe.so"
if ($LASTEXITCODE -ne 0) { throw 'Vulkan probe compilation failed' }
Write-Output 'Built opt-in Vulkan motion probe; normal playback is unchanged.'
