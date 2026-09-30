// Nuvio compatibility shim (spec §5.2). Rebuilds, in terms of the `kino` global, the runtime a
// Nuvio scraper expects: CommonJS `module`/`require`, a browser-shaped `fetch`, `axios`, the REAL
// `cheerio-without-node-native`, `crypto-js` and `Buffer` (vendored under `nuvio-vendor/`, and
// concatenated by NuvioPluginConverter after this file only when the scraper can need them), and
// `TMDB_API_KEY`, so an unmodified scraper's top-level code and `getStreams` export run as-is.
// It also gives the browser Web Crypto API (`crypto.subtle`, `crypto.getRandomValues`).
// Everything here is plugin.js's own module scope: nothing is added to globalThis except
// TMDB_API_KEY and `crypto` (and whatever a scraper itself writes through `global`), and
// prelude.js/web.js/kino.fetch stay exactly what native Kino plugins get.
// `__NUVIO_TMDB_API_KEY__` is replaced by NuvioPluginConverter before this ships in a plugin.

var module = { exports: {} };
var exports = module.exports;

// The vendored libraries are module-scope variables defined AFTER this file (see
// NuvioPluginConverter), so they are looked up when a scraper calls require(), never here; a
// library the converter left out (the scraper never named it) is a clear error, not a ReferenceError.
function __nuvioVendored(name, lib) {
  if (lib === undefined) throw new Error("Nuvio compat: require('" + name + "') was not bundled with this scraper");
  return lib;
}

function require(name) {
  if (name === "cheerio" || name === "cheerio-without-node-native" || name === "react-native-cheerio") {
    return __nuvioVendored(name, typeof __nuvioLibCheerio === "undefined" ? undefined : __nuvioLibCheerio);
  }
  if (name === "crypto-js") return __nuvioVendored(name, typeof __nuvioLibCryptoJs === "undefined" ? undefined : __nuvioLibCryptoJs);
  if (name === "buffer") return __nuvioVendored(name, typeof __nuvioLibBuffer === "undefined" ? undefined : __nuvioLibBuffer);
  if (name === "axios") return __nuvioAxios;
  throw new Error("Nuvio compat: unsupported require('" + name + "')");
}

// `kino.fetch(url, opts)` already takes the same `(url, opts)` shape Nuvio's own scrapers call
// `fetch` with (method/headers/body/redirect); passing the two arguments through as-is is still
// right (wrapping them into one bundled `req` object here previously made `kino.fetch` receive that
// whole object as its `url` argument instead, `opts` then undefined, corrupting the very URL a
// scraper asked for). But its resolved response's `text()`/`json()`/`base64()` are plain SYNCHRONOUS
// functions -- correct and required for native Kino plugins (prelude.js's own contract, untouched
// here), NOT the browser Fetch API shape real Nuvio scrapers assume, where `response.json()` and
// `.text()` themselves return PROMISES (`response.json().then(fn)`, `await response.json()`) and
// `response.headers.get(name)` looks a header up by name. This wrapper bridges kino's synchronous
// response into that shape for scraper code only.
function fetch(url, opts) {
  return kino.fetch(url, opts || {}).then(function (r) {
    // `r.headers`: a plain object, keys already lowercased, repeated headers joined with ", ", never
    // `set-cookie` (see PluginHttp.kt) -- so a case-insensitive `get` only needs to lowercase the ask.
    var raw = r.headers || {};
    var headers = {
      get: function (name) { var v = raw[String(name).toLowerCase()]; return v === undefined ? null : v; },
      has: function (name) { return raw[String(name).toLowerCase()] !== undefined; },
      forEach: function (fn) { for (var k in raw) fn(raw[k], k); },
      entries: function () { var out = []; for (var k in raw) out.push([k, raw[k]]); return out; },
    };
    return {
      ok: r.ok, status: r.status, url: r.url, statusText: "", redirected: false, headers: headers,
      // `Promise.resolve().then(...)`, not `new Promise(function (resolve) { resolve(r.json()) })`:
      // a throw INSIDE a `.then` callback is what reliably becomes a REJECTED promise (the mechanism
      // the rest of this sandbox's own async/await already depends on) -- a throw while a `new
      // Promise` executor is still running its OWN synchronous call stack, measured, escaped this
      // engine's Promise machinery entirely instead of rejecting. This way both `await r.json()` and
      // `r.json().then(fn)`/`.catch(fn)` see a real rejection, matching browser `fetch()`.
      // `kino.fetch`'s own text()/json()/base64() stay synchronous underneath.
      text: function () { return Promise.resolve().then(function () { return r.text(); }); },
      json: function () { return Promise.resolve().then(function () { return r.json(); }); },
      base64: function () { return Promise.resolve().then(function () { return r.base64(); }); },
    };
  });
}

var __NUVIO_TMDB_KEY = "__NUVIO_TMDB_API_KEY__";
globalThis.TMDB_API_KEY = __NUVIO_TMDB_KEY;

// --- What Node/React Native have and QuickJS doesn't, that real scrapers touch. Module scope only. ---

// `process.env.X || fallback` (vidnest) must read "not set", not throw. Nothing else: a scraper
// that sniffs `process.versions.node` must keep concluding it is not on Node.
var process = { env: {} };

// React Native's name for the global object, which scrapers write to at their top level
// (`global.URL_VALIDATION_ENABLED = true;` in dvdplay and mallumv). It IS globalThis there too: this
// plugin's own sandbox, shared with no other plugin.
var global = globalThis;

// Retry back-offs (`await new Promise(r => setTimeout(r, 1000))` in 4khdhub, moviebox,
// dahmermovies) over kino.sleep, which takes 0..5000 ms per call: longer waits are slept in slices
// so clearTimeout stops one within a slice. Every wait counts against the call's own time limit,
// and a timer nobody clears keeps the call open until it fires (quickjs-kt awaits pending jobs).
var __nuvioTimers = {};
var __nuvioTimerSeq = 0;
function setTimeout(fn, ms) {
  var id = ++__nuvioTimerSeq;
  var args = Array.prototype.slice.call(arguments, 2);
  var left = Math.max(0, Math.floor(Number(ms) || 0));
  __nuvioTimers[id] = true;
  (async function () {
    await null;
    while (left > 0 && __nuvioTimers[id]) {
      var slice = Math.min(left, 500);
      left -= slice;
      await kino.sleep(slice);
    }
    if (!__nuvioTimers[id]) return;
    delete __nuvioTimers[id];
    if (typeof fn === "function") fn.apply(undefined, args);
  })().catch(function (e) { console.error("Nuvio compat: a setTimeout callback threw", e && e.message ? e.message : String(e)); });
  return id;
}
function clearTimeout(id) { delete __nuvioTimers[id]; }

// --- The browser Web Crypto API: `crypto.subtle`, `crypto.getRandomValues`, `crypto.randomUUID`. ---
// Scrapers written for the browser call it directly (PelisPlusHD's Embed69 resolver hashes a proof of
// work with `crypto.subtle.digest` and decrypts its links with AES-CBC). AES, HMAC and SHA-1/256/512
// run natively through kino.crypto (javax.crypto); SHA-384 (which kino.crypto lacks) goes through the
// vendored crypto-js, which NuvioPluginConverter bundles whenever a scraper mentions `subtle`. Bytes
// cross into kino.crypto as hex. Every subtle method answers a Promise and reports failures the way
// browsers do: a DOMException-like Error whose `name` is OperationError, DataError, ... (names that are
// legal and match no Java class: see trampas.md on quickjs-kt error names).
var __nuvioWebCrypto = (function () {
  var HEX = [];
  for (var h = 0; h < 256; h++) HEX.push((h < 16 ? "0" : "") + h.toString(16));
  var nibble = function (c) { return c < 58 ? c - 48 : (c | 32) - 87; };

  function domError(name, message) {
    var e = new Error(message);
    e.name = name;
    return e;
  }
  // A fresh copy: WebCrypto takes a snapshot of its inputs, and the caller may reuse its buffer.
  function bytesOf(data, what) {
    if (data instanceof ArrayBuffer) return new Uint8Array(data.slice(0));
    if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength));
    throw new TypeError("Failed to execute crypto.subtle: " + what + " is not an ArrayBuffer, TypedArray or DataView");
  }
  // In 8 KB slices: one string per byte in a single array would cost 16 bytes each (trampas.md).
  function toHex(bytes) {
    var chunks = [];
    for (var start = 0; start < bytes.length; start += 8192) {
      var end = Math.min(bytes.length, start + 8192), part = new Array(end - start);
      for (var i = start; i < end; i++) part[i - start] = HEX[bytes[i]];
      chunks.push(part.join(""));
    }
    return chunks.join("");
  }
  function fromHex(hex) {
    var out = new Uint8Array(hex.length >> 1);
    for (var i = 0, j = 0; i < out.length; i++, j += 2) out[i] = (nibble(hex.charCodeAt(j)) << 4) | nibble(hex.charCodeAt(j + 1));
    return out;
  }
  function cryptoJs(what) {
    if (typeof __nuvioLibCryptoJs === "undefined") throw domError("NotSupportedError", what + " is not available to this scraper");
    return __nuvioLibCryptoJs;
  }
  function toWordArray(bytes) {
    var words = new Array((bytes.length + 3) >> 2).fill(0);
    for (var i = 0; i < bytes.length; i++) words[i >>> 2] |= bytes[i] << (24 - (i % 4) * 8);
    return cryptoJs("SHA-384").lib.WordArray.create(words, bytes.length);
  }
  function fromWordArray(wa) {
    var out = new Uint8Array(wa.sigBytes);
    for (var i = 0; i < out.length; i++) out[i] = (wa.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
    return out;
  }

  // WebCrypto matches algorithm names case-insensitively; everything below compares upper case.
  function nameOf(alg) {
    var n = typeof alg === "string" ? alg : alg && alg.name;
    if (typeof n !== "string") throw new TypeError("Algorithm: name is missing");
    return n.toUpperCase();
  }
  var HASHES = { "SHA-1": "sha1", "SHA-256": "sha256", "SHA-384": "sha384", "SHA-512": "sha512" };
  function hashOf(alg) {
    var n = nameOf(alg);
    if (!HASHES[n]) throw domError("NotSupportedError", "Unrecognized hash: " + n);
    return n;
  }

  function digestBytes(hashName, bytes) {
    if (hashName === "SHA-384") return fromWordArray(cryptoJs("SHA-384").SHA384(toWordArray(bytes)));
    return fromHex(kino.crypto.hash(HASHES[hashName], toHex(bytes), { inputEncoding: "hex", outputEncoding: "hex" }));
  }
  function hmacBytes(hashName, key, bytes) {
    if (hashName === "SHA-384") return fromWordArray(cryptoJs("HMAC SHA-384").HmacSHA384(toWordArray(bytes), toWordArray(key)));
    return fromHex(kino.crypto.hmac(HASHES[hashName], toHex(key), toHex(bytes), { keyEncoding: "hex", inputEncoding: "hex", outputEncoding: "hex" }));
  }

  // --- keys: an opaque CryptoKey-like object; its bytes live only in this closure ---
  var keyBytes = new WeakMap();
  function CryptoKey() { throw new TypeError("Illegal constructor"); }
  function makeKey(algorithm, extractable, usages, bytes) {
    var key = Object.create(CryptoKey.prototype);
    Object.defineProperties(key, {
      type: { value: "secret", enumerable: true },
      extractable: { value: !!extractable, enumerable: true },
      algorithm: { value: Object.freeze(algorithm), enumerable: true },
      usages: { value: Object.freeze(usages.slice()), enumerable: true },
    });
    keyBytes.set(key, bytes);
    return key;
  }
  function keyFor(key, algName, usage) {
    var bytes = key && keyBytes.get(key);
    if (!bytes) throw new TypeError("parameter 'key' is not a CryptoKey");
    if (key.algorithm.name !== algName) throw domError("InvalidAccessError", "The requested operation is not valid for the provided key");
    if (key.usages.indexOf(usage) < 0) throw domError("InvalidAccessError", "The key does not support the '" + usage + "' operation");
    return bytes;
  }
  var AES = { "AES-CBC": "cbc", "AES-CTR": "ctr", "AES-GCM": "gcm" };
  var CANONICAL = { "AES-CBC": "AES-CBC", "AES-CTR": "AES-CTR", "AES-GCM": "AES-GCM", "HMAC": "HMAC" };

  function importKey(format, keyData, algorithm, extractable, usages) {
    var name = nameOf(algorithm);
    if (!CANONICAL[name]) throw domError("NotSupportedError", "Unrecognized algorithm: " + name);
    if (format !== "raw") throw domError("NotSupportedError", "Only the \"raw\" key format is supported, not " + format);
    var bytes = bytesOf(keyData, "keyData");
    if (!Array.isArray(usages) || !usages.length) throw new SyntaxError("Usages cannot be empty when creating a key");
    var allowed = name === "HMAC" ? ["sign", "verify"] : ["encrypt", "decrypt", "wrapKey", "unwrapKey"];
    for (var i = 0; i < usages.length; i++) {
      if (allowed.indexOf(usages[i]) < 0) throw new SyntaxError("Cannot create a key using the specified key usages");
    }
    if (name === "HMAC") {
      if (!algorithm || !algorithm.hash) throw new TypeError("HmacImportParams: hash is required");
      if (!bytes.length) throw domError("DataError", "HMAC key data must not be empty");
      return makeKey({ name: "HMAC", hash: { name: hashOf(algorithm.hash) }, length: bytes.length * 8 }, extractable, usages, bytes);
    }
    if (bytes.length !== 16 && bytes.length !== 24 && bytes.length !== 32) {
      throw domError("DataError", "AES key data must be 128, 192 or 256 bits");
    }
    return makeKey({ name: CANONICAL[name], length: bytes.length * 8 }, extractable, usages, bytes);
  }

  function exportKey(format, key) {
    var bytes = key && keyBytes.get(key);
    if (!bytes) throw new TypeError("parameter 'key' is not a CryptoKey");
    if (format !== "raw") throw domError("NotSupportedError", "Only the \"raw\" key format is supported, not " + format);
    if (!key.extractable) throw domError("InvalidAccessError", "key is not extractable");
    return bytes.slice(0).buffer;
  }

  // One kino.crypto AES call; any failure it reports (bad padding, wrong GCM tag, bad sizes) is the
  // OperationError a browser rejects with.
  function aes(mode, key, blockMode, ivBytes, dataBytes, aadBytes) {
    var p = { key: toHex(key), keyEncoding: "hex", iv: toHex(ivBytes), ivEncoding: "hex", data: toHex(dataBytes), inputEncoding: "hex", outputEncoding: "hex" };
    if (aadBytes && aadBytes.length) { p.aad = toHex(aadBytes); p.aadEncoding = "hex"; }
    var alg = "aes-" + key.length * 8 + "-" + blockMode;
    try {
      return fromHex(mode === "encrypt" ? kino.crypto.encrypt(alg, p) : kino.crypto.decrypt(alg, p));
    } catch (e) {
      throw domError("OperationError", "The operation failed for an operation-specific reason");
    }
  }
  function concat(a, b) {
    var out = new Uint8Array(a.length + b.length);
    out.set(a, 0);
    out.set(b, a.length);
    return out;
  }

  // javax.crypto's CTR carries across all 128 bits; WebCrypto's counter is only the rightmost
  // `length` bits and wraps to zero without touching the rest. They agree until that wrap, so the
  // data is split at every wrap and each run restarts with the counter bits cleared.
  function aesCtr(key, params, data) {
    var counter = bytesOf(params && params.counter, "counter");
    var length = params && params.length;
    if (counter.length !== 16) throw domError("OperationError", "AesCtrParams: counter must be 16 bytes");
    if (!(length >= 1 && length <= 128) || length !== Math.floor(length)) throw domError("OperationError", "AesCtrParams: length must be between 1 and 128");
    var out = new Uint8Array(data.length);
    var offset = 0;
    while (offset < data.length) {
      // Blocks left before the counter bits wrap: 2^length - (counter's low bits). Only the low 32
      // bits of it matter when any higher counter bit is 0 (then more than 2^32 blocks remain).
      var low = 0;
      for (var b = 12; b < 16; b++) low = low * 256 + counter[b];
      var room;
      if (length < 32) {
        room = Math.pow(2, length) - (low % Math.pow(2, length));
      } else {
        var highAllOnes = true;
        for (var bit = 32; bit < length && highAllOnes; bit++) {
          if (!((counter[15 - (bit >> 3)] >> (bit & 7)) & 1)) highAllOnes = false;
        }
        room = highAllOnes ? Math.pow(2, 32) - low : Infinity;
      }
      var take = Math.min(data.length - offset, room * 16);
      out.set(aes("encrypt", key, "ctr", counter, data.subarray(offset, offset + take)), offset);
      offset += take;
      if (offset < data.length) {
        for (var clear = 0; clear < length; clear++) counter[15 - (clear >> 3)] &= ~(1 << (clear & 7));
      }
    }
    return out;
  }

  var TAG_LENGTHS = [32, 64, 96, 104, 112, 120, 128];
  // kino.crypto always uses a 128-bit tag. A shorter WebCrypto tag is that one truncated; to check
  // one on decrypt, the plaintext is recovered with an encrypt (GCM is a keystream XOR) and
  // re-encrypted to get the full tag to compare against.
  function aesGcm(mode, key, params, data) {
    var iv = bytesOf(params && params.iv, "iv");
    if (!iv.length) throw domError("OperationError", "AesGcmParams: iv must not be empty");
    var aad = params.additionalData === undefined ? null : bytesOf(params.additionalData, "additionalData");
    var tagLength = params.tagLength === undefined ? 128 : params.tagLength;
    if (TAG_LENGTHS.indexOf(tagLength) < 0) throw domError("OperationError", "AesGcmParams: tagLength is not valid");
    var tagBytes = tagLength / 8;
    if (mode === "encrypt") {
      var sealed = aes("encrypt", key, "gcm", iv, data, aad);
      return tagBytes === 16 ? sealed : sealed.slice(0, sealed.length - 16 + tagBytes);
    }
    if (data.length < tagBytes) throw domError("OperationError", "The ciphertext is shorter than its tag");
    if (tagBytes === 16) return aes("decrypt", key, "gcm", iv, data, aad);
    var body = data.subarray(0, data.length - tagBytes);
    var plain = aes("encrypt", key, "gcm", iv, body, null).slice(0, body.length);
    var expected = aes("encrypt", key, "gcm", iv, plain, aad);
    var diff = 0;
    for (var i = 0; i < tagBytes; i++) diff |= expected[body.length + i] ^ data[body.length + i];
    if (diff) throw domError("OperationError", "The operation failed for an operation-specific reason");
    return plain;
  }

  function cipher(mode, algorithm, key, data) {
    var name = nameOf(algorithm);
    if (!AES[name]) throw domError("NotSupportedError", "Unrecognized algorithm: " + name);
    var keyData = keyFor(key, name, mode);
    var bytes = bytesOf(data, "data");
    if (name === "AES-CBC") {
      var iv = bytesOf(algorithm.iv, "iv");
      if (iv.length !== 16) throw domError("OperationError", "AesCbcParams: iv must be 16 bytes");
      return aes(mode, keyData, "cbc", iv, bytes, null);
    }
    if (name === "AES-CTR") return aesCtr(keyData, algorithm, bytes);
    return aesGcm(mode, keyData, algorithm, bytes);
  }

  function sign(algorithm, key, data) {
    var name = nameOf(algorithm);
    if (name !== "HMAC") throw domError("NotSupportedError", "Unrecognized algorithm: " + name);
    return hmacBytes(key && key.algorithm && key.algorithm.hash && key.algorithm.hash.name, keyFor(key, "HMAC", "sign"), bytesOf(data, "data"));
  }
  function verify(algorithm, key, signature, data) {
    var name = nameOf(algorithm);
    if (name !== "HMAC") throw domError("NotSupportedError", "Unrecognized algorithm: " + name);
    var expected = hmacBytes(key && key.algorithm && key.algorithm.hash && key.algorithm.hash.name, keyFor(key, "HMAC", "verify"), bytesOf(data, "data"));
    var given = bytesOf(signature, "signature");
    if (given.length !== expected.length) return false;
    var diff = 0;
    for (var i = 0; i < given.length; i++) diff |= given[i] ^ expected[i];
    return diff === 0;
  }

  // Every subtle method: its throw becomes a rejection (see fetch() above for why .then, not new Promise).
  function promised(fn) {
    return function () {
      var args = arguments, self = this;
      return Promise.resolve().then(function () { return fn.apply(self, args); });
    };
  }
  var buffered = function (fn) { return promised(function () { var out = fn.apply(this, arguments); return out instanceof Uint8Array ? out.buffer : out; }); };

  var subtle = Object.freeze({
    digest: buffered(function (algorithm, data) { return digestBytes(hashOf(algorithm), bytesOf(data, "data")); }),
    importKey: promised(importKey),
    exportKey: promised(exportKey),
    encrypt: buffered(function (algorithm, key, data) { return cipher("encrypt", algorithm, key, data); }),
    decrypt: buffered(function (algorithm, key, data) { return cipher("decrypt", algorithm, key, data); }),
    sign: buffered(sign),
    verify: promised(verify),
  });

  var INTEGER_ARRAYS = ["Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array", "Uint32Array", "BigInt64Array", "BigUint64Array"];
  function getRandomValues(array) {
    var kind = array && ArrayBuffer.isView(array) && Object.prototype.toString.call(array).slice(8, -1);
    if (!kind || INTEGER_ARRAYS.indexOf(kind) < 0) throw domError("TypeMismatchError", "The data provided is not an integer-type array");
    if (array.byteLength > 65536) throw domError("QuotaExceededError", "The ArrayBufferView's byte length (" + array.byteLength + ") exceeds the number of bytes of entropy available via this API (65536).");
    var bytes = new Uint8Array(array.buffer, array.byteOffset, array.byteLength);
    // kino.crypto.randomBytes hands out at most 1024 bytes per call.
    for (var at = 0; at < bytes.length; at += 1024) {
      bytes.set(fromHex(kino.crypto.randomBytes(Math.min(1024, bytes.length - at), "hex")), at);
    }
    return array;
  }

  return Object.freeze({
    subtle: subtle,
    getRandomValues: getRandomValues,
    randomUUID: function () { return kino.crypto.uuid(); },
    CryptoKey: CryptoKey,
  });
})();

// Scraper code finds it as the bare `crypto` and, browser-style, on globalThis (this plugin's own
// sandbox, shared with no other plugin). One the sandbox already has is kept, never replaced.
var crypto = typeof globalThis.crypto === "object" && globalThis.crypto !== null ? globalThis.crypto : __nuvioWebCrypto;
if (typeof globalThis.crypto !== "object" || globalThis.crypto === null) globalThis.crypto = crypto;

// crypto-js asks its environment for a secure random source when it needs one (a passphrase
// encrypt's salt); its vendored build reads this name where it would read Node's `global` (see
// nuvio-vendor/crypto-js.js's header). With globalThis.crypto set above it finds that first anyway.
var __nuvioCryptoRandomSource = { crypto: crypto };

// --- axios, on top of the fetch above: what scrapers use of it, not the whole library. ---
// `axios(config)` / `axios(url, config)`, `.request/.get/.delete/.head/.options/.post/.put/.patch`,
// `.create(defaults)` (baseURL, headers, timeout, params merged), `params` as the query string, `data`
// as JSON (or a string/URLSearchParams as is), `responseType: "text"`, `maxRedirects: 0`, and
// `validateStatus`. A non-2xx answer REJECTS with an Error carrying `.response`, like axios.
var __nuvioAxios = (function () {
  function merge(base, extra) {
    var out = {};
    var k;
    for (k in base || {}) out[k] = base[k];
    for (k in extra || {}) if (extra[k] !== undefined) out[k] = extra[k];
    out.headers = {};
    [base && base.headers, extra && extra.headers].forEach(function (h) {
      for (var name in h || {}) {
        var v = h[name];
        if (v !== undefined && v !== null && typeof v !== "object") out.headers[name] = String(v);
      }
    });
    var p1 = base && base.params, p2 = extra && extra.params;
    if (p1 && p2 && !(p1 instanceof URLSearchParams) && !(p2 instanceof URLSearchParams)) {
      out.params = {};
      for (k in p1) out.params[k] = p1[k];
      for (k in p2) out.params[k] = p2[k];
    }
    return out;
  }
  function hasHeader(headers, name) {
    for (var k in headers) if (k.toLowerCase() === name) return true;
    return false;
  }
  function urlOf(config) {
    var url = String(config.url || "");
    if (config.baseURL && !/^[a-z][a-z0-9+.-]*:/i.test(url)) {
      url = String(config.baseURL).replace(/\/+$/, "") + (url ? "/" + url.replace(/^\/+/, "") : "");
    }
    var params = config.params;
    if (params) {
      var query = params instanceof URLSearchParams ? params : new URLSearchParams();
      if (!(params instanceof URLSearchParams)) {
        for (var key in params) {
          var value = params[key];
          if (value === undefined || value === null) continue;
          if (Array.isArray(value)) value.forEach(function (v) { query.append(key + "[]", String(v)); });
          else query.append(key, value instanceof Date ? value.toISOString() : typeof value === "object" ? JSON.stringify(value) : String(value));
        }
      }
      var text = query.toString();
      if (text) url += (url.indexOf("?") < 0 ? "?" : "&") + text;
    }
    return url;
  }
  function axiosError(message, config, response, code) {
    var e = new Error(message);
    e.isAxiosError = true;
    e.config = config;
    e.code = code;
    if (response) e.response = response;
    return e;
  }
  function request(config) {
    return Promise.resolve().then(function () {
      var method = String(config.method || "get").toUpperCase();
      var headers = config.headers;
      var body;
      var data = config.data;
      if (data !== undefined && data !== null && method !== "GET" && method !== "HEAD") {
        if (typeof data === "string") body = data;
        else if (data instanceof URLSearchParams) {
          body = data.toString();
          if (!hasHeader(headers, "content-type")) headers["Content-Type"] = "application/x-www-form-urlencoded";
        } else {
          body = JSON.stringify(data);
          if (!hasHeader(headers, "content-type")) headers["Content-Type"] = "application/json";
        }
      }
      var opts = { method: method, headers: headers, redirect: config.maxRedirects === 0 ? "manual" : "follow" };
      if (body !== undefined) opts.body = body;
      var timeout = Math.floor(Number(config.timeout) || 0);
      if (timeout > 0) opts.timeoutMs = Math.min(timeout, 30000);
      return kino.fetch(urlOf(config), opts).then(function (r) {
        var text = r.text();
        var parsed = text;
        if (config.responseType !== "text" && typeof text === "string" && text.length) {
          try { parsed = JSON.parse(text); } catch (e) { parsed = text; }
        }
        var plainHeaders = {};
        for (var name in r.headers || {}) plainHeaders[name] = r.headers[name];
        var response = { data: parsed, status: r.status, statusText: "", headers: plainHeaders, config: config, request: { responseURL: r.url } };
        var valid = config.validateStatus === null ? true
          : typeof config.validateStatus === "function" ? config.validateStatus(r.status)
          : r.status >= 200 && r.status < 300;
        if (!valid) {
          throw axiosError("Request failed with status code " + r.status, config, response, r.status >= 500 ? "ERR_BAD_RESPONSE" : "ERR_BAD_REQUEST");
        }
        return response;
      }, function (e) {
        throw axiosError(e && e.message ? e.message : String(e), config, null, e && e.code === "timeout" ? "ECONNABORTED" : (e && e.code) || "ERR_NETWORK");
      });
    });
  }
  function create(defaults) {
    var instance = function (urlOrConfig, config) {
      return typeof urlOrConfig === "string"
        ? instance.request(merge(config, { url: urlOrConfig }))
        : instance.request(urlOrConfig);
    };
    instance.defaults = merge({ headers: {} }, defaults);
    instance.request = function (config) { return request(merge(instance.defaults, config)); };
    ["get", "delete", "head", "options"].forEach(function (m) {
      instance[m] = function (url, config) { return instance.request(merge(config, { method: m, url: url })); };
    });
    ["post", "put", "patch"].forEach(function (m) {
      instance[m] = function (url, data, config) { return instance.request(merge(config, { method: m, url: url, data: data })); };
    });
    instance.create = function (more) { return create(merge(instance.defaults, more)); };
    instance.isAxiosError = function (e) { return !!(e && e.isAxiosError); };
    instance.default = instance;
    return instance;
  }
  return create({});
})();
