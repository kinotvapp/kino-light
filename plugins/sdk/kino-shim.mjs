// Node stand-in for the `kino` global Kino gives plugins (see GUIDE.md). Same shapes, same host
// check, same caps and error codes as the app, read from contract.json. Node 18+ (global fetch).
//
// Kino's own app code is authoritative: its own network and host-gate logic decide what a plugin
// may really do. This file only APPROXIMATES their host, redirect and request-cap rules so you can
// develop locally; if the two ever disagree, the app is right. Known differences:
// kino.html.select exists only in the app (it uses Jsoup); a host that resolves to a private
// address is not refused; the cookie jar keeps name/value/domain/path/expiry/secure but not every
// RFC 6265 corner; nothing enforces the per-call time or memory limits.
import { createCipheriv, createDecipheriv, createHash, createHmac, pbkdf2Sync, randomBytes, randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { contract, hostMatches, isUserServer, kb } from "./contract.mjs";

// Captured at load: the runner later replaces console.error to keep stdout clean, and kino.log
// must not be routed through that replacement (it would print two prefixes).
const writeErr = console.error.bind(console);

export function kinoError(code, message) {
  const c = typeof code === "string" && /^[a-z_]{1,32}$/.test(code) ? code : "unknown";
  const e = new Error(String(message ?? "").slice(0, contract.errors.maxMessageChars));
  Object.defineProperty(e, "name", { value: `KinoError_${c}` });
  Object.defineProperty(e, "code", { value: c, enumerable: true });
  return e;
}

export { hostMatches as hostAllowed };

const loadJson = (file, fallback) => (file && existsSync(file) ? JSON.parse(readFileSync(file, "utf8")) : fallback);
const saveJson = (file, value) => {
  if (!file) return;
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify(value, null, 1));
};

/**
 * [config]: the setting values (`--config key=value` / sdk/config.json); defaults from the
 * manifest are applied here like the app does. [record]/[replay]: a JSON file of kino.fetch
 * exchanges, so a test can run offline and give the same answers every time. [fetchImpl]: the
 * network itself (tests point it at a local server).
 */
export function createKino(manifest, { appVersion = "sdk", lang = "es-CO", storageFile = null, cookiesFile = null, config = {}, record = null, replay = null, fetchImpl = globalThis.fetch } = {}) {
  const f = contract.fetch;
  const storage = loadJson(storageFile, {});
  const cookieJar = loadJson(cookiesFile, []);
  const tape = replay ? loadJson(replay, null) : record ? [] : null;
  if (replay && !tape) throw new Error(`--replay: ${replay} not found`);
  let requests = 0;

  const values = {};
  for (const s of manifest.settings || []) {
    // A url setting never takes a manifest default (the app refuses one): only a typed server counts.
    const v = config[s.key] !== undefined ? config[s.key] : s.default !== undefined && contract.settings.types[s.type]?.canHaveDefault !== false ? s.default : s.type === "toggle" ? false : s.type === "select" ? s.options[0].value : undefined;
    if (v !== undefined && v !== "") values[s.key] = s.type === "toggle" ? v === true || v === "true" : String(v);
  }
  const servers = (manifest.settings || []).filter((s) => s.type === "url" && typeof values[s.key] === "string" && isUserServer(values[s.key])).map((s) => new URL(values[s.key].trim()));
  const port = (u) => u.port || (u.protocol === "https:" ? "443" : "80");
  const serverOf = (u) => servers.find((s) => s.protocol === u.protocol && s.hostname === u.hostname && port(s) === port(u));

  function gate(u, from) {
    const typed = serverOf(u);
    if (typed) {
      if (from && serverOf(from) && serverOf(from) !== typed) throw kinoError("host_not_allowed", "host no permitido: " + u.hostname);
      return;
    }
    if (!hostMatches(u.hostname, manifest.hosts)) throw kinoError("host_not_allowed", "host no permitido: " + u.hostname);
    if (u.protocol !== "https:") throw kinoError("host_not_allowed", "solo se permite https");
  }

  // --- cookies: enough of RFC 6265 for logins (Domain, Path, Expires, Max-Age, Secure) ---
  const dropExpired = () => { const now = Date.now(); for (let i = cookieJar.length - 1; i >= 0; i--) if (cookieJar[i].expires <= now) cookieJar.splice(i, 1); };
  const cookieMatches = (c, u) => {
    const h = u.hostname;
    const domainOk = c.hostOnly ? h === c.domain : h === c.domain || h.endsWith("." + c.domain);
    const pathOk = u.pathname === c.path || u.pathname.startsWith(c.path.endsWith("/") ? c.path : c.path + "/");
    return domainOk && pathOk && (!c.secure || u.protocol === "https:");
  };
  function storeCookies(u, headers) {
    const list = typeof headers.getSetCookie === "function" ? headers.getSetCookie() : [];
    for (const line of list) {
      const [pair, ...attrs] = line.split(";").map((s) => s.trim());
      const eq = pair.indexOf("=");
      if (eq <= 0) continue;
      const c = { name: pair.slice(0, eq), value: pair.slice(eq + 1), domain: u.hostname, hostOnly: true, path: "/", secure: false, expires: Number.MAX_SAFE_INTEGER };
      const dir = u.pathname.lastIndexOf("/");
      c.path = dir > 0 ? u.pathname.slice(0, dir) : "/";
      for (const a of attrs) {
        const [k, v = ""] = a.split("=");
        const key = k.toLowerCase();
        if (key === "domain" && v) {
          const d = v.replace(/^\./, "").toLowerCase();
          if (u.hostname !== d && !u.hostname.endsWith("." + d)) { c.domain = null; break; }
          c.domain = d; c.hostOnly = false;
        } else if (key === "path" && v.startsWith("/")) c.path = v;
        else if (key === "secure") c.secure = true;
        else if (key === "max-age") c.expires = Date.now() + Number(v) * 1000;
        else if (key === "expires" && c.expires === Number.MAX_SAFE_INTEGER) c.expires = Date.parse(v) || c.expires;
      }
      if (!c.domain) continue;
      const i = cookieJar.findIndex((x) => x.name === c.name && x.domain === c.domain && x.path === c.path);
      if (i !== -1) cookieJar.splice(i, 1);
      if (c.expires > Date.now()) cookieJar.push(c);
    }
    const perDomain = {};
    for (let i = cookieJar.length - 1; i >= 0; i--) {
      perDomain[cookieJar[i].domain] = (perDomain[cookieJar[i].domain] || 0) + 1;
      if (perDomain[cookieJar[i].domain] > contract.cookies.maxPerHost) cookieJar.splice(i, 1);
    }
    const size = () => cookieJar.reduce((n, c) => n + c.name.length + c.value.length + c.domain.length + c.path.length, 0);
    while (cookieJar.length && size() > contract.cookies.maxTotalBytes) cookieJar.shift();
    saveJson(cookiesFile, cookieJar);
  }
  const cookieHeader = (u) => { dropExpired(); return cookieJar.filter((c) => cookieMatches(c, u)).map((c) => `${c.name}=${c.value}`).join("; "); };

  function requestBody(body, headers) {
    const setType = (t) => { if (!Object.keys(headers).some((k) => k.toLowerCase() === "content-type")) headers["Content-Type"] = t; };
    if (body === undefined || body === null) return undefined;
    if (typeof body === "string") return body;
    if (typeof body !== "object") return String(body);
    if ("json" in body) { setType("application/json; charset=utf-8"); return JSON.stringify(body.json) ?? "null"; }
    if ("form" in body) {
      if (body.form === null || typeof body.form !== "object") throw kinoError("invalid_request", "body.form debe ser un objeto");
      headers["Content-Type"] = "application/x-www-form-urlencoded";
      // OkHttp's FormBody encoding: spaces as %20, not "+".
      return Object.keys(body.form).map((k) => encodeURIComponent(k) + "=" + encodeURIComponent(String(body.form[k]))).join("&");
    }
    if ("base64" in body) return Buffer.from(String(body.base64), "base64");
    throw kinoError("invalid_request", "body debe ser un texto, { json }, { form } o { base64 }");
  }

  const textual = (type) => !type || /charset=/i.test(type) || /^text\//i.test(type) || /[/+](json|xml)\b/i.test(type) || /javascript|x-www-form-urlencoded/i.test(type);

  function response(status, url, headerList, bytes) {
    const headers = {};
    for (const [k, v] of headerList) if (!k.startsWith("set-cookie")) headers[k] = headers[k] ? headers[k] + ", " + v : v;
    const buf = Buffer.from(bytes);
    const type = headers["content-type"];
    const charset = /charset=([^;]+)/i.exec(type || "");
    const decode = () => (charset && !/utf-?8/i.test(charset[1]) ? new TextDecoder(charset[1].trim()).decode(buf) : buf.toString("utf8"));
    return Object.freeze({
      ok: status >= 200 && status < 300, status, url, headers: Object.freeze(headers),
      text: () => (textual(type) ? decode() : buf.toString("utf8")),
      json: () => JSON.parse(textual(type) ? decode() : buf.toString("utf8")),
      base64: () => buf.toString("base64"),
    });
  }

  async function fetchGated(url, opts = {}) {
    const o = opts || {};
    let method = String(o.method === undefined ? "GET" : o.method).toUpperCase();
    if (!f.methods.includes(method)) throw kinoError("invalid_request", "método no permitido: " + method.slice(0, 20));
    const redirect = o.redirect === undefined ? "follow" : String(o.redirect);
    if (!f.redirectModes.includes(redirect)) throw kinoError("invalid_request", 'redirect debe ser "follow" o "manual"');
    const headers = {};
    for (const k of Object.keys(o.headers || {})) headers[k] = String(o.headers[k]);
    let body = requestBody(o.body, headers);
    const size = String(url).length + JSON.stringify(headers).length + (body ? (typeof body === "string" ? body.length : body.length * 2) : 0);
    if (size > f.maxRequestChars) throw kinoError("too_large", `solicitud demasiado grande (más de ${kb(f.maxRequestChars)})`);
    let current;
    try { current = new URL(String(url)); } catch { throw kinoError("invalid_request", "URL inválida: " + String(url).slice(0, 200)); }
    let previous = null;
    for (let hop = 0; hop <= f.maxRedirects; hop++) {
      gate(current, previous);
      if (++requests > f.maxRequestsPerCall) throw kinoError("invalid_request", `demasiadas solicitudes en una sola llamada (máximo ${f.maxRequestsPerCall})`);
      if (!Object.keys(headers).some((k) => k.toLowerCase() === "user-agent")) headers["User-Agent"] = `Kino/${appVersion} (plugin ${manifest.id})`;
      const sendHeaders = { ...headers };
      if (o.cookies !== false) { const c = cookieHeader(current); if (c) sendHeaders.Cookie = c; }
      const key = JSON.stringify([method, current.toString(), typeof body === "string" ? body : body ? body.toString("base64") : null]);
      let status, headerList, bytes;
      const taped = tape && replay ? tape.find((t) => t.key === key) : null;
      if (replay) {
        if (!taped) throw kinoError("network", "--replay: no recorded answer for " + method + " " + current);
        ({ status, headers: headerList } = taped);
        bytes = Buffer.from(taped.body, "base64");
      } else {
        const requested = Math.trunc(Number(o.timeoutMs));
        const timeoutMs = Number.isFinite(requested) && requested > 0 ? Math.min(requested, f.maxTimeoutMs) : f.defaultTimeoutMs;
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeoutMs);
        let r;
        try {
          r = await fetchImpl(current, { method, headers: sendHeaders, body: ["POST", "PUT", "PATCH"].includes(method) ? body ?? "" : undefined, redirect: "manual", signal: controller.signal });
          bytes = Buffer.from(await r.arrayBuffer());
        } catch (e) {
          throw kinoError(controller.signal.aborted ? "timeout" : "network", controller.signal.aborted ? "la solicitud tardó demasiado" : "error de red: " + String(e.message).slice(0, 200));
        } finally {
          clearTimeout(timer);
        }
        status = r.status;
        headerList = [...r.headers].map(([k, v]) => [k.toLowerCase(), v]);
        if (o.cookies !== false) storeCookies(current, r.headers);
        if (tape) tape.push({ key, status, headers: headerList.filter(([k]) => !k.startsWith("set-cookie")), body: bytes.toString("base64") });
      }
      if (bytes.length > f.maxBodyBytes) throw kinoError("too_large", `respuesta demasiado grande (más de ${kb(f.maxBodyBytes)})`);
      const location = headerList.find(([k]) => k === "location");
      if ([301, 302, 303, 307, 308].includes(status) && location && redirect === "follow") {
        if (status === 303 || ((status === 301 || status === 302) && method === "POST")) { method = "GET"; body = undefined; }
        previous = current;
        current = new URL(location[1], current);
        continue;
      }
      return response(status, current.toString(), headerList, bytes);
    }
    throw kinoError("network", "demasiadas redirecciones");
  }

  // --- crypto (node:crypto), same names, encodings and errors as the app ---
  const k = contract.crypto;
  const buf = (v, enc, field) => {
    if (typeof v !== "string") throw kinoError("crypto_error", `falta "${field}"`);
    if (!k.encodings.includes(enc)) throw kinoError("crypto_error", "codificación desconocida: " + String(enc).slice(0, 20));
    if (enc === "hex" && (v.length % 2 !== 0 || /[^0-9a-f]/i.test(v))) throw kinoError("crypto_error", `"${field}" no es hexadecimal válido`);
    if (enc === "base64" && /[^A-Za-z0-9+/=_\-\s]/.test(v)) throw kinoError("crypto_error", `"${field}" no es base64 válido`);
    const b = Buffer.from(v, enc === "utf8" ? "utf8" : enc === "hex" ? "hex" : "base64");
    if (b.length > k.maxDataBytes) throw kinoError("crypto_error", `"${field}" pasa de ${kb(k.maxDataBytes)}`);
    return b;
  };
  const out = (b, enc) => {
    if (!k.encodings.includes(enc)) throw kinoError("crypto_error", "codificación desconocida: " + String(enc).slice(0, 20));
    return b.toString(enc === "utf8" ? "utf8" : enc);
  };
  function cipher(decrypt, alg, p = {}) {
    if (!k.ciphers.includes(alg)) throw kinoError("crypto_error", "cifrado desconocido: " + String(alg).slice(0, 20));
    const key = buf(p.key, p.keyEncoding || "utf8", "key");
    const data = buf(p.data, p.inputEncoding || (decrypt ? "base64" : "utf8"), "data");
    const bits = alg.startsWith("des") ? 192 : Number(alg.split("-")[1]);
    if (key.length * 8 !== bits) throw kinoError("crypto_error", `la clave de ${alg} debe tener ${bits / 8} bytes, tiene ${key.length}`);
    const mode = alg.split("-")[2];
    const iv = mode === "ecb" ? null : buf(p.iv, p.ivEncoding || "utf8", "iv");
    const padding = p.padding === undefined ? "pkcs7" : p.padding;
    if (padding !== "pkcs7" && padding !== "none") throw kinoError("crypto_error", "relleno desconocido: " + String(padding).slice(0, 20));
    try {
      if (mode === "gcm") {
        const c = decrypt ? createDecipheriv(alg, key, iv) : createCipheriv(alg, key, iv);
        if (p.aad !== undefined) c.setAAD(buf(p.aad, p.aadEncoding || "utf8", "aad"));
        if (decrypt) {
          if (data.length < 16) throw kinoError("crypto_error", "al texto cifrado le falta la etiqueta de 16 bytes");
          c.setAuthTag(data.subarray(data.length - 16));
          return out(Buffer.concat([c.update(data.subarray(0, data.length - 16)), c.final()]), p.outputEncoding || "utf8");
        }
        return out(Buffer.concat([c.update(data), c.final(), c.getAuthTag()]), p.outputEncoding || "base64");
      }
      const c = decrypt ? createDecipheriv(alg, key, iv) : createCipheriv(alg, key, iv);
      if (mode !== "ctr") c.setAutoPadding(padding === "pkcs7");
      return out(Buffer.concat([c.update(data), c.final()]), p.outputEncoding || (decrypt ? "utf8" : "base64"));
    } catch (e) {
      if (e.code && String(e.code).startsWith("KinoError")) throw e;
      if (e.name && e.name.startsWith("KinoError")) throw e;
      throw kinoError("crypto_error", decrypt ? "no se pudo descifrar: clave o iv equivocados" : "operación criptográfica inválida");
    }
  }
  const crypto = Object.freeze({
    hash(alg, data, p = {}) {
      if (!k.hashes.includes(alg)) throw kinoError("crypto_error", "algoritmo de hash desconocido: " + String(alg).slice(0, 20));
      return out(createHash(alg).update(buf(String(data), p.inputEncoding || "utf8", "data")).digest(), p.outputEncoding || "hex");
    },
    hmac(alg, key, data, p = {}) {
      if (!k.hashes.includes(alg)) throw kinoError("crypto_error", "algoritmo de hmac desconocido: " + String(alg).slice(0, 20));
      const keyBuf = buf(String(key), p.keyEncoding || "utf8", "key");
      if (!keyBuf.length) throw kinoError("crypto_error", "la clave del hmac está vacía");
      return out(createHmac(alg, keyBuf).update(buf(String(data), p.inputEncoding || "utf8", "data")).digest(), p.outputEncoding || "hex");
    },
    encrypt: (alg, p) => cipher(false, alg, p),
    decrypt: (alg, p) => cipher(true, alg, p),
    pbkdf2(hash, password, salt, iterations, keyLength, p = {}) {
      if (!k.pbkdf2Hashes.includes(hash)) throw kinoError("crypto_error", "hash de pbkdf2 desconocido: " + String(hash).slice(0, 20));
      if (!Number.isInteger(iterations) || iterations < 1 || iterations > k.pbkdf2MaxIterations) throw kinoError("crypto_error", `iteraciones de pbkdf2 entre 1 y ${k.pbkdf2MaxIterations}`);
      if (!Number.isInteger(keyLength) || keyLength < 1 || keyLength > k.pbkdf2MaxKeyBytes) throw kinoError("crypto_error", `longitud de clave de pbkdf2 entre 1 y ${k.pbkdf2MaxKeyBytes} bytes`);
      return out(pbkdf2Sync(buf(String(password), p.keyEncoding || "utf8", "password"), buf(String(salt), p.inputEncoding || "utf8", "salt"), iterations, keyLength, hash), p.outputEncoding || "hex");
    },
    randomBytes(n, enc = "hex") {
      if (!Number.isInteger(n) || n < 1 || n > k.randomMaxBytes) throw kinoError("crypto_error", `randomBytes acepta de 1 a ${k.randomMaxBytes} bytes`);
      return out(randomBytes(n), enc);
    },
    uuid: () => randomUUID(),
  });

  const kino = Object.freeze({
    apiVersion: contract.apiVersion,
    appVersion,
    lang,
    fetch: fetchGated,
    html: Object.freeze({
      select() {
        throw new Error("kino.html.select only exists inside Kino (it uses Jsoup): test it by installing the plugin in the app");
      },
    }),
    storage: Object.freeze({
      get: (key) => (Object.prototype.hasOwnProperty.call(storage, String(key)) ? storage[String(key)] : null),
      set: (key, v) => {
        const previous = storage[String(key)];
        storage[String(key)] = String(v);
        if (Buffer.byteLength(JSON.stringify(storage)) > contract.storage.maxTotalBytes) {
          if (previous === undefined) delete storage[String(key)]; else storage[String(key)] = previous;
          throw new Error(`almacenamiento del plugin lleno (${kb(contract.storage.maxTotalBytes)})`);
        }
        saveJson(storageFile, storage);
      },
      remove: (key) => { delete storage[String(key)]; saveJson(storageFile, storage); },
      keys: () => Object.keys(storage),
    }),
    config: Object.freeze({
      get: (key) => values[String(key)],
      all: () => ({ ...values }),
    }),
    cookies: Object.freeze({
      get(url, name) {
        let u;
        try { u = new URL(String(url)); gate(u, null); } catch { return null; }
        const c = cookieJar.filter((x) => cookieMatches(x, u) && x.name === String(name)).pop();
        return c ? c.value : null;
      },
      clear() { cookieJar.length = 0; saveJson(cookiesFile, cookieJar); },
    }),
    crypto,
    async sleep(ms) {
      await null;
      if (!Number.isInteger(ms) || ms < 0 || ms > contract.sleep.maxMs) throw kinoError("invalid_request", `kino.sleep acepta de 0 a ${contract.sleep.maxMs} ms`);
      await new Promise((resolve) => setTimeout(resolve, ms));
    },
    error: (code, message) => kinoError(code, message),
    log: (...args) => writeErr("[kino.log]", ...args),
  });

  return {
    kino,
    servers: servers.map((s) => s.toString()),
    resetBudget: () => { requests = 0; },
    saveTape: () => { if (record && tape) saveJson(record, tape); },
  };
}
