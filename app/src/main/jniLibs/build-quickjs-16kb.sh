#!/usr/bin/env bash
# Rebuilds libquickjs.so of io.github.dokar3:quickjs-kt:1.0.0-alpha13 with 16 KB ELF page alignment
# and drops it next to this script, in app/src/main/jniLibs/<abi>/, where it replaces the 4 KB-aligned
# copy shipped in the AAR (see the pickFirsts rule in app/build.gradle.kts and README.md here).
#
# Same sources (upstream tag v1.0.0-alpha13 + its pinned submodules), same NDK (r26b, the one the
# AAR's lib was built with, per its .note.android.ident / .comment), same CMakeLists and same
# release flags as upstream's quickjs/build.gradle.kts. The only change is the linker option
# upstream itself added in v1.0.1: -Wl,-z,max-page-size=16384.
#
# Usage: app/src/main/jniLibs/build-quickjs-16kb.sh [work-dir]   (needs git, network, the Android SDK)
set -euo pipefail

UPSTREAM_URL="https://github.com/dokar3/quickjs-kt.git"
UPSTREAM_TAG="v1.0.0-alpha13"
UPSTREAM_COMMIT="d2fefdc451678d09bbef5526c3b00f12e41cd5cf"
QUICKJS_SUBMODULE_COMMIT="36911f0d3ab1a4c190a4d5cbe7c2db225a455389"
CVECTOR_SUBMODULE_COMMIT="774773d4cb1e66dd736e90e7f482d4f22af464c6"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${QJS_NDK:-$SDK/ndk/26.1.10909125}"
CMAKE_DIR="${QJS_CMAKE_DIR:-$SDK/cmake/3.22.1/bin}"
API=21
ABIS=(arm64-v8a armeabi-v7a)

OUT="$(cd "$(dirname "$0")" && pwd)"
WORK="${1:-$(mktemp -d)}"
SRC="$WORK/quickjs-kt"

[ -d "$NDK" ] || { echo "NDK not found: $NDK (sdkmanager 'ndk;26.1.10909125')" >&2; exit 1; }
[ -x "$CMAKE_DIR/cmake" ] || { echo "CMake not found: $CMAKE_DIR (sdkmanager 'cmake;3.22.1')" >&2; exit 1; }

if [ ! -d "$SRC/.git" ]; then
    git clone --quiet "$UPSTREAM_URL" "$SRC"
fi
git -C "$SRC" checkout --quiet "$UPSTREAM_COMMIT"
[ "$(git -C "$SRC" rev-parse "$UPSTREAM_TAG^{commit}")" = "$UPSTREAM_COMMIT" ] \
    || { echo "tag $UPSTREAM_TAG no longer points at $UPSTREAM_COMMIT" >&2; exit 1; }
git -C "$SRC" submodule update --init --quiet
[ "$(git -C "$SRC/quickjs/native/quickjs" rev-parse HEAD)" = "$QUICKJS_SUBMODULE_COMMIT" ] || exit 1
[ "$(git -C "$SRC/quickjs/native/c-vector" rev-parse HEAD)" = "$CVECTOR_SUBMODULE_COMMIT" ] || exit 1

# Upstream release flags: defaultConfig cFlags + release cFlags, CMAKE_BUILD_TYPE=MinSizeRel.
C_FLAGS="-fstrict-aliasing -g0 -Os -fomit-frame-pointer -DNDEBUG -fvisibility=hidden"
LINK_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

for abi in "${ABIS[@]}"; do
    build="$WORK/build-$abi"
    rm -rf "$build"
    "$CMAKE_DIR/cmake" -S "$SRC/quickjs/native" -B "$build" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$CMAKE_DIR/ninja" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_NDK="$NDK" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$API" \
        -DANDROID_TOOLCHAIN=clang \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_C_FLAGS="$C_FLAGS" \
        -DCMAKE_SHARED_LINKER_FLAGS="$LINK_FLAGS" \
        -DTARGET_PLATFORM=android \
        -DLIBRARY_TYPE=shared > "$WORK/cmake-$abi.log"
    "$CMAKE_DIR/cmake" --build "$build" > "$WORK/ninja-$abi.log"
    mkdir -p "$OUT/$abi"
    # Same as AGP's stripDebugDebugSymbols: drop the symbol table, keep .dynsym (the JNI exports).
    "$NDK/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64/bin/llvm-strip" \
        --strip-unneeded -o "$OUT/$abi/libquickjs.so" "$build/libquickjs.so"
done

READELF="$NDK/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64/bin/llvm-readelf"
for abi in "${ABIS[@]}"; do
    so="$OUT/$abi/libquickjs.so"
    if "$READELF" -lW "$so" | awk '$1 == "LOAD" { print $NF }' | grep -qv '^0x4000$'; then
        echo "FAIL: $so has a LOAD segment not aligned to 16 KB" >&2
        exit 1
    fi
    shasum -a 256 "$so"
done
echo "OK: work dir $WORK"
