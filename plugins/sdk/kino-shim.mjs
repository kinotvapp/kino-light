// Node stand-in for the `kino` global Kino gives plugins (see GUIDE.md). Same shapes, same host
// check, same caps and error codes as the app, read from contract.json. Node 18+ (global fetch).
//
// Kino's own app code is authoritative: its own network and host-gate logic decide what a plugin
// may really do. This file only APPROXIMATES their host, redirect and request-cap rules so you can
// develop locally; if the two ever disagree, the app is right. Known differences:
// kino.html.select exists only in the app (it uses Jsoup); a host that resolves to a private
// address is not refused; the cookie jar keeps name/value/domain/path/expiry/secure but not every
// RFC 6265 corner; nothing enforces the per-call time or memory limits.
//
// Sealed secrets (spec 2026-09-29-plugin-sealed-secrets-design.md §5, §6): this kit can never open
// a seal -- only the app, with the native private key, can -- so it reads the PLAIN values straight
// from `.kino-secrets.json` next to the manifest (`{ "<name>": "<value>" }`, written by hand during
// development; `seal.mjs` only ever produces the sealed string that goes in the manifest) and
// simulates the app's marker, substitution, declared-host-and-https and redaction rules on top of that.
import { createCipheriv, createDecipheriv, createHash, createHmac, pbkdf2Sync, randomBytes, randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { contract, hostMatches, isUserServer, kb, schemeAllowed } from "./contract.mjs";
import { filterRelevant, shortQuery, sortBySimilarity } from "./kino-rank.mjs";

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

// --- sealed secrets: the same encodings, marker shape and redaction the app's PluginSecrets uses
// (spec 2026-09-29-plugin-sealed-secrets §5, §6). The kit never opens a seal: it reads the plain
// value straight from .kino-secrets.json, so it can simulate substitution and redaction without
// the app's X25519 native bridge. ---

const SEALED_CRYPTO_REFUSED = "no se puede usar un dato sellado aquí";

/** RFC 3986 unreserved bytes percent-encoded (UTF-8, uppercase hex): a URL path segment or query value. */
function encodeUrlComponent(value) {
  let out = "";
  for (const byte of Buffer.from(value, "utf8")) {
    const ch = String.fromCharCode(byte);
    if ((byte >= 0x41 && byte <= 0x5a) || (byte >= 0x61 && byte <= 0x7a) || (byte >= 0x30 && byte <= 0x39) || ch === "-" || ch === "." || ch === "_" || ch === "~") out += ch;
    else out += "%" + byte.toString(16).toUpperCase().padStart(2, "0");
  }
  return out;
}

/**
 * [value] inside a JSON string, without the quotes -- the app's PluginSecrets.jsonEscape: `"` and
 * `\\` escaped, the short escapes for the usual control characters and `\\u00XX` for the rest; `/`
 * too when [slash], and every UTF-16 unit past ASCII as `\\uXXXX` when [nonAscii] (a character past
 * the BMP as its two surrogates). [upper] picks the hex digits' case.
 */
function jsonEscape(value, { slash = false, nonAscii = false, upper = true } = {}) {
  const hex4 = (code) => { const h = code.toString(16).padStart(4, "0"); return "\\u" + (upper ? h.toUpperCase() : h); };
  let out = "";
  for (let i = 0; i < value.length; i++) {
    const ch = value[i];
    const code = value.charCodeAt(i);
    if (ch === '"') out += '\\"';
    else if (ch === "\\") out += "\\\\";
    else if (ch === "/" && slash) out += "\\/";
    else if (ch === "\n") out += "\\n";
    else if (ch === "\r") out += "\\r";
    else if (ch === "\t") out += "\\t";
    else if (ch === "\b") out += "\\b";
    else if (ch === "\f") out += "\\f";
    else if (code < 0x20 || (nonAscii && code > 0x7f)) out += hex4(code);
    else out += ch;
  }
  return out;
}

/** Inside a JSON string, without the surrounding quotes: what the app substitutes into a JSON body. */
const encodeJsonStringBody = (value) => jsonEscape(value);

/** `java.net.URLEncoder.encode(value, "UTF-8")`: a form's own encoding, "+" for a space. */
function javaFormEncode(value) {
  let out = "";
  for (const byte of Buffer.from(value, "utf8")) {
    const ch = String.fromCharCode(byte);
    if ((byte >= 0x41 && byte <= 0x5a) || (byte >= 0x61 && byte <= 0x7a) || (byte >= 0x30 && byte <= 0x39) || ch === "-" || ch === "_" || ch === "." || ch === "*") out += ch;
    else if (byte === 0x20) out += "+";
    else out += "%" + byte.toString(16).toUpperCase().padStart(2, "0");
  }
  return out;
}

function encodeSealedValue(value, encoding) {
  if (encoding === "url") return encodeUrlComponent(value);
  if (encoding === "json") return encodeJsonStringBody(value);
  return value;
}

/**
 * Every form [redact] replaces for an opened [plain] value, the same list as the app's
 * PluginSecrets.echoForms: what [substitute] writes (raw, the URL encoding, the JSON encoding), and
 * how a server commonly echoes a value back (URLEncoder's form encoding and its "%20" twin, base64
 * and base64url, with and without padding, and inside a JSON string with `/` as `\\/` and non-ASCII
 * as `\\uXXXX` -- PHP's json_encode, Python's json.dumps -- in either hex case, and each combination).
 */
function echoForms(plain) {
  const bytes = Buffer.from(plain, "utf8");
  const b64 = bytes.toString("base64");
  const b64url = b64.replace(/\+/g, "-").replace(/\//g, "_");
  const form = javaFormEncode(plain);
  const json = [];
  for (const upper of [true, false]) for (const slash of [false, true]) for (const nonAscii of [false, true]) json.push(jsonEscape(plain, { slash, nonAscii, upper }));
  return new Set([
    plain, encodeUrlComponent(plain), encodeJsonStringBody(plain), ...json,
    form, form.replace(/\+/g, "%20"),
    b64, b64.replace(/=+$/, ""), b64url, b64url.replace(/=+$/, ""),
  ]);
}

/**
 * One runtime's sealed secrets: [manifest.secrets]' names, each given a marker
 * (`__kinoSecret_<name>_<nonce>__`) random per runtime. The kit reads plain values from
 * [secretsFile] (`.kino-secrets.json`, `{ "<name>": "<value>" }`), opening one on its first
 * substitution -- a name declared in the manifest but missing there throws then -- and every one the
 * file has on the first redaction of non-empty text, like the app (a value can come back before
 * this runtime used it: a cookie an earlier one set, a server echo). Null when the manifest
 * declares no secrets, exactly like the app's `pluginSecretsFor`.
 */
function pluginSecretsFor(manifest, secretsFile) {
  const names = Object.keys(manifest.secrets || {});
  if (names.length === 0) return null;
  const nonce = randomBytes(8).toString("hex");
  const markers = Object.fromEntries(names.map((n) => [n, `__kinoSecret_${n}_${nonce}__`]));
  const values = loadJson(secretsFile, {});
  const opened = {};
  const marker = (name) => markers[name];
  const containsMarker = (text) => typeof text === "string" && Object.values(markers).some((m) => text.includes(m));
  const isMarker = (text) => typeof text === "string" && Object.values(markers).includes(text);
  function plainOf(name) {
    if (!(name in opened)) {
      if (!Object.prototype.hasOwnProperty.call(values, name)) {
        throw new Error(`falta el valor del secreto ${name} en .kino-secrets.json`);
      }
      opened[name] = String(values[name]);
    }
    return opened[name];
  }
  /** Every declared value .kino-secrets.json has, opened once: what the app's openAll does before redacting. */
  let allOpened = false;
  function openAll() {
    if (allOpened) return;
    for (const name of names) if (!(name in opened) && Object.prototype.hasOwnProperty.call(values, name)) opened[name] = String(values[name]);
    allOpened = true;
  }
  function substitute(text, encoding = "raw") {
    let out = text;
    for (const [name, m] of Object.entries(markers)) if (out.includes(m)) out = out.split(m).join(encodeSealedValue(plainOf(name), encoding));
    return out;
  }
  /**
   * Every opened value, in every form, replaced by [placeholderFor](name), in ONE left-to-right pass
   * over [text]: the leftmost occurrence of any form is replaced, the longest form when several start
   * at that same position, and the scan continues past it -- like the app's
   * PluginSecrets.Redaction.replaceIn. A short value that happens to be a substring of another form,
   * or of a placeholder just inserted, is never matched a second time once the pass is past it: doing
   * one full pass per form (mutating the text before the next, shorter form's pass ran over it) could
   * match a short secret's value INSIDE a placeholder an earlier, longer pass had just written. Plain
   * substring search (`indexOf`), never a regex: the caps (16 secrets, 4096 bytes each, ~20 forms)
   * can build a combined alternation past V8's regex size limit -- measured, not hypothetical.
   * [text] unchanged when nothing opened is found in it.
   */
  function redactWith(text, placeholderFor) {
    if (typeof text !== "string" || text === "") return text;
    openAll();
    const forms = [];
    for (const [name, plain] of Object.entries(opened)) {
      if (!plain) continue;
      const placeholder = placeholderFor(name);
      for (const f of echoForms(plain)) if (f) forms.push([f, placeholder]);
    }
    if (forms.length === 0) return text;
    forms.sort((a, b) => b[0].length - a[0].length); // longest first: on a position tie the longest form wins.
    const next = forms.map(([f]) => text.indexOf(f));
    let out = "";
    let cursor = 0;
    for (;;) {
      let best = -1;
      for (let i = 0; i < forms.length; i++) if (next[i] >= 0 && (best < 0 || next[i] < next[best])) best = i;
      if (best < 0) break;
      const [form, placeholder] = forms[best];
      out += text.slice(cursor, next[best]) + placeholder;
      cursor = next[best] + form.length;
      // Every form's occurrence the replacement covered (including the one just used) moves past it.
      for (let i = 0; i < forms.length; i++) if (next[i] >= 0 && next[i] < cursor) next[i] = text.indexOf(forms[i][0], cursor);
    }
    return out + text.slice(cursor);
  }
  const redact = (text) => redactWith(text, (name) => markers[name]);
  /** True when [text] holds a declared value in any form [redact] replaces. */
  function containsValue(text) {
    if (typeof text !== "string" || text === "") return false;
    openAll();
    return Object.values(opened).some((plain) => plain && [...echoForms(plain)].some((f) => text.includes(f)));
  }
  // A stable, nonce-free placeholder for [record]/[replay]'s tape (spec §6): the marker itself is
  // random per runtime, so a tape keyed or written with it could never match a later run (a fresh
  // `--replay` process gets a different nonce than the `--record` one that made the tape) -- and a
  // record-time nonce baked into a committed fixtures.json would be a small, pointless leak of its
  // own. Keyed by name only, so two runtimes that open the same secret from the same
  // .kino-secrets.json always agree on it. A real NUL byte can't come from a marker or an opened
  // value's own forms, and no plugin or server text this kit handles has a legitimate reason to hold
  // one -- but a HAND-EDITED tape file's `\u0000` JSON escape decodes to one, so this is a
  // replay-only, self-inflicted collision risk, not a guarantee against arbitrary input.
  const canonicalToken = (name) => `\u0000kino-secret:${name}\u0000`;
  const redactToCanonical = (text) => redactWith(text, canonicalToken);
  /** The reverse of [redactToCanonical]'s placeholder: THIS runtime's own marker, for a taped answer read back on replay. */
  function fromCanonical(text) {
    if (typeof text !== "string") return text;
    let out = text;
    for (const name of names) {
      const token = canonicalToken(name);
      if (out.includes(token)) out = out.split(token).join(markers[name]);
    }
    return out;
  }
  return { marker, containsMarker, isMarker, substitute, redact, containsValue, redactToCanonical, fromCanonical, sealedHosts: manifest.hosts || [] };
}

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
export function createKino(manifest, { appVersion = "sdk", lang = "es-CO", storageFile = null, cookiesFile = null, secretsFile = null, config = {}, record = null, replay = null, fetchImpl = globalThis.fetch } = {}) {
  const f = contract.fetch;
  const storage = loadJson(storageFile, {});
  // An entry is a bare string (permanent, the format before ttlMs existed) or { v, e } (expires at
  // epoch ms `e`). Dropped lazily, on the next read or write that touches this instance -- never a
  // background timer -- so it stops counting against the cap the moment it is noticed.
  const storageEntryValue = (raw) => (raw !== null && typeof raw === "object" ? raw.v : raw);
  const purgeExpiredStorage = () => {
    const now = Date.now();
    let changed = false;
    for (const k of Object.keys(storage)) {
      const raw = storage[k];
      if (raw !== null && typeof raw === "object" && typeof raw.e === "number" && raw.e <= now) { delete storage[k]; changed = true; }
    }
    if (changed) saveJson(storageFile, storage);
  };
  const cookieJar = loadJson(cookiesFile, []);
  const tape = replay ? loadJson(replay, null) : record ? [] : null;
  if (replay && !tape) throw new Error(`--replay: ${replay} not found`);
  let requests = 0;

  // A plugin whose manifest declares secrets: markers, substitution, the declared-host-and-https
  // rule (checkSealedHost below) and redaction, all simulated without opening the seal.
  const pluginSecrets = pluginSecretsFor(manifest, secretsFile);
  const redact = (text) => (pluginSecrets ? pluginSecrets.redact(text) : text);

  const values = {};
  for (const s of manifest.settings || []) {
    // A url setting never takes a manifest default (the app refuses one): only a typed server counts.
    const v = config[s.key] !== undefined ? config[s.key] : s.default !== undefined && contract.settings.types[s.type]?.canHaveDefault !== false ? s.default : s.type === "toggle" ? false : s.type === "select" ? s.options[0].value : undefined;
    if (s.type === "list") {
      // Entries with only their own fields, trimmed, all-blank ones dropped (what the app stores).
      const entries = (Array.isArray(v) ? v : []).map((e) => Object.fromEntries((s.fields || []).map((f) => [f.key, String(e?.[f.key] ?? "").trim()]))).filter((e) => Object.values(e).some(Boolean));
      if (entries.length) values[s.key] = entries;
    } else if (v !== undefined && v !== "") values[s.key] = s.type === "toggle" ? v === true || v === "true" : String(v);
  }
  const typedUrls = (manifest.settings || []).flatMap((s) => s.type === "url" ? [values[s.key]] : s.type === "list" ? (values[s.key] || []).flatMap((e) => (s.fields || []).filter((f) => f.type === "url").map((f) => e[f.key])) : []);
  const servers = typedUrls.filter((u) => typeof u === "string" && isUserServer(u)).map((u) => new URL(u.trim()));
  const port = (u) => u.port || (u.protocol === "https:" ? "443" : "80");
  const serverOf = (u) => servers.find((s) => s.protocol === u.protocol && s.hostname === u.hostname && port(s) === port(u));

  function gate(u, from) {
    const typed = serverOf(u);
    if (typed) {
      if (from && serverOf(from) && serverOf(from) !== typed) throw kinoError("host_not_allowed", "host no permitido: " + u.hostname);
      return;
    }
    if (!hostMatches(u.hostname, manifest.hosts)) throw kinoError("host_not_allowed", "host no permitido: " + u.hostname);
    if (!schemeAllowed(u, manifest)) throw kinoError("host_not_allowed", "solo se permite https");
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
    for (const [k, v] of headerList) if (!k.startsWith("set-cookie")) headers[k] = headers[k] ? headers[k] + ", " + redact(v) : redact(v);
    const buf = Buffer.from(bytes);
    const type = headers["content-type"];
    const charset = /charset=([^;]+)/i.exec(type || "");
    const isLatin1 = (cs) => /^\s*(iso-?8859-1|latin-?1|us-ascii)\s*$/i.test(cs);
    /**
     * Node's `TextDecoder("iso-8859-1")` actually decodes windows-1252 (the WHATWG "iso-8859-1" label
     * aliases to it), which disagrees with `Buffer.from(str, "latin1")` on bytes 0x80-0x9F -- that one
     * maps every byte straight to the code point of the same number, true ISO-8859-1. [base64] below
     * re-encodes with `"latin1"`, so decoding those bytes with `TextDecoder` first would silently
     * change them on the round trip. Read with `Buffer.toString("latin1")` here instead, so decode and
     * the encode in [base64] agree on the same (true Latin-1) mapping for every byte.
     */
    const decode = () => (!charset || /utf-?8/i.test(charset[1]) ? buf.toString("utf8") : isLatin1(charset[1]) ? buf.toString("latin1") : new TextDecoder(charset[1].trim()).decode(buf));
    /**
     * The app's base64 twin (DefaultPluginHost.base64): a binary body's bytes as they came (not
     * scanned); a text body's bytes re-encoded from the REDACTED text in its own charset when
     * redaction changed it -- and when the twin's bytes, read as UTF-8 or as Latin-1, still hold a
     * value (a wrong declared charset), the redacted text's UTF-8 bytes instead.
     */
    function base64() {
      if (!pluginSecrets || !textual(type)) return buf.toString("base64");
      const raw = decode();
      const redacted = redact(raw);
      const enc = !charset || /utf-?8/i.test(charset[1]) ? "utf8" : isLatin1(charset[1]) ? "latin1" : null;
      const twin = redacted === raw ? buf : enc ? Buffer.from(redacted, enc) : null;
      if (!twin || pluginSecrets.containsValue(twin.toString("utf8")) || pluginSecrets.containsValue(twin.toString("latin1"))) return Buffer.from(redacted, "utf8").toString("base64");
      return twin.toString("base64");
    }
    return Object.freeze({
      ok: status >= 200 && status < 300, status, url: redact(url), headers: Object.freeze(headers),
      text: () => redact(textual(type) ? decode() : buf.toString("utf8")),
      json: () => JSON.parse(redact(textual(type) ? decode() : buf.toString("utf8"))),
      base64,
    });
  }

  /**
   * One hop of a request carrying a sealed value (spec §5): only to a host the MANIFEST declares
   * (never a typed server or one approved reactively -- the app has no reactive approval in the
   * kit either), and only over https -- checked before the ordinary [gate], which still applies on
   * top for a request that carries no secret.
   */
  function checkSealedHost(u, allowed) {
    if (!hostMatches(u.hostname, allowed)) {
      throw kinoError("host_not_allowed", "este plugin no puede enviar datos sellados a " + u.hostname.slice(0, 100));
    }
    if (u.protocol !== "https:") {
      throw kinoError("host_not_allowed", "este plugin no puede enviar datos sellados sin https a " + u.hostname.slice(0, 100));
    }
  }

  const badHeaderChar = (ch) => { const cp = ch.codePointAt(0); return cp !== 9 && (cp < 0x20 || cp > 0x7e); };

  async function fetchGated(url, opts = {}) {
    const o = opts || {};
    let method = String(o.method === undefined ? "GET" : o.method).toUpperCase();
    if (!f.methods.includes(method)) throw kinoError("invalid_request", "método no permitido: " + method.slice(0, 20));
    const redirect = o.redirect === undefined ? "follow" : String(o.redirect);
    if (!f.redirectModes.includes(redirect)) throw kinoError("invalid_request", 'redirect debe ser "follow" o "manual"');
    const headers = {};
    for (const k of Object.keys(o.headers || {})) headers[k] = String(o.headers[k]);
    let carriesSecret = false;
    // The size cap is checked BEFORE any substitution, on every body kind alike -- exactly like the
    // app, where the prelude computes it on the marker-laden request before it ever crosses to the
    // Kotlin side that substitutes.
    let body = requestBody(o.body, headers);
    const size = String(url).length + JSON.stringify(headers).length + (body ? (typeof body === "string" ? body.length : body.length * 2) : 0);
    if (size > f.maxRequestChars) throw kinoError("too_large", `solicitud demasiado grande (más de ${kb(f.maxRequestChars)})`);
    let current;
    try { current = new URL(String(url)); } catch { throw kinoError("invalid_request", "URL inválida: " + String(url).slice(0, 200)); }
    if (pluginSecrets) {
      // Headers: substituted raw, then refused if the plain value put a character OkHttp can't
      // send in a header (a line break or anything else outside tab/space..tilde) -- named only by
      // the header, never the value.
      for (const name of Object.keys(headers)) {
        if (!pluginSecrets.containsMarker(headers[name])) continue;
        carriesSecret = true;
        const substituted = pluginSecrets.substitute(headers[name]);
        if ([...substituted].some(badHeaderChar)) {
          throw kinoError("invalid_request", `el encabezado ${name.slice(0, 40)} no puede llevar este dato sellado: tiene caracteres no permitidos`);
        }
        headers[name] = substituted;
      }
      // The body: a form's fields are substituted RAW at the object level and re-encoded (so a
      // plain value with a "&", "=" or space is percent-encoded like any other form value, never
      // spliced unescaped into the wire body); a JSON body is JSON-string-escaped in its already-
      // serialized text; a text body is substituted raw.
      const isForm = o.body && typeof o.body === "object" && o.body.form && typeof o.body.form === "object";
      if (isForm && pluginSecrets.containsMarker(body)) {
        carriesSecret = true;
        const substitutedForm = {};
        for (const [fk, fv] of Object.entries(o.body.form)) {
          substitutedForm[pluginSecrets.substitute(String(fk))] = pluginSecrets.substitute(String(fv));
        }
        body = requestBody({ ...o.body, form: substitutedForm }, headers);
      } else if (typeof body === "string" && pluginSecrets.containsMarker(body)) {
        carriesSecret = true;
        const isJson = o.body && typeof o.body === "object" && "json" in o.body;
        body = pluginSecrets.substitute(body, isJson ? "json" : "raw");
      }
      // The URL: only the path and the query (never the scheme, userinfo, host, port or fragment --
      // a plain value must never become a host name, looked up in DNS or logged as one).
      if (pluginSecrets.containsMarker(current.pathname) || pluginSecrets.containsMarker(current.search)) {
        carriesSecret = true;
        if (pluginSecrets.containsMarker(current.pathname)) current.pathname = pluginSecrets.substitute(current.pathname, "url");
        if (pluginSecrets.containsMarker(current.search)) current.search = pluginSecrets.substitute(current.search, "url");
      }
    }
    let previous = null;
    for (let hop = 0; hop <= f.maxRedirects; hop++) {
      if (carriesSecret) checkSealedHost(current, pluginSecrets.sealedHosts);
      gate(current, previous);
      if (++requests > f.maxRequestsPerCall) throw kinoError("invalid_request", `demasiadas solicitudes en una sola llamada (máximo ${f.maxRequestsPerCall})`);
      if (!Object.keys(headers).some((k) => k.toLowerCase() === "user-agent")) headers["User-Agent"] = `Kino/${appVersion} (plugin ${manifest.id})`;
      const sendHeaders = { ...headers };
      if (o.cookies !== false) { const c = cookieHeader(current); if (c) sendHeaders.Cookie = c; }
      // A tape (--record/--replay) is keyed and stored in a nonce-free, per-name CANONICAL form,
      // never the plain value and never the runtime's own random marker: [record, then replay
      // offline]'s author commits fixtures.json, so it must never carry a secret to disk -- and a
      // marker's nonce is random per runtime, so a plain marker in the key would never match again
      // once a fresh `--replay` process (a different nonce than the `--record` one) looked it up.
      // redactToCanonical/fromCanonical are no-ops without pluginSecrets, so an ordinary plugin's
      // key and tape are unchanged.
      const canon = (text) => (pluginSecrets ? pluginSecrets.redactToCanonical(text) : text);
      const uncanon = (text) => (pluginSecrets ? pluginSecrets.fromCanonical(text) : text);
      // Each piece is canonicalized BEFORE it is composed into the key's JSON array, never after: a
      // JSON body already holds a value in its OWN (single) JSON-string escaping, and stringifying
      // it a second time as one element of this array would escape it again (a `"` or `\` doubled),
      // so an echo form that only matches the single-escaped text would silently miss the doubled one.
      const bodyKeyPart = typeof body === "string" ? canon(body) : body ? canon(body.toString("base64")) : null;
      const key = JSON.stringify([canon(method), canon(current.toString()), bodyKeyPart]);
      let status, headerList, bytes;
      const taped = tape && replay ? tape.find((t) => t.key === key) : null;
      if (replay) {
        if (!taped) throw kinoError("network", redact("--replay: no recorded answer for " + method + " " + current));
        ({ status, headers: headerList } = taped);
        headerList = headerList.map(([k2, v]) => [k2, uncanon(v)]);
        bytes = Buffer.from(taped.body, "base64");
        if (pluginSecrets) {
          const asText = bytes.toString("utf8");
          if (Buffer.from(asText, "utf8").equals(bytes)) {
            const restored = uncanon(asText);
            if (restored !== asText) bytes = Buffer.from(restored, "utf8");
          }
        }
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
          if (controller.signal.aborted) throw kinoError("timeout", "la solicitud tardó demasiado");
          // Not cut to 200 chars for a sealed request: its detail may quote what the server echoed
          // back, and a value straddling the cut would leave a piece redaction can't recognize --
          // redacted whole here, then kinoError's own cap still applies.
          const detail = redact(String(e.message));
          throw kinoError("network", "error de red: " + (carriesSecret ? detail : detail.slice(0, 200)));
        } finally {
          clearTimeout(timer);
        }
        status = r.status;
        headerList = [...r.headers].map(([k, v]) => [k.toLowerCase(), v]);
        if (o.cookies !== false) storeCookies(current, r.headers);
        if (tape) {
          // The recorded answer, canonicalized too: a server that echoes the secret back (an echo
          // endpoint, a test fixture) must never write its plain value -- or a marker some OTHER
          // runtime will never recognize -- into the tape file.
          const storedHeaders = headerList.filter(([k]) => !k.startsWith("set-cookie")).map(([k, v]) => [k, canon(v)]);
          let storedBody = bytes.toString("base64");
          if (pluginSecrets) {
            const asText = bytes.toString("utf8");
            // Only rewrite a body that round-trips as UTF-8 text -- true binary bytes are left as
            // recorded (spec: binary bodies are not scanned), and can't hold a text-form value.
            if (Buffer.from(asText, "utf8").equals(bytes)) {
              const canonText = canon(asText);
              if (canonText !== asText) storedBody = Buffer.from(canonText, "utf8").toString("base64");
            }
          }
          tape.push({ key, status, headers: storedHeaders, body: storedBody });
        }
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
  // A marker in `data`, `iv` or `aad` is refused whatever the key is (spec §5): those are paths
  // that hand the value back or let it be computed (a sealed iv or aad falls to CBC-vs-ECB /
  // GHASH's E_K(0), even under a sealed key). Checked before anything else, exactly like the app.
  const sealedIn = (v) => typeof v === "string" && pluginSecrets && pluginSecrets.containsMarker(v);
  const refuseSealedDataLike = (p) => {
    if (sealedIn(p.data) || sealedIn(p.iv) || sealedIn(p.aad)) throw kinoError("crypto_error", SEALED_CRYPTO_REFUSED);
  };
  /** A key-like string (cipher key, hmac key, pbkdf2 password/salt) substituted if it holds a marker. */
  const openKeyLike = (v) => (sealedIn(v) ? pluginSecrets.substitute(v) : v);
  const redactOut = (v) => (pluginSecrets ? pluginSecrets.redact(v) : v);

  function cipher(decrypt, alg, p = {}) {
    if (pluginSecrets) {
      refuseSealedDataLike(p);
      // A cipher key must be EXACTLY one marker, nothing before or after it: the rest of a longer
      // key would be known, and peeling it off shrinks the search to the secret alone.
      if (sealedIn(p.key) && !pluginSecrets.isMarker(p.key)) throw kinoError("crypto_error", SEALED_CRYPTO_REFUSED);
      // And only as an AES key: a des-ede3 key read under a JS-chosen keyEncoding can carry little
      // entropy per byte, which puts it in reach of a search.
      if (sealedIn(p.key) && !String(alg).startsWith("aes-")) throw kinoError("crypto_error", SEALED_CRYPTO_REFUSED);
    }
    if (!k.ciphers.includes(alg)) throw kinoError("crypto_error", "cifrado desconocido: " + String(alg).slice(0, 20));
    const key = buf(openKeyLike(p.key), p.keyEncoding || "utf8", "key");
    const data = buf(p.data, p.inputEncoding || (decrypt ? "base64" : "utf8"), "data");
    const bits = alg.startsWith("des") ? 192 : Number(alg.split("-")[1]);
    if (key.length * 8 !== bits) throw kinoError("crypto_error", `la clave de ${alg} debe tener ${bits / 8} bytes, tiene ${key.length}`);
    const mode = alg.split("-")[2];
    const iv = mode === "ecb" ? null : buf(p.iv, p.ivEncoding || "utf8", "iv");
    const padding = p.padding === undefined ? "pkcs7" : p.padding;
    if (padding !== "pkcs7" && padding !== "none") throw kinoError("crypto_error", "relleno desconocido: " + String(padding).slice(0, 20));
    try {
      let result;
      if (mode === "gcm") {
        const c = decrypt ? createDecipheriv(alg, key, iv) : createCipheriv(alg, key, iv);
        if (p.aad !== undefined) c.setAAD(buf(p.aad, p.aadEncoding || "utf8", "aad"));
        if (decrypt) {
          if (data.length < 16) throw kinoError("crypto_error", "al texto cifrado le falta la etiqueta de 16 bytes");
          c.setAuthTag(data.subarray(data.length - 16));
          result = out(Buffer.concat([c.update(data.subarray(0, data.length - 16)), c.final()]), p.outputEncoding || "utf8");
        } else {
          result = out(Buffer.concat([c.update(data), c.final(), c.getAuthTag()]), p.outputEncoding || "base64");
        }
      } else {
        const c = decrypt ? createDecipheriv(alg, key, iv) : createCipheriv(alg, key, iv);
        if (mode !== "ctr") c.setAutoPadding(padding === "pkcs7");
        result = out(Buffer.concat([c.update(data), c.final()]), p.outputEncoding || (decrypt ? "utf8" : "base64"));
      }
      return redactOut(result);
    } catch (e) {
      if (e.code && String(e.code).startsWith("KinoError")) throw e;
      if (e.name && e.name.startsWith("KinoError")) throw e;
      throw kinoError("crypto_error", decrypt ? "no se pudo descifrar: clave o iv equivocados" : "operación criptográfica inválida");
    }
  }
  const crypto = Object.freeze({
    hash(alg, data, p = {}) {
      if (sealedIn(String(data))) throw kinoError("crypto_error", SEALED_CRYPTO_REFUSED);
      if (!k.hashes.includes(alg)) throw kinoError("crypto_error", "algoritmo de hash desconocido: " + String(alg).slice(0, 20));
      return redactOut(out(createHash(alg).update(buf(String(data), p.inputEncoding || "utf8", "data")).digest(), p.outputEncoding || "hex"));
    },
    hmac(alg, key, data, p = {}) {
      if (sealedIn(String(data))) throw kinoError("crypto_error", SEALED_CRYPTO_REFUSED);
      if (!k.hashes.includes(alg)) throw kinoError("crypto_error", "algoritmo de hmac desconocido: " + String(alg).slice(0, 20));
      // Unlike a cipher key, the hmac key may join a marker with other text (e.g. OAuth 1's
      // `consumerSecret&tokenSecret`): HMAC mixes its whole key through a hash, so a known part
      // never splits the unknown one off.
      const keyBuf = buf(openKeyLike(String(key)), p.keyEncoding || "utf8", "key");
      if (!keyBuf.length) throw kinoError("crypto_error", "la clave del hmac está vacía");
      return redactOut(out(createHmac(alg, keyBuf).update(buf(String(data), p.inputEncoding || "utf8", "data")).digest(), p.outputEncoding || "hex"));
    },
    encrypt: (alg, p) => cipher(false, alg, p),
    decrypt: (alg, p) => cipher(true, alg, p),
    pbkdf2(hash, password, salt, iterations, keyLength, p = {}) {
      if (!k.pbkdf2Hashes.includes(hash)) throw kinoError("crypto_error", "hash de pbkdf2 desconocido: " + String(hash).slice(0, 20));
      if (!Number.isInteger(iterations) || iterations < 1 || iterations > k.pbkdf2MaxIterations) throw kinoError("crypto_error", `iteraciones de pbkdf2 entre 1 y ${k.pbkdf2MaxIterations}`);
      if (!Number.isInteger(keyLength) || keyLength < 1 || keyLength > k.pbkdf2MaxKeyBytes) throw kinoError("crypto_error", `longitud de clave de pbkdf2 entre 1 y ${k.pbkdf2MaxKeyBytes} bytes`);
      // password and salt may both join a marker with other text, same reasoning as the hmac key.
      const password0 = openKeyLike(String(password)), salt0 = openKeyLike(String(salt));
      return redactOut(out(pbkdf2Sync(buf(password0, p.keyEncoding || "utf8", "password"), buf(salt0, p.inputEncoding || "utf8", "salt"), iterations, keyLength, hash), p.outputEncoding || "hex"));
    },
    randomBytes(n, enc = "hex") {
      if (!Number.isInteger(n) || n < 1 || n > k.randomMaxBytes) throw kinoError("crypto_error", `randomBytes acepta de 1 a ${k.randomMaxBytes} bytes`);
      return redactOut(out(randomBytes(n), enc));
    },
    uuid: () => redactOut(randomUUID()),
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
      get: (key) => {
        purgeExpiredStorage();
        const k = String(key);
        return Object.prototype.hasOwnProperty.call(storage, k) ? storageEntryValue(storage[k]) : null;
      },
      set: (key, v, options) => {
        const k = String(key), value = String(v);
        let expiresAt;
        if (options !== null && typeof options === "object" && options.ttlMs !== undefined && options.ttlMs !== null) {
          const ttlMs = options.ttlMs;
          if (!Number.isInteger(ttlMs) || ttlMs <= 0 || ttlMs > contract.storage.maxTtlMs) {
            throw new Error(`kino.storage.set: ttlMs debe ser un entero mayor que 0 y de hasta ${contract.storage.maxTtlMs} ms (30 días)`);
          }
          expiresAt = Date.now() + ttlMs;
        }
        purgeExpiredStorage();
        const previous = storage[k];
        storage[k] = expiresAt === undefined ? value : { v: value, e: expiresAt };
        if (Buffer.byteLength(JSON.stringify(storage)) > contract.storage.maxTotalBytes) {
          if (previous === undefined) delete storage[k]; else storage[k] = previous;
          throw new Error(`almacenamiento del plugin lleno (${kb(contract.storage.maxTotalBytes)})`);
        }
        saveJson(storageFile, storage);
      },
      remove: (key) => { purgeExpiredStorage(); delete storage[String(key)]; saveJson(storageFile, storage); },
      keys: () => { purgeExpiredStorage(); return Object.keys(storage); },
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
        return c ? redact(c.value) : null;
      },
      clear() { cookieJar.length = 0; saveJson(cookiesFile, cookieJar); },
    }),
    crypto,
    rank: Object.freeze({ shortQuery, sortBySimilarity, filterRelevant }),
    async sleep(ms) {
      await null;
      if (!Number.isInteger(ms) || ms < 0 || ms > contract.sleep.maxMs) throw kinoError("invalid_request", `kino.sleep acepta de 0 a ${contract.sleep.maxMs} ms`);
      await new Promise((resolve) => setTimeout(resolve, ms));
    },
    // apiVersion 4: a marker for a secret the manifest's `secrets` declares (spec §5). A name is at
    // most 32 characters, so cutting to 64 before the lookup can never turn an undeclared name into
    // a declared one -- the same cut the app's prelude applies.
    secret(name) {
      const s = String(name).slice(0, 64);
      const m = pluginSecrets && pluginSecrets.marker(s);
      if (!m) throw new Error("este plugin no declara el secreto " + s.slice(0, 40));
      return m;
    },
    error: (code, message) => kinoError(code, message),
    log: (...args) => writeErr("[kino.log]", ...args.map((a) => (typeof a === "string" ? redact(a) : a))),
  });

  return {
    kino,
    servers: servers.map((s) => s.toString()),
    resetBudget: () => { requests = 0; },
    saveTape: () => { if (record && tape) saveJson(record, tape); },
  };
}
