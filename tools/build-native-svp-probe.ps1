param(
    [string]$SourceDir = "$PSScriptRoot/../../open-svpflow",
    [string]$ToolchainRoot = "$PSScriptRoot/../../toolchains"
)
$ErrorActionPreference = 'Stop'
$SourceDir = (Resolve-Path $SourceDir).Path
$ToolchainRoot = (Resolve-Path $ToolchainRoot).Path
$revision = 'deadbad2201d77056173c05c2380758d88612019'
if ((git -C $SourceDir rev-parse HEAD) -ne $revision) { throw 'Unexpected open-svpflow revision' }
$patch = Join-Path $PSScriptRoot 'open-svpflow-android.patch'
git -C $SourceDir apply --reverse --check $patch 2>$null
if ($LASTEXITCODE -ne 0) {
    git -C $SourceDir apply --check $patch
    if ($LASTEXITCODE -ne 0) { throw 'Android patch cannot be applied' }
    git -C $SourceDir apply $patch
    if ($LASTEXITCODE -ne 0) { throw 'Android patch failed' }
}
$env:SKYWING_TOOLCHAIN_ROOT = $ToolchainRoot
$env:RUSTUP_HOME = Join-Path $ToolchainRoot 'rustup'
$env:CARGO_HOME = Join-Path $ToolchainRoot 'cargo'
$env:PATH = (Join-Path $env:CARGO_HOME 'bin') + ';' + $env:PATH
$wrapper = Join-Path $ToolchainRoot 'skywing-host-link.cmd'
@"
@echo off
python "$PSScriptRoot\native_host_link.py" %*
"@ | Set-Content $wrapper
$clang = Join-Path $ToolchainRoot 'android-ndk-r28c/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER = $wrapper
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $clang
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS = '-C link-arg=--target=aarch64-linux-android29 -C link-arg=-Wl,-z,max-page-size=16384'
cargo +1.98.1-x86_64-pc-windows-gnu build --manifest-path "$SourceDir/Cargo.toml" --target aarch64-linux-android --release --locked -p svpflow1 -p svpflow2 -p svpflow-capi
if ($LASTEXITCODE -ne 0) { throw 'Native engine build failed' }
$libraries = Join-Path $SourceDir 'target/aarch64-linux-android/release'
$output = Join-Path $PSScriptRoot '../app/src/androidTest/jniLibs/arm64-v8a'
New-Item -ItemType Directory -Force $output | Out-Null
foreach ($name in 'libopen_svpflow.so', 'libsvpflow1_vs.so', 'libsvpflow2_vs.so') {
    Copy-Item (Join-Path $libraries $name) $output
}
& $clang --target=aarch64-linux-android29 -shared -fPIC -O2 -DSKYWING_JNI -I "$SourceDir/crates/svpflow-capi/include" "$PSScriptRoot/native_svp_probe.c" -L $libraries -lopen_svpflow '-Wl,-z,max-page-size=16384' -o "$output/libskywing_svp_probe.so"
if ($LASTEXITCODE -ne 0) { throw 'JNI probe build failed' }
$appOutput = Join-Path $PSScriptRoot '../app/src/main/jniLibs/arm64-v8a'
New-Item -ItemType Directory -Force $appOutput | Out-Null
foreach ($name in 'libopen_svpflow.so', 'libsvpflow1_vs.so', 'libsvpflow2_vs.so') {
    Copy-Item (Join-Path $libraries $name) $appOutput
}
$clangCpp = Join-Path $ToolchainRoot 'android-ndk-r28c/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
& $clangCpp --target=aarch64-linux-android29 -shared -fPIC -O3 -std=c++17 -static-libstdc++ -Wall -Wextra -Werror -I "$SourceDir/crates/svpflow-capi/include" "$PSScriptRoot/../app/src/main/cpp/native_svp_stream.cpp" -L $libraries -lopen_svpflow -llog '-Wl,-z,max-page-size=16384' -o "$appOutput/libskywing_svp_stream.so"
if ($LASTEXITCODE -ne 0) { throw 'Live JNI bridge build failed' }
Write-Output 'Native app and opt-in test libraries built.'
