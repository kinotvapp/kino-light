// The kit's mirror of Kino's native live-channel readers: M3uParser.kt and XmltvParser.kt (and the
// grouping of PlaylistGroups.kt). Both sides are pinned to the same shared fixtures,
// docs/plugins/fixtures/live/*.m3u|*.xml with an .expected.json next to each: the app's JVM tests
// and this kit's node tests read the SAME files, so the two can't drift. This file exists so a
// plugin author sees, before installing anything, exactly what Kino will keep from a list and a guide.
// It is not the authority: when the two disagree, the app wins and this file is the bug.
import { constants as zlibConstants, gunzipSync } from "node:zlib";
import { contract, liveStreamUrlAllowed, playlistUrlAllowed } from "./contract.mjs";

const live = () => contract.live;

// ---------- M3U ----------

const ATTR = /([A-Za-z0-9_-]+)=(?:"([^"]*)"|(\S+))/g;
const KEPT_HEADERS = { "user-agent": "User-Agent", referer: "Referer", referrer: "Referer", origin: "Origin", cookie: "Cookie" };
// Kotlin's String.trim(): Java whitespace plus space separators, never U+FEFF (which JS trims).
const WS = "\\t\\n\\v\\f\\r\\u001C-\\u001F \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";
const TRIM = new RegExp(`^[${WS}]+|[${WS}]+$`, "g");
const ktTrim = (s) => s.replace(TRIM, "");
const isBlank = (s) => ktTrim(s) === "";
const after = (s, ch, missing = s) => { const i = s.indexOf(ch); return i < 0 ? missing : s.slice(i + ch.length); };
const before = (s, ch, missing = s) => { const i = s.indexOf(ch); return i < 0 ? missing : s.slice(0, i); };
const startsWithIgnoreCase = (s, prefix) => s.slice(0, prefix.length).toUpperCase() === prefix.toUpperCase();

/** A UTF-8 BOM is dropped; strict UTF-8 is tried, and on malformed input the whole file is read as ISO-8859-1. */
export function decodeM3u(bytes) {
  const b = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
  const body = b.length >= 3 && b[0] === 0xef && b[1] === 0xbb && b[2] === 0xbf ? b.subarray(3) : b;
  try {
    return new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(body);
  } catch {
    return body.toString("latin1");
  }
}

// java.net.URLDecoder.decode(s, "UTF-8"): runs of %XX become bytes decoded as UTF-8 (bad bytes as
// U+FFFD); a bad or short escape throws, which the caller turns into "no value".
function urlDecode(s) {
  let out = "";
  for (let i = 0; i < s.length;) {
    if (s[i] !== "%") { out += s[i++]; continue; }
    const bytes = [];
    while (i < s.length && s[i] === "%") {
      const hex = s.slice(i + 1, i + 3);
      if (hex.length < 2 || !/^(?:[0-9a-fA-F]{2}|\+[0-9a-fA-F])$/.test(hex)) throw new Error("bad escape");
      bytes.push(parseInt(hex, 16));
      i += 3;
    }
    out += new TextDecoder("utf-8").decode(Uint8Array.from(bytes));
  }
  return out;
}

function pairs(s, into) {
  for (const pair of s.split("&")) {
    const name = KEPT_HEADERS[ktTrim(before(pair, "=")).toLowerCase()];
    if (!name) continue;
    let value = null;
    try { value = urlDecode(ktTrim(after(pair, "=", "")).replaceAll("+", "%2B")); } catch { value = null; }
    if (value) into[name] = value;
  }
}

function vlcOpt(v, into) {
  const key = ktTrim(before(v, "=")).toLowerCase();
  const name = key === "http-user-agent" ? "User-Agent" : key === "http-referrer" || key === "http-referer" ? "Referer" : key === "http-origin" ? "Origin" : null;
  if (!name) return;
  const value = ktTrim(after(v, "=", ""));
  if (value) into[name] = value;
}

function kodiProp(v, into) {
  const key = ktTrim(before(v, "=")).toLowerCase();
  if (key === "inputstream.adaptive.stream_headers" || key === "inputstream.adaptive.common_headers") pairs(after(v, "=", ""), into);
}

function extinf(line) {
  const body = after(line, ":", "");
  let quoted = false;
  let comma = -1;
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (c === '"') quoted = !quoted;
    else if (c === "," && !quoted) { comma = i; break; }
  }
  const head = comma >= 0 ? body.slice(0, comma) : body;
  const attrs = new Map();
  for (const m of head.matchAll(ATTR)) attrs.set(m[1].toLowerCase(), ktTrim(m[2] !== undefined ? m[2] : m[3] ?? ""));
  return { attrs, title: comma >= 0 ? ktTrim(body.slice(comma + 1)) : "" };
}

function channelNumber(v) {
  if (v === undefined || !/^[+-]?\d+$/.test(v)) return 0;
  const n = Number(v);
  return Number.isInteger(n) && n >= 1 && n <= live().maxChannelNumber ? n : 0;
}

function entryOf(info, urlLine, extgrp, pending) {
  const pipe = urlLine.indexOf("|");
  const url = ktTrim(pipe >= 0 ? urlLine.slice(0, pipe) : urlLine);
  const scheme = before(url, "://", "").toLowerCase();
  if (scheme !== "http" && scheme !== "https") return null;
  const get = (k) => info.attrs.get(k) ?? "";
  const tvgName = get("tvg-name");
  const name = (isBlank(info.title) ? tvgName : info.title).slice(0, 200);
  if (isBlank(name)) return null;
  const headers = { ...pending };
  if (pipe >= 0) pairs(urlLine.slice(pipe + 1), headers);
  const group = get("group-title");
  return {
    name, url, tvgId: get("tvg-id"), tvgName, logo: get("tvg-logo"), number: channelNumber(info.attrs.get("tvg-chno")),
    group: isBlank(group) ? extgrp : group, language: get("tvg-language"), country: get("tvg-country"), headers,
  };
}

/**
 * An M3U/M3U8 list, parsed by the app's rules: `{ entries, total, skipped }` (plus `hidden` and
 * `refused` when a `hide` or `allow` filter is given). `total` = valid entries seen, `entries` the
 * first `maxEntries` of them, `skipped` = broken ones. An entry `hide` matches, or whose URL `allow`
 * refuses, is counted apart and never spends `maxEntries`. Bytes are decoded as the app does; a
 * string is read as given.
 */
export function parseM3u(input, { maxEntries = live().maxChannelsPerProvider, hide = null, allow = null } = {}) {
  const text = typeof input === "string" ? input : decodeM3u(input);
  const out = [];
  let total = 0, skipped = 0, hidden = 0, refused = 0;
  let info = null;
  let extgrp = "";
  let headers = {};
  for (const raw of text.split(/\r\n|\n|\r/)) {
    const line = ktTrim(raw);
    if (line === "") continue;
    if (startsWithIgnoreCase(line, "#EXTINF")) {
      if (info) skipped++;
      info = extinf(line);
    } else if (startsWithIgnoreCase(line, "#EXTGRP:")) extgrp = ktTrim(after(line, ":"));
    else if (startsWithIgnoreCase(line, "#EXTVLCOPT:")) vlcOpt(after(line, ":"), headers);
    else if (startsWithIgnoreCase(line, "#KODIPROP:")) kodiProp(after(line, ":"), headers);
    else if (line.startsWith("#")) continue;
    else {
      const entry = info ? entryOf(info, line, extgrp, headers) : null;
      info = null;
      extgrp = "";
      headers = {};
      if (!entry) skipped++;
      else if (hide && hide(entry)) hidden++;
      else if (allow && !allow(entry.url)) refused++;
      else { total++; if (out.length < maxEntries) out.push(entry); }
    }
  }
  if (info) skipped++;
  const result = { entries: out, total, skipped };
  if (hide || allow) Object.assign(result, { hidden, refused });
  return result;
}

// ---------- grouping, as PlaylistGroups.kt ----------

/** Group titles hidden in every playlist (lowercased, trimmed), on top of the playlist's own `hideGroups`. */
export const ADULT_GROUPS = ["adultos", "adulto", "adult", "adults", "xxx", "18+", "+18", "for adults", "porn", "porno"];
const NO_GROUP = "Sin categoría";
const ID = new RegExp(contract.output.itemIdPattern);

export function isHiddenGroup(group, hideGroups = []) {
  const g = (ktTrim(group) || NO_GROUP).toLowerCase();
  return ADULT_GROUPS.includes(g) || hideGroups.includes(g);
}

/**
 * A playlist as Kino shows it: parsed with the adult and `hideGroups` groups hidden and `allow`'s
 * refusals counted, then grouped into categories. `channels` kept, `total` valid and visible,
 * `skipped` (broken lines, refused hosts and the very same url+name twice), `hidden`. A tvg-id used
 * by more than one entry is listed in `duplicateTvgIds`: only its first entry keeps the tvg-id as
 * its code, the later ones get a code from their URL and name (their favourites and recents follow
 * the URL, and a copy inserted BEFORE the first one takes the tvg-id code over).
 */
export function summarisePlaylist(input, { hideGroups = [], allow = null, maxChannels = live().maxChannelsPerProvider, maxCategories = live().maxCategoriesPerProvider } = {}) {
  const r = parseM3u(input, { maxEntries: maxChannels, hide: (e) => isHiddenGroup(e.group, hideGroups), allow });
  const tvgCount = new Map();
  const firstWithTvg = new Map();
  r.entries.forEach((e, i) => {
    tvgCount.set(e.tvgId, (tvgCount.get(e.tvgId) || 0) + 1);
    if (!firstWithTvg.has(e.tvgId)) firstWithTvg.set(e.tvgId, i);
  });
  const categories = new Map();
  const seen = new Set();
  const channels = [];
  let skipped = r.skipped + r.refused;
  let dropped = 0;
  for (const [i, e] of r.entries.entries()) {
    if (channels.length >= maxChannels || maxCategories <= 0) continue;
    // The app's channel code: the tvg-id when it is a valid id and this is the list's first entry
    // with it, else the url and name; the very same code twice is one channel, the copy counted as skipped.
    const key = ID.test(e.tvgId) && firstWithTvg.get(e.tvgId) === i ? `id:${e.tvgId}` : `h:${e.url}|${e.name}`;
    if (seen.has(key)) { skipped++; dropped++; continue; }
    seen.add(key);
    const group = ktTrim(e.group) || NO_GROUP;
    let cat = categories.get(group);
    if (!cat) {
      if (categories.size < maxCategories - 1) categories.set(group, cat = { title: group, count: 0 });
      else { cat = categories.get("\u0000otros") || { title: "Otros", count: 0 }; categories.set("\u0000otros", cat); }
    }
    cat.count++;
    channels.push({ ...e, category: cat.title });
  }
  const duplicateTvgIds = [...tvgCount].filter(([id, n]) => id && n > 1).map(([id]) => id);
  return {
    channels: channels.length, total: r.total - dropped, skipped, hidden: r.hidden,
    categories: [...categories.values()], entries: channels, duplicateTvgIds,
  };
}

// ---------- XMLTV ----------

const TIME = /^(\d{14}|\d{12})\s*([+-]\d{4})?/;
const ENCODING_DECL = /encoding\s*=\s*["']([^"'?>]+)["']/i;
const NATIVE_LATIN1 = ["iso-8859-1", "iso8859-1", "latin1", "us-ascii", "ascii"];
const HEAD_SCAN_BYTES = 8192;
const MAX_TEXT_CHARS = 2000;
const MAX_TITLE_CHARS = 200;
const MAX_DESC_CHARS = 2000;

/** NFD, strip combining marks, lowercase, keep only `[a-z0-9]`: accents and case never split a match. */
export function normaliseName(s) {
  return String(s).normalize("NFD").replace(/\p{Mn}+/gu, "").toLowerCase().replace(/[^a-z0-9]/g, "");
}

const daysIn = (y, m) => new Date(Date.UTC(y, m, 0)).getUTCDate();

/** `yyyyMMddHHmm[ss] [±HHMM]` to epoch ms; no offset means UTC. Null on anything else. */
export function parseXmltvTime(value) {
  const m = TIME.exec(ktTrim(String(value)));
  if (!m) return null;
  const d = m[1].padEnd(14, "0");
  const [y, mo, day, h, mi, s] = [d.slice(0, 4), d.slice(4, 6), d.slice(6, 8), d.slice(8, 10), d.slice(10, 12), d.slice(12, 14)].map(Number);
  if (mo < 1 || mo > 12 || day < 1 || day > 31 || mi > 59 || s > 59 || h > 24 || (h === 24 && (mi || s))) return null;
  // java.time's SMART resolver: day 31 of a 30-day month is its last day, 24:00 the next midnight.
  const local = Date.UTC(y, mo - 1, Math.min(day, daysIn(y, mo)), h, mi, s);
  const off = m[2] || "+0000";
  const oh = Number(off.slice(1, 3));
  const om = Number(off.slice(3, 5));
  if (oh > 18 || om > 59 || (oh === 18 && om > 0)) return null;
  return local - (off[0] === "-" ? -1 : 1) * (oh * 60 + om) * 60000;
}

const ENTITIES = { lt: "<", gt: ">", amp: "&", quot: '"', apos: "'" };
function decodeEntities(s) {
  return s.replace(/&(#x[0-9a-fA-F]+|#\d+|lt|gt|amp|quot|apos);/g, (all, e) => {
    if (e[0] !== "#") return ENTITIES[e];
    const cp = e[1] === "x" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
    return cp <= 0x10ffff ? String.fromCodePoint(cp) : all;
  });
}

/** An element's character data as SAX hands it over: child tags dropped, CDATA literal, entities decoded. */
function textOf(inner) {
  let out = "";
  for (const m of inner.matchAll(/<!\[CDATA\[([\s\S]*?)\]\]>|<!--[\s\S]*?-->|<[^>]*>|[^<]+/g)) {
    if (m[1] !== undefined) out += m[1];
    else if (m[0][0] !== "<") out += decodeEntities(m[0]);
    if (out.length >= MAX_TEXT_CHARS) break;
  }
  return ktTrim(out.slice(0, MAX_TEXT_CHARS));
}

function attrsOf(s) {
  const out = new Map();
  for (const m of s.matchAll(/([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g)) if (!out.has(m[1])) out.set(m[1], decodeEntities(m[2] ?? m[3]));
  return out;
}

const tooLarge = (e) => e && (e.code === "ERR_BUFFER_TOO_LARGE" || e instanceof RangeError);

/** Gunzip under the byte cap. Past it, or on a cut or corrupt stream, what inflates is kept and `truncated` set. */
function gunzipCapped(buf, maxBytes) {
  try {
    return { bytes: gunzipSync(buf, { maxOutputLength: maxBytes }), truncated: false };
  } catch (e) {
    if (!tooLarge(e)) {
      try { return { bytes: gunzipSync(buf, { finishFlush: zlibConstants.Z_SYNC_FLUSH, maxOutputLength: maxBytes }), truncated: true }; }
      catch (e2) { if (!tooLarge(e2)) return { bytes: Buffer.alloc(0), truncated: true }; }
    }
    // Over the cap: inflate shorter and shorter prefixes until one fits.
    for (let len = buf.length >> 1; len > 18; len >>= 1) {
      try { return { bytes: gunzipSync(buf.subarray(0, len), { finishFlush: zlibConstants.Z_SYNC_FLUSH, maxOutputLength: maxBytes }), truncated: true }; }
      catch (e3) { if (!tooLarge(e3)) break; }
    }
    return { bytes: Buffer.alloc(0), truncated: true };
  }
}

/** The document as text: BOM first, else the declared encoding (anything unknown read as ISO-8859-1). */
function decodeXml(b, maxBytes) {
  if (b.length >= 3 && b[0] === 0xef && b[1] === 0xbb && b[2] === 0xbf) return utf8(b.subarray(3));
  if (b.length >= 2 && b[0] === 0xfe && b[1] === 0xff) return { text: new TextDecoder("utf-16be").decode(b.subarray(2)), truncated: false };
  if (b.length >= 2 && b[0] === 0xff && b[1] === 0xfe) return { text: new TextDecoder("utf-16le").decode(b.subarray(2)), truncated: false };
  const declared = ENCODING_DECL.exec(b.subarray(0, HEAD_SCAN_BYTES).toString("latin1"))?.[1]?.toLowerCase();
  if (!declared || declared === "utf-8" || declared === "utf8") return utf8(b);
  if (NATIVE_LATIN1.includes(declared)) return { text: b.toString("latin1"), truncated: false };
  if (declared.startsWith("utf-16")) return { text: new TextDecoder(declared === "utf-16be" ? "utf-16be" : "utf-16le").decode(b), truncated: false };
  // Decoded, as the app does for what Expat can't read: the byte cap applies to the text counted as UTF-8 too.
  let text;
  try { text = new TextDecoder(declared).decode(b); } catch { text = b.toString("latin1"); }
  const encoded = Buffer.from(text, "utf8");
  return encoded.length > maxBytes ? { text: encoded.subarray(0, maxBytes).toString("utf8"), truncated: true } : { text, truncated: false };
}

function utf8(b) {
  try { return { text: new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(b), truncated: false }; }
  catch { return { text: new TextDecoder("utf-8", { ignoreBOM: true }).decode(b), truncated: true }; }
}

const WALK = /<(channel|programme)\b([^>]*?)(?:\/>|>([\s\S]*?)<\/\1\s*>)/g;
const CHILD = Object.fromEntries(["title", "desc"].map((tag) => [tag, new RegExp(`<${tag}\\b[^>]*?(?:\\/>|>([\\s\\S]*?)<\\/${tag}\\s*>)`)]));
/** The first `<title>`/`<desc>` child's text (the app keeps the first one, even if blank), or null. */
const firstChild = (inner, tag) => {
  const m = CHILD[tag].exec(inner);
  return m ? textOf(m[1] ?? "") : null;
};

/**
 * An XMLTV guide (plain or gzip), read by the app's rules: `{ displayNames, programmes, truncated }`,
 * plus `refused: true` when it was turned down for declaring a DOCTYPE.
 * Only the wanted channels (`wantedIds`, or any display name whose `normaliseName` is in
 * `wantedNames`; `wantedIds` null wants every channel, at most 5000) inside `[from, to)`, each list
 * sorted by start, one per start time, the earliest `maxPerChannel` kept. A document with any
 * DOCTYPE is refused: empty, not truncated. A byte cap, a cut download or a broken tail keeps what
 * was read and sets `truncated`. Programmes are `{ title, start, end, description }`, times in epoch ms.
 */
export function parseXmltv(buffer, { from, to, wantedIds = null, wantedNames = new Set(), maxPerChannel = live().maxGuideEntriesPerChannel, maxBytes = live().maxEpgBytes } = {}) {
  let bytes = Buffer.isBuffer(buffer) ? buffer : Buffer.from(buffer);
  let truncated = false;
  if (bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b) {
    const g = gunzipCapped(bytes, maxBytes);
    bytes = g.bytes;
    truncated = g.truncated;
  } else if (bytes.length > maxBytes) {
    bytes = bytes.subarray(0, maxBytes);
    truncated = true;
  }
  const decoded = decodeXml(bytes, maxBytes);
  const text = decoded.text;
  truncated = truncated || decoded.truncated;
  // `refused` marks a guide turned down on purpose (the app returns the same empty guide, silently).
  const refused = { displayNames: {}, programmes: {}, truncated: false, refused: true };
  // The app's fast path scans the raw head for `<!ENTITY`, comments included: so does this.
  if (text.slice(0, HEAD_SCAN_BYTES).includes("<!ENTITY")) return refused;
  // The app refuses a real DOCTYPE structurally; a "<!DOCTYPE" inside a comment or CDATA is text.
  if (/<!DOCTYPE/i.test(text.replace(/<!--[\s\S]*?(?:-->|$)|<!\[CDATA\[[\s\S]*?(?:\]\]>|$)/g, ""))) return refused;

  const names = new Map();
  const wanted = new Map();
  const progs = new Map();
  const known = new Set();
  const admit = (id) => {
    if (known.has(id)) return true;
    if (known.size >= live().maxChannelsPerProvider) return false;
    known.add(id);
    return true;
  };
  for (const m of text.matchAll(WALK)) {
    const attrs = attrsOf(m[2]);
    const inner = m[3] ?? "";
    if (m[1] === "channel") {
      const id = attrs.get("id");
      if (id === undefined) continue;
      const displayNames = [...inner.matchAll(/<display-name\b[^>]*?(?:\/>|>([\s\S]*?)<\/display-name\s*>)/g)].map((d) => textOf(d[1] ?? ""));
      if (wantedIds === null && !admit(id)) continue;
      const ok = wantedIds === null || wantedIds.has(id) || displayNames.some((n) => wantedNames.has(normaliseName(n)));
      wanted.set(id, ok);
      if (ok) names.set(id, displayNames);
      continue;
    }
    const start = attrs.has("start") ? parseXmltvTime(attrs.get("start")) : null;
    const end = attrs.has("stop") ? parseXmltvTime(attrs.get("stop")) : null;
    const channel = attrs.get("channel") ?? "";
    const title = firstChild(inner, "title");
    const ok = wanted.has(channel) ? wanted.get(channel) : wantedIds !== null ? wantedIds.has(channel) : admit(channel);
    if (!ok || start === null || end === null || !(end > start) || !(end > from) || !(start < to) || title === null || isBlank(title)) continue;
    let perChannel = progs.get(channel);
    if (!perChannel) progs.set(channel, perChannel = new Map());
    if (perChannel.has(start)) continue;
    const entry = { title: title.slice(0, MAX_TITLE_CHARS), start, end, description: (firstChild(inner, "desc") ?? "").slice(0, MAX_DESC_CHARS) };
    if (perChannel.size < maxPerChannel) perChannel.set(start, entry);
    else {
      const latest = Math.max(...perChannel.keys());
      if (start < latest) { perChannel.delete(latest); perChannel.set(start, entry); }
    }
  }
  // Well-formed ends with its root closed; anything else is a cut download or a broken tail.
  if (!/<\/tv\s*>\s*(?:<!--[\s\S]*?-->\s*)*$/.test(text)) truncated = true;
  return {
    displayNames: Object.fromEntries(names),
    programmes: Object.fromEntries([...progs].filter(([, m]) => m.size).map(([id, m]) => [id, [...m.values()].sort((a, b) => a.start - b.start)])),
    truncated,
  };
}

// ---------- downloads, as the app makes them ----------

/**
 * Downloads a playlist or guide the way the app does: `headers` sent, redirects followed by hand
 * (at most 5) with every hop checked by `allow` (null = any http(s) URL), at most `maxBytes`.
 * Throws an Error with a readable message on anything else.
 */
export async function download(url, { headers = {}, maxBytes, allow = null, fetchImpl = globalThis.fetch } = {}) {
  let current = String(url);
  for (let hop = 0; hop <= 5; hop++) {
    let u;
    try { u = new URL(current); } catch { throw new Error(`dirección inválida: ${current.slice(0, 100)}`); }
    if (u.protocol !== "http:" && u.protocol !== "https:") throw new Error(`solo http(s): ${current.slice(0, 100)}`);
    if (allow && !allow(current)) throw new Error(`${u.hostname} no es un host declarado ni un servidor escrito`);
    const r = await fetchImpl(current, { headers, redirect: "manual" });
    if (r.status >= 300 && r.status < 400 && r.headers.get("location")) {
      current = new URL(r.headers.get("location"), current).toString();
      continue;
    }
    if (!r.ok) throw new Error(`respondió ${r.status}`);
    const chunks = [];
    let size = 0;
    for await (const chunk of r.body ?? []) {
      size += chunk.length;
      if (size > maxBytes) throw new Error(`pasa de ${Math.round(maxBytes / (1024 * 1024))} MB`);
      chunks.push(Buffer.from(chunk));
    }
    return Buffer.concat(chunks);
  }
  throw new Error("demasiadas redirecciones");
}

// ---------- what the kit prints ----------

/** "N canales en M categorías; K entradas descartadas; L ocultas (adultos)", plus the cut notice when there is one. */
export function summaryLines(s) {
  const lines = [`${s.channels} canales en ${s.categories.length} categorías; ${s.skipped} entradas descartadas; ${s.hidden} ocultas (adultos)`];
  if (s.total > s.channels) lines.push(`Lista recortada: ${s.channels} de ${s.total} canales`);
  if (s.duplicateTvgIds.length) {
    lines.push(`note: ${s.duplicateTvgIds.length} tvg-id used more than once (${s.duplicateTvgIds.slice(0, 5).join(", ")}${s.duplicateTvgIds.length > 5 ? ", …" : ""}): after the first, those channels get a code from their URL and name, which changes if the URL does; keep tvg-ids unique and stable`);
  }
  return lines;
}

/** The first `max` channels as `group › name  url`, each followed by what is on now when a guide is given. */
export function channelLines(s, { guide = null, now = Date.now(), max = 20 } = {}) {
  const nameToId = new Map();
  if (guide) for (const [id, list] of Object.entries(guide.displayNames)) for (const n of list) if (!nameToId.has(normaliseName(n))) nameToId.set(normaliseName(n), id);
  const lines = [];
  for (const e of s.entries.slice(0, max)) {
    lines.push(`${e.category} › ${e.name}  ${e.url}`);
    if (!guide) continue;
    const list = (e.tvgId && guide.programmes[e.tvgId]) || guide.programmes[nameToId.get(normaliseName(e.name))] || [];
    const onAir = list.find((p) => p.start <= now && now < p.end);
    lines.push(onAir ? `    ahora: ${onAir.title}` : "    sin guía");
  }
  return lines;
}

/** Parses a guide for the first `max` channels of a summary, over a window around `now`. */
export function guideFor(s, bytes, { now = Date.now(), max = 20 } = {}) {
  const shown = s.entries.slice(0, max);
  return parseXmltv(bytes, {
    from: now, to: now + 1,
    wantedIds: new Set(shown.map((e) => e.tvgId).filter(Boolean)),
    wantedNames: new Set(shown.map((e) => normaliseName(e.name)).filter(Boolean)),
  });
}

/**
 * One `{ playlist }` declaration as checked by checkOutput, downloaded and grouped as the app does:
 * the download under the strict host rule (declared hosts or a typed server, every redirect too),
 * the entries under the channel stream rule (`liveStreamHosts: "any"` included).
 */
export async function loadPlaylist(p, { manifest, servers = [], fetchImpl = globalThis.fetch }) {
  const bytes = await download(p.url, { headers: p.headers || {}, maxBytes: live().maxPlaylistBytes, allow: playlistUrlAllowed(manifest, servers), fetchImpl });
  return summarisePlaylist(bytes, { hideGroups: p.hideGroups || [], allow: liveStreamUrlAllowed(manifest, servers) });
}
