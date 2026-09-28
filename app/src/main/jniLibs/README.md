# libquickjs.so rebuilt with 16 KB page alignment

`io.github.dokar3:quickjs-kt:1.0.0-alpha13` (the last quickjs-kt built with Kotlin 2.0; see
`app/build.gradle.kts`) ships `libquickjs.so` linked for 4 KB pages: every `LOAD` segment has
`p_align 0x1000`. Android 15+ shows the "app isn't 16 KB compatible" warning for that, and Google
Play requires 16 KB alignment for 64-bit libs. Every other native lib in the APK is already 0x4000.

The `.so` files here are the **same upstream sources, rebuilt with the same toolchain and flags**,
plus the linker option upstream itself added in v1.0.1 (`-Wl,-z,max-page-size=16384`) and **one
source patch** (`patches/`, below). The `pickFirsts += "**/libquickjs.so"` rule in
`app/build.gradle.kts` makes this copy the one that ships instead of the AAR's. The Kotlin side
stays alpha13's. JVM unit tests use `quickjs-kt-jvm` (desktop natives) and never load these files;
on a macOS arm64 host they load the same patched sources instead (see "Desktop library for the
unit tests").

## The patch: closing one QuickJs broke every other one

`patches/0001-keep-process-wide-jni-caches.patch` (applied by the script with `git apply`).

alpha13's native code keeps the `JavaVM *` and ~80 JNI class/method/field IDs in **process-wide
statics** (`jni/jni_globals.c`, `jni/jni_globals_generated.c`), shared by every live QuickJs.
`initGlobals` (in `QuickJs.create`) caches the VM; `releaseGlobals` (in `QuickJs.close`) called
`clear_java_vm_cache()` and `clear_jni_refs_cache(env)`. So closing *any* runtime left every other
live one without a way back into Kotlin until the next `create`: `get_jni_env()` returned NULL, a
binding call (`kino.storage.get`, `kino.fetch`, `config()` while the prelude loads) unwound with a
`null` JS exception, `evaluate` answered null (a raw NPE in `PluginRuntime.call`) or the prelude
stopped before defining `__kinoCall`. It also deleted global class refs another thread could be
using at that moment. `PluginRuntimePool` closes runtimes on idle, timeout, update and uninstall,
and the install probe closes one, so this hit production. Measured: `PluginRuntimeIsolationTest`
fails 3 of its cases on the unpatched library, deterministically.

The patch deletes those two calls: the caches are filled once per process and never cleared.
That is correct because a process has exactly one JavaVM for its whole life, and the cached
classes (`java.*` plus quickjs-kt's own) live as long as their class loader, i.e. the process; the
set is fixed, so nothing grows. A refcount across live instances was rejected: it still clears on
the last close while a `create` on another thread may be mid-way, and buys nothing over never
clearing. The lazy getters' first-fill race (two threads both calling `NewGlobalRef`) at worst leaks
one class ref, once. `PluginRuntime` also defends in Kotlin (a prelude without `__kinoCall` fails
`open`; a null result fails the call and discards the runtime), for any other native failure.

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
d1a4768b2580140cecfa0f1d09c4b3c6b3820bee9dad8bf1a440d9deb97057d2  arm64-v8a/libquickjs.so
3a8e8a90a9fa8b249e38bb06986ebe1b0909cd629acbe995fff6152636c265a1  armeabi-v7a/libquickjs.so
7673c12e7f26fa8365282051d73181901420e253181695d5eebfc1a916ec2e0f  ../../test/resources/jni/macos_aarch64/libquickjs.dylib
```

The build is reproducible: two runs from separate fresh clones gave these same hashes (the dylib
with zig 0.16.0 and Zulu JDK 17 headers). Before the patch the `.so` hashes were `41eeab5d…` and
`73217f8d…`.

## Checked against the AAR's original

(Measured before the patch. After it, the dynamic symbols, `NEEDED` and `SONAME` were re-checked
and are still identical; `.text` shrank by the now-unused `clear_jni_refs_cache`.)

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
upstream, checks the tag and submodule commits above, applies `patches/*.patch`, builds both ABIs,
overwrites the `.so` files here, fails if any `LOAD` segment is not 0x4000 and prints the sha256 of
each file. On a macOS arm64 host with `zig` and a JDK it then rebuilds the desktop test library.

## Desktop library for the unit tests

`app/src/test/resources/jni/macos_aarch64/libquickjs.dylib` is the same patched source built the
way upstream builds its desktop natives (`-DTARGET_PLATFORM=macos_aarch64`, `MinSizeRel`, upstream's
`cmake/zig-toolchain-macos_aarch64.cmake`, JNI headers from the local JDK). quickjs-kt-jvm finds
its native library with `classLoader.getResource("/jni/macos_aarch64/libquickjs.dylib")`, and the
unit-test classpath puts the test resources before the dependency jars, so this copy shadows the
unpatched one inside the jar — no Gradle wiring. On any other host (Linux, Intel Mac, Windows) the
tests load the jar's **unpatched** library and `PluginRuntimeIsolationTest` fails, as it should: the
bug is still in that library. Build that host's library the same way (`TARGET_PLATFORM` =
`linux_x64`, `macos_x64`, …) and drop it next to this one under `jni/<platform>/`.

Drop this directory, the script, the test dylib and the `pickFirsts` rule once the app can move to
a quickjs-kt release whose AAR is already 16 KB aligned (needs Kotlin >= 2.3) **and** whose
`releaseGlobals` no longer clears the process-wide caches — check that before dropping the patch:
`PluginRuntimeIsolationTest` must stay green.

## Licenses

QuickJS (Fabrice Bellard, Charlie Gordon) and c-vector (Evan Teran) are MIT; quickjs-kt (dokar3)
is Apache-2.0. Their notices are in `licenses/`, with the list of files this build modifies
(Apache-2.0 section 4(b)) in `licenses/quickjs-kt-MODIFICATIONS.txt`.
