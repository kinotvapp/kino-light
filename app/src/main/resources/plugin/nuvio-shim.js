// Nuvio compatibility shim (spec §5.2). Rebuilds, in terms of the `kino` global, the runtime a
// Nuvio scraper expects: CommonJS `module`/`require`, a browser-shaped `fetch`, `axios`, the REAL
// `cheerio-without-node-native`, `crypto-js` and `Buffer` (vendored under `nuvio-vendor/`, and
// concatenated by NuvioPluginConverter after this file only when the scraper can need them), and
// `TMDB_API_KEY`, so an unmodified scraper's top-level code and `getStreams` export run as-is.
// Everything here is plugin.js's own module scope: nothing is added to globalThis except
// TMDB_API_KEY (and whatever a scraper itself writes through `global`), and
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

// crypto-js asks its environment for a secure random source when it needs one (a passphrase
// encrypt's salt); its vendored build reads this name where it would read Node's `global` (see
// nuvio-vendor/crypto-js.js's header), so no `crypto` global exists for a scraper to mistake for WebCrypto.
var __nuvioCryptoRandomSource = {
  crypto: {
    getRandomValues: function (array) {
      var hex = kino.crypto.randomBytes(array.length * array.BYTES_PER_ELEMENT, "hex");
      var bytes = new Uint8Array(array.buffer, array.byteOffset, array.byteLength);
      for (var i = 0; i < bytes.length; i++) bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
      return array;
    },
  },
};

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
