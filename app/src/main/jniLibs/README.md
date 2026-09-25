# libquickjs.so rebuilt with 16 KB page alignment

`io.github.dokar3:quickjs-kt:1.0.0-alpha13` (the last quickjs-kt built with Kotlin 2.0; see
`app/build.gradle.kts`) ships `libquickjs.so` linked for 4 KB pages: every `LOAD` segment has
`p_align 0x1000`. Android 15+ shows the "app isn't 16 KB compatible" warning for that, and Google
Play requires 16 KB alignment for 64-bit libs. Every other native lib in the APK is already 0x4000.

The `.so` files here are the **same upstream sources, rebuilt with the same toolchain and flags**,
plus the linker option upstream itself added in v1.0.1 (`-Wl,-z,max-page-size=16384`). The
`pickFirsts += "**/libquickjs.so"` rule in `app/build.gradle.kts` makes this copy the one that
ships instead of the AAR's. The Kotlin side stays alpha13's; JVM unit tests keep using
`quickjs-kt-jvm` (desktop natives) and never load these files.

Why not a newer quickjs-kt-android AAR's lib (those are already 16 KB): 1.0.6+ export different
JNI symbols, and 1.0.1–1.0.5 export the same names but their native code calls
`QuickJs.clearHandledPromiseRejection()` (added to the Kotlin class in 1.0.1, absent in alpha13)
and bundle a newer QuickJS engine. They are not drop-in replacements.

## Provenance

| | |
|---|---|
| Upstream repo | https://github.com/dokar3/quickjs-kt |
| Tag / commit | `v1.0.0-alpha13` = `d2fefdc451678d09bbef5526c3b00f12e41cd5cf` |
| QuickJS submodule | https://github.com/bellard/quickjs `36911f0d3ab1a4c190a4d5cbe7c2db225a455389` (`VERSION` 2024-02-14) |
| c-vector submodule | https://github.com/eteran/c-vector `774773d4cb1e66dd736e90e7f482d4f22af464c6` |
| NDK | r26b `26.1.10909125` (clang 17.0.2, the one the AAR's lib was built with per its `.comment` / `.note.android.ident`) |
| CMake | 3.22.1 from the Android SDK, upstream's `quickjs/native/CMakeLists.txt` unchanged |
| ABIs / API | `arm64-v8a`, `armeabi-v7a` (the app's `abiFilters`), `android-21` (upstream's minSdk) |

Build, per ABI (what `build-quickjs-16kb.sh` runs):

```
cmake -S quickjs/native -B build-$ABI -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-21 -DANDROID_TOOLCHAIN=clang \
  -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DCMAKE_C_FLAGS="-fstrict-aliasing -g0 -Os -fomit-frame-pointer -DNDEBUG -fvisibility=hidden" \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
  -DTARGET_PLATFORM=android -DLIBRARY_TYPE=shared
cmake --build build-$ABI
llvm-strip --strip-unneeded -o $ABI/libquickjs.so build-$ABI/libquickjs.so
```

The C flags and `CMAKE_BUILD_TYPE` are upstream's release config (`quickjs/build.gradle.kts`,
`defaultConfig` + `buildTypes.release`). The strip only drops the static symbol table, like AGP's
own strip step; `.dynsym` (the JNI exports) is untouched.

## sha256

```
41eeab5d3f2b90d5f53066513cf5b152b41adda79e625222cdb78d9a9d80577d  arm64-v8a/libquickjs.so
73217f8dabbd749f40f818a1d1baf5e1d5714cf6c02bdb904b1db179c723662f  armeabi-v7a/libquickjs.so
```

The build is reproducible: two runs from separate fresh clones gave these same hashes.

## Checked against the AAR's original

- `LOAD` `p_align`: 0x1000 in the AAR, 0x4000 here, both ABIs.
- Exported dynamic symbols (`llvm-nm -D --defined-only`, the 19 `Java_com_dokar_quickjs_QuickJs_*`
  plus the rest), imported symbols, `NEEDED` (liblog, libm, libdl, libc) and `SONAME`: identical.
- Every allocated section has the same size; `.rodata` is byte-identical; the disassembled `.text`
  is the same instruction sequence (132854 on arm64, 142417 on armv7); only address operands
  moved, because the RW segments now start on a 16 KB boundary.

## Rebuild

```
app/src/main/jniLibs/build-quickjs-16kb.sh [work-dir]
```

Needs git, network and the Android SDK with `ndk;26.1.10909125` and `cmake;3.22.1`. It clones
upstream, checks the tag and submodule commits above, builds both ABIs, overwrites the `.so` files
here, fails if any `LOAD` segment is not 0x4000 and prints the sha256 of each file.

Drop this directory, the script and the `pickFirsts` rule once the app can move to a quickjs-kt
release whose AAR is already 16 KB aligned (needs Kotlin >= 2.3).

## Licenses

QuickJS (Fabrice Bellard, Charlie Gordon) and c-vector (Evan Teran) are MIT; quickjs-kt (dokar3)
is Apache-2.0. Their notices are in `licenses/`.
