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

/**
 * The one scheme rule of a declared host (the app's `EffectiveHosts.allowsScheme`): https always;
 * plain http only on a host the manifest marked `insecureHttp` (apiVersion 2), matched exactly --
 * never through a `*.` pattern, never a subdomain. A typed server has its own rule (`isUserServer`).
 */
export function schemeAllowed(u, manifest) {
  if (u.protocol === "https:") return true;
  if (u.protocol !== "http:") return false;
  const h = String(u.hostname).toLowerCase().replace(/\.$/, "");
  return (manifest.insecureHosts || []).includes(h);
}

/** The functions the entry file must export for [caps], as the app's ManifestParser.requiredExports. */
export function requiredExports(caps) {
  return [...new Set(caps.flatMap((c) => contract.capabilities.exports[c] || (contract.capabilities.declarative.includes(c) ? [] : [c])))];
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
  // Empty is judged once the settings are read (below), and only from noHostsApiVersion: an older
  // manifest gets the refusal it always got, at the point it always got it.
  const emptyHostsAllowedLater = o.hosts.length === 0 && o.apiVersion >= m.noHostsApiVersion;
  if (!emptyHostsAllowedLater && (o.hosts.length < m.minHosts || o.hosts.length > m.maxHosts)) return bad("hosts", `El campo "hosts" debe tener de 1 a ${m.maxHosts} dominios`);
  const hostEntries = [];
  for (const raw of o.hosts) {
    if (typeof raw === "string") { hostEntries.push({ host: raw, insecure: false }); continue; }
    if (raw !== null && typeof raw === "object" && !Array.isArray(raw)) {
      // The object shape itself -- {host, insecureHttp} -- is apiVersion 2, whatever insecureHttp's
      // value: a v1 manifest gets the same clear refusal either way.
      if (o.apiVersion < m.insecureHostApiVersion) return bad("hosts", `Un host con "insecureHttp" necesita apiVersion ${m.insecureHostApiVersion}`);
      hostEntries.push({ host: typeof raw.host === "string" ? raw.host : "", insecure: raw.insecureHttp === true });
      continue;
    }
    hostEntries.push({ host: "", insecure: false });
  }
  const badHost = hostEntries.find((e) => !isValidHostPattern(e.host));
  if (badHost !== undefined) return bad("hosts", `El dominio "${badHost.host}" no está permitido`);
  // A comodín widens which servers accept plain http far more than one named host: not allowed on
  // an insecureHttp entry even though it is fine on an https-only one.
  const wildcardInsecure = hostEntries.find((e) => e.insecure && e.host.startsWith("*."));
  if (wildcardInsecure !== undefined) return bad("hosts", 'Un host con "insecureHttp" no puede tener comodín ("*.")');
  const hosts = hostEntries.map((e) => e.host);
  const insecureHosts = [...new Set(hostEntries.filter((e) => e.insecure).map((e) => e.host))];
  if (!Array.isArray(o.capabilities)) return bad("capabilities", 'Falta el campo "capabilities"');
  const caps = [...new Set(o.capabilities.map((c) => (typeof c === "string" ? c : "")))];
  const unknownCap = caps.find((c) => !contract.capabilities.names.includes(c));
  if (unknownCap !== undefined) return bad("capabilities", `Capacidad desconocida: "${unknownCap}"`);
  const tooNewCap = caps.find((c) => (contract.capabilities.apiVersions[c] ?? 1) > o.apiVersion);
  if (tooNewCap !== undefined) return bad("capabilities", `Esta capacidad necesita apiVersion ${contract.capabilities.apiVersions[tooNewCap]}`);
  const missingRequiredCap = contract.capabilities.required.find((c) => !caps.includes(c));
  if (missingRequiredCap !== undefined) return bad("capabilities", `El plugin debe declarar "${missingRequiredCap}"`);
  if (!contract.capabilities.atLeastOneOf.some((c) => caps.includes(c))) {
    return bad("capabilities", `El plugin debe declarar "${contract.capabilities.atLeastOneOf.join('" o "')}"`);
  }
  let liveStreamHostsAny = false;
  // Below its apiVersion the field is unknown and ignored like any other (v1/v2 stay as they were).
  if (o.liveStreamHosts !== undefined && o.apiVersion >= m.liveStreamHosts.apiVersion) {
    const lsh = m.liveStreamHosts;
    if (o.liveStreamHosts !== lsh.value) return bad("liveStreamHosts", `El campo "liveStreamHosts" solo admite "${lsh.value}"`);
    if (!caps.includes(lsh.requires)) return bad("liveStreamHosts", `"liveStreamHosts" necesita la capacidad "${lsh.requires}"`);
    liveStreamHostsAny = true;
  }
  let streamHostsAny = false;
  if (o.streamHosts !== undefined && o.apiVersion >= m.streamHosts.apiVersion) {
    if (o.streamHosts !== m.streamHosts.value) return bad("streamHosts", `El campo "streamHosts" solo admite "${m.streamHosts.value}"`);
    streamHostsAny = true;
  }
  // Below its apiVersion the field is unknown and ignored like any other. Kino honors it only on a
  // plugin it converted from a Nuvio scraper (validate.mjs warns a hand-written one); the parse is the same.
  let fetchHostsAny = false;
  if (o.fetchHosts !== undefined && o.apiVersion >= m.fetchHosts.apiVersion) {
    if (o.fetchHosts !== m.fetchHosts.value) return bad("fetchHosts", `El campo "fetchHosts" solo admite "${m.fetchHosts.value}"`);
    fetchHostsAny = true;
  }
  // Only discovery reads it (never the runtime): valid at every apiVersion, exactly true or false.
  if (o.discoverable !== undefined && typeof o.discoverable !== "boolean") return bad("discoverable", 'El campo "discoverable" debe ser true o false');
  const discoverable = o.discoverable === undefined ? m.discoverable.default : o.discoverable;
  // Below its apiVersion the field is unknown and ignored like any other (v1/v2/v3 stay as they were).
  let secrets = {};
  if (o.secrets !== undefined && o.apiVersion >= m.secrets.apiVersion) {
    if (o.secrets === null || typeof o.secrets !== "object" || Array.isArray(o.secrets)) return bad("secrets", 'El campo "secrets" debe ser un objeto');
    const secretNames = Object.keys(o.secrets);
    if (secretNames.length > m.secrets.maxSecrets) return bad("secrets", `El campo "secrets" admite hasta ${m.secrets.maxSecrets} secretos`);
    const NAME = re(m.secrets.namePattern);
    const parsed = {};
    for (const name of secretNames) {
      if (!NAME.test(name)) return bad("secrets", `El secreto "${name.slice(0, 40)}" tiene un nombre inválido`);
      if (!isWellFormedSeal(o.secrets[name], m.secrets)) return bad("secrets", `El secreto "${name}" no es un sello de Kino válido`);
      parsed[name] = o.secrets[name];
    }
    secrets = parsed;
  }
  if (o.color !== undefined && o.color !== "" && !re(m.colorPattern).test(o.color)) return bad("color", 'El campo "color" debe ser del tipo #RRGGBB');
  if (o.icon !== undefined && o.icon !== "" && (!isSafeRelativePath(o.icon) || !o.icon.endsWith(".png"))) return bad("icon", 'El campo "icon" debe ser una ruta relativa a un .png');
  if (o.permissions !== undefined && !Array.isArray(o.permissions)) return bad("permissions", 'El campo "permissions" debe ser una lista');
  for (const p of o.permissions || []) {
    if (typeof p !== "string") return bad("permissions", 'El campo "permissions" solo puede tener textos');
    if (!knownPermissions.includes(p)) return bad("permissions", `permiso desconocido: ${p.slice(0, 40)}`);
  }
  if (o.settings !== undefined && !Array.isArray(o.settings)) return bad("settings", 'El campo "settings" debe ser una lista');
  const settingsError = validateSettings(o.settings || [], o.apiVersion);
  if (settingsError) return bad("settings", settingsError);
  if (hosts.length === 0 && !(o.settings || []).some((x) => x.type === "url" || (x.type === "list" && Array.isArray(x.fields) && x.fields.some((f) => f.type === "url")))) {
    return bad("hosts", 'El campo "hosts" solo puede estar vacío si el plugin tiene un ajuste de tipo "url"');
  }
  return { ok: true, manifest: { ...o, hosts: [...new Set(hosts)], capabilities: caps, permissions: o.permissions || [], settings: o.settings || [], insecureHosts, liveStreamHostsAny, streamHostsAny, fetchHostsAny, discoverable, secrets } };
}

/**
 * Shape only, like the app's SealedSecrets.isWellFormed: the kit never opens a seal. The overhead
 * (32-byte ephemeral pubkey + 12-byte nonce + 16-byte GCM tag = 60 bytes) plus 1..maxValueBytes of
 * plaintext bounds the decoded length; SealedSecrets.kt is the source of truth for these numbers.
 */
function isWellFormedSeal(seal, s) {
  if (typeof seal !== "string" || !seal.startsWith(s.prefix)) return false;
  const body = seal.slice(s.prefix.length);
  if (body.length === 0 || !/^[A-Za-z0-9_-]+$/.test(body)) return false;
  const raw = Buffer.from(body, "base64url");
  const overhead = 60;
  return raw.length >= overhead + 1 && raw.length <= overhead + s.maxValueBytes;
}

function validateSettings(list, apiVersion = contract.maxApiVersion) {
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
    if (o.fields !== undefined && o.type !== "list") return `Solo un ajuste de tipo list tiene "fields"`;
    if (o.type === "list") {
      const L = s.list;
      if (apiVersion < L.apiVersion) return `El ajuste "${key}" es una lista: necesita apiVersion ${L.apiVersion}`;
      if (o.max !== undefined && !(Number.isInteger(o.max) && o.max >= 1 && o.max <= L.maxEntries)) return `"max" del ajuste "${key}" va de 1 a ${L.maxEntries}`;
      if (!Array.isArray(o.fields) || o.fields.length === 0 || o.fields.length > L.maxFields) return `El ajuste "${key}" necesita de 1 a ${L.maxFields} campos`;
      const fkeys = new Set();
      for (const f of o.fields) {
        const fk = f && typeof f.key === "string" ? f.key : "";
        if (!re(s.keyPattern).test(fk) || fkeys.has(fk)) return `Un campo del ajuste "${key}" tiene una clave inválida o repetida`;
        fkeys.add(fk);
        const fl = typeof f.label === "string" ? f.label.trim() : "";
        if (!fl || fl.length > s.labelMaxChars) return `Un campo del ajuste "${key}" necesita un nombre de 1 a ${s.labelMaxChars} caracteres`;
        if (!L.fieldTypes.includes(f.type)) return `Un campo del ajuste "${key}" debe ser de tipo ${L.fieldTypes.join(" o ")}`;
        if (typeof f.hint === "string" && f.hint.trim().length > s.hintMaxChars) return `La ayuda de un campo del ajuste "${key}" pasa de ${s.hintMaxChars} caracteres`;
        if (f.required !== undefined && typeof f.required !== "boolean") return `"required" de un campo del ajuste "${key}" debe ser true o false`;
        if (f.default !== undefined && f.default !== null) return `Un campo del ajuste "${key}" no puede tener valor por defecto`;
      }
    }
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

function items(list, max, { allowSeries, allowLive, servers }, drop) {
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
    if (!o().itemKinds.includes(x.kind)) return drop(`item ${id}: invalid kind '${String(x.kind).slice(0, 20)}'`);
    if (x.kind === "series" && !allowSeries) return drop(`item ${id}: series without the episodes capability`);
    // Silently, like any invalid item: an apiVersion 1 plugin never declared it could go live.
    if (x.kind === "live" && !allowLive) return drop(`item ${id}: live needs apiVersion ${o().liveKindApiVersion}`);
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
      // A channel has no length: whatever the plugin put there is ignored, never shown.
      runtimeMinutes: x.kind !== "live" && Number.isInteger(x.runtimeMinutes) && x.runtimeMinutes >= o().minRuntimeMinutes && x.runtimeMinutes <= o().maxRuntimeMinutes ? x.runtimeMinutes : 0,
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
    out.push({ id, title, ref, items: list, genre: genreOf(r.genre) });
  });
  return out;
}

function episodes(value, drop, servers) {
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
    eps.push({
      season, number: e.number, ref: e.ref, title: text(e.title, o().maxTitleChars),
      // The same image and length rules as an item's poster and runtimeMinutes (PluginOutput.episodes).
      still: image(e.still, servers), overview: text(e.overview, o().maxTextChars),
      airDate: re(o().airDatePattern).test(text(e.airDate, 10)) ? text(e.airDate, 10) : "",
      runtimeMinutes: Number.isInteger(e.runtimeMinutes) && e.runtimeMinutes >= o().minRuntimeMinutes && e.runtimeMinutes <= o().maxRuntimeMinutes ? e.runtimeMinutes : 0,
    });
  });
  return { series: value.series && typeof value.series === "object" ? value.series : null, episodes: eps, seasons: seasons(value, drop) };
}

/** The optional sibling `seasons` of an episodes answer, read as the app reads them (PluginOutput.seasonsOf). */
function seasons(value, drop) {
  if (!("seasons" in value) || value.seasons === undefined) return [];
  if (!Array.isArray(value.seasons)) { drop("seasons: not a list, ignored"); return []; }
  const seen = new Set();
  const out = [];
  value.seasons.forEach((s, i) => {
    if (out.length >= o().maxSeasons) { drop(`seasons: beyond ${o().maxSeasons} dropped`); return; }
    if (s === null || typeof s !== "object" || Array.isArray(s)) return;
    const id = typeof s.id === "number" ? String(s.id) : s.id;
    if (typeof id !== "string" || !re(o().itemIdPattern).test(id)) return drop(`seasons: #${i} has an invalid id`);
    if (typeof s.ref !== "string" || !s.ref || s.ref.length > o().maxRefChars) return drop(`seasons: ${id} has no valid ref`);
    const title = text(s.title, o().maxTitleChars);
    if (!title) return drop(`seasons: ${id} has no title`);
    if (seen.has(id)) return drop(`seasons: duplicate ${id} dropped`);
    seen.add(id);
    const number = Number.isInteger(s.number) && s.number >= 1 && s.number <= o().maxSeasonNumber ? s.number : 0;
    out.push({ id, ref: s.ref, title, number, current: s.current === true });
  });
  return out;
}

/** Up to `maxHeaders` request headers as the app keeps them: a token name, none of the forbidden ones, a string value with no line break. */
const HEADER_NAME = /^[A-Za-z0-9-]{1,64}$/;
const FORBIDDEN_HEADERS = ["host", "content-length", "transfer-encoding", "connection"];
/** A declared `genre`, read as Genre.parse: the id when it is in the closed vocabulary (case and outer spaces ignored), else null. */
function genreOf(raw) {
  const g = typeof raw === "string" ? raw.trim().toLowerCase() : "";
  return contract.genres.includes(g) ? g : null;
}

function headersOf(h) {
  const out = {};
  if (h === null || typeof h !== "object" || Array.isArray(h)) return out;
  for (const k of Object.keys(h)) {
    if (Object.keys(out).length >= o().maxHeaders) break;
    const v = h[k];
    if (typeof v !== "string" || !HEADER_NAME.test(k) || FORBIDDEN_HEADERS.includes(k.toLowerCase())) continue;
    if (v.length > 4096 || v.includes("\n") || v.includes("\r")) continue;
    out[k] = v;
  }
  return out;
}

/**
 * The optional `drm` block, read ONLY for a plugin that declares the capability (`allowDrm`) and
 * when it is the only DRM-shaped key present; any other of `drmKeys` refuses the stream as it
 * always did. Mirrors the app's PluginOutput.drmOf, message for message.
 */
function drmOf(value, check, allowDrm) {
  const present = o().drmKeys.filter((k) => k in value);
  if (present.length === 0) return null;
  if (!allowDrm || present.length !== 1 || present[0] !== o().drm.field) throw new Error("El video tiene DRM y los plugins no lo soportan");
  const d = value[o().drm.field];
  if (d === null || typeof d !== "object" || Array.isArray(d)) throw new Error("El DRM del video no es válido");
  if (!o().drm.types.includes(d.type)) throw new Error("El video usa un DRM que Kino no soporta");
  check(d.licenseUrl, "La licencia del video");
  return { type: d.type, licenseUrl: d.licenseUrl, licenseHeaders: headersOf(d.licenseHeaders) };
}

function stream(value, { manifest, servers, allowDrm, liveChannel = false }) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error("El plugin no devolvió un video");
  const check = urlChecker(manifest, servers);
  const drm = drmOf(value, check, allowDrm);
  // `liveStreamHosts: "any"` relaxes a live channel's own stream URL only; its subtitles, audio and
  // license stay on the strict rule below. `streamHosts: "any"` on a movie or an episode is the app's
  // broad-video rule (EffectiveHosts.anyPublicVideoHost, the player's own resolve): the URL AND its
  // side subtitles and audio tracks; never the license. On a live channel it is the live rule.
  const anyVideo = !liveChannel && manifest.streamHostsAny;
  const anyPublic = liveStreamUrlAllowed(manifest, servers);
  if ((liveChannel && manifest.liveStreamHostsAny) || manifest.streamHostsAny) {
    if (!anyPublic(value.url)) throw new Error("El video apunta a una dirección local");
  } else check(value.url, "El video");
  const sideOk = (url, what) => {
    if (anyVideo) return anyPublic(url);
    try { check(url, what); return true; } catch { return false; }
  };
  const expires = Number.isInteger(value.expiresInSeconds) && value.expiresInSeconds >= o().minExpiresInSeconds && value.expiresInSeconds <= o().maxExpiresInSeconds ? value.expiresInSeconds : 0;
  const subtitles = (Array.isArray(value.subtitles) ? value.subtitles : []).slice(0, o().maxSubtitles).filter((s) => sideOk(s && s.url, "El subtítulo"));
  // One URL is one merged child in the app: a repeated URL is kept once, the first wins.
  const audioUrls = new Set();
  const audioTracks = (Array.isArray(value.audioTracks) ? value.audioTracks : []).slice(0, o().maxAudioTracks).filter((a) => {
    if (!sideOk(a && a.url, "El audio")) return false;
    if (audioUrls.has(a.url)) return false;
    audioUrls.add(a.url);
    return true;
  });
  return { ...value, headers: headersOf(value.headers), subtitles, audioTracks, expiresInSeconds: expires, drm };
}

/** The stream URL rule (the app's PluginOutput.checkUrl): a typed server, or the scheme rule and a declared host. */
function urlChecker(manifest, servers) {
  return (url, what) => {
    let u;
    try { u = new URL(String(url)); } catch { throw new Error(`${what} tiene una dirección inválida`); }
    if (servers.some((s) => sameServer(s, u))) return;
    if (!schemeAllowed(u, manifest)) throw new Error(`${what} debe usar https`);
    if (!hostMatches(u.hostname, manifest.hosts)) throw new Error(`${what} apunta a ${u.hostname}, que el plugin no declaró`);
  };
}

const live = () => contract.live;

/** HostRules.isPublicIpv4Literal: a canonical dotted IPv4 outside every private, local and reserved range. */
export function isPublicIpv4Literal(host) {
  const parts = String(host).split(".");
  if (parts.length !== 4) return false;
  const b = parts.map((p) => (/^\d+$/.test(p) && Number(p) <= 255 && String(Number(p)) === p ? Number(p) : -1));
  if (b.includes(-1)) return false;
  if (b[0] === 0 || b[0] === 10 || b[0] === 127 || b[0] >= 224) return false;
  if (b[0] === 100 && b[1] >= 64 && b[1] <= 127) return false;
  if (b[0] === 169 && b[1] === 254) return false;
  if (b[0] === 172 && b[1] >= 16 && b[1] <= 31) return false;
  if (b[0] === 192 && b[1] === 168) return false;
  if (b[0] === 192 && b[1] === 0 && (b[2] === 0 || b[2] === 2)) return false;
  if (b[0] === 198 && (b[1] === 18 || b[1] === 19)) return false;
  return true;
}

/**
 * The playlist and XMLTV download rule (strict, never `liveStreamHosts: "any"`): a server the person
 * typed, or the scheme rule and a declared host. Every redirect hop is judged the same way.
 */
export function playlistUrlAllowed(manifest, servers = []) {
  const check = urlChecker(manifest, servers);
  return (url) => { try { check(url, ""); return true; } catch { return false; } };
}

/**
 * A live channel's own stream URL (PluginOutput.checkUrl with anyPublicLiveHost): the strict rule,
 * or, with `liveStreamHosts: "any"`, any public host over http(s), never a local one and never
 * another port or scheme of a server the person typed.
 */
export function liveStreamUrlAllowed(manifest, servers = []) {
  const strict = playlistUrlAllowed(manifest, servers);
  const typedNames = servers.map((s) => { try { return new URL(s).hostname; } catch { return null; } });
  return (url) => {
    if (strict(url)) return true;
    if (!manifest.liveStreamHostsAny && !manifest.streamHostsAny) return false;
    let u;
    try { u = new URL(String(url)); } catch { return false; }
    if (u.protocol !== "http:" && u.protocol !== "https:") return false;
    if (typedNames.includes(u.hostname)) return false;
    return isPublicIpv4Literal(u.hostname) || !isLocalAddress(u.hostname);
  };
}

/** A `{ playlist: {...} }` declaration, read as PluginOutput.playlistOf: null (dropped) when it can't be used. */
function playlistOf(p, { manifest, servers }, drop) {
  const allows = (url) => { try { urlChecker(manifest, servers)(url, ""); return true; } catch { return false; } };
  const url = typeof p.url === "string" ? p.url : "";
  if (!live().playlistFormats.includes(p.format)) { drop("playlist: unknown format"); return null; }
  // Strict on purpose: the list and its guide are downloaded by the app, never under liveStreamHosts "any".
  if (!allows(url)) { drop(`playlist: ${url.slice(0, 100)} is not a declared host`); return null; }
  let epgUrl = "";
  if (p.epg !== null && typeof p.epg === "object" && !Array.isArray(p.epg) && live().epgFormats.includes(p.epg.format)) {
    const e = typeof p.epg.url === "string" ? p.epg.url : "";
    if (allows(e)) epgUrl = e; else drop("playlist: epg host not declared, guide dropped");
  }
  const h = typeof p.refreshHours === "number" ? Math.trunc(p.refreshHours) : NaN;
  const refreshHours = h >= live().minRefreshHours && h <= live().maxRefreshHours ? h : live().defaultRefreshHours;
  const hideGroups = [...new Set((Array.isArray(p.hideGroups) ? p.hideGroups : []).slice(0, live().maxHideGroups)
    .map((g) => (typeof g === "string" ? g.trim().toLowerCase().slice(0, 100) : "")).filter(Boolean))];
  return { url, headers: headersOf(p.headers), epgUrl, refreshHours, hideGroups, resolve: p.resolve === true, streamHeaders: headersOf(p.streamHeaders), genre: genreOf(p.genre) };
}

/** liveCategories(), read as PluginOutput.liveCategories: `{ categories, playlists }`. */
function liveCategories(value, ctx, drop) {
  let list = value;
  if (!Array.isArray(value)) {
    if (value !== null && typeof value === "object" && "playlist" in value) list = [value];
    else { drop("liveCategories: the answer is not a JSON array"); return { categories: [], playlists: [] }; }
  }
  const categories = [];
  const playlists = [];
  const seen = new Set();
  list.forEach((c, i) => {
    if (c === null || typeof c !== "object" || Array.isArray(c)) return;
    if ("playlist" in c) {
      if (c.playlist === null || typeof c.playlist !== "object" || Array.isArray(c.playlist)) return drop(`liveCategories: #${i} playlist is not an object`);
      if (playlists.length >= live().maxPlaylists) return drop(`liveCategories: playlists beyond ${live().maxPlaylists} dropped`);
      const p = playlistOf(c.playlist, ctx, drop);
      if (p) playlists.push(p);
      return;
    }
    if (categories.length >= live().maxCategories) return drop(`liveCategories: beyond ${live().maxCategories} dropped`);
    // optString in the app: a numeric id is its text.
    const id = typeof c.id === "number" ? String(c.id) : typeof c.id === "string" ? c.id : "";
    if (!re(o().itemIdPattern).test(id)) return drop(`liveCategories: #${i} has an invalid id`);
    const title = text(c.title, o().maxTitleChars);
    if (!title) return drop(`liveCategories: ${id} has no title`);
    if (c.adult === true) return drop(`liveCategories: ${id} adult, dropped`);
    if (seen.has(id)) return drop(`liveCategories: duplicate ${id} dropped`);
    seen.add(id);
    const cc = typeof c.country === "string" ? c.country.trim().toUpperCase() : "";
    categories.push({ id, title, country: /^[A-Z]{2}$/.test(cc) ? cc : "", genre: genreOf(c.genre) });
  });
  return { categories, playlists };
}

/** liveChannels(), read as PluginOutput.liveChannels. Every channel carries `ref` ("" when absent) and `stream` (null when absent). */
function liveChannels(value, ctx, drop) {
  let list = value;
  let next = null;
  if (!Array.isArray(value)) {
    if (value === null || typeof value !== "object" || !Array.isArray(value.items)) { drop("liveChannels: the answer is not a list or a page"); return { items: [], next: null }; }
    list = value.items;
    next = typeof value.next === "string" && value.next ? value.next : null;
    if (next && next.length > o().maxCursorChars) { drop(`page: next longer than ${o().maxCursorChars} dropped`); next = null; }
  }
  const out = [];
  const seen = new Set();
  list.forEach((c, i) => {
    if (out.length >= live().maxChannelsPerPage) { drop(`liveChannels: beyond ${live().maxChannelsPerPage} dropped`); return; }
    if (c === null || typeof c !== "object") return;
    // optString in the app: a numeric id is its text.
    const id = typeof c.id === "number" ? String(c.id) : typeof c.id === "string" ? c.id : "";
    if (!re(o().itemIdPattern).test(id)) return drop(`liveChannels: #${i} has an invalid id`);
    if (id.startsWith(live().reservedIdPrefix)) return drop(`liveChannels: ${id} uses a reserved id`);
    const title = text(c.title, o().maxTitleChars);
    if (!title) return drop(`liveChannels: ${id} has no title`);
    if (c.adult === true) return drop(`liveChannels: ${id} adult, dropped`);
    const ref = typeof c.ref === "string" ? c.ref : "";
    if (ref.length > o().maxRefChars) return drop(`liveChannels: ${id} has an invalid ref`);
    let checked = null;
    if (c.stream !== null && typeof c.stream === "object" && !Array.isArray(c.stream)) {
      try { checked = stream(c.stream, { ...ctx, liveChannel: true }); } catch (e) { return drop(`liveChannels: ${id} stream refused: ${e.message}`); }
    }
    if (!ref && !checked) return drop(`liveChannels: ${id} has neither a ref nor a stream`);
    if (seen.has(id)) return drop(`liveChannels: duplicate ${id} dropped`);
    seen.add(id);
    const number = Number.isInteger(c.number) && c.number >= 1 && c.number <= live().maxChannelNumber ? c.number : 0;
    const categoryId = typeof c.categoryId === "string" && re(o().itemIdPattern).test(c.categoryId) ? c.categoryId : "";
    out.push({ id, title, ref, logo: image(c.logo, ctx.servers), number, categoryId, stream: checked });
  });
  return { items: out, next };
}

/**
 * guide(), read as PluginOutput.guide -- except the requested-ids and time-window filters, which
 * need the call's arguments: the kit checks shape and limits only.
 */
function guide(value, drop) {
  if (!Array.isArray(value)) { drop("guide: the answer is not a JSON array"); return []; }
  const per = new Map();
  value.forEach((g, i) => {
    if (g === null || typeof g !== "object") return;
    const channelId = typeof g.channelId === "string" ? g.channelId : "";
    const title = text(g.title, o().maxTitleChars);
    if (!channelId || !title) return drop(`guide: #${i} has no channel or title`);
    if (!Number.isFinite(g.start) || !Number.isFinite(g.end) || g.end <= g.start) return drop(`guide: #${i} has an invalid start/end`);
    const list = per.get(channelId) || [];
    if (list.length >= live().maxGuideEntriesPerChannel) return drop(`guide: ${channelId} beyond ${live().maxGuideEntriesPerChannel} dropped`);
    if (list.some((e) => e.start === Math.trunc(g.start))) return;
    list.push({ channelId, title, start: Math.trunc(g.start), end: Math.trunc(g.end), description: text(g.description, o().maxTextChars) });
    per.set(channelId, list);
  });
  return [...per.values()].flatMap((l) => l.sort((a, b) => a.start - b.start));
}

/**
 * What the app would keep of [value], the answer of [fn]: `{ value, drops }` (drops are the log
 * lines the app writes). Throws, with the app's message, when the app would refuse it outright.
 * [servers]: the url settings' values (`--config`), which the app allows like declared hosts.
 */
/**
 * `liveChannel`: the `resolve` answer is for a live channel's ref (the app knows; the kit is told),
 * so `liveStreamHosts: "any"` applies to its stream URL.
 */
export function checkOutput(fn, value, manifest, servers = [], { liveChannel = false } = {}) {
  const drops = [];
  const drop = (m) => { drops.push(m); };
  const ctx = {
    allowSeries: manifest.capabilities.includes("episodes"),
    allowNext: manifest.capabilities.includes("browse"),
    // Live channels are apiVersion 2: a v1 plugin's live item is dropped like any invalid one.
    allowLive: Number.isInteger(manifest.apiVersion) && manifest.apiVersion >= o().liveKindApiVersion,
    servers,
  };
  const json = JSON.stringify(value === undefined ? null : value);
  if (json.length > o().maxResultChars) throw new Error("respuesta del plugin demasiado grande (más de 2 millones de caracteres)");
  const parsed = JSON.parse(json);
  switch (fn) {
    case "search": return { value: page(parsed, o().maxSearchItems, ctx, drop), drops };
    case "browse": return { value: page(parsed, o().maxBrowseItems, { ...ctx, allowNext: true }, drop), drops };
    case "home": return { value: rows(parsed, ctx, drop), drops };
    case "episodes": return { value: episodes(parsed, drop, servers), drops };
    // Widevine is the `drm` capability (apiVersion 2 by the manifest rules): without it every DRM-shaped key refuses the stream.
    case "resolve": return { value: stream(parsed, { manifest, servers, allowDrm: manifest.capabilities.includes("drm"), liveChannel }), drops };
    case "liveCategories": return { value: liveCategories(parsed, { manifest, servers }, drop), drops };
    case "liveChannels": return { value: liveChannels(parsed, { manifest, servers, allowDrm: manifest.capabilities.includes("drm") }, drop), drops };
    case "guide": return { value: guide(parsed, drop), drops };
    default: throw new Error(`unknown function ${fn}`);
  }
}
