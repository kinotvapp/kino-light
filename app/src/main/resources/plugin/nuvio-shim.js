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

function fetch(url, opts) {
  opts = opts || {};
  var req = { url: url, method: opts.method || "GET", headers: opts.headers || {} };
  if (opts.body !== undefined) req.body = { text: String(opts.body) };
  return kino.fetch(req).then(function (r) {
    return {
      ok: r.ok, status: r.status, url: r.url, headers: r.headers,
      json: function () { return Promise.resolve(JSON.parse(r.text || "")); },
      text: function () { return Promise.resolve(r.text || ""); },
    };
  });
}

globalThis.TMDB_API_KEY = "__NUVIO_TMDB_API_KEY__";

// cheerio subset: `$(sel).attr()/.text()/.html()/.each()/.eq()` only. `kino.html.select` is flat (no
// DOM to walk), so anything that traverses the tree throws a clear error instead of silently
// returning the wrong node (spec §8: "recorrido de HTML complejo").
var __nuvioCheerio = {
  load: function (html) {
    return function (selector) {
      var matches = kino.html.select(html, selector);
      var api = {
        length: matches.length,
        attr: function (name) { return matches.length ? matches[0].attrs[name] : undefined; },
        text: function () { return matches.length ? matches[0].text : ""; },
        html: function () { return matches.length ? matches[0].html : null; },
        each: function (fn) { matches.forEach(function (m, i) { fn.call(m, i, m); }); return api; },
        eq: function (i) {
          var m = matches[i];
          return { attr: function (n) { return m ? m.attrs[n] : undefined; }, text: function () { return m ? m.text : ""; } };
        },
        parent: function () { throw new Error("Nuvio compat: cheerio .parent() has no translation"); },
        next: function () { throw new Error("Nuvio compat: cheerio .next() has no translation"); },
        siblings: function () { throw new Error("Nuvio compat: cheerio .siblings() has no translation"); },
        find: function () { throw new Error("Nuvio compat: cheerio .find() on a result set has no translation"); },
      };
      return api;
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
