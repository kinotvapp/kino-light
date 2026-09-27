// The implementation behind `kino.rank.*` (docs/plugins/README.md, section 5, "kino.rank"): ranks
// and filters what a plugin's own search backend returns against the query Kino asked for, for a
// backend that only matches a loose bag of shared words rather than the title as a whole.
//
// This file is the ONE place the algorithm is written down. `kino-shim.mjs` imports it directly (a
// real import, not a copy). The app's `prelude.js` has no module loader inside the sandbox to import
// it with, so it carries a literal copy of the block between the two markers below, indented the
// same two spaces prelude.js's own IIFE needs -- kept byte-identical by a test in kit.test.mjs
// ("kino.rank: the shim and the runtime run the exact same code"). Never edit one copy without the
// other; run that test after touching either.
//
// Pure JS, no native call in it anywhere: nothing here ever crosses into Kotlin, so it needs none of
// the hardening `kino.fetch`/`kino.crypto` apply against a plugin that reassigns a built-in (that
// hardening exists to protect data on its way to native code; a plugin that breaks its own
// `Array.prototype` only ever breaks its own `kino.rank` results).

// --- kino.rank shared core: BEGIN (byte-identical in kino-rank.mjs and prelude.js) ---
  const FOLD_ACCENTS = {
    á: "a", à: "a", ä: "a", â: "a", ã: "a", å: "a", é: "e", è: "e", ë: "e", ê: "e",
    í: "i", ì: "i", ï: "i", î: "i", ó: "o", ò: "o", ö: "o", ô: "o", õ: "o",
    ú: "u", ù: "u", ü: "u", û: "u", ñ: "n", ç: "c",
  };

  // NFKD decomposes an accented letter into its plain letter plus a combining mark (e.g. "ã" ->
  // "a" + U+0303); stripping the marks folds every decomposable Latin accent at once. `.normalize`
  // is feature-detected, not assumed, because this same code also runs inside Kino's sandboxed JS
  // engine: the manual table above is the fallback for an engine where it is missing.
  function foldAccents(text) {
    if (typeof text.normalize === "function") {
      return text.normalize("NFKD").replace(/[̀-ͯ]/g, "");
    }
    return text.replace(/[áàäâãåéèëêíìïîóòöôõúùüûñç]/g, (c) => FOLD_ACCENTS[c]);
  }

  // Words of 3+ letters, folded to plain lowercase ascii. 1-2 letter words ("el", "de", "a", "of")
  // are dropped: they are exactly what makes unrelated titles look alike.
  function titleTokens(text) {
    const plain = foldAccents(String(text || "").toLowerCase());
    return new Set((plain.match(/[a-z0-9]+/g) || []).filter((w) => w.length > 2));
  }

  // `getTitle(item)` may return a string or an array of them (a title known in more than one field
  // or language): every form's tokens are unioned.
  function tokensOf(getTitle, item) {
    const tokens = new Set();
    for (const form of [].concat(getTitle(item))) for (const t of titleTokens(form)) tokens.add(t);
    return tokens;
  }

  function sharedCount(a, b) {
    let n = 0;
    for (const t of a) if (b.has(t)) n++;
    return n;
  }

  // `query` may be one title or several forms of it (a backend may only know a title in one
  // language): a bare string is treated the same as a one-element array.
  function queryForms(query) {
    return [].concat(query).filter((q) => typeof q === "string" && q.length > 0);
  }

  function defaultGetTitle(item) {
    return item.title;
  }

  // The items that share the most words with any form of `query` go first; ties keep the order
  // `items` was given in (stable). No usable token in `query` at all: the order is left untouched.
  function sortBySimilarity(items, query, getTitle) {
    const of = getTitle || defaultGetTitle;
    const requested = queryForms(query).map(titleTokens).filter((t) => t.size > 0);
    if (requested.length === 0) return items;
    return items
      .map((item, index) => {
        const ofItem = tokensOf(of, item);
        return { item, index, score: Math.max(...requested.map((r) => sharedCount(r, ofItem))) };
      })
      .sort((a, b) => b.score - a.score || a.index - b.index)
      .map((x) => x.item);
  }

  // Minimum share of a requested title's distinctive words an item must carry to count as a real
  // match (0.6 = "most of them").
  const MIN_RELEVANCE = 0.6;

  // Reordering alone (sortBySimilarity) still leaves a page of near-misses when the title genuinely
  // is not on the backend; this drops them, so an absent title comes back with 0 results instead.
  function filterRelevant(items, query, getTitle) {
    const of = getTitle || defaultGetTitle;
    const forms = queryForms(query).map(titleTokens).filter((t) => t.size > 0);
    if (forms.length === 0) return items;
    return items.filter((item) => {
      const ofItem = tokensOf(of, item);
      return forms.some((form) => sharedCount(form, ofItem) / form.size >= MIN_RELEVANCE);
    });
  }

  // Ask a loose-matching backend the title's HEAD, not the whole thing: a long title returns
  // everything that shares one common word with it, drowning the real match; the head alone keeps
  // the backend's own ranking useful. A one- or two-letter head ("El", "A") identifies nothing, so
  // the whole text is used instead. Not a plain "-": that would cut inside a hyphenated word like
  // "Spider-Man".
  function shortQuery(query) {
    const text = String(query || "").trim();
    const head = text.split(/[:,|–—]/)[0].trim();
    return head.length >= 3 ? head : text;
  }
// --- kino.rank shared core: END ---

export { filterRelevant, shortQuery, sortBySimilarity };
