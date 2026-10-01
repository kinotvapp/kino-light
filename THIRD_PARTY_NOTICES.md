# Third-party notices

Kino's own code is covered by `LICENSE` (Apache-2.0). The third-party code below is **not** covered by
that license: each part keeps its own license, whose full text is in the file named.

## Code included in this repository

| Path | Component | License | License text |
|---|---|---|---|
| `app/src/main/resources/plugin/nuvio-vendor/cheerio.js` | cheerio-without-node-native 0.20.2, bundled with boolbase 1.0.0, css-select 1.2.0, css-what 2.1.3, dom-serializer 0.1.1, domelementtype 1.3.1, domhandler 2.4.2, domutils 1.5.1, entities 1.1.2, eventemitter2 1.0.5, htmlparser2-without-node-native 3.9.2, inherits 2.0.4, lodash 4.18.1, nth-check 1.0.2 | MIT; ISC (boolbase, inherits); BSD-2-Clause (css-what, domelementtype, domhandler, domutils, entities, nth-check); BSD-style (css-select) | `app/src/main/resources/plugin/nuvio-vendor/cheerio.LICENSE.txt` |
| `app/src/main/resources/plugin/nuvio-vendor/buffer.js` | buffer 6.0.3, bundled with base64-js 1.5.1 and ieee754 1.2.1 | MIT; BSD-3-Clause (ieee754) | `app/src/main/resources/plugin/nuvio-vendor/buffer.LICENSE.txt` |
| `app/src/main/resources/plugin/nuvio-vendor/crypto-js.js` | crypto-js 4.2.0 | MIT | `app/src/main/resources/plugin/nuvio-vendor/crypto-js.LICENSE.txt` |
| `app/src/main/jniLibs/arm64-v8a/libquickjs.so`, `app/src/main/jniLibs/armeabi-v7a/libquickjs.so`, `app/src/test/resources/jni/macos_aarch64/libquickjs.dylib` | QuickJS (Fabrice Bellard, Charlie Gordon) | MIT | `app/src/main/jniLibs/licenses/quickjs-MIT.txt` |
| same binaries | quickjs-kt 1.0.0-alpha13 native code (dokar3), rebuilt with one patch (`app/src/main/jniLibs/patches/`) | Apache-2.0 | `app/src/main/jniLibs/licenses/quickjs-kt-Apache-2.0.txt`; changes in `app/src/main/jniLibs/licenses/quickjs-kt-MODIFICATIONS.txt` |
| same binaries | c-vector (Evan Teran) | MIT | `app/src/main/jniLibs/licenses/c-vector-MIT.txt` |
| `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` | Gradle wrapper | Apache-2.0 | header of `gradlew`; https://www.apache.org/licenses/LICENSE-2.0 |

The three `nuvio-vendor` bundles are vendored unmodified; each file's header names its origin.

## Code fetched or loaded outside this repository

- **Mbed TLS 3.6.2** — not in this repository. `app/src/main/cpp/CMakeLists.txt` downloads it from
  https://github.com/Mbed-TLS/mbedtls at build time and links it into the app's native library.
  Mbed TLS is dual-licensed Apache-2.0 OR GPL-2.0-or-later; Kino uses it under Apache-2.0.
- **Google Cast receiver framework** — `receiver/index.html` loads it from `gstatic.com` at run
  time; it is not included here and is covered by Google's terms.
- **Gradle and Maven dependencies** (AndroidX, Media3, Kotlin, quickjs-kt's Kotlin side, etc.) —
  downloaded at build time under their own licenses, as declared in their published metadata.

## Nuvio scrapers

No Nuvio scraper code is in this repository. When someone adds a Nuvio repository in the app, Kino
downloads that repository's scrapers and converts them into plugins on that person's device. The
scrapers keep their authors' license (commonly GPL-3.0); the app says so in the picker and in each
converted plugin's description. Kino does not redistribute the scrapers, converted or not, and
their license does not extend to Kino's code.
