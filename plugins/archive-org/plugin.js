// Kino plugin: Internet Archive (archive.org) — public-domain films and classic TV.
// Declared hosts: archive.org and *.archive.org (downloads redirect to a storage node such as
// dn720705.ca.archive.org).

const BASE = "https://archive.org";
const FIELDS = ["identifier", "title", "year", "description"];
const VALID_ID = /^[A-Za-z0-9._-]{1,100}$/;
const PLAYABLE_EXT = /\.(mp4|m4v|webm)$/i;
const SUBTITLE_EXT = /\.(vtt|srt)$/i;
// Preferred playable files, best first (archive.org's "format" field, lowercased).
const FORMAT_RANK = ["h.264", "h.264 hd", "mpeg4", "512kb mpeg4"];
const FILMS = "collection:(feature_films) AND mediatype:(movies)";
const TV = "collection:(classic_tv) AND mediatype:(movies)";
const CARTOONS = "collection:(animationandcartoons) AND mediatype:(movies)";

function advancedUrl(query, rows, page = 1, sort = "downloads desc") {
  const parts = ["q=" + encodeURIComponent(query)];
  for (const f of FIELDS) parts.push("fl%5B%5D=" + f);
  parts.push("sort%5B%5D=" + encodeURIComponent(sort), "rows=" + rows, "page=" + page, "output=json");
  return BASE + "/advancedsearch.php?" + parts.join("&");
}

// Throws only AFTER its first await: in Kino a throw before a function's first await can't be
// caught by its caller.
async function getJson(url) {
  const r = await kino.fetch(url);
  if (!r.ok) throw new Error("archive.org respondió " + r.status);
  return r.json();
}

function first(v) {
  return Array.isArray(v) ? v[0] : v;
}

function toItem(doc, kind) {
  const description = [].concat(doc.description || []).join(" ").replace(/<[^>]*>/g, "").trim();
  return {
    id: doc.identifier,
    ref: doc.identifier,
    title: String(first(doc.title) || doc.identifier),
    kind,
    year: doc.year ? String(first(doc.year)) : undefined,
    poster: BASE + "/services/img/" + encodeURIComponent(doc.identifier),
    overview: description ? description.slice(0, 400) : undefined,
  };
}

async function docs(query, rows, page = 1, sort) {
  const data = await getJson(advancedUrl(query, rows, page, sort));
  // A query archive.org can't parse still answers 200, with {"error": ...} instead of "response".
  if (!data.response) throw new Error("archive.org no entendió la búsqueda");
  return data.response.docs.filter((d) => VALID_ID.test(d.identifier));
}

// ---- The person's own addresses (Configurar: the "sources" list) ---------------------------------------
const NEWEST = "addeddate desc";

// One address: a collection or item (archive.org/details/<id>) or a search (archive.org/search?query=...).
// Anything else, or another site, is left out: the plugin only ever talks to archive.org.
function parseSource(raw) {
  const text = String(raw || "").trim();
  if (!text) return null;
  let u;
  try {
    u = new URL(text);
  } catch {
    return null;
  }
  if (u.protocol !== "https:" && u.protocol !== "http:") return null;
  if (u.hostname !== "archive.org" && !u.hostname.endsWith(".archive.org")) return null;
  const details = /^\/details\/([^/?#]+)/.exec(u.pathname);
  if (details) {
    const id = decodeURIComponent(details[1]);
    return VALID_ID.test(id) ? { kind: "details", id } : null;
  }
  if (u.pathname === "/search") {
    const q = (u.searchParams.get("query") || u.searchParams.get("q") || "").trim();
    if (q) return { kind: "search", query: q };
  }
  return null;
}

function sources() {
  const out = [];
  const entries = kino.config.get("sources");
  (Array.isArray(entries) ? entries : []).forEach((e, i) => {
    const s = parseSource(e.url);
    // `id` is the archive.org identifier; `key` is this address's position; `category` is the optional row name the person gave it.
    if (s) out.push({ ...s, key: "src" + (i + 1), category: String(e.category || "").trim().slice(0, 60) });
  });
  return out;
}

// What a details address lists: the videos of a collection, or, when nothing is filed under it, that one item.
// Asked once per address for as long as the runtime lives.
const scopes = new Map();
async function scopeInfo(source) {
  if (source.kind === "search") return { query: "(" + source.query + ") AND mediatype:(movies)", item: false };
  if (!scopes.has(source.key)) {
    const collection = "collection:(" + source.id + ") AND mediatype:(movies)";
    const inside = await docs(collection, 1);
    scopes.set(source.key, inside.length ? { query: collection, item: false } : { query: "identifier:(" + source.id + ") AND mediatype:(movies)", item: true });
  }
  return scopes.get(source.key);
}

async function scopeOf(source) {
  return (await scopeInfo(source)).query;
}

// An item address with several videos shows one card per video (a single video stays the item's one card). The card's id is
// `<identifier>~<n>` (an id cannot hold `|`); its ref is `<identifier>|<file>`, which `resolve` already plays.
const MAX_VIDEO_CARDS = 100;
async function videoCards(identifier, doc) {
  const meta = await metadata(identifier);
  const originals = videoOriginals(meta.files);
  if (originals.length < 2) return [];
  const base = toItem(doc || { identifier, title: first(meta.metadata && meta.metadata.title) }, "movie");
  return originals.slice(0, MAX_VIDEO_CARDS).map((f, i) => ({
    ...base,
    id: identifier + "~" + (i + 1),
    ref: identifier + "|" + f.name,
    title: (base.title + " · " + episodeTitle(f)).slice(0, 200),
  }));
}

// The cards of a list of archive.org docs: the ones that are an item address with several videos are expanded.
async function cardsOf(found, sourceList) {
  const itemIds = new Set();
  for (const s of sourceList) if (s.kind === "details" && (await scopeInfo(s)).item) itemIds.add(s.id);
  const out = [];
  for (const d of found) {
    let parts = [];
    if (itemIds.has(d.identifier)) {
      try {
        parts = await videoCards(d.identifier, d);
      } catch (e) {
        kino.log("videos of", d.identifier, "failed", e.message);
      }
    }
    if (parts.length) out.push(...parts);
    else out.push(toItem(d, "movie"));
  }
  return out;
}

async function titleOf(source) {
  if (source.kind === "search") return ("Búsqueda: " + source.query).slice(0, 80);
  try {
    const data = await getJson(BASE + "/metadata/" + encodeURIComponent(source.id) + "/metadata");
    const title = first(data && data.result && data.result.title);
    if (title) return String(title).slice(0, 80);
  } catch (e) {
    kino.log("title failed", source.id, e.message);
  }
  return source.id;
}

// The Home rows the addresses make: an address with a category joins the row of that category (same name, any capitals; the
// row keeps the first spelling and is keyed by the first address's slot); an address without one is a row of its own.
function ownRows() {
  const out = [];
  const byCategory = new Map();
  for (const s of sources()) {
    if (!s.category) {
      out.push({ key: s.key, title: null, sources: [s] });
      continue;
    }
    const name = s.category.toLowerCase();
    if (!byCategory.has(name)) {
      const row = { key: "cat" + s.key.slice(3), title: s.category, sources: [] };
      byCategory.set(name, row);
      out.push(row);
    }
    byCategory.get(name).sources.push(s);
  }
  return out;
}

// One archive.org query for a whole row (or for every address, in a search): its addresses' scopes, joined.
async function queryOf(sourceList) {
  const scopesOf = [];
  for (const s of sourceList) scopesOf.push(await scopeOf(s));
  return scopesOf.length === 1 ? scopesOf[0] : scopesOf.map((x) => "(" + x + ")").join(" OR ");
}

export async function search(query) {
  // Letters, digits and apostrophes inside words only: any other character can be query syntax to
  // advancedsearch (a stray "/", "-", "&" or "'" makes it answer {"error": ...}). So are the words
  // and/or/not in any case (a dangling one is an error too); dropping them never changes which
  // titles match. Word edges are spelled out with \p{} classes: \b is ASCII-only, so it would cut
  // the "or" out of "Señor".
  const text = String(query.q || "")
    .replace(/[^\p{L}\p{M}\p{N}' ]+/gu, " ")
    .replace(/(?<![\p{L}\p{M}\p{N}])'|'(?![\p{L}\p{M}\p{N}])/gu, " ")
    .replace(/(?<![\p{L}\p{M}\p{N}])(and|or|not)(?![\p{L}\p{M}\p{N}])/giu, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!text) return [];
  const title = "title:(" + text + ")";
  // Both collections are always searched: `type` is only a hint. Kino sends it from TMDB's movie/tv
  // split, which does not line up with archive.org's (public-domain films and classic TV are mixed,
  // and a title can be in both), so filtering by it lost real matches. It only decides which group
  // comes first.
  const groups = [
    { kind: "movie", where: FILMS },
    { kind: "series", where: TV },
  ];
  if (query.type === "series") groups.reverse();
  const out = [];
  const seen = new Set();
  // What is inside the person's own addresses comes first.
  const mine = sources();
  if (mine.length) {
    try {
      const hits = (await docs(title + " AND (" + (await queryOf(mine)) + ")", 25)).filter((d) => !seen.has(d.identifier));
      for (const card of await cardsOf(hits, mine)) {
        if (seen.has(card.id)) continue;
        seen.add(card.id);
        out.push(card);
      }
      // An item address with several videos is also searched by its videos' own titles.
      const words = text.toLowerCase().split(" ");
      for (const s of mine) {
        if (s.kind !== "details" || !(await scopeInfo(s)).item) continue;
        for (const card of await videoCards(s.id)) {
          if (seen.has(card.id) || !words.every((w) => card.title.toLowerCase().includes(w))) continue;
          seen.add(card.id);
          out.push(card);
        }
      }
    } catch (e) {
      kino.log("search in the person's addresses failed", e.message);
    }
  }
  for (const group of groups) {
    for (const d of await docs(title + " AND " + group.where, 25)) {
      // An item can be in both collections: it is listed once, as the kind of the group that came first.
      if (seen.has(d.identifier)) continue;
      seen.add(d.identifier);
      out.push(toItem(d, group.kind));
    }
  }
  return out;
}

// Home rows; each one's id is also its "Ver más" ref (the browse capability).
const ROWS = [
  { id: "films", title: "Películas de dominio público", query: FILMS, kind: "movie" },
  { id: "tv", title: "Televisión clásica", query: TV, kind: "series" },
  { id: "cartoons", title: "Animación clásica", query: CARTOONS, kind: "movie" },
];
const ROW_SIZE = 30;
const PAGE_SIZE = 50;

export async function home() {
  const out = [];
  // The person's own addresses, newest first, before the plugin's own rows.
  for (const row of ownRows()) {
    try {
      const found = await docs(await queryOf(row.sources), ROW_SIZE, 1, NEWEST);
      if (found.length) out.push({ id: row.key, title: row.title || (await titleOf(row.sources[0])), ref: row.key, items: await cardsOf(found, row.sources) });
    } catch (e) {
      kino.log("home row failed", row.key, e.message);
    }
  }
  for (const row of ROWS) {
    try {
      const found = await docs(row.query, ROW_SIZE);
      out.push({ id: row.id, title: row.title, ref: row.id, items: found.map((d) => toItem(d, row.kind)) });
    } catch (e) {
      kino.log("home row failed", row.id, e.message);
    }
  }
  return out;
}

// "Ver más" on a Home row: the same query, a page at a time. The cursor is the next page number.
export async function browse(ref, cursor) {
  await null; // the checks below may throw: never before the first await
  const own = ownRows().find((r) => r.key === ref);
  const row = own || ROWS.find((r) => r.id === ref);
  if (!row) throw kino.error("not_found", "esa fila ya no existe");
  const page = cursor ? Number(cursor) : 1;
  if (!Number.isInteger(page) || page < 1 || page > 100) throw kino.error("not_found", "página inválida");
  const found = own ? await docs(await queryOf(own.sources), PAGE_SIZE, page, NEWEST) : await docs(row.query, PAGE_SIZE, page);
  return { items: own ? await cardsOf(found, own.sources) : found.map((d) => toItem(d, row.kind)), next: found.length === PAGE_SIZE ? String(page + 1) : undefined };
}

async function metadata(id) {
  const data = await getJson(BASE + "/metadata/" + encodeURIComponent(id));
  if (!data || !Array.isArray(data.files)) throw new Error("archive.org no tiene ese item");
  return data;
}

// "Season 2" after "Season 10" is wrong; compare digit runs as numbers.
function natural(a, b) {
  const x = a.split(/(\d+)/);
  const y = b.split(/(\d+)/);
  for (let i = 0; i < Math.min(x.length, y.length); i++) {
    if (x[i] === y[i]) continue;
    if (i % 2 === 1) return Number(x[i]) - Number(y[i]);
    return x[i] < y[i] ? -1 : 1;
  }
  return x.length - y.length;
}

// A video is an original file something playable exists for: itself (mp4/m4v/webm) or a derivative
// made from it (its `original` field). The extension of the original tells nothing: real items
// hold .avi, .mpg, .mkv and .divx originals next to their derived mp4.
function videoOriginals(files) {
  const playable = new Set();
  for (const f of files) {
    if (!PLAYABLE_EXT.test(f.name)) continue;
    const from = f.source === "original" ? f.name : f.original;
    if (from) playable.add(from);
  }
  return files
    .filter((f) => f.source === "original" && playable.has(f.name))
    .sort((a, b) => natural(a.name, b.name));
}

function stem(name) {
  return name.replace(/\.[^./]+$/, "");
}

function rank(f) {
  const i = FORMAT_RANK.indexOf(String(f.format || "").toLowerCase());
  return i === -1 ? FORMAT_RANK.length : i;
}

function bestPlayable(files, original) {
  const family = files.filter((f) => (f.name === original.name || f.original === original.name) && PLAYABLE_EXT.test(f.name));
  family.sort((a, b) => rank(a) - rank(b));
  return family[0] || null;
}

function downloadUrl(id, name) {
  return BASE + "/download/" + encodeURIComponent(id) + "/" + name.split("/").map(encodeURIComponent).join("/");
}

function subtitlesFor(id, files, original) {
  const base = stem(original.name) + ".";
  return files
    .filter((f) => SUBTITLE_EXT.test(f.name) && f.name.startsWith(base))
    .map((f) => ({
      lang: /[._](es|spa|spanish)[._]/i.test(f.name) ? "es" : "en",
      url: downloadUrl(id, f.name),
      format: /\.srt$/i.test(f.name) ? "srt" : "vtt",
    }));
}

function numbering(originals) {
  const found = originals.map((f) => /S(\d{1,3})E(\d{1,4})/i.exec(f.name));
  if (found.every((m) => m)) return found.map((m) => ({ season: Number(m[1]), number: Number(m[2]) }));
  return originals.map((_, i) => ({ season: 1, number: i + 1 }));
}

function episodeTitle(f) {
  if (f.title) return String(f.title);
  const name = stem(f.name.split("/").pop());
  const m = /S\d{1,3}E\d{1,4}\s*-\s*(.+)$/i.exec(name);
  return m ? m[1] : name;
}

export async function episodes(ref) {
  const meta = await metadata(ref);
  const originals = videoOriginals(meta.files);
  const numbers = numbering(originals);
  return {
    series: {
      title: String(first(meta.metadata && meta.metadata.title) || ref),
      poster: BASE + "/services/img/" + encodeURIComponent(ref),
    },
    episodes: originals.map((f, i) => ({
      season: numbers[i].season,
      number: numbers[i].number,
      ref: ref + "|" + f.name,
      title: episodeTitle(f),
    })),
  };
}

export async function resolve(ref) {
  const cut = ref.indexOf("|");
  const id = cut === -1 ? ref : ref.slice(0, cut);
  const wanted = cut === -1 ? null : ref.slice(cut + 1);
  const meta = await metadata(id);
  const originals = videoOriginals(meta.files);
  const original = wanted ? originals.find((f) => f.name === wanted) : originals[0];
  if (!original) throw new Error("este item no tiene video");
  const file = bestPlayable(meta.files, original);
  if (!file) throw new Error("no hay una versión que se pueda reproducir (mp4 o webm)");
  const seconds = Number(file.length || original.length || 0);
  return {
    url: downloadUrl(id, file.name),
    mime: /\.webm$/i.test(file.name) ? "video/webm" : "video/mp4",
    subtitles: subtitlesFor(id, meta.files, original),
    durationMs: seconds > 0 ? Math.round(seconds * 1000) : undefined,
  };
}
