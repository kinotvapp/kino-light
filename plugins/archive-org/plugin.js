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

function advancedUrl(query, rows) {
  const parts = ["q=" + encodeURIComponent(query)];
  for (const f of FIELDS) parts.push("fl%5B%5D=" + f);
  parts.push("sort%5B%5D=" + encodeURIComponent("downloads desc"), "rows=" + rows, "page=1", "output=json");
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

async function docs(query, rows) {
  const data = await getJson(advancedUrl(query, rows));
  // A query archive.org can't parse still answers 200, with {"error": ...} instead of "response".
  if (!data.response) throw new Error("archive.org no entendió la búsqueda");
  return data.response.docs.filter((d) => VALID_ID.test(d.identifier));
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
  const out = [];
  if (query.type !== "series") out.push(...(await docs(title + " AND " + FILMS, 25)).map((d) => toItem(d, "movie")));
  if (query.type !== "movie") out.push(...(await docs(title + " AND " + TV, 25)).map((d) => toItem(d, "series")));
  return out;
}

export async function home() {
  const rows = [
    { id: "films", title: "Películas de dominio público", query: FILMS, kind: "movie" },
    { id: "tv", title: "Televisión clásica", query: TV, kind: "series" },
    { id: "cartoons", title: "Animación clásica", query: CARTOONS, kind: "movie" },
  ];
  const out = [];
  for (const row of rows) {
    try {
      const found = await docs(row.query, 30);
      out.push({ id: row.id, title: row.title, items: found.map((d) => toItem(d, row.kind)) });
    } catch (e) {
      kino.log("home row failed", row.id, e.message);
    }
  }
  return out;
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
