// Nuvio compatibility shim (spec §5.2). Defines enough of Nuvio's own plugin runtime -- CommonJS
// `module`/`require`, `fetch`, a `cheerio`/`crypto-js` subset, `TMDB_API_KEY` -- in terms of the
// `kino` global, so an unmodified Nuvio scraper's top-level code and `getStreams` export run as-is.
// `__NUVIO_TMDB_API_KEY__` is replaced by NuvioPluginConverter before this ships in a plugin.

var module = { exports: {} };
var exports = module.exports;

function require(name) {
  if (name === "crypto-js") return __nuvioCryptoJs;
  if (name === "cheerio" || name === "cheerio-without-node-native" || name === "react-native-cheerio") return __nuvioCheerio;
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

globalThis.TMDB_API_KEY = "__NUVIO_TMDB_API_KEY__";

// cheerio subset: `$(selector)` queries the loaded document; `$(el)`/`$(matches)` wraps an existing
// element or result set instead of re-querying, so `$(this)`/`$(el)` inside `.each` work the way real
// scrapers use them. `kino.html.select` is flat (no DOM to walk), so `.find(sel)` is answered the only
// honest way available: re-selecting inside each matched element's own INNER html
// ([PluginHtml]/`kino.html.select`'s `html` field) and unioning the results. Anything that needs an
// actual parent/sibling pointer has no translation and throws a clear error instead of silently
// returning the wrong node (spec §8: "recorrido de HTML complejo").
var __NUVIO_CHEERIO_NO_TRANSLATION = ["parent", "parents", "next", "prev", "siblings", "closest", "children"];
var __nuvioCheerio = {
  load: function (html) {
    // A "match" is what `kino.html.select` returns per element: `{ text, html (INNER html), attrs }`.
    function resultSet(matches) {
      var api = {
        length: matches.length,
        attr: function (name) { return matches.length ? matches[0].attrs[name] : undefined; },
        // cheerio's own `.text()` on a SET concatenates every matched element's text; `.attr()`/
        // `.html()` only ever look at the first element, matching cheerio too.
        text: function () { var out = ""; for (var i = 0; i < matches.length; i++) out += matches[i].text; return out; },
        html: function () { return matches.length ? matches[0].html : null; },
        each: function (fn) { for (var i = 0; i < matches.length; i++) fn.call(matches[i], i, matches[i]); return api; },
        map: function (fn) {
          var out = [];
          for (var i = 0; i < matches.length; i++) out.push(fn.call(matches[i], i, matches[i]));
          return { get: function () { return out; }, toArray: function () { return out; } };
        },
        eq: function (i) { return resultSet(i >= 0 && i < matches.length ? [matches[i]] : []); },
        first: function () { return api.eq(0); },
        last: function () { return api.eq(matches.length - 1); },
        get: function (i) { return i === undefined ? matches.slice() : matches[i]; },
        toArray: function () { return matches.slice(); },
        find: function (selector) {
          var found = [];
          for (var i = 0; i < matches.length; i++) {
            var inner = kino.html.select(matches[i].html, selector);
            for (var j = 0; j < inner.length; j++) found.push(inner[j]);
          }
          return resultSet(found);
        },
        __nuvioMatches: matches,
      };
      __NUVIO_CHEERIO_NO_TRANSLATION.forEach(function (name) {
        api[name] = function () { throw new Error("Nuvio compat: cheerio ." + name + "() has no translation"); };
      });
      return api;
    }
    return function (selectorOrElement) {
      if (typeof selectorOrElement === "string") return resultSet(kino.html.select(html, selectorOrElement));
      // `$(this)`/`$(el)` inside `.each`, or re-wrapping an earlier result set/array of raw elements.
      if (selectorOrElement && Array.isArray(selectorOrElement.__nuvioMatches)) return resultSet(selectorOrElement.__nuvioMatches);
      if (Array.isArray(selectorOrElement)) return resultSet(selectorOrElement);
      if (selectorOrElement && typeof selectorOrElement === "object") return resultSet([selectorOrElement]);
      return resultSet([]);
    };
  },
};

// crypto-js subset: AES/TripleDES with an explicit key+iv, via kino.crypto (same algorithms, spec
// §5.2). Does NOT implement CryptoJS's own OpenSSL-style passphrase key derivation
// (EVP_BytesToKey) for a call with no `iv` -- a scraper that decrypts that way needs that derivation
// added here once a real one needs it; it is not silently approximated.
var __nuvioCryptoJs = {
  AES: {
    decrypt: function (ciphertext, key, opts) {
      var out = kino.crypto.decrypt("aes-256-cbc", {
        key: String(key), iv: (opts && opts.iv) ? String(opts.iv) : "", data: String(ciphertext),
        inputEncoding: "base64", outputEncoding: "utf8",
      });
      return { toString: function () { return out; } };
    },
    encrypt: function (plaintext, key, opts) {
      var out = kino.crypto.encrypt("aes-256-cbc", { key: String(key), iv: (opts && opts.iv) ? String(opts.iv) : "", data: String(plaintext) });
      return { toString: function () { return out; } };
    },
  },
  TripleDES: {
    decrypt: function (ciphertext, key) {
      var out = kino.crypto.decrypt("des-ede3-cbc", { key: String(key), data: String(ciphertext), inputEncoding: "base64", outputEncoding: "utf8" });
      return { toString: function () { return out; } };
    },
  },
  enc: { Utf8: "utf8", Base64: "base64", Hex: "hex" },
};
