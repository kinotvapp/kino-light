// The plugin contract as the Node kit sees it: contract.json (the numbers and rules) plus the same
// validation the app runs on a manifest and on what each function returns. Kino's own app code is
// authoritative; a test in the app pins every value here to it.
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

// Published kit: contract.json next to the sdk/ folder. Kino's own repo: docs/plugins/contract.json.
export function loadContract() {
  for (const p of [join(here, "..", "contract.json"), join(here, "..", "..", "docs", "plugins", "contract.json")]) {
    if (existsSync(p)) return JSON.parse(readFileSync(p, "utf8"));
  }
  throw new Error("contract.json not found next to sdk/");
}

export const contract = loadContract();

const re = (pattern) => new RegExp(pattern);
// Every regex/list below is read from contract.json, never hand-retyped: a hand-typed copy of the
// app's version pattern once allowed unbounded digits per segment where the app's own regex bounds
// each to 6 — the drift a hand-duplicated value invites, and exactly what this avoids.
const SEMVER = re(contract.manifest.versionPattern);
const PATH_SEGMENT = re(contract.manifest.pathSegmentPattern);
const LABEL = re(contract.hostRules.labelPattern);
const PRIVATE_SUFFIXES = contract.hostRules.privateSuffixes;

/** "5 MB" / "256 KB": how the app phrases a byte limit in its own Spanish messages. */
export function kb(bytes) {
  return bytes % (1024 * 1024) === 0 ? `${bytes / 1024 / 1024} MB` : `${bytes / 1024} KB`;
}

export function isSafeRelativePath(p) {
  return typeof p === "string" && p.length > 0 && p.length <= contract.manifest.maxPathChars && !p.startsWith("/") && !p.includes("\\") &&
    p.split("/").every((s) => s !== "" && s !== "." && s !== ".." && PATH_SEGMENT.test(s));
}

export function isValidHostPattern(pattern) {
  const host = pattern.startsWith("*.") ? pattern.slice(2) : pattern;
  if (!host || host.includes("*") || host.length > contract.hostRules.maxHostChars || host.includes(":") || host.includes("[")) return false;
  if (host === "localhost" || PRIVATE_SUFFIXES.some((s) => host.endsWith(s))) return false;
  const labels = host.split(".");
  if (labels.length < 2 || !labels.every((l) => LABEL.test(l))) return false;
  return !/^\d+$/.test(labels[labels.length - 1]);
}

export function hostMatches(host, patterns) {
  const h = String(host).toLowerCase().replace(/\.$/, "");
  return patterns.some((p) => (p.startsWith("*.") ? h.endsWith("." + p.slice(2)) : h === p));
}

/** Same checks, same order, same Spanish messages as the app's own manifest validation. Returns { ok, field?, message?, manifest? }. */
export function validateManifest(text, { knownPermissions = contract.permissions } = {}) {
  const m = contract.manifest;
  const bad = (field, message) => ({ ok: false, field, message });
  if (Buffer.byteLength(text, "utf8") > m.maxBytes) return bad("kino-plugin.json", "El manifiesto pesa más de 16 KB");
  let o;
  try { o = JSON.parse(text); } catch { return bad("kino-plugin.json", "El manifiesto no es un JSON válido"); }
  if (o === null || typeof o !== "object" || Array.isArray(o)) return bad("kino-plugin.json", "El manifiesto no es un JSON válido");
  const id = typeof o.id === "string" ? o.id : "";
  if (!re(m.idPattern).test(id)) return bad("id", 'El campo "id" debe tener de 2 a 40 letras minúsculas, números o guiones');
  if (m.reservedIds.includes(id)) return bad("id", `El id "${id}" está reservado por Kino`);
  const name = typeof o.name === "string" ? o.name.trim() : "";
  if (!name || name.length > m.nameMaxChars) return bad("name", 'El campo "name" debe tener de 1 a 40 caracteres');
  if (!SEMVER.test(typeof o.version === "string" ? o.version : "")) return bad("version", 'El campo "version" debe ser del tipo 1.2.3');
  if (!Number.isInteger(o.apiVersion)) return bad("apiVersion", 'El campo "apiVersion" debe ser un número entero');
  if (o.apiVersion > contract.maxApiVersion) return bad("apiVersion", "Este plugin necesita una versión más nueva de Kino");
  if (o.apiVersion < 1) return bad("apiVersion", 'El campo "apiVersion" debe ser 1 o mayor');
  if (!isSafeRelativePath(o.entry) || !o.entry.endsWith(".js")) return bad("entry", 'El campo "entry" debe ser una ruta relativa a un archivo .js');
  if (!Array.isArray(o.hosts)) return bad("hosts", 'Falta el campo "hosts"');
  const hosts = o.hosts.map((h) => (typeof h === "string" ? h : ""));
  if (hosts.length < m.minHosts || hosts.length > m.maxHosts) return bad("hosts", `El campo "hosts" debe tener de 1 a ${m.maxHosts} dominios`);
  const badHost = hosts.find((h) => !isValidHostPattern(h));
  if (badHost !== undefined) return bad("hosts", `El dominio "${badHost}" no está permitido`);
  if (!Array.isArray(o.capabilities)) return bad("capabilities", 'Falta el campo "capabilities"');
  const caps = [...new Set(o.capabilities.map((c) => (typeof c === "string" ? c : "")))];
  const unknownCap = caps.find((c) => !contract.capabilities.names.includes(c));
  if (unknownCap !== undefined) return bad("capabilities", `Capacidad desconocida: "${unknownCap}"`);
  const missingRequiredCap = contract.capabilities.required.find((c) => !caps.includes(c));
  if (missingRequiredCap !== undefined) return bad("capabilities", `El plugin debe declarar "${missingRequiredCap}"`);
  if (!contract.capabilities.atLeastOneOf.some((c) => caps.includes(c))) {
    return bad("capabilities", `El plugin debe declarar "${contract.capabilities.atLeastOneOf.join('" o "')}"`);
  }
  if (o.color !== undefined && o.color !== "" && !re(m.colorPattern).test(o.color)) return bad("color", 'El campo "color" debe ser del tipo #RRGGBB');
  if (o.icon !== undefined && o.icon !== "" && (!isSafeRelativePath(o.icon) || !o.icon.endsWith(".png"))) return bad("icon", 'El campo "icon" debe ser una ruta relativa a un .png');
  if (o.permissions !== undefined && !Array.isArray(o.permissions)) return bad("permissions", 'El campo "permissions" debe ser una lista');
  for (const p of o.permissions || []) {
    if (typeof p !== "string") return bad("permissions", 'El campo "permissions" solo puede tener textos');
    if (!knownPermissions.includes(p)) return bad("permissions", `permiso desconocido: ${p.slice(0, 40)}`);
  }
  if (o.settings !== undefined && !Array.isArray(o.settings)) return bad("settings", 'El campo "settings" debe ser una lista');
  const settingsError = validateSettings(o.settings || []);
  if (settingsError) return bad("settings", settingsError);
  return { ok: true, manifest: { ...o, hosts: [...new Set(hosts)], capabilities: caps, permissions: o.permissions || [], settings: o.settings || [] } };
}

function validateSettings(list) {
  const s = contract.settings;
  if (list.length > s.max) return `El plugin pide más de ${s.max} ajustes`;
  const keys = new Set();
  for (let i = 0; i < list.length; i++) {
    const o = list[i];
    if (o === null || typeof o !== "object" || Array.isArray(o)) return `El ajuste #${i + 1} no es válido`;
    const key = typeof o.key === "string" ? o.key : "";
    if (!re(s.keyPattern).test(key)) return `El ajuste #${i + 1} tiene una clave inválida`;
    if (keys.has(key)) return `El ajuste "${key}" está repetido`;
    keys.add(key);
    const label = typeof o.label === "string" ? o.label.trim() : "";
    if (!label || label.length > s.labelMaxChars) return `El ajuste "${key}" necesita un nombre de 1 a ${s.labelMaxChars} caracteres`;
    const type = s.types[o.type];
    if (!type || typeof o.type !== "string") return `El ajuste "${key}" tiene un tipo desconocido`;
    if (typeof o.hint === "string" && o.hint.trim().length > s.hintMaxChars) return `La ayuda del ajuste "${key}" pasa de ${s.hintMaxChars} caracteres`;
    if (o.required !== undefined && typeof o.required !== "boolean") return `"required" del ajuste "${key}" debe ser true o false`;
    if (o.required === true && !type.canBeRequired) return `El ajuste "${key}" no puede ser obligatorio`;
    if (o.type === "select") {
      if (!Array.isArray(o.options) || o.options.length === 0) return `El ajuste "${key}" necesita opciones`;
      if (o.options.length > s.maxOptions) return `El ajuste "${key}" tiene más de ${s.maxOptions} opciones`;
      const values = new Set();
      for (const opt of o.options) {
        const v = opt && typeof opt.value === "string" ? opt.value : "";
        const l = opt && typeof opt.label === "string" ? opt.label.trim() : "";
        if (!v || v.length > s.optionValueMaxChars || !l || l.length > s.optionLabelMaxChars) return `Una opción del ajuste "${key}" no es válida`;
        if (values.has(v)) return `El ajuste "${key}" repite la opción "${v}"`;
        values.add(v);
      }
    }
    if (o.default !== undefined && o.default !== null) {
      // A typed server becomes an allowed host; only the person may type one (a `hint` shows an example).
      if (type.canHaveDefault === false) return `El ajuste "${key}" de tipo ${o.type} no puede tener valor por defecto: usa "hint"`;
      const fits = o.type === "toggle" ? typeof o.default === "boolean"
        : o.type === "select" ? o.options.some((x) => x.value === o.default)
          : typeof o.default === "string" && o.default.length <= type.maxChars;
      if (!fits) return `El valor por defecto del ajuste "${key}" no sirve para su tipo`;
    }
  }
  return null;
}

/** A server a person may type (spec §1.4): http(s), not loopback, link-local, unspecified or localhost. */
export function isUserServer(value) {
  let u;
  try { u = new URL(String(value).trim()); } catch { return false; }
  if (u.protocol !== "http:" && u.protocol !== "https:" || !u.hostname) return false;
  const h = u.hostname.toLowerCase().replace(/^\[|\]$/g, "").replace(/\.$/, "");
  if (h === "localhost" || h.endsWith(".localhost")) return false;
  if (/^127\./.test(h) || /^169\.254\./.test(h) || h === "0.0.0.0" || h === "::1" || h === "::" || /^fe[89ab][0-9a-f]:/i.test(h)) return false;
  if (/^::ffff:(127\.|169\.254\.|0\.0\.0\.0)/i.test(h) || /^::ffff:7f/i.test(h)) return false;
  return true;
}

// ---------- output: what the app keeps and drops ----------

const o = () => contract.output;
const text = (v, max) => (typeof v === "string" ? v : typeof v === "number" ? String(v) : "").trim().slice(0, max);
const isLocalAddress = (host) => {
  const h = host.toLowerCase().replace(/\.$/, "");
  if (!h || h.includes(":") || h.includes("[")) return true;
  if (h === "localhost" || PRIVATE_SUFFIXES.some((s) => h.endsWith(s))) return true;
  return /^\d+$/.test(h.split(".").pop());
};

function image(v, servers) {
  const s = typeof v === "string" ? v.trim() : "";
  if (s.length > o().maxImageUrlChars) return "";
  let u;
  try { u = new URL(s); } catch { return ""; }
  if (servers.some((srv) => sameServer(srv, u))) return s;
  if (u.protocol !== "https:" || !s.startsWith("https://")) return "";
  return isLocalAddress(u.hostname) ? "" : s;
}

function sameServer(server, u) {
  let s;
  try { s = new URL(server); } catch { return false; }
  const port = (x) => x.port || (x.protocol === "https:" ? "443" : "80");
  return s.protocol === u.protocol && s.hostname === u.hostname && port(s) === port(u);
}

function strings(v, max, maxChars) {
  if (!Array.isArray(v)) return [];
  const out = [];
  for (const x of v) {
    if (out.length >= max) break;
    const s = typeof x === "string" ? x.trim().slice(0, maxChars) : "";
    if (s && !out.includes(s)) out.push(s);
  }
  return out;
}

function items(list, max, { allowSeries, servers }, drop) {
  const out = [];
  const seen = new Set();
  (Array.isArray(list) ? list : []).forEach((x, i) => {
    if (out.length >= max) { drop(`items beyond ${max} dropped`); return; }
    if (x === null || typeof x !== "object") return;
    const id = typeof x.id === "string" ? x.id : "";
    if (!re(o().itemIdPattern).test(id)) return drop(`item #${i}: invalid id`);
    if (typeof x.ref !== "string" || !x.ref || x.ref.length > o().maxRefChars) return drop(`item ${id}: invalid ref`);
    const title = text(x.title, o().maxTitleChars);
    if (!title) return drop(`item ${id}: no title`);
    if (x.kind !== "movie" && x.kind !== "series") return drop(`item ${id}: invalid kind '${String(x.kind).slice(0, 20)}'`);
    if (x.kind === "series" && !allowSeries) return drop(`item ${id}: series without the episodes capability`);
    if (x.adult === true) return drop(`item ${id}: adult, dropped`);
    if (seen.has(id)) return;
    seen.add(id);
    const ids = x.ids && typeof x.ids === "object" ? x.ids : {};
    out.push({
      id, ref: x.ref, title, kind: x.kind, year: text(x.year, 10),
      poster: image(x.poster, servers), backdrop: image(x.backdrop, servers),
      overview: text(x.overview, o().maxTextChars), lang: text(x.lang, 20), quality: text(x.quality, 20),
      originalTitle: text(x.originalTitle, o().maxTitleChars),
      genres: strings(x.genres, o().maxGenres, o().maxGenreChars),
      rating: typeof x.rating === "number" && x.rating >= o().minRating && x.rating <= o().maxRating ? x.rating : null,
      runtimeMinutes: Number.isInteger(x.runtimeMinutes) && x.runtimeMinutes >= o().minRuntimeMinutes && x.runtimeMinutes <= o().maxRuntimeMinutes ? x.runtimeMinutes : 0,
      tmdb: Number.isInteger(ids.tmdb) && ids.tmdb > 0 ? ids.tmdb : 0,
      imdb: typeof ids.imdb === "string" && re(o().imdbPattern).test(ids.imdb) ? ids.imdb : "",
      badges: strings(x.badges, o().maxBadges, o().maxBadgeChars),
    });
  });
  return out;
}

function page(value, max, ctx, drop) {
  if (Array.isArray(value)) return { items: items(value, max, ctx, drop), next: null };
  if (value === null || typeof value !== "object" || !Array.isArray(value.items)) {
    drop("the answer is not a list or a page");
    return { items: [], next: null };
  }
  let next = typeof value.next === "string" && value.next ? value.next : null;
  if (next && !ctx.allowNext) { drop("page: next dropped, the plugin doesn't declare browse"); next = null; }
  if (next && next.length > o().maxCursorChars) { drop(`page: next longer than ${o().maxCursorChars} dropped`); next = null; }
  return { items: items(value.items, max, ctx, drop), next };
}

function rows(value, ctx, drop) {
  if (!Array.isArray(value)) { drop("home: the answer is not a JSON array"); return []; }
  const out = [];
  const seen = new Set();
  value.forEach((r, i) => {
    if (out.length >= o().maxHomeRows) { drop(`home: rows beyond ${o().maxHomeRows} dropped`); return; }
    if (r === null || typeof r !== "object") return;
    const id = typeof r.id === "string" ? r.id : "";
    if (!re(o().itemIdPattern).test(id)) return drop(`home: row ${i} has an invalid id`);
    const title = text(r.title, o().maxTitleChars);
    if (!title) return drop(`home: row ${id} has no title`);
    const list = items(r.items, o().maxRowItems, ctx, drop);
    if (!list.length) return;
    if (seen.has(id)) return drop(`home: duplicate row ${id} dropped`);
    seen.add(id);
    let ref = typeof r.ref === "string" && r.ref ? r.ref : null;
    if (ref && !ctx.allowNext) { drop(`home: row ${id} has a ref but the plugin doesn't declare browse`); ref = null; }
    if (ref && ref.length > o().maxRefChars) { drop(`home: row ${id} ref too long`); ref = null; }
    out.push({ id, title, ref, items: list });
  });
  return out;
}

function episodes(value, drop) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error("La lista de capítulos no es válida");
  if (!Array.isArray(value.episodes)) throw new Error("El plugin no devolvió capítulos");
  const seen = new Set();
  const eps = [];
  value.episodes.forEach((e, i) => {
    if (eps.length >= o().maxEpisodes) { drop(`episodes: beyond ${o().maxEpisodes} dropped`); return; }
    if (e === null || typeof e !== "object") return;
    const season = Number.isInteger(e.season) && e.season >= 1 && e.season <= o().maxSeasonNumber ? e.season : 1;
    if (!Number.isInteger(e.number) || e.number < 1 || e.number > o().maxEpisodeNumber) return drop(`episodes: #${i} has no valid number`);
    if (typeof e.ref !== "string" || !e.ref || e.ref.length > o().maxRefChars) return drop(`episodes: #${i} has no valid ref`);
    const key = season + "x" + e.number;
    if (seen.has(key)) return drop(`episodes: duplicate S${season}E${e.number} dropped`);
    seen.add(key);
    eps.push({ season, number: e.number, ref: e.ref, title: text(e.title, o().maxTitleChars), airDate: re(o().airDatePattern).test(text(e.airDate, 10)) ? text(e.airDate, 10) : "" });
  });
  return { series: value.series && typeof value.series === "object" ? value.series : null, episodes: eps };
}

function stream(value, { hosts, servers }) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error("El plugin no devolvió un video");
  if (o().drmKeys.some((k) => k in value)) throw new Error("El video tiene DRM y los plugins no lo soportan");
  const check = (url, what) => {
    let u;
    try { u = new URL(String(url)); } catch { throw new Error(`${what} tiene una dirección inválida`); }
    if (servers.some((s) => sameServer(s, u))) return;
    if (u.protocol !== "https:") throw new Error(`${what} debe usar https`);
    if (!hostMatches(u.hostname, hosts)) throw new Error(`${what} apunta a ${u.hostname}, que el plugin no declaró`);
  };
  check(value.url, "El video");
  const expires = Number.isInteger(value.expiresInSeconds) && value.expiresInSeconds >= o().minExpiresInSeconds && value.expiresInSeconds <= o().maxExpiresInSeconds ? value.expiresInSeconds : 0;
  const subtitles = (Array.isArray(value.subtitles) ? value.subtitles : []).slice(0, o().maxSubtitles).filter((s) => {
    try { check(s && s.url, "El subtítulo"); return true; } catch { return false; }
  });
  return { ...value, subtitles, expiresInSeconds: expires };
}

/**
 * What the app would keep of [value], the answer of [fn]: `{ value, drops }` (drops are the log
 * lines the app writes). Throws, with the app's message, when the app would refuse it outright.
 * [servers]: the url settings' values (`--config`), which the app allows like declared hosts.
 */
export function checkOutput(fn, value, manifest, servers = []) {
  const drops = [];
  const drop = (m) => { drops.push(m); };
  const ctx = { allowSeries: manifest.capabilities.includes("episodes"), allowNext: manifest.capabilities.includes("browse"), servers };
  const json = JSON.stringify(value === undefined ? null : value);
  if (json.length > o().maxResultChars) throw new Error("respuesta del plugin demasiado grande (más de 2 millones de caracteres)");
  const parsed = JSON.parse(json);
  switch (fn) {
    case "search": return { value: page(parsed, o().maxSearchItems, ctx, drop), drops };
    case "browse": return { value: page(parsed, o().maxBrowseItems, { ...ctx, allowNext: true }, drop), drops };
    case "home": return { value: rows(parsed, ctx, drop), drops };
    case "episodes": return { value: episodes(parsed, drop), drops };
    case "resolve": return { value: stream(parsed, { hosts: manifest.hosts, servers }), drops };
    default: throw new Error(`unknown function ${fn}`);
  }
}
