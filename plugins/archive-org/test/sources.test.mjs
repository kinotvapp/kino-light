// The person's own archive.org addresses (the "sources" list setting): a Home row each, first in the results of a search,
// and paged by "Ver más". Offline: every answer is made here, and the URLs the plugin asked for are recorded.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { validate } from "../../sdk/validate.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

const doc = (id, title) => ({ identifier: id, title, year: "1950", description: "d" });

/** An archive.org that knows: what each advancedsearch `q` answers, and the metadata titles. */
function archive({ answers = {}, titles = {}, files = {} } = {}) {
  const asked = [];
  const fetchImpl = async (url) => {
    asked.push(String(url));
    const u = new URL(url);
    if (u.pathname === "/advancedsearch.php") {
      const q = u.searchParams.get("q");
      // An OR of several scopes answers with the union of what each one holds, like archive.org.
      const docs = [];
      for (const [needle, list] of Object.entries(answers)) if (q.includes(needle)) for (const d of list) if (!docs.some((x) => x.identifier === d.identifier)) docs.push(d);
      return new Response(JSON.stringify({ response: { docs } }), { status: 200, headers: { "content-type": "application/json" } });
    }
    const whole = /^\/metadata\/([^/]+)$/.exec(u.pathname);
    if (whole && files[whole[1]]) return new Response(JSON.stringify({ metadata: { title: titles[whole[1]] || whole[1] }, files: files[whole[1]] }), { status: 200, headers: { "content-type": "application/json" } });
    const m = /^\/metadata\/([^/]+)\/metadata$/.exec(u.pathname);
    if (m && titles[m[1]]) return new Response(JSON.stringify({ result: { title: titles[m[1]] } }), { status: 200, headers: { "content-type": "application/json" } });
    return new Response("{}", { status: 404 });
  };
  return { fetchImpl, asked };
}

/** Test configs are written as slots (url1, cat2 ...) for readability; the plugin gets them as its list, in slot order, gaps closed. */
function asList(config = {}) {
  const slots = [...new Set(Object.keys(config).map((k) => /^(?:url|cat)(\d+)$/.exec(k)?.[1]).filter(Boolean))].sort((a, b) => a - b);
  return slots.length ? { sources: slots.map((n) => ({ url: config["url" + n] ?? "", category: config["cat" + n] ?? "" })) } : {};
}

async function run(fn, args, config, fetchImpl) {
  const r = await validate(root, { run: fn, args, config: asList(config), fetchImpl });
  assert.deepEqual(r.problems, []);
  assert.deepEqual(r.drops, []);
  return r.output;
}

const BUILT_IN = ["films", "tv", "cartoons"];
/** What the three built-in rows need, so a Home run has no empty row for the checker to drop. */
const builtIns = { feature_films: [doc("f1", "Film")], classic_tv: [doc("t1", "TV")], animationandcartoons: [doc("c1", "Cartoon")] };

test("the manifest declares one list of addresses, each with an optional category, and Kino accepts it", async () => {
  const r = await validate(root);
  assert.deepEqual(r.problems, []);
  const m = JSON.parse(readFileSync(join(root, "kino-plugin.json"), "utf8"));
  assert.equal(m.apiVersion, 4);
  assert.equal(m.settings.length, 1);
  const list = m.settings[0];
  assert.deepEqual([list.key, list.type, list.max], ["sources", "list", 30]);
  assert.deepEqual(list.fields.map((f) => [f.key, f.type, !!f.required]), [["url", "url", true], ["category", "text", false]]);
});

test("without addresses the Home is what it always was", async () => {
  const { fetchImpl } = archive({ answers: builtIns });
  const rows = await run("home", [], {}, fetchImpl);
  assert.deepEqual(rows.map((r) => r.id), BUILT_IN);
});

test("a collection address becomes the first Home row, newest additions first, titled after the collection", async () => {
  const { fetchImpl, asked } = archive({
    answers: { "collection:(mis-pelis)": [doc("a", "Nueva"), doc("b", "Vieja")], ...builtIns },
    titles: { "mis-pelis": "Mis películas" },
  });
  const rows = await run("home", [], { url1: "https://archive.org/details/mis-pelis" }, fetchImpl);
  assert.deepEqual(rows.map((r) => r.id), ["src1", ...BUILT_IN]);
  assert.equal(rows[0].title, "Mis películas");
  assert.equal(rows[0].ref, "src1");
  assert.deepEqual(rows[0].items.map((i) => i.id), ["a", "b"]);
  assert.ok(rows[0].items.every((i) => i.kind === "movie"));
  const own = asked.find((u) => u.includes("mis-pelis") && u.includes("advancedsearch") && u.includes("rows=30"));
  assert.ok(decodeURIComponent(own).includes("collection:(mis-pelis) AND mediatype:(movies)"));
  assert.ok(decodeURIComponent(own).includes("sort[]=addeddate desc"), "the newest first");
});

test("an item address is a row with that one video when it is not a collection", async () => {
  const { fetchImpl } = archive({ answers: { "identifier:(un-video)": [doc("un-video", "Un video")], ...builtIns }, titles: { "un-video": "Un video" } });
  const rows = await run("home", [], { url1: "https://archive.org/details/un-video" }, fetchImpl);
  assert.equal(rows[0].id, "src1");
  assert.deepEqual(rows[0].items.map((i) => i.id), ["un-video"]);
});

test("a search address runs that search, movies only", async () => {
  const { fetchImpl, asked } = archive({ answers: { "subject:noir": [doc("n1", "Noir")], ...builtIns } });
  const rows = await run("home", [], { url2: "https://archive.org/search?query=subject%3Anoir" }, fetchImpl);
  assert.equal(rows[0].id, "src1");
  assert.deepEqual(rows[0].items.map((i) => i.id), ["n1"]);
  const own = decodeURIComponent(asked.find((u) => u.includes("noir")));
  assert.ok(own.includes("(subject:noir) AND mediatype:(movies)"));
});

test("an address that is not archive.org, or is garbage, is left out without breaking the Home", async () => {
  const { fetchImpl } = archive({ answers: builtIns });
  const rows = await run("home", [], { url1: "https://example.com/details/x", url2: "no es una url", url3: "https://archive.org/about" }, fetchImpl);
  assert.deepEqual(rows.map((r) => r.id), BUILT_IN);
});

test("Ver más pages an own row, and an unknown row is not_found", async () => {
  const many = Array.from({ length: 50 }, (_, i) => doc("x" + i, "X" + i));
  const { fetchImpl } = archive({ answers: { "collection:(mis-pelis)": many } });
  const cfg = { url1: "https://archive.org/details/mis-pelis" };
  const page = await run("browse", ["src1"], cfg, fetchImpl);
  assert.equal(page.items.length, 50);
  assert.equal(page.next, "2");
  const r = await validate(root, { run: "browse", args: ["src5"], config: asList(cfg), fetchImpl });
  assert.ok(r.problems.some((p) => /not_found|ya no existe/.test(p)) || r.output == null);
});

test("a search puts what is inside the person's addresses first, once", async () => {
  const { fetchImpl } = archive({
    answers: {
      "collection:(mis-pelis)": [doc("mine", "Casablanca copia")],
      // The same video is also in the global results, after another one: the own one still leads, and is listed once.
      feature_films: [doc("f2", "Casablanca"), doc("mine", "Casablanca copia")],
    },
  });
  const out = await run("search", ["casablanca"], { url1: "https://archive.org/details/mis-pelis" }, fetchImpl);
  assert.deepEqual(out.items.map((i) => i.id), ["mine", "f2"]);
});

test("addresses with the same category (any capitals) share one Home row, in a single query, named as typed first", async () => {
  const { fetchImpl, asked } = archive({
    answers: { "collection:(uno)": [doc("a", "A")], "collection:(dos)": [doc("b", "B")], ...builtIns },
    titles: { uno: "Uno", dos: "Dos" },
  });
  const cfg = { url1: "https://archive.org/details/uno", cat1: "Mis clásicos", url2: "https://archive.org/details/dos", cat2: "mis CLÁSICOS" };
  const rows = await run("home", [], cfg, fetchImpl);
  assert.deepEqual(rows.map((r) => r.id), ["cat1", ...BUILT_IN]);
  assert.equal(rows[0].title, "Mis clásicos");
  assert.equal(rows[0].ref, "cat1");
  assert.deepEqual(rows[0].items.map((i) => i.id).sort(), ["a", "b"]);
  const combined = asked.map(decodeURIComponent).find((u) => u.includes("rows=30") && u.includes("collection:(uno)") && u.includes("collection:(dos)"));
  assert.ok(combined && combined.includes(" OR "), "one query for the whole category");
});

test("one address with a category is a row named after the category, not after the collection", async () => {
  const { fetchImpl } = archive({ answers: { "collection:(uno)": [doc("a", "A")], ...builtIns }, titles: { uno: "Título de la colección" } });
  const rows = await run("home", [], { url3: "https://archive.org/details/uno", cat3: "Para los niños" }, fetchImpl);
  assert.equal(rows[0].id, "cat1");
  assert.equal(rows[0].title, "Para los niños");
});

test("addresses without a category keep a row each, next to a categorised one", async () => {
  const { fetchImpl } = archive({ answers: { "collection:(uno)": [doc("a", "A")], "collection:(dos)": [doc("b", "B")], "collection:(tres)": [doc("c", "C")], ...builtIns }, titles: { uno: "Uno", dos: "Dos", tres: "Tres" } });
  const cfg = { url1: "https://archive.org/details/uno", url2: "https://archive.org/details/dos", cat2: "Solo dos", url3: "https://archive.org/details/tres" };
  const rows = await run("home", [], cfg, fetchImpl);
  assert.deepEqual(rows.map((r) => r.id), ["src1", "cat2", "src3", ...BUILT_IN]);
  assert.deepEqual(rows.slice(0, 3).map((r) => r.title), ["Uno", "Solo dos", "Tres"]);
});

test("a category with no address behind it makes no row", async () => {
  const { fetchImpl } = archive({ answers: builtIns });
  const rows = await run("home", [], { cat1: "Nada", url2: "no es una url", cat2: "Tampoco" }, fetchImpl);
  assert.deepEqual(rows.map((r) => r.id), BUILT_IN);
});

test("Ver más on a category pages the whole category's query", async () => {
  const many = Array.from({ length: 50 }, (_, i) => doc("x" + i, "X" + i));
  const { fetchImpl, asked } = archive({ answers: { "collection:(uno)": many, "collection:(dos)": many } });
  const cfg = { url1: "https://archive.org/details/uno", cat1: "Mix", url2: "https://archive.org/details/dos", cat2: "Mix" };
  const page = await run("browse", ["cat1"], cfg, fetchImpl);
  assert.equal(page.items.length, 50);
  assert.equal(page.next, "2");
  const q = asked.map(decodeURIComponent).find((u) => u.includes("rows=50"));
  assert.ok(q.includes("collection:(uno)") && q.includes("collection:(dos)") && q.includes(" OR "));
});

test("a search looks inside every address at once, whatever their categories", async () => {
  const { fetchImpl, asked } = archive({ answers: { "collection:(uno)": [doc("a", "Cine A")], "collection:(dos)": [doc("b", "Cine B")] } });
  const cfg = { url1: "https://archive.org/details/uno", cat1: "X", url2: "https://archive.org/details/dos" };
  const out = await run("search", ["cine"], cfg, fetchImpl);
  assert.ok(out.items.some((i) => i.id === "a") || out.items.some((i) => i.id === "b"));
  const own = asked.map(decodeURIComponent).find((u) => u.includes("title:(cine)") && u.includes("collection:(uno)") && u.includes("collection:(dos)"));
  assert.ok(own, "one search over both scopes");
});

const mp4 = (name, extra = {}) => ({ name, source: "original", format: "h.264", length: "60", ...extra });

test("an item address with several videos is one card per video, playable one by one", async () => {
  const { fetchImpl } = archive({
    answers: { "identifier:(serie)": [doc("serie", "Mi serie")], ...builtIns },
    titles: { serie: "Mi serie" },
    files: { serie: [mp4("S01E01 - Uno.mp4"), mp4("S01E02 - Dos.mp4"), mp4("S01E03 - Tres.mp4")] },
  });
  const rows = await run("home", [], { url1: "https://archive.org/details/serie" }, fetchImpl);
  assert.equal(rows[0].id, "src1");
  assert.deepEqual(rows[0].items.map((i) => i.id), ["serie~1", "serie~2", "serie~3"]);
  assert.deepEqual(rows[0].items.map((i) => i.ref), ["serie|S01E01 - Uno.mp4", "serie|S01E02 - Dos.mp4", "serie|S01E03 - Tres.mp4"]);
  assert.ok(rows[0].items.every((i) => i.kind === "movie" && i.title.startsWith("Mi serie")));
  assert.deepEqual(rows[0].items.map((i) => i.title.split(" · ")[1]), ["Uno", "Dos", "Tres"]);
});

test("an item with a single video stays one card with the plain identifier", async () => {
  const { fetchImpl } = archive({
    answers: { "identifier:(solo)": [doc("solo", "Solo uno")], ...builtIns },
    files: { solo: [mp4("solo.mp4")] },
  });
  const rows = await run("home", [], { url1: "https://archive.org/details/solo" }, fetchImpl);
  assert.deepEqual(rows[0].items.map((i) => [i.id, i.ref]), [["solo", "solo"]]);
});

test("a category can hold a collection and a multi-video item together", async () => {
  const { fetchImpl } = archive({
    answers: { "collection:(col)": [doc("c1", "De la colección")], "identifier:(serie)": [doc("serie", "Mi serie")], ...builtIns },
    files: { serie: [mp4("a.mp4"), mp4("b.mp4")] },
  });
  const cfg = { url1: "https://archive.org/details/col", cat1: "Mezcla", url2: "https://archive.org/details/serie", cat2: "Mezcla" };
  const rows = await run("home", [], cfg, fetchImpl);
  assert.equal(rows[0].id, "cat1");
  assert.deepEqual(rows[0].items.map((i) => i.id).sort(), ["c1", "serie~1", "serie~2"]);
});

test("a search finds a video by its own title inside a multi-video item address", async () => {
  // archive.org's own title search does not match the item ("Mi serie"), so the plugin has to look inside it.
  const { fetchImpl } = archive({
    answers: {},
    files: { serie: [mp4("Capitulo uno.mp4"), mp4("El gran final.mp4")] },
  });
  const out = await run("search", ["gran final"], { url1: "https://archive.org/details/serie" }, fetchImpl);
  assert.deepEqual(out.items.map((i) => i.ref), ["serie|El gran final.mp4"]);
});

// ---- Searching by every title Kino knows for the work ---------------------------------------------------
const searchOf = (fields) => JSON.stringify({ q: "", type: "any", year: 0, originalTitle: "", altTitles: [], ...fields });
const doy = (id, title, year) => ({ identifier: id, title, year, description: "d" });
const searches = (asked) => asked.filter((u) => u.includes("/advancedsearch.php")).map((u) => new URL(u).searchParams.get("q"));

test("a Spanish title archive.org does not know finds the film by its original title", async () => {
  const { fetchImpl, asked } = archive({
    answers: { "title:(The Great Train Robbery) AND collection:(feature_films)": [doy("TheGreatTrainRobbery_555", "The Great Train Robbery", "1903")] },
  });
  const out = await run("search", [searchOf({ q: "Asalto y robo de un tren", originalTitle: "The Great Train Robbery", year: 1903 })], {}, fetchImpl);
  assert.deepEqual(out.items.map((i) => i.id), ["TheGreatTrainRobbery_555"]);
  const qs = searches(asked);
  assert.ok(qs.some((q) => q.startsWith("title:(Asalto y robo de un tren) AND")), "what was typed is still asked");
  assert.ok(qs.some((q) => q.startsWith("title:(The Great Train Robbery) AND")));
});

test("each title is asked by its head, so a subtitle archive.org lacks does not hide the film", async () => {
  const { fetchImpl, asked } = archive({ answers: { "title:(Nosferatu) AND": [doy("nosferatu-1922_202504", "Nosferatu (1922)", "1922")] } });
  const out = await run("search", [searchOf({ q: "Nosferatu, el vampiro", originalTitle: "Nosferatu, eine Symphonie des Grauens" })], {}, fetchImpl);
  assert.deepEqual(out.items.map((i) => i.id), ["nosferatu-1922_202504"]);
  // Both heads are "Nosferatu": one form, asked once per collection.
  assert.deepEqual(searches(asked).map((q) => q.split(" AND ")[0]), ["title:(Nosferatu)", "title:(Nosferatu)"]);
});

test("with the year known, the film from that year (give or take one) comes first", async () => {
  const { fetchImpl } = archive({
    answers: {
      "title:(Nosferatu) AND collection:(feature_films)": [
        doy("nosferatu-1979", "Nosferatu the Vampyre", "1979"),
        doy("nosferatu-noyear", "Nosferatu", undefined),
        doy("nosferatu-1923", "Nosferatu", "1923"),
        doy("nosferatu-1922", "Nosferatu", "1922"),
      ],
    },
  });
  const out = await run("search", [searchOf({ q: "Nosferatu", year: 1922 })], {}, fetchImpl);
  assert.deepEqual(out.items.map((i) => i.id), ["nosferatu-1923", "nosferatu-1922", "nosferatu-noyear", "nosferatu-1979"]);
});

test("an item found by several titles, or in both collections, is listed once", async () => {
  const same = doy("the-kid-1921", "The Kid", "1921");
  const { fetchImpl, asked } = archive({ answers: { "title:(El chico)": [same], "title:(The Kid)": [same, doy("the-kid-tv", "The Kid", "1921")] } });
  // "Metrópolis" and "Metropolis" are one title: asked once.
  const out = await run("search", [searchOf({ q: "El chico", originalTitle: "The Kid", altTitles: ["Metrópolis", "Metropolis"] })], {}, fetchImpl);
  assert.deepEqual(out.items.map((i) => i.id), ["the-kid-1921", "the-kid-tv"]);
  assert.equal(searches(asked).filter((q) => /^title:\(Metr/.test(q)).length, 2);
});

test("a near-miss is dropped, but an item whose identifier is the title stays", async () => {
  const { fetchImpl } = archive({
    answers: {
      "title:(The General) AND collection:(feature_films)": [doy("general-1926", "The General", "1926"), doy("thegeneral", "Buster Keaton 1926 restored", "1926"), doy("other", "General Motors ad", "1950")],
    },
  });
  const out = await run("search", [searchOf({ q: "El maquinista de la General", originalTitle: "The General" })], {}, fetchImpl);
  assert.deepEqual(out.items.map((i) => i.id).sort(), ["general-1926", "thegeneral"]);
});

test("at most four titles are asked, two requests each, however many Kino sends", async () => {
  const { fetchImpl, asked } = archive({ answers: {} });
  const altTitles = ["Uno largo", "Dos largo", "Tres largo", "Cuatro largo", "Cinco largo"];
  await run("search", [searchOf({ q: "Cero largo", originalTitle: "Original largo", altTitles })], {}, fetchImpl);
  const heads = searches(asked).map((q) => q.split(" AND ")[0]);
  assert.equal(asked.length, 8);
  assert.deepEqual([...new Set(heads)], ["title:(Cero largo)", "title:(Original largo)", "title:(Uno largo)", "title:(Dos largo)"]);
});

test("without an original title a search asks exactly what it always did", async () => {
  const { fetchImpl, asked } = archive({ answers: { "collection:(feature_films)": [doy("f1", "Casablanca", "1942")], "collection:(classic_tv)": [doy("t1", "Casablanca", "1955")] } });
  const out = await run("search", [searchOf({ q: "Casablanca" })], {}, fetchImpl);
  assert.deepEqual(out.items.map((i) => [i.id, i.kind]), [["f1", "movie"], ["t1", "series"]]);
  assert.deepEqual(searches(asked), ["title:(Casablanca) AND collection:(feature_films) AND mediatype:(movies)", "title:(Casablanca) AND collection:(classic_tv) AND mediatype:(movies)"]);
});

test("the person's addresses are searched by every title in one request", async () => {
  const { fetchImpl, asked } = archive({ answers: { "collection:(mis-pelis)": [doy("mine", "The Great Train Robbery", "1903")] } });
  const out = await run("search", [searchOf({ q: "Asalto y robo de un tren", originalTitle: "The Great Train Robbery" })], { url1: "https://archive.org/details/mis-pelis" }, fetchImpl);
  assert.equal(out.items[0].id, "mine");
  const own = searches(asked).find((q) => q.includes("collection:(mis-pelis)") && q.includes("title:"));
  assert.ok(own.startsWith("(title:(Asalto y robo de un tren) OR title:(The Great Train Robbery)) AND"));
});
