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
  if (name === "crypto" || name === "node:crypto") return __nuvioNodeCrypto;
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
// `opts.signal` (an AbortSignal, below) is honored here and never reaches kino.fetch: an already
// aborted signal rejects before any request goes out, and an abort while the request is in flight
// rejects right away with the signal's reason (an AbortError unless the scraper gave its own); the
// native request itself runs on and its answer is ignored.
function fetch(url, opts) {
  var signal = opts && opts.signal;
  if (!signal) return __nuvioFetch(url, opts);
  var rest = {};
  for (var k in opts) if (k !== "signal") rest[k] = opts[k];
  return Promise.resolve().then(function () {
    return __nuvioAbortable(signal, function () {
      // A timeout signal also bounds the native request, so it doesn't outlive the wait by much.
      var left = signal.__deadline === undefined ? 0 : signal.__deadline - Date.now();
      if (left > 0 && !(rest.timeoutMs > 0)) rest.timeoutMs = Math.min(Math.ceil(left), 30000);
      return __nuvioFetch(url, rest);
    });
  });
}

function __nuvioFetch(url, opts) {
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

// --- AbortController / AbortSignal, as browsers and Node have them (QuickJS has neither). ---
// Scrapers cancel slow hoster checks with them (`new AbortController()` + `setTimeout(() =>
// c.abort(), 2500)`, `fetch(url, { signal })`, `AbortSignal.timeout(ms)`); without them the first
// `new AbortController()` is a ReferenceError. There is no EventTarget here, so a signal keeps its own
// 'abort' listeners. The errors are DOMException-like: an Error named AbortError (code 20) or
// TimeoutError (code 23). `AbortSignal.timeout(ms)` answers `aborted` from its deadline and only
// starts a timer while someone listens (fetch does, while its request is in flight): an idle timer
// would keep the whole call open until it fired (see setTimeout above).
function __nuvioDomError(name, code, message) {
  var e = new Error(message);
  e.name = name;
  e.code = code;
  return e;
}
function __nuvioAbortError() { return __nuvioDomError("AbortError", 20, "This operation was aborted"); }

function AbortSignal() { throw new TypeError("Illegal constructor"); }
function __nuvioNewSignal() {
  var s = Object.create(AbortSignal.prototype);
  s.__aborted = false;
  s.__reason = undefined;
  s.__listeners = [];
  s.__onabort = null;
  s.__timer = 0;
  return s;
}
function __nuvioSignalAbort(signal, reason) {
  if (signal.__aborted) return;
  signal.__aborted = true;
  signal.__reason = reason === undefined ? __nuvioAbortError() : reason;
  if (signal.__timer) { clearTimeout(signal.__timer); signal.__timer = 0; }
  var event = { type: "abort", target: signal, currentTarget: signal };
  var handlers = (signal.__onabort ? [signal.__onabort] : []).concat(signal.__listeners.map(function (l) { return l.fn; }));
  signal.__listeners = [];
  handlers.forEach(function (fn) {
    try { fn.call(signal, event); } catch (e) { console.error("Nuvio compat: an abort listener threw", e && e.message ? e.message : String(e)); }
  });
}
// A timeout signal's timer runs only while it has a listener; see the section comment.
function __nuvioSignalArm(signal) {
  var wanted = signal.__deadline !== undefined && !signal.__aborted && (signal.__onabort || signal.__listeners.length > 0);
  if (wanted && !signal.__timer) {
    signal.__timer = setTimeout(function () {
      signal.__timer = 0;
      __nuvioSignalAbort(signal, __nuvioDomError("TimeoutError", 23, "The operation timed out"));
    }, Math.max(0, signal.__deadline - Date.now()));
  } else if (!wanted && signal.__timer) {
    clearTimeout(signal.__timer);
    signal.__timer = 0;
  }
}
Object.defineProperties(AbortSignal.prototype, {
  aborted: {
    get: function () {
      if (!this.__aborted && this.__deadline !== undefined && Date.now() >= this.__deadline) {
        __nuvioSignalAbort(this, __nuvioDomError("TimeoutError", 23, "The operation timed out"));
      }
      return this.__aborted;
    },
  },
  reason: { get: function () { return this.aborted ? this.__reason : undefined; } },
  onabort: {
    get: function () { return this.__onabort; },
    set: function (fn) { this.__onabort = typeof fn === "function" ? fn : null; __nuvioSignalArm(this); },
  },
});
AbortSignal.prototype.addEventListener = function (type, fn, options) {
  if (type !== "abort" || typeof fn !== "function" || this.aborted) return;
  for (var i = 0; i < this.__listeners.length; i++) if (this.__listeners[i].fn === fn) return;
  this.__listeners.push({ fn: fn });
  __nuvioSignalArm(this);
};
AbortSignal.prototype.removeEventListener = function (type, fn) {
  if (type !== "abort") return;
  this.__listeners = this.__listeners.filter(function (l) { return l.fn !== fn; });
  __nuvioSignalArm(this);
};
AbortSignal.prototype.throwIfAborted = function () { if (this.aborted) throw this.__reason; };
AbortSignal.abort = function (reason) {
  var s = __nuvioNewSignal();
  __nuvioSignalAbort(s, reason);
  return s;
};
AbortSignal.timeout = function (ms) {
  var s = __nuvioNewSignal();
  s.__deadline = Date.now() + Math.max(0, Number(ms) || 0);
  return s;
};
AbortSignal.any = function (signals) {
  var s = __nuvioNewSignal();
  var list = Array.prototype.slice.call(signals || []);
  for (var i = 0; i < list.length; i++) {
    if (list[i].aborted) { __nuvioSignalAbort(s, list[i].reason); return s; }
  }
  list.forEach(function (source) {
    source.addEventListener("abort", function () { __nuvioSignalAbort(s, source.reason); });
  });
  return s;
};

function AbortController() {
  if (!(this instanceof AbortController)) throw new TypeError("Constructor AbortController requires 'new'");
  this.signal = __nuvioNewSignal();
}
AbortController.prototype.abort = function (reason) { __nuvioSignalAbort(this.signal, reason); };

// Runs `start()` (a promise-returning request) under `signal`: rejects at once when the signal is
// already aborted (start() never runs), or as soon as it aborts while the request is in flight; the
// request's own late answer is then ignored. The listener is removed once the request settles, so a
// timeout signal stops its timer with it. The already-aborted case THROWS (callers run this inside a
// `.then`): a `Promise.reject` handed back from a `.then` callback is still unhandled for a moment,
// and this sandbox fails the whole call on an unhandled rejection (measured with axios).
function __nuvioAbortable(signal, start, toError) {
  var fail = toError || function (reason) { return reason; };
  if (signal.aborted) throw fail(signal.reason);
  return new Promise(function (resolve, reject) {
    function onAbort() { reject(fail(signal.reason)); }
    signal.addEventListener("abort", onAbort);
    Promise.resolve().then(start).then(function (value) {
      signal.removeEventListener("abort", onAbort);
      resolve(value);
    }, function (e) {
      signal.removeEventListener("abort", onAbort);
      reject(e);
    });
  });
}

// --- The browser Web Crypto API: `crypto.subtle`, `crypto.getRandomValues`, `crypto.randomUUID`. ---
// Scrapers written for the browser call it directly (PelisPlusHD's Embed69 resolver hashes a proof of
// work with `crypto.subtle.digest` and decrypts its links with AES-CBC). AES, HMAC and SHA-1/256/512
// run natively through kino.crypto (javax.crypto); SHA-384 (which kino.crypto lacks) goes through the
// vendored crypto-js, which NuvioPluginConverter bundles whenever a scraper mentions `subtle`. Bytes
// cross into kino.crypto as hex. Every subtle method answers a Promise and reports failures the way
// browsers do: a DOMException-like Error whose `name` is OperationError, DataError, ... (names that are
// legal and match no Java class: see trampas.md on quickjs-kt error names).
// The byte helpers below, shared with Node's `crypto` further down (set inside the IIFE).
var __nuvioCryptoKit;
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
    return cryptoJs("this algorithm").lib.WordArray.create(words, bytes.length);
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

  __nuvioCryptoKit = { toHex: toHex, fromHex: fromHex, cryptoJs: cryptoJs, toWordArray: toWordArray, fromWordArray: fromWordArray };

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

// --- Node's `crypto` module: `require('crypto')` / `require('node:crypto')`. ---
// What Node-style scrapers use of it (EntrePeliculasySeries and MegaDede solve Embed69's proof of
// work with createHash and decrypt its links with createDecipheriv): hashes, HMAC, AES/3DES ciphers
// with Node's streaming update/final, PBKDF2, random bytes. Buffers in and out, over the vendored
// `Buffer` (bundled whenever a scraper names this module). The work runs natively through
// kino.crypto; SHA-384 and the PBKDF2 cases kino.crypto refuses go through the vendored crypto-js.
var __nuvioNodeCrypto = (function () {
  var kit = function () { return __nuvioCryptoKit; };
  function buf() {
    if (typeof Buffer === "undefined") throw new Error("Nuvio compat: require('crypto') needs Buffer, which was not bundled with this scraper");
    return Buffer;
  }
  function encodingOf(enc) {
    var e = String(enc).toLowerCase();
    return e === "utf-8" ? "utf8" : e === "binary" ? "latin1" : e;
  }
  function bytesIn(data, enc, what) {
    if (typeof data === "string") {
      var e = enc ? encodingOf(enc) : "utf8";
      // buffer@6 predates base64url; its base64 decoder already tolerates missing padding.
      if (e === "base64url") { data = data.replace(/-/g, "+").replace(/_/g, "/"); e = "base64"; }
      return buf().from(data, e);
    }
    if (data instanceof ArrayBuffer) return new Uint8Array(data);
    if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    throw new TypeError("The \"" + what + "\" argument must be of type string or an instance of Buffer, TypedArray, or DataView.");
  }
  // `bytes` is always a fresh array here, so the Buffer may share its memory.
  function bytesOut(bytes, enc) {
    var b = buf().from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    if (enc === undefined || enc === null || enc === "buffer") return b;
    var e = encodingOf(enc);
    if (e === "hex") return kit().toHex(bytes);
    if (e === "base64url") return b.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    return b.toString(e);
  }
  function join(chunks) {
    if (chunks.length === 1) return chunks[0];
    var size = 0, i;
    for (i = 0; i < chunks.length; i++) size += chunks[i].length;
    var out = new Uint8Array(size);
    for (i = 0, size = 0; i < chunks.length; i++) { out.set(chunks[i], size); size += chunks[i].length; }
    return out;
  }
  var hex = function (bytes) { return kit().toHex(bytes); };

  var HASHES = { md5: "MD5", sha1: "SHA1", sha256: "SHA256", sha384: "SHA384", sha512: "SHA512" };
  function hashName(alg) {
    var n = String(alg).toLowerCase().replace(/^rsa-/, "").replace(/-/g, "");
    if (!HASHES[n]) throw new Error("Digest method not supported: " + alg);
    return n;
  }
  function digest(n, bytes) {
    if (n === "sha384") return kit().fromWordArray(kit().cryptoJs("SHA-384").SHA384(kit().toWordArray(bytes)));
    return kit().fromHex(kino.crypto.hash(n, hex(bytes), { inputEncoding: "hex", outputEncoding: "hex" }));
  }
  function hmac(n, key, bytes) {
    if (n === "sha384") return kit().fromWordArray(kit().cryptoJs("HMAC SHA-384").HmacSHA384(kit().toWordArray(bytes), kit().toWordArray(key)));
    return kit().fromHex(kino.crypto.hmac(n, hex(key), hex(bytes), { keyEncoding: "hex", inputEncoding: "hex", outputEncoding: "hex" }));
  }
  function makeHash(n, key) {
    var chunks = [], done = false;
    var h = {
      update: function (data, enc) {
        if (done) throw new Error("Digest already called");
        chunks.push(new Uint8Array(bytesIn(data, enc, "data")));
        return h;
      },
      digest: function (enc) {
        if (done) throw new Error("Digest already called");
        done = true;
        var all = join(chunks);
        return bytesOut(key ? hmac(n, key, all) : digest(n, all), enc);
      },
    };
    if (!key) h.copy = function () { var c = makeHash(n, null); chunks.forEach(function (x) { c.update(x); }); return c; };
    return h;
  }

  // [key bytes, iv bytes (0: none; -1: any non-empty), mode, block bytes]
  var CIPHERS = {
    "aes-128-cbc": [16, 16, "cbc", 16], "aes-192-cbc": [24, 16, "cbc", 16], "aes-256-cbc": [32, 16, "cbc", 16],
    "aes-128-ecb": [16, 0, "ecb", 16], "aes-192-ecb": [24, 0, "ecb", 16], "aes-256-ecb": [32, 0, "ecb", 16],
    "aes-128-ctr": [16, 16, "ctr", 16], "aes-192-ctr": [24, 16, "ctr", 16], "aes-256-ctr": [32, 16, "ctr", 16],
    "aes-128-gcm": [16, -1, "gcm", 16], "aes-192-gcm": [24, -1, "gcm", 16], "aes-256-gcm": [32, -1, "gcm", 16],
    "des-ede3-cbc": [24, 8, "cbc", 8], "des-ede3-ecb": [24, 0, "ecb", 8],
  };
  var ALIASES = { aes128: "aes-128-cbc", aes192: "aes-192-cbc", aes256: "aes-256-cbc", "des-ede3": "des-ede3-ecb", des3: "des-ede3-cbc" };
  function kinoCipher(encrypt, alg, key, iv, data, padding, aad) {
    if (!data.length && padding === "none") return new Uint8Array(0);
    var p = { key: hex(key), keyEncoding: "hex", data: hex(data), inputEncoding: "hex", outputEncoding: "hex", padding: padding };
    if (iv.length) { p.iv = hex(iv); p.ivEncoding = "hex"; }
    if (aad && aad.length) { p.aad = hex(aad); p.aadEncoding = "hex"; }
    return kit().fromHex(encrypt ? kino.crypto.encrypt(alg, p) : kino.crypto.decrypt(alg, p));
  }
  function providerError(reason) { return new Error("error:1C800064:Provider routines::" + reason); }

  // Node's streaming contract: update() hands back what it can already (whole blocks; for CBC/ECB
  // decryption with padding, all but the last block, which final() unpads), final() the rest. Each
  // update recomputes over everything so far and returns only the new part: CBC/ECB/CTR/GCM
  // outputs are prefixes of each other, and scrapers pass their data in one or two pieces.
  function makeCipher(encrypt, algorithm, key, iv, options) {
    var name = String(algorithm).toLowerCase();
    name = ALIASES[name] || name;
    var spec = CIPHERS[name];
    if (!spec) throw new Error("Unknown cipher: " + algorithm);
    var mode = spec[2], block = spec[3];
    var k = new Uint8Array(bytesIn(key, undefined, "key"));
    if (k.length !== spec[0]) throw new RangeError("Invalid key length");
    var v = iv === null || iv === undefined ? new Uint8Array(0) : new Uint8Array(bytesIn(iv, undefined, "iv"));
    if (spec[1] === -1 ? !v.length : v.length !== spec[1]) throw new TypeError("Invalid initialization vector");
    var tagLength = mode === "gcm" && options && options.authTagLength !== undefined ? options.authTagLength : 16;
    if (mode === "gcm" && !(tagLength >= 4 && tagLength <= 16)) throw new TypeError("Invalid authentication tag length: " + tagLength);
    var chunks = [], total = 0, emitted = 0, autoPadding = true, aad = null, tag = null, authTag = null, finished = false;

    // Same-length output for a prefix of the input. CTR and GCM are a keystream XOR, so their
    // encryption also decrypts (GCM's tag, the part that differs, is dropped here).
    function transform(bytes) {
      if (mode === "gcm") return kinoCipher(true, name, k, v, bytes, "none", null).subarray(0, bytes.length);
      if (mode === "ctr") return kinoCipher(true, name, k, v, bytes, "none", null);
      return kinoCipher(encrypt, name, k, v, bytes, "none", null);
    }
    function ready() {
      if (mode === "gcm" || mode === "ctr") return total;
      if (!encrypt && autoPadding) return Math.max(0, Math.floor((total - 1) / block)) * block;
      return Math.floor(total / block) * block;
    }
    var c = {
      update: function (data, inEnc, outEnc) {
        if (finished) throw new Error("Unsupported state");
        var b = new Uint8Array(bytesIn(data, inEnc, "data"));
        chunks.push(b);
        total += b.length;
        var r = ready();
        var out = new Uint8Array(0);
        if (r > emitted) {
          out = transform(join(chunks).subarray(0, r)).slice(emitted, r);
          emitted = r;
        }
        return bytesOut(out, outEnc);
      },
      final: function (outEnc) {
        if (finished) throw new Error("Unsupported state");
        finished = true;
        var data = join(chunks);
        var out = new Uint8Array(0);
        if (mode === "gcm") {
          if (encrypt) {
            var sealed = kinoCipher(true, name, k, v, data, "none", aad);
            authTag = sealed.slice(sealed.length - 16, sealed.length - 16 + tagLength);
          } else {
            if (!tag) throw new Error("Unsupported state or unable to authenticate data");
            var check = kinoCipher(true, name, k, v, transform(data), "none", aad);
            var diff = 0;
            for (var i = 0; i < tag.length; i++) diff |= check[data.length + i] ^ tag[i];
            if (diff) throw new Error("Unsupported state or unable to authenticate data");
          }
        } else if (mode !== "ctr") {
          if (data.length % block !== 0 && (!encrypt || !autoPadding)) throw providerError("wrong final block length");
          if (!encrypt && autoPadding && !data.length) throw providerError("wrong final block length");
          var whole;
          try {
            whole = kinoCipher(encrypt, name, k, v, data, autoPadding ? "pkcs7" : "none", null);
          } catch (e) {
            throw providerError("bad decrypt");
          }
          out = whole.slice(emitted);
        }
        return bytesOut(out, outEnc);
      },
      setAutoPadding: function (on) { autoPadding = on !== false; return c; },
      setAAD: function (data) { aad = new Uint8Array(bytesIn(data, undefined, "buffer")); return c; },
      getAuthTag: function () {
        if (!encrypt || !authTag) throw new Error("Invalid state for operation getAuthTag");
        return bytesOut(authTag.slice());
      },
      setAuthTag: function (t) {
        if (encrypt) throw new Error("Invalid state for operation setAuthTag");
        tag = new Uint8Array(bytesIn(t, undefined, "buffer"));
        if (tag.length < 4 || tag.length > 16) throw new TypeError("Invalid authentication tag length: " + tag.length);
        return c;
      },
    };
    return c;
  }

  function randomBytes(size, callback) {
    var n = Math.floor(Number(size));
    if (!(n >= 0) || n > 2147483647) throw new RangeError("The value of \"size\" is out of range.");
    var out = new Uint8Array(n);
    for (var at = 0; at < n; at += 1024) out.set(kit().fromHex(kino.crypto.randomBytes(Math.min(1024, n - at), "hex")), at);
    var b = bytesOut(out);
    if (typeof callback === "function") { Promise.resolve().then(function () { callback(null, b); }); return undefined; }
    return b;
  }

  function pbkdf2Sync(password, salt, iterations, keylen, digestName) {
    var n = hashName(digestName === undefined ? "sha1" : digestName);
    var pw = new Uint8Array(bytesIn(password, undefined, "password"));
    var s = new Uint8Array(bytesIn(salt, undefined, "salt"));
    if (!(iterations >= 1) || iterations !== Math.floor(iterations)) throw new RangeError("The value of \"iterations\" is out of range.");
    if (!(keylen >= 0) || keylen !== Math.floor(keylen)) throw new RangeError("The value of \"keylen\" is out of range.");
    if (keylen === 0) return bytesOut(new Uint8Array(0));
    // kino.crypto: sha1/sha256/sha512, up to 100000 iterations and 64 bytes; crypto-js otherwise.
    if ((n === "sha1" || n === "sha256" || n === "sha512") && iterations <= 100000 && keylen <= 64) {
      return bytesOut(kit().fromHex(kino.crypto.pbkdf2(n, hex(pw), hex(s), iterations, keylen, { keyEncoding: "hex", inputEncoding: "hex", outputEncoding: "hex" })));
    }
    var C = kit().cryptoJs("PBKDF2 with " + n);
    var words = C.PBKDF2(kit().toWordArray(pw), kit().toWordArray(s), { keySize: Math.ceil(keylen / 4), iterations: iterations, hasher: C.algo[HASHES[n]] });
    return bytesOut(kit().fromWordArray(words).slice(0, keylen));
  }

  var nodeCrypto = {
    createHash: function (alg) { return makeHash(hashName(alg), null); },
    createHmac: function (alg, key) { return makeHash(hashName(alg), new Uint8Array(bytesIn(key, undefined, "key"))); },
    createCipheriv: function (alg, key, iv, options) { return makeCipher(true, alg, key, iv, options); },
    createDecipheriv: function (alg, key, iv, options) { return makeCipher(false, alg, key, iv, options); },
    randomBytes: randomBytes,
    pseudoRandomBytes: randomBytes,
    randomUUID: function () { return kino.crypto.uuid(); },
    pbkdf2Sync: pbkdf2Sync,
    pbkdf2: function (password, salt, iterations, keylen, digestName, callback) {
      var out, err = null;
      try { out = pbkdf2Sync(password, salt, iterations, keylen, digestName); } catch (e) { err = e; }
      Promise.resolve().then(function () { callback(err, out); });
    },
    timingSafeEqual: function (a, b) {
      var x = bytesIn(a, undefined, "buf1"), y = bytesIn(b, undefined, "buf2");
      if (x.length !== y.length) throw new RangeError("Input buffers must have the same byte length");
      var diff = 0;
      for (var i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
      return diff === 0;
    },
    getHashes: function () { return Object.keys(HASHES); },
    getCiphers: function () { return Object.keys(CIPHERS); },
    getRandomValues: function (array) { return crypto.getRandomValues(array); },
    webcrypto: crypto,
    subtle: crypto.subtle,
    constants: {},
  };
  nodeCrypto.default = nodeCrypto;
  return Object.freeze(nodeCrypto);
})();

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
      // `signal` (an AbortSignal) cancels like fetch's, rejecting with axios's own CanceledError.
      if (config.signal) return __nuvioAbortable(config.signal, function () { return send(config, opts); }, function () { return canceledError(config); });
      return send(config, opts);
    });
  }
  function canceledError(config) {
    var e = axiosError("canceled", config, null, "ERR_CANCELED");
    e.name = "CanceledError";
    e.__CANCEL__ = true;
    return e;
  }
  function send(config, opts) {
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
    instance.isCancel = function (e) { return !!(e && e.__CANCEL__); };
    instance.default = instance;
    return instance;
  }
  return create({});
})();
