// node --test plugins/sdk/test/kit.test.mjs   (Node 18+)
// The kit against the same rules and vectors the app's JVM tests use.
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { createServer } from "node:http";
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { checkOutput, contract, validateManifest } from "../contract.mjs";
import { createKino } from "../kino-shim.mjs";
import { filterRelevant, shortQuery, sortBySimilarity } from "../kino-rank.mjs";
import { validate } from "../validate.mjs";
import { scaffold } from "../init.mjs";
import { call } from "../run.mjs";
import { ADULT_GROUPS, loadPlaylist, normaliseName, parseM3u, parseXmltv, parseXmltvTime, summarisePlaylist } from "../live-playlist.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const archive = join(here, "..", "..", "archive-org");

const manifest = (extra = {}) => JSON.stringify({
  id: "demo", name: "Demo", version: "1.0.0", apiVersion: 1, entry: "plugin.js",
  hosts: ["example.com"], capabilities: ["search", "resolve"], ...extra,
});

test("contract.json is the one the app pins", () => {
  assert.equal(contract.apiVersion, 3);
  assert.deepEqual(contract.capabilities.names, ["search", "home", "browse", "episodes", "resolve", "download", "drm", "channels"]);
  assert.deepEqual(contract.capabilities.declarative, ["download", "drm"]);
  assert.deepEqual(contract.permissions, []);
});

test("manifest rules and Spanish messages match the app", () => {
  assert.equal(validateManifest(manifest()).ok, true);
  const cases = [
    [{ permissions: ["local-network"] }, "permissions", "permiso desconocido: local-network"],
    [{ settings: [{ key: "Server", label: "x", type: "text" }] }, "settings", "El ajuste #1 tiene una clave inválida"],
    [{ settings: [{ key: "k", label: "x", type: "toggle", required: true }] }, "settings", 'El ajuste "k" no puede ser obligatorio'],
    [{ settings: [{ key: "k", label: "x", type: "select" }] }, "settings", 'El ajuste "k" necesita opciones'],
    [{ settings: [{ key: "k", label: "x", type: "url", default: "http://127.0.0.1/" }] }, "settings", 'El ajuste "k" de tipo url no puede tener valor por defecto: usa "hint"'],
    [{ settings: [{ key: "k", label: "x", type: "url", default: "http://192.168.1.1" }] }, "settings", 'El ajuste "k" de tipo url no puede tener valor por defecto: usa "hint"'],
    [{ settings: [{ key: "k", label: "x", type: "url", default: "" }] }, "settings", 'El ajuste "k" de tipo url no puede tener valor por defecto: usa "hint"'],
    [{ capabilities: ["search"] }, "capabilities", 'El plugin debe declarar "resolve"'],
    [{ capabilities: ["search", "resolve", "download"] }, "capabilities", "Esta capacidad necesita apiVersion 2"],
    [{ capabilities: ["search", "resolve", "drm"] }, "capabilities", "Esta capacidad necesita apiVersion 2"],
    [{ hosts: ["192.168.1.1"] }, "hosts", 'El dominio "192.168.1.1" no está permitido'],
    [{ hosts: [{ host: "x.example.com", insecureHttp: true }] }, "hosts", 'Un host con "insecureHttp" necesita apiVersion 2'],
    [{ apiVersion: 2, hosts: [{ host: "*.example.com", insecureHttp: true }] }, "hosts", 'Un host con "insecureHttp" no puede tener comodín ("*.")'],
    [{ apiVersion: 2, hosts: [{ host: "nas.local", insecureHttp: true }] }, "hosts", 'El dominio "nas.local" no está permitido'],
    [{ id: "magis" }, "id", 'El id "magis" está reservado por Kino'],
    // The app's version regex bounds each segment to 6 digits (Regex("^(0|[1-9]\\d{0,5})...")); a
    // hand-typed unbounded copy would wrongly accept this.
    [{ version: "1234567.0.0" }, "version", 'El campo "version" debe ser del tipo 1.2.3'],
  ];
  for (const [extra, field, message] of cases) {
    assert.deepEqual(validateManifest(manifest(extra)), { ok: false, field, message }, JSON.stringify(extra));
  }
  assert.equal(validateManifest(manifest({ permissions: ["x"] }), { knownPermissions: ["x"] }).ok, true);
});

test("apiVersion 2: download/drm and an insecureHttp host validate and are exposed on the manifest", () => {
  const r = validateManifest(manifest({
    apiVersion: 2,
    hosts: ["archive.org", { host: "x.example.com", insecureHttp: true }],
    capabilities: ["search", "resolve", "download", "drm"],
  }));
  assert.equal(r.ok, true);
  assert.deepEqual(r.manifest.hosts, ["archive.org", "x.example.com"]);
  assert.deepEqual(r.manifest.insecureHosts, ["x.example.com"]);
  assert.deepEqual(r.manifest.capabilities, ["search", "resolve", "download", "drm"]);
});

test("apiVersion 2: empty hosts validate only with a url setting, as the app rules", () => {
  const server = { key: "server", label: "Servidor", type: "url", required: true };
  const ok = validateManifest(manifest({ apiVersion: 2, hosts: [], settings: [server] }));
  assert.equal(ok.ok, true);
  assert.deepEqual(ok.manifest.hosts, []);
  assert.equal(contract.manifest.noHostsApiVersion, 2);
  assert.deepEqual(validateManifest(manifest({ hosts: [], settings: [server] })),
    { ok: false, field: "hosts", message: 'El campo "hosts" debe tener de 1 a 20 dominios' });
  const noUrl = { ok: false, field: "hosts", message: 'El campo "hosts" solo puede estar vacío si el plugin tiene un ajuste de tipo "url"' };
  assert.deepEqual(validateManifest(manifest({ apiVersion: 2, hosts: [] })), noUrl);
  assert.deepEqual(validateManifest(manifest({ apiVersion: 2, hosts: [], settings: [{ key: "user", label: "Usuario", type: "text" }] })), noUrl);
});

test("validate() does not require download/drm to be exported functions", async () => {
  const dir = mkdtempSync(join(tmpdir(), "kino-declarative-"));
  try {
    writeFileSync(join(dir, "kino-plugin.json"), manifest({ apiVersion: 2, capabilities: ["search", "resolve", "download", "drm"] }));
    writeFileSync(join(dir, "plugin.js"), "export async function search(){ return [] }\nexport async function resolve(){ return { url: 'https://example.com/a' } }");
    const r = await validate(dir);
    assert.deepEqual(r.problems, []);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("the archive-org plugin passes the kit's checks", async () => {
  const r = await validate(archive);
  assert.deepEqual(r.problems, []);
});

function runValidateCli(args) {
  // stdio fully piped (never inherited): the child's own stderr must not leak into this test run's
  // own output, and both cases still capture it on e.stdout/e.stderr for the assertions below.
  const opts = { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] };
  try {
    const stdout = execFileSync(process.execPath, [join(here, "..", "validate.mjs"), ...args], opts);
    return { code: 0, stdout, stderr: "" };
  } catch (e) {
    return { code: e.status, stdout: e.stdout ?? "", stderr: e.stderr ?? "" };
  }
}

// A JS stack trace (an uncaught throw) looks nothing like the app's own Spanish refusal text; any
// of these lines means the CLI crashed instead of reporting cleanly.
const looksLikeAStackTrace = (s) => /TypeError|ReferenceError|at file:|at Object\.|at async /.test(s);

test("validate.mjs's CLI reports the app's refusal instead of crashing on the most common failures", () => {
  const dir = mkdtempSync(join(tmpdir(), "kino-badplugin-"));
  try {
    const noManifest = runValidateCli([dir]);
    assert.equal(noManifest.code, 1);
    assert.ok(!looksLikeAStackTrace(noManifest.stderr), `unexpected stack trace:\n${noManifest.stderr}`);
    assert.match(noManifest.stderr, /no kino-plugin\.json/);

    writeFileSync(join(dir, "kino-plugin.json"), JSON.stringify({ id: "X" }));
    const badManifest = runValidateCli([dir]);
    assert.equal(badManifest.code, 1);
    assert.ok(!looksLikeAStackTrace(badManifest.stderr), `unexpected stack trace:\n${badManifest.stderr}`);
    assert.match(badManifest.stderr, /El campo/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("validate() reports a problem instead of throwing when --replay points at a missing file", async () => {
  const r = await validate(archive, { run: "search", args: ["algo"], replay: join(here, "does-not-exist.json") });
  assert.equal(r.ok, false);
  assert.ok(r.problems.some((p) => p.includes("not found")));
  assert.deepEqual(r.drops, []);
});

test("crypto gives the app's vectors", () => {
  const { kino } = createKino(JSON.parse(manifest()));
  const c = kino.crypto;
  assert.equal(c.hash("md5", "abc"), "900150983cd24fb0d6963f7d28e17f72");
  assert.equal(c.hmac("sha256", "Jefe", "what do ya want for nothing?"), "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
  const key = "2b7e151628aed2a6abf7158809cf4f3c", iv = "000102030405060708090a0b0c0d0e0f";
  const enc = c.encrypt("aes-128-cbc", { key, iv, keyEncoding: "hex", ivEncoding: "hex", data: "hola mundo", outputEncoding: "hex" });
  assert.equal(enc, "91d3f1bb5aa666718bdcd8514571632a");
  assert.equal(c.decrypt("aes-128-cbc", { key, iv, keyEncoding: "hex", ivEncoding: "hex", data: enc, inputEncoding: "hex" }), "hola mundo");
  const gcm = c.encrypt("aes-128-gcm", { key: "00".repeat(16), keyEncoding: "hex", iv: "00".repeat(12), ivEncoding: "hex", data: "00".repeat(16), inputEncoding: "hex", outputEncoding: "hex" });
  assert.equal(gcm, "0388dace60b6a392f328c2b971b2fe78ab6e47d42cec13bdf53a67b21257bddf");
  assert.equal(c.pbkdf2("sha1", "password", "salt", 2, 20), "ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957");
  assert.equal(c.encrypt("des-ede3-ecb", { key: "0123456789abcdef23456789abcdef01456789abcdef0123", keyEncoding: "hex", data: "5468652071756663", inputEncoding: "hex", padding: "none", outputEncoding: "hex" }), "a826fd8ce53b855f");
  assert.throws(() => c.hash("sha3", "x"), (e) => e.code === "crypto_error" && e.name === "KinoError_crypto_error");
  assert.throws(() => c.pbkdf2("sha1", "p", "s", 100001, 20), (e) => e.code === "crypto_error");
  assert.throws(() => c.randomBytes(1025), (e) => e.code === "crypto_error");
});

test("config, storage keys, typed errors and sleep", async () => {
  const m = JSON.parse(manifest({ settings: [
    { key: "server", label: "Servidor", type: "url", required: true },
    { key: "hd", label: "HD", type: "toggle" },
    { key: "q", label: "Calidad", type: "select", options: [{ value: "auto", label: "A" }] },
  ] }));
  const { kino } = createKino(m, { config: { server: "http://192.168.1.10:8096", hd: "true" } });
  assert.deepEqual(kino.config.all(), { server: "http://192.168.1.10:8096", hd: true, q: "auto" });
  kino.storage.set("a", "1");
  assert.deepEqual(kino.storage.keys(), ["a"]);
  assert.throws(() => kino.storage.set("big", "x".repeat(300 * 1024)), /256 KB/);
  const e = kino.error("not_found", "x".repeat(500));
  assert.equal(e.code, "not_found");
  assert.equal(e.message.length, 200);
  assert.equal(kino.error("NOPE", "m").code, "unknown");
  await assert.rejects(kino.sleep(6000), (err) => err.code === "invalid_request");
});

test("kino.storage entries with a ttlMs expire, are purged, and old data keeps working", () => {
  const dir = mkdtempSync(join(tmpdir(), "kino-storage-"));
  const storageFile = join(dir, "storage.json");
  try {
    // Data written before ttlMs existed: a bare string per key, no wrapper at all.
    writeFileSync(storageFile, JSON.stringify({ legacy: "still here" }));
    const m = JSON.parse(manifest());
    const opened = () => createKino(m, { storageFile }).kino;

    let kino = opened();
    assert.equal(kino.storage.get("legacy"), "still here");
    kino.storage.set("temp", "v", { ttlMs: 1000 });
    assert.equal(kino.storage.get("temp"), "v");
    assert.deepEqual(kino.storage.keys().sort(), ["legacy", "temp"]);
    kino.storage.set("permanent", "p"); // no options: unaffected, exactly as before.

    // Move "temp" into the past on disk instead of waiting: a fresh instance now sees it expired.
    const onDisk = JSON.parse(readFileSync(storageFile, "utf8"));
    onDisk.temp = { v: "v", e: Date.now() - 1 };
    writeFileSync(storageFile, JSON.stringify(onDisk));

    kino = opened();
    assert.equal(kino.storage.get("temp"), null);
    assert.deepEqual(kino.storage.keys().sort(), ["legacy", "permanent"]);
    // The read purged it: the file no longer carries the expired entry.
    assert.equal(JSON.parse(readFileSync(storageFile, "utf8")).temp, undefined);

    for (const ttlMs of [0, -1, 1.5, NaN, Infinity, contract.storage.maxTtlMs + 1]) {
      assert.throws(() => kino.storage.set("bad", "v", { ttlMs }), /ttlMs/, `ttlMs ${ttlMs} must be refused`);
    }
    assert.equal(kino.storage.get("bad"), null);
    kino.storage.set("ok", "v", { ttlMs: contract.storage.maxTtlMs }); // the cap itself is accepted
    assert.equal(kino.storage.get("ok"), "v");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// Ported from the cookbook this promoted (branch feat/plugin-sdk-ranking-helper), against the
// shipped kino.rank.* functions rather than a copy-pasted recipe.
test("kino.rank.shortQuery cuts at the first separator, but never a plain hyphen", () => {
  assert.equal(shortQuery("Avatar: Aang, El ultimo Maestro Aire"), "Avatar");
  assert.equal(shortQuery("Movie – Subtitle"), "Movie");
  assert.equal(shortQuery("Movie — Subtitle"), "Movie");
  assert.equal(shortQuery("One, Two, Three"), "One");
  // A one- or two-letter head identifies nothing: the whole text is kept instead.
  assert.equal(shortQuery("A: The Beginning"), "A: The Beginning");
  // No separator present at all: the whole (trimmed) text is the head, so it is returned either way.
  assert.equal(shortQuery("  El Ultimo Refugio  "), "El Ultimo Refugio");
  // Not a plain "-": it must not cut inside a hyphenated word.
  assert.equal(shortQuery("Spider-Man: Far From Home"), "Spider-Man");
  assert.equal(shortQuery(""), "");
});

test("kino.rank.sortBySimilarity puts the item sharing the most words first, stable on ties", () => {
  const items = [
    { name: "Saga of Something Else" }, // shares only "saga": 1
    { name: "Totally Unrelated Movie" }, // shares nothing: 0
    { name: "Warrior Saga Legends" }, // shares "warrior", "saga": 2
    { name: "Dragon Warrior Saga: Special Edition" }, // shares all 3
  ];
  const getTitle = (x) => x.name;
  const sorted = sortBySimilarity(items, "Dragon Warrior Saga", getTitle).map(getTitle);
  assert.deepEqual(sorted, [
    "Dragon Warrior Saga: Special Edition",
    "Warrior Saga Legends",
    "Saga of Something Else",
    "Totally Unrelated Movie",
  ]);
  // Several forms of the query (a title known in more than one language): the best match of any wins.
  assert.deepEqual(sortBySimilarity(items, ["Ay", "Dragon Warrior Saga"], getTitle).map(getTitle), sorted);
  // No requested title carries any 3+ letter token: nothing to rank by, so the order is untouched.
  assert.deepEqual(sortBySimilarity(items, "Ay", getTitle).map(getTitle), items.map(getTitle));
  // getTitle defaults to `.title`.
  const titled = items.map((x) => ({ title: x.name }));
  assert.deepEqual(sortBySimilarity(titled, "Dragon Warrior Saga").map((x) => x.title), sorted);
});

test("kino.rank.filterRelevant drops hits that only share a stray word", () => {
  const items = [
    { name: "Saga of Something Else" }, // 1 of 3 tokens: 0.33, dropped
    { name: "Totally Unrelated Movie" }, // 0 of 3: dropped
    { name: "Warrior Saga Legends" }, // 2 of 3: 0.67, kept
    { name: "Dragon Warrior Saga: Special Edition" }, // 3 of 3: kept
  ];
  const getTitle = (x) => x.name;
  assert.deepEqual(
    filterRelevant(items, "Dragon Warrior Saga", getTitle).map(getTitle),
    ["Warrior Saga Legends", "Dragon Warrior Saga: Special Edition"],
  );
  // An absent title: 0 results, not a page of near-misses.
  assert.deepEqual(filterRelevant(items, "Completely Different Name", getTitle), []);
});

test("kino.rank: filterRelevant then sortBySimilarity leaves the real match first, the noise gone", () => {
  const items = [
    { name: "Saga of Something Else" },
    { name: "Totally Unrelated Movie" },
    { name: "Warrior Saga Legends" },
    { name: "Dragon Warrior Saga: Special Edition" },
  ];
  const getTitle = (x) => x.name;
  const result = sortBySimilarity(filterRelevant(items, "Dragon Warrior Saga", getTitle), "Dragon Warrior Saga", getTitle).map(getTitle);
  assert.deepEqual(result, ["Dragon Warrior Saga: Special Edition", "Warrior Saga Legends"]);
});

test("kino.rank: titleTokens folds accents and keeps a word whose only accent is ã or å", () => {
  // Regression coverage via the public functions: an earlier FOLD_ACCENTS with no ã/å entry fell
  // outside the word regex and dropped the whole word instead of just leaving an accent on it.
  const items = [{ title: "São Paulo em Chamas" }, { title: "Unrelated" }];
  assert.deepEqual(sortBySimilarity(items, "Sao Paulo").map((x) => x.title), ["São Paulo em Chamas", "Unrelated"]);
  assert.deepEqual(filterRelevant(items, "Sao Paulo").map((x) => x.title), ["São Paulo em Chamas"]);
});

// Robustness convention (see kino-rank.mjs's own header comment): a bad `items` argument never
// throws, and neither does a bad title on one entry -- only a coded error crossing a real boundary
// (kino.fetch, kino.crypto, kino.sleep) does that.
test("kino.rank: a non-array items answers [] instead of throwing", () => {
  for (const bad of [null, undefined, "not an array", 42, { title: "x" }]) {
    assert.deepEqual(sortBySimilarity(bad, "Dragon Warrior Saga"), []);
    assert.deepEqual(filterRelevant(bad, "Dragon Warrior Saga"), []);
  }
});

test("kino.rank: an item with no usable title is dropped by filterRelevant and sorts last in sortBySimilarity", () => {
  const real = { title: "Dragon Warrior Saga: Special Edition" };
  const noTitleAtAll = { note: "no title field" };
  const numericTitle = { title: 7 };
  const arrayOfJunk = { title: [1, 2, 3] };
  const items = [null, undefined, noTitleAtAll, numericTitle, arrayOfJunk, real];

  assert.deepEqual(filterRelevant(items, "Dragon Warrior Saga"), [real]);

  const sorted = sortBySimilarity(items, "Dragon Warrior Saga");
  // The one real match goes first; every title-less item follows, in its original relative order.
  assert.equal(sorted[0], real);
  assert.deepEqual(sorted.slice(1), [null, undefined, noTitleAtAll, numericTitle, arrayOfJunk]);
});

test("kino.rank: a getTitle that throws is treated as a missing title, not a crash", () => {
  const boom = () => { throw new Error("backend field is missing"); };
  const real = { title: "Dragon Warrior Saga: Special Edition" };
  const items = [{ broken: true }, real];

  assert.deepEqual(filterRelevant(items, "Dragon Warrior Saga", boom), []);
  assert.deepEqual(sortBySimilarity(items, "Dragon Warrior Saga", boom), items);
});

test("kino.rank: getTitle answering a non-string, or an array with none, is a missing title too", () => {
  const real = { name: "Dragon Warrior Saga: Special Edition" };
  const weird = { name: 123 };
  const mixedArray = { name: [123, null, "Dragon Warrior Saga: Special Edition"] };
  const getTitle = (x) => x.name;

  assert.deepEqual(filterRelevant([weird], "Dragon Warrior Saga", getTitle), []);
  // A form buried in an array of junk is still found and used.
  assert.deepEqual(filterRelevant([mixedArray], "Dragon Warrior Saga", getTitle), [mixedArray]);
  assert.deepEqual(sortBySimilarity([weird, real], "Dragon Warrior Saga", getTitle), [real, weird]);
});

test("kino.rank: the shim wires the exact same functions the runtime inlines", () => {
  const { kino } = createKino(JSON.parse(manifest()));
  // Same module, not a copy: kino-shim.mjs imports kino-rank.mjs directly.
  assert.equal(kino.rank.shortQuery, shortQuery);
  assert.equal(kino.rank.sortBySimilarity, sortBySimilarity);
  assert.equal(kino.rank.filterRelevant, filterRelevant);
});

// The app's prelude.js has no module loader to import kino-rank.mjs with, so it carries a literal
// copy of the algorithm instead (see both files' own comments). This is what keeps that copy honest.
// prelude.js lives only in the app repo, not in a published plugin repo: skip there.
test("kino.rank: the shim and the runtime run the exact same code", (t) => {
  const preludePath = join(here, "..", "..", "..", "app", "src", "main", "resources", "plugin", "prelude.js");
  if (!existsSync(preludePath)) { t.skip("no app repo around this kit"); return; }
  const BEGIN = "kino.rank shared core: BEGIN (byte-identical in kino-rank.mjs and prelude.js)";
  const END = "kino.rank shared core: END";
  const coreOf = (path) => {
    const text = readFileSync(path, "utf8");
    const beginIdx = text.indexOf(BEGIN);
    assert.notEqual(beginIdx, -1, `${path} is missing the BEGIN marker`);
    const contentStart = text.indexOf("\n", beginIdx) + 1;
    const endIdx = text.indexOf(END, contentStart);
    assert.notEqual(endIdx, -1, `${path} is missing the END marker`);
    const contentEnd = text.lastIndexOf("\n", endIdx) + 1;
    return text.slice(contentStart, contentEnd);
  };
  const shim = coreOf(join(here, "..", "kino-rank.mjs"));
  const prelude = coreOf(preludePath);
  assert.equal(prelude, shim);
});

// Same as the app: a url setting's manifest default is never a server the plugin may reach, even
// when a manifest skips validation and hands one to the shim directly.
test("a url setting's manifest default is ignored: only a typed server counts", async () => {
  const m = JSON.parse(manifest({ settings: [{ key: "server", label: "Servidor", type: "url", default: "http://192.168.1.1" }] }));
  const { kino } = createKino(m, { fetchImpl: () => { throw new Error("must not reach the network"); } });
  assert.equal(kino.config.get("server"), undefined);
  await assert.rejects(kino.fetch("http://192.168.1.1/"), (err) => err.code === "host_not_allowed");
});

function server(handler) {
  return new Promise((resolve) => {
    const s = createServer(handler).listen(0, "127.0.0.1", () => resolve(s));
  });
}

// The app never lets a typed server be loopback, and neither does the kit: tests type 10.0.2.2
// and this fetch delivers it to the local server.
const toLocal = (port) => (url, init) => fetch(String(url).replace("10.0.2.2:8096", `127.0.0.1:${port}`), init);
const typedServer = JSON.parse(manifest({ settings: [{ key: "server", label: "Servidor", type: "url", required: true }] }));

test("fetch v2: bodies, cookies, manual redirects, hidden set-cookie, binary, typed codes", async () => {
  const seen = [];
  const s = await server((req, res) => {
    let body = "";
    req.on("data", (d) => { body += d; });
    req.on("end", () => {
      seen.push({ url: req.url, method: req.method, type: req.headers["content-type"], cookie: req.headers.cookie, body });
      if (req.url === "/login") { res.writeHead(302, { Location: "https://evil.example/", "Set-Cookie": "sid=abc; Path=/" }); return res.end(); }
      if (req.url === "/bin") { res.writeHead(200, { "Content-Type": "application/octet-stream" }); return res.end(Buffer.from([1, 2, 3])); }
      res.writeHead(200, { "Content-Type": "application/json", "Set-Cookie": "t=2" });
      res.end('{"ok":true}');
    });
  });
  const { kino } = createKino(typedServer, { config: { server: "http://10.0.2.2:8096" }, fetchImpl: toLocal(s.address().port) });
  const at = (p) => "http://10.0.2.2:8096" + p;
  try {
    const login = await kino.fetch(at("/login"), { method: "POST", body: { form: { user: "ana maría", pass: "a&b" } }, redirect: "manual" });
    assert.equal(login.status, 302);
    assert.equal(login.headers.location, "https://evil.example/");
    assert.equal(login.headers["set-cookie"], undefined);
    assert.equal(kino.cookies.get(at("/"), "sid"), "abc");
    const j = await kino.fetch(at("/j"), { method: "PUT", body: { json: { q: 1 } } });
    assert.equal(j.json().ok, true);
    await kino.fetch(at("/nocookie"), { cookies: false });
    const bin = await kino.fetch(at("/bin"));
    assert.equal(bin.base64(), "AQID");
    assert.deepEqual(seen.map((r) => [r.url, r.cookie ?? null]), [["/login", null], ["/j", "sid=abc"], ["/nocookie", null], ["/bin", "sid=abc; t=2"]]);
    assert.equal(seen[0].body, "user=ana%20mar%C3%ADa&pass=a%26b");
    assert.equal(seen[0].type, "application/x-www-form-urlencoded");
    assert.equal(seen[1].body, '{"q":1}');
    await assert.rejects(kino.fetch("https://evil.example/"), (e) => e.code === "host_not_allowed");
    await assert.rejects(kino.fetch("http://example.com/"), (e) => e.code === "host_not_allowed");
    await assert.rejects(kino.fetch("http://10.0.2.2:9999/"), (e) => e.code === "host_not_allowed");
    await assert.rejects(kino.fetch("https://example.com/", { method: "TRACE" }), (e) => e.code === "invalid_request");
    await assert.rejects(kino.fetch("https://example.com/", { body: { weird: 1 }, method: "POST" }), (e) => e.code === "invalid_request");
    await assert.rejects(kino.fetch("https://example.com/", { method: "POST", body: "x".repeat(1100000) }), (e) => e.code === "too_large");
    assert.equal(seen.length, 4);
  } finally {
    s.close();
  }
});

test("record, then replay offline gives the same answer", async () => {
  const dir = mkdtempSync(join(tmpdir(), "kino-tape-"));
  const tape = join(dir, "tape.json");
  const s = await server((req, res) => { res.writeHead(200, { "Content-Type": "application/json", "Set-Cookie": "a=1" }); res.end('{"n":' + req.url.length + "}"); });
  const config = { server: "http://10.0.2.2:8096" };
  try {
    const rec = createKino(typedServer, { config, record: tape, fetchImpl: toLocal(s.address().port) });
    const live = await rec.kino.fetch("http://10.0.2.2:8096/abc", { method: "POST", body: { json: { q: 1 } } });
    rec.saveTape();
    assert.equal(live.json().n, 4);
  } finally {
    s.close();
  }
  const saved = JSON.parse(readFileSync(tape, "utf8"));
  assert.equal(saved.length, 1);
  assert.ok(saved.every((t) => t.headers.every(([k]) => !k.startsWith("set-cookie"))));
  const rep = createKino(typedServer, { config, replay: tape, fetchImpl: () => { throw new Error("replay must not touch the network"); } });
  const again = await rep.kino.fetch("http://10.0.2.2:8096/abc", { method: "POST", body: { json: { q: 1 } } });
  assert.equal(again.json().n, 4);
  await assert.rejects(rep.kino.fetch("http://10.0.2.2:8096/other"), (e) => e.code === "network");
  rmSync(dir, { recursive: true, force: true });
});

test("checkOutput drops what the app drops", () => {
  const m = JSON.parse(manifest());
  const r = checkOutput("search", { items: [{ id: "a", ref: "r", title: "A", kind: "movie" }, { id: "b", ref: "r", title: "B", kind: "movie", adult: true }], next: "2" }, { ...m, capabilities: ["search", "resolve"] });
  assert.deepEqual(r.value.items.map((i) => i.id), ["a"]);
  assert.equal(r.value.next, null);
  assert.ok(r.drops.some((d) => d.includes("browse")));
  assert.ok(r.drops.some((d) => d.includes("adult")));
  assert.throws(() => checkOutput("resolve", { url: "http://example.com/v.mp4" }, m), /https/);
  const lan = checkOutput("resolve", { url: "http://192.168.1.10:8096/v.mp4", expiresInSeconds: 10 }, m, ["http://192.168.1.10:8096/"]);
  assert.equal(lan.value.expiresInSeconds, 0);
});

test("checkOutput keeps a live item only for an apiVersion 2 plugin, and never its duration", () => {
  const items = [
    { id: "c1", ref: "ch-1", title: "Canal Uno", kind: "live", runtimeMinutes: 120 },
    { id: "m", ref: "r", title: "M", kind: "movie", runtimeMinutes: 90 },
  ];
  const v1 = checkOutput("search", items, { ...JSON.parse(manifest()), capabilities: ["search", "resolve"] });
  assert.deepEqual(v1.value.items.map((i) => i.id), ["m"]);
  assert.ok(v1.drops.some((d) => d.includes("c1") && d.includes("live")));
  const v2 = checkOutput("search", items, { ...JSON.parse(manifest({ apiVersion: 2 })), capabilities: ["search", "resolve"] });
  assert.deepEqual(v2.value.items.map((i) => [i.id, i.kind, i.runtimeMinutes]), [["c1", "live", 0], ["m", "movie", 90]]);
  const home = checkOutput("home", [{ id: "vivo", title: "En vivo", items }], { ...JSON.parse(manifest({ apiVersion: 2 })), capabilities: ["home", "resolve"] });
  assert.deepEqual(home.value[0].items.map((i) => i.kind), ["live", "movie"]);
  assert.deepEqual(contract.output.itemKinds, ["movie", "series", "live"]);
  assert.equal(contract.output.liveKindApiVersion, 2);
});

test("checkOutput validates a stream's audioTracks like its subtitles", () => {
  const m = JSON.parse(manifest());
  const r = checkOutput("resolve", {
    url: "https://example.com/v.mp4",
    audioTracks: [
      { lang: "en", url: "https://example.com/a-en.aac", label: "English" },
      { lang: "es", url: "https://evil.example/a-es.aac" },
    ],
  }, m);
  assert.deepEqual(r.value.audioTracks.map((a) => a.lang), ["en"]);
  const many = checkOutput("resolve", {
    url: "https://example.com/v.mp4",
    audioTracks: Array.from({ length: 10 }, (_, i) => ({ lang: "en", url: `https://example.com/a${i}.aac` })),
  }, m);
  assert.equal(many.value.audioTracks.length, contract.output.maxAudioTracks);
});

test("checkOutput keeps an audio track URL given twice once, the first wins, as the app does", () => {
  const m = JSON.parse(manifest());
  const r = checkOutput("resolve", {
    url: "https://example.com/v.mp4",
    audioTracks: [
      { lang: "es", url: "https://example.com/a.aac", label: "Latino" },
      { lang: "en", url: "https://example.com/a.aac" },
      { lang: "fr", url: "https://example.com/b.aac" },
    ],
  }, m);
  assert.deepEqual(r.value.audioTracks.map((a) => a.lang), ["es", "fr"]);
});

test("checkOutput accepts a widevine drm block only for a plugin that declares drm, and checks its license like the url", () => {
  const stream = {
    url: "https://example.com/v.mpd",
    drm: { type: "widevine", licenseUrl: "https://example.com/lic", licenseHeaders: { Authorization: "Bearer t", Host: "evil", "X-Bad": "a\nb" } },
  };
  const plain = { ...JSON.parse(manifest({ apiVersion: 2 })), capabilities: ["search", "resolve"] };
  assert.throws(() => checkOutput("resolve", stream, plain), /El video tiene DRM y los plugins no lo soportan/);
  assert.throws(() => checkOutput("resolve", stream, JSON.parse(manifest())), /El video tiene DRM y los plugins no lo soportan/);
  const withDrm = { ...plain, capabilities: ["search", "resolve", "drm"] };
  const r = checkOutput("resolve", stream, withDrm).value;
  assert.deepEqual(r.drm, { type: "widevine", licenseUrl: "https://example.com/lic", licenseHeaders: { Authorization: "Bearer t" } });
  assert.equal(checkOutput("resolve", { url: "https://example.com/v.mp4" }, withDrm).value.drm, null);
  const bad = (drm) => () => checkOutput("resolve", { url: "https://example.com/v.mpd", drm }, withDrm);
  assert.throws(bad({ type: "widevine", licenseUrl: "http://example.com/lic" }), /La licencia del video debe usar https/);
  assert.throws(bad({ type: "widevine", licenseUrl: "https://evil.example/lic" }), /La licencia del video apunta a evil.example, que el plugin no declaró/);
  assert.throws(bad({ type: "widevine" }), /La licencia del video tiene una dirección inválida/);
  assert.throws(bad({ type: "playready", licenseUrl: "https://example.com/lic" }), /El video usa un DRM que Kino no soporta/);
  assert.throws(bad("widevine"), /El DRM del video no es válido/);
  for (const k of ["license", "licenseUrl", "drmLicenseUrl", "keySystem", "widevine"]) {
    assert.throws(() => checkOutput("resolve", { url: "https://example.com/v.mpd", [k]: "x" }, withDrm), /El video tiene DRM/);
    assert.throws(() => checkOutput("resolve", { ...stream, [k]: "x" }, withDrm), /El video tiene DRM/);
  }
  assert.deepEqual(contract.output.drm, { field: "drm", types: ["widevine"] });
  assert.equal(contract.output.maxHeaders, 20);
  // A protected video may still bring side audio tracks: both are kept (the app plays the audio clear).
  const both = checkOutput("resolve", { ...stream, audioTracks: [{ lang: "es", url: "https://example.com/a.aac" }, { lang: "en", url: "https://evil.example/a.aac" }] }, withDrm).value;
  assert.equal(both.drm.licenseUrl, "https://example.com/lic");
  assert.deepEqual(both.audioTracks.map((a) => a.lang), ["es"]);
});

test("checkOutput lets a stream, its subtitles, audio and license use http only on a host declared insecureHttp", () => {
  const m = validateManifest(manifest({
    apiVersion: 2,
    hosts: ["api.example.com", { host: "cdn.example.com", insecureHttp: true }, "lic.example.com"],
    capabilities: ["search", "resolve", "drm"],
  })).manifest;
  const r = checkOutput("resolve", {
    url: "http://cdn.example.com/v.mpd",
    subtitles: [{ lang: "es", url: "http://cdn.example.com/s.vtt" }, { lang: "en", url: "http://api.example.com/s.vtt" }],
    audioTracks: [{ lang: "es", url: "http://cdn.example.com/a.aac" }, { lang: "en", url: "http://lic.example.com/a.aac" }],
    drm: { type: "widevine", licenseUrl: "http://cdn.example.com/lic" },
  }, m).value;
  assert.equal(r.url, "http://cdn.example.com/v.mpd");
  assert.deepEqual(r.subtitles.map((s) => s.lang), ["es"]);
  assert.deepEqual(r.audioTracks.map((a) => a.lang), ["es"]);
  assert.equal(r.drm.licenseUrl, "http://cdn.example.com/lic");
  // Every other declared host stays https-only, and https still works on the insecure one.
  assert.throws(() => checkOutput("resolve", { url: "http://api.example.com/v.mp4" }, m), /El video debe usar https/);
  assert.throws(() => checkOutput("resolve", { url: "http://sub.cdn.example.com/v.mp4" }, m), /El video debe usar https/);
  assert.throws(() => checkOutput("resolve", { url: "https://cdn.example.com/v.mp4", drm: { type: "widevine", licenseUrl: "http://lic.example.com/l" } }, m), /La licencia del video debe usar https/);
  assert.equal(checkOutput("resolve", { url: "https://cdn.example.com/v.mp4" }, m).value.url, "https://cdn.example.com/v.mp4");
  // A v1 manifest (never an insecure host) is unchanged: http is refused on every declared host.
  assert.throws(() => checkOutput("resolve", { url: "http://example.com/v.mp4" }, validateManifest(manifest()).manifest), /El video debe usar https/);
});

test("kino.fetch reaches a host declared insecureHttp over http, and no other declared host", async () => {
  const s = await server((req, res) => { res.writeHead(200, { "Content-Type": "text/plain" }); res.end("hola " + req.url); });
  const m = validateManifest(manifest({ apiVersion: 2, hosts: ["api.example.com", { host: "cdn.example.com", insecureHttp: true }] })).manifest;
  const port = s.address().port;
  const local = (url, init) => fetch(String(url).replace(/^http:\/\/[^/]+/, `http://127.0.0.1:${port}`), init);
  let touched = 0;
  const { kino } = createKino(m, { fetchImpl: (url, init) => { touched++; return local(url, init); } });
  try {
    const r = await kino.fetch("http://cdn.example.com/x");
    assert.equal(r.status, 200);
    assert.equal(r.text(), "hola /x");
    assert.equal(r.url, "http://cdn.example.com/x");
    await assert.rejects(kino.fetch("http://api.example.com/x"), (e) => e.code === "host_not_allowed" && /https/.test(e.message));
    await assert.rejects(kino.fetch("http://sub.cdn.example.com/x"), (e) => e.code === "host_not_allowed");
    assert.equal(touched, 1);
    // A v1 plugin never has an insecure host: http on its declared host is refused as always.
    const v1 = createKino(JSON.parse(manifest()), { fetchImpl: () => { throw new Error("must not reach the network"); } }).kino;
    await assert.rejects(v1.fetch("http://example.com/"), (e) => e.code === "host_not_allowed");
  } finally {
    s.close();
  }
});

test("checkOutput keeps an episode's still, overview and runtimeMinutes as the app does", () => {
  const m = JSON.parse(manifest({ capabilities: ["search", "episodes", "resolve"] }));
  const r = checkOutput("episodes", {
    episodes: [
      { number: 1, ref: "e1", title: "Uno", still: "https://example.com/e1.png", overview: "  Primero  ", runtimeMinutes: 42 },
      { number: 2, ref: "e2", still: "http://example.com/e2.png", runtimeMinutes: 0 },
      { number: 3, ref: "e3", still: "https://192.168.1.5/e3.png", runtimeMinutes: 99999 },
    ],
  }, m);
  const [one, two, three] = r.value.episodes;
  assert.equal(one.still, "https://example.com/e1.png");
  assert.equal(one.overview, "Primero");
  assert.equal(one.runtimeMinutes, 42);
  // The image rule of an item's poster: https only, never the home network; a bad length is 0.
  assert.equal(two.still, "");
  assert.equal(two.runtimeMinutes, 0);
  assert.equal(three.still, "");
  assert.equal(three.runtimeMinutes, 0);
  // The person's own server (a url setting) may serve the still, over http too.
  const own = checkOutput("episodes", { episodes: [{ number: 1, ref: "e1", still: "http://192.168.1.5:8096/e1.png" }] }, m, ["http://192.168.1.5:8096"]);
  assert.equal(own.value.episodes[0].still, "http://192.168.1.5:8096/e1.png");
});

test("checkOutput reads an episodes answer's sibling seasons as the app does", () => {
  const m = JSON.parse(manifest({ capabilities: ["search", "episodes", "resolve"] }));
  const none = checkOutput("episodes", { episodes: [{ number: 1, ref: "e1" }] }, m);
  assert.deepEqual(none.value.seasons, []);
  const r = checkOutput("episodes", {
    episodes: [{ number: 1, ref: "e1" }],
    seasons: [
      { id: "s1", ref: "S1", title: "Temporada 1", number: 1 },
      { id: "s2", ref: "S2", title: "Temporada 2", number: 2, current: true },
      { id: "s2", ref: "S2b", title: "Repetida" },
      { id: "bad id!", ref: "S3", title: "T" },
      { id: "s4", ref: "", title: "T" },
      { id: "s5", ref: "S5", title: "  " },
      { id: "s6", ref: "S6", title: "Sin número", number: 1000, current: "yes" },
    ],
  }, m);
  assert.deepEqual(r.value.seasons, [
    { id: "s1", ref: "S1", title: "Temporada 1", number: 1, current: false },
    { id: "s2", ref: "S2", title: "Temporada 2", number: 2, current: true },
    { id: "s6", ref: "S6", title: "Sin número", number: 0, current: false },
  ]);
  assert.equal(r.drops.length, 4);
  const many = { episodes: [], seasons: Array.from({ length: 60 }, (_, i) => ({ id: `s${i}`, ref: `S${i}`, title: `T${i}` })) };
  assert.equal(checkOutput("episodes", many, m).value.seasons.length, contract.output.maxSeasons);
  assert.deepEqual(checkOutput("episodes", { episodes: [], seasons: "T1, T2" }, m).value.seasons, []);
});

test("run.mjs's call() gives a clear message for a malformed search argument, not a bare JSON error", async () => {
  await assert.rejects(
    call({ search: () => {} }, "search", ['{"q": bad json']),
    (e) => e instanceof Error && !(e instanceof SyntaxError) && /search argument/.test(e.message) && /not valid JSON/.test(e.message),
  );
});

test("init scaffolds a plugin the kit accepts, and never overwrites", async () => {
  const dir = mkdtempSync(join(tmpdir(), "kino-init-"));
  const target = join(dir, "mi-plugin");
  const written = scaffold(target, { name: "Mi plugin", host: "example.org" });
  assert.deepEqual(written.sort(), ["README.md", "kino-plugin.json", "plugin.js", "test/plugin.test.mjs"]);
  const r = await validate(target);
  assert.deepEqual(r.problems, []);
  writeFileSync(join(target, "plugin.js"), "// mine");
  assert.deepEqual(scaffold(target, {}), []);
  assert.equal(readFileSync(join(target, "plugin.js"), "utf8"), "// mine");
  rmSync(dir, { recursive: true, force: true });
});

// This plan's own recorded trap: `node --test` on a bare directory argument fails on Node 24 (it
// needs the explicit file). The scaffolded README must not tell an author to hit it.
test("the scaffolded README points at the explicit test file, never a bare test/ directory", () => {
  const dir = mkdtempSync(join(tmpdir(), "kino-init-readme-"));
  try {
    scaffold(dir, {});
    const readme = readFileSync(join(dir, "README.md"), "utf8");
    assert.ok(readme.includes("node --test test/plugin.test.mjs"), "README should point at the explicit test file");
    assert.doesNotMatch(readme, /node --test test\/\s/, "README must not tell authors to run node --test on a bare directory");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

function dtsPath() {
  const candidates = [join(here, "..", "..", "kino.d.ts"), join(here, "..", "..", "..", "docs", "plugins", "kino.d.ts")];
  return candidates.find((p) => { try { readFileSync(p); return true; } catch { return false; } });
}

// kino.d.ts's header claims its numeric comments "come from contract.json"; nothing enforced that
// claim until now. This ties each documented number to the contract value it describes, so the two
// can't silently drift apart.
test("kino.d.ts's documented numbers match contract.json", () => {
  const dts = readFileSync(dtsPath(), "utf8");
  const c = contract;
  const kb = (bytes) => (bytes % (1024 * 1024) === 0 ? `${bytes / 1024 / 1024} MB` : `${bytes / 1024} KB`);
  const mustContain = [
    `at most ${c.output.maxRefChars} characters`,
    `https, at most ${c.output.maxImageUrlChars} characters`,
    `At most ${c.output.maxGenres}, each at most ${c.output.maxGenreChars} characters`,
    `${c.output.minRuntimeMinutes}..${c.output.maxRuntimeMinutes}`,
    `At most ${c.output.maxBadges}, each at most ${c.output.maxBadgeChars} characters`,
    `at most ${c.output.maxCursorChars} characters`,
    `1..${c.output.maxSeasonNumber}, default 1.`,
    `1..${c.output.maxEpisodeNumber}`,
    `${c.output.minExpiresInSeconds}..${c.output.maxExpiresInSeconds}:`,
    `at most ${c.fetch.maxRequestChars.toLocaleString("en-US")} characters`,
    `at most ${c.fetch.maxRedirects} hops`,
    `Default ${c.fetch.defaultTimeoutMs}, at most ${c.fetch.maxTimeoutMs}.`,
    `at most ${kb(c.fetch.maxBodyBytes)}`,
    `at most ${c.errors.maxMessageChars} characters`,
    `0..${c.sleep.maxMs} ms`,
    `${c.storage.maxTotalBytes / 1024} KB in total`,
    `at most ${c.storage.maxTtlMs.toLocaleString("en-US")} ms (30 days)`,
    `Data at most ${kb(c.crypto.maxDataBytes)}`,
    `iterations at most ${c.crypto.pbkdf2MaxIterations}, keyLength at most ${c.crypto.pbkdf2MaxKeyBytes} bytes`,
    `1..${c.crypto.randomMaxBytes} bytes`,
    `at most ${c.search.maxAltTitles}, each at most ${c.search.maxAltTitleChars} characters`,
    c.output.itemIdPattern,
    c.output.imdbPattern,
    c.search.types.map((t) => `"${t}"`).join(" | "),
  ];
  for (const needle of mustContain) assert.ok(dts.includes(needle), `kino.d.ts is out of date with contract.json: missing "${needle}"`);
});

function declaredKino() {
  const lines = readFileSync(dtsPath(), "utf8").split("\n");
  const out = new Set();
  const path = [];
  let depth = 0;
  for (const raw of lines.slice(lines.findIndex((l) => l.startsWith("declare namespace kino")))) {
    const line = raw.trim();
    const ns = /^(?:declare )?namespace (\w+) \{$/.exec(line);
    if (ns) { path.push(ns[1]); depth++; continue; }
    const fn = /^function (\w+)\(/.exec(line);
    if (fn) out.add([...path, fn[1]].join(".") + "=function");
    const cst = /^const (\w+):/.exec(line);
    if (cst) out.add([...path, cst[1]].join(".") + "=value");
    if (line === "}") { path.pop(); depth--; if (depth === 0) break; }
  }
  return out;
}

test("kino.d.ts declares exactly what the kit's kino has", () => {
  const { kino } = createKino(JSON.parse(manifest()));
  const out = new Set();
  const walk = (o, p) => Object.keys(o).forEach((k) => {
    const v = o[k];
    if (typeof v === "function") out.add(`${p}.${k}=function`);
    else if (v !== null && typeof v === "object") walk(v, `${p}.${k}`);
    else out.add(`${p}.${k}=value`);
  });
  walk(kino, "kino");
  assert.deepEqual([...out].sort(), [...declaredKino()].sort());
});

test("apiVersion 3: channels validates only on v3, and needs liveCategories + liveChannels exported", async () => {
  const caps = ["home", "resolve", "channels"];
  assert.deepEqual(validateManifest(manifest({ capabilities: caps })), { ok: false, field: "capabilities", message: "Esta capacidad necesita apiVersion 3" });
  assert.deepEqual(validateManifest(manifest({ apiVersion: 2, capabilities: caps })), { ok: false, field: "capabilities", message: "Esta capacidad necesita apiVersion 3" });
  assert.deepEqual(validateManifest(manifest({ capabilities: ["search", "resolve", "download"] })), { ok: false, field: "capabilities", message: "Esta capacidad necesita apiVersion 2" });
  assert.equal(validateManifest(manifest({ apiVersion: 3, capabilities: caps })).ok, true);
  const dir = mkdtempSync(join(tmpdir(), "kino-channels-"));
  try {
    writeFileSync(join(dir, "kino-plugin.json"), manifest({ apiVersion: 3, capabilities: caps }));
    writeFileSync(join(dir, "plugin.js"), "export async function home(){ return [] }\nexport async function resolve(){ return { url: 'https://example.com/a.m3u8' } }");
    const missing = await validate(dir);
    assert.ok(missing.problems.some((p) => p.includes("liveCategories, liveChannels")));
    writeFileSync(join(dir, "plugin.js"), "export async function home(){ return [] }\nexport async function resolve(){ return { url: 'https://example.com/a.m3u8' } }\nexport async function liveCategories(){ return [] }\nexport async function liveChannels(){ return { items: [] } }");
    assert.deepEqual((await validate(dir)).problems, []);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("checkOutput reads liveCategories, liveChannels and guide as the app does", () => {
  const m = { ...JSON.parse(manifest({ apiVersion: 3 })), capabilities: ["home", "resolve", "channels"] };
  const cats = checkOutput("liveCategories", [{ id: "news", title: "Noticias", country: "co" }, { id: "news", title: "x" }, { id: "a", title: "A", adult: true }], m);
  assert.deepEqual(cats.value.categories, [{ id: "news", title: "Noticias", country: "CO" }]);
  const page = checkOutput("liveChannels", { items: [
    { id: "c1", title: "Uno", ref: "r1", number: 7, categoryId: "news" },
    { id: "c2", title: "Dos", ref: "" },
    { id: "c3", title: "Tres", ref: "r3", number: 10000 },
  ], next: "p2" }, m);
  assert.deepEqual(page.value.items.map((c) => [c.id, c.number]), [["c1", 7], ["c3", 0]]);
  assert.equal(page.value.next, "p2");
  const g = checkOutput("guide", [
    { channelId: "c1", title: "B", start: 2000, end: 3000 },
    { channelId: "c1", title: "A", start: 1000, end: 2000, description: "d" },
    { channelId: "c1", title: "Mal", start: 5, end: 4 },
  ], m);
  assert.deepEqual(g.value.map((e) => e.title), ["A", "B"]);
  assert.equal(contract.live.maxChannelsPerPage, 500);
});

test("checkOutput reads inline streams and playlist declarations as the app does", () => {
  const m = { ...JSON.parse(manifest({ apiVersion: 3, hosts: ["cdn.example.com"] })), capabilities: ["home", "resolve", "channels"] };
  const page = checkOutput("liveChannels", { items: [
    { id: "a", title: "A", stream: { url: "https://cdn.example.com/a.m3u8" } },
    { id: "b", title: "B", stream: { url: "https://evil.example.org/b.m3u8" } },
    { id: "~c", title: "C", ref: "r" },
  ] }, m);
  assert.deepEqual(page.value.items.map((c) => c.id), ["a"]);
  assert.equal(page.value.items[0].ref, "");
  assert.equal(page.value.items[0].stream.url, "https://cdn.example.com/a.m3u8");
  const cats = checkOutput("liveCategories", [
    { id: "news", title: "Noticias" },
    { playlist: { url: "https://cdn.example.com/l.m3u", format: "m3u", epg: { url: "https://cdn.example.com/g.xml", format: "xmltv" } } },
    { playlist: { url: "https://evil.example.org/l.m3u", format: "m3u" } },
  ], m);
  assert.deepEqual(cats.value.categories.map((c) => c.id), ["news"]);
  assert.deepEqual(cats.value.playlists.map((p) => [p.url, p.epgUrl, p.refreshHours]), [["https://cdn.example.com/l.m3u", "https://cdn.example.com/g.xml", 12]]);
  // Strict like the app: only the boolean true, never the string "true"; an array epg is ignored.
  const stringy = checkOutput("liveCategories", { playlist: { url: "https://cdn.example.com/l.m3u", format: "m3u", resolve: "true", epg: [] } }, m);
  assert.deepEqual(stringy.value.playlists.map((p) => [p.resolve, p.epgUrl]), [[false, ""]]);
});

test("run.mjs builds the live arguments the app sends", async () => {
  const seen = [];
  const plugin = {
    liveCategories: async (a) => { seen.push(["liveCategories", a]); return []; },
    liveChannels: async (a) => { seen.push(["liveChannels", a]); return { items: [] }; },
    guide: async (a) => { seen.push(["guide", a.channelIds, a.to - a.from]); return []; },
  };
  await call(plugin, "liveCategories", []);
  await call(plugin, "liveChannels", ["news", "p2"]);
  await call(plugin, "guide", ["c1,c2"]);
  assert.deepEqual(seen, [
    ["liveCategories", null],
    ["liveChannels", { categoryId: "news", cursor: "p2" }],
    ["guide", ["c1", "c2"], 24 * 3600 * 1000],
  ]);
});

test("liveStreamHosts any is read only on v3, and needs channels there", () => {
  const caps = ["home", "resolve", "channels"];
  assert.equal(validateManifest(manifest({ apiVersion: 3, capabilities: caps, liveStreamHosts: "any" })).manifest.liveStreamHostsAny, true);
  // v1/v2 ignore it like any unknown field: never refused, never honoured.
  for (const apiVersion of [1, 2]) for (const v of ["any", "x", true]) {
    assert.equal(validateManifest(manifest({ apiVersion, liveStreamHosts: v })).manifest.liveStreamHostsAny, false);
  }
  assert.deepEqual(validateManifest(manifest({ apiVersion: 3, liveStreamHosts: "any" })), { ok: false, field: "liveStreamHosts", message: '"liveStreamHosts" necesita la capacidad "channels"' });
  assert.deepEqual(validateManifest(manifest({ apiVersion: 3, capabilities: caps, liveStreamHosts: "x" })), { ok: false, field: "liveStreamHosts", message: 'El campo "liveStreamHosts" solo admite "any"' });
  assert.equal(validateManifest(manifest({ apiVersion: 3, capabilities: caps })).manifest.liveStreamHostsAny, false);
  assert.deepEqual(contract.manifest.liveStreamHosts, { value: "any", apiVersion: 3, requires: "channels" });
});

test("discoverable: an optional boolean at every apiVersion; false is a note, not a problem", async () => {
  assert.equal(validateManifest(manifest()).manifest.discoverable, true);
  for (const apiVersion of [1, 2, 3]) {
    assert.equal(validateManifest(manifest({ apiVersion, discoverable: false })).manifest.discoverable, false);
  }
  for (const value of ["no", 0, null, []]) {
    assert.deepEqual(validateManifest(manifest({ discoverable: value })), { ok: false, field: "discoverable", message: 'El campo "discoverable" debe ser true o false' });
  }
  assert.deepEqual(contract.discovery, { topic: "kino-plugin", maxResults: 30 });
  assert.deepEqual(contract.manifest.discoverable, { default: true });
  const dir = mkdtempSync(join(tmpdir(), "kino-discoverable-"));
  try {
    writeFileSync(join(dir, "plugin.js"), "export async function search(){ return { items: [] } }\nexport async function resolve(){ return { url: 'https://example.com/a.m3u8' } }");
    writeFileSync(join(dir, "kino-plugin.json"), manifest());
    assert.deepEqual((await validate(dir)).notes, []);
    writeFileSync(join(dir, "kino-plugin.json"), manifest({ discoverable: false }));
    const r = await validate(dir);
    assert.equal(r.ok, true);
    assert.deepEqual(r.notes, ["No aparecerá en la búsqueda de Kino"]);
    const cli = spawnSync(process.execPath, [join(here, "..", "validate.mjs"), dir], { encoding: "utf8" });
    assert.equal(cli.status, 0);
    assert.match(cli.stderr, /No aparecerá en la búsqueda de Kino/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ---------- live channels: the kit's own M3U/XMLTV readers (apiVersion 3) ----------

// The shared corpus the app's JVM tests read too. It lives only in Kino's repo
// (docs/plugins/fixtures/live); a published plugin repo (sdk/ and contract.json at its root) doesn't
// ship it, so these tests skip there.
const fixturesDir = join(here, "..", "..", "..", "docs", "plugins", "fixtures", "live");
const fixtures = existsSync(fixturesDir) ? fixturesDir : null;
const needFixtures = (t) => { if (!fixtures) t.skip("no shared live fixtures around this kit"); return !!fixtures; };
const M3U_FIXTURES = ["basic", "bom-crlf", "latin1", "broken", "headers", "unterminated-quote", "huge-line"];
// Whole-playlist fixtures: their .expected.json holds the manifest, the typed servers, the
// declaration and the grouped counts, checked here through loadPlaylist and in the app's
// PluginLivePlaylistTest through PluginLiveProvider.
const PLAYLIST_FIXTURES = ["any-hosts"];

test("the kit's M3U reader gives the app's exact answer on every shared fixture", (t) => {
  if (!needFixtures(t)) return;
  for (const name of M3U_FIXTURES) {
    const got = parseM3u(readFileSync(join(fixtures, `${name}.m3u`)));
    const want = JSON.parse(readFileSync(join(fixtures, `${name}.expected.json`), "utf8"));
    assert.deepEqual(got, want, name);
  }
});

test("every .m3u in the shared corpus has its .expected.json and is checked here", (t) => {
  if (!needFixtures(t)) return;
  const m3u = readdirSync(fixtures).filter((f) => f.endsWith(".m3u")).map((f) => f.slice(0, -4)).sort();
  assert.deepEqual(m3u, [...M3U_FIXTURES, ...PLAYLIST_FIXTURES].sort());
  for (const name of m3u) assert.ok(existsSync(join(fixtures, `${name}.expected.json`)), name);
});

test("loadPlaylist keeps, skips and hides exactly what the app does (README live recipe 1)", async (t) => {
  if (!needFixtures(t)) return;
  for (const name of PLAYLIST_FIXTURES) {
    const want = JSON.parse(readFileSync(join(fixtures, `${name}.expected.json`), "utf8"));
    const m = validateManifest(manifest({ apiVersion: 3, capabilities: ["home", "resolve", "channels"], ...want.manifest }));
    assert.equal(m.ok, true, name);
    const bytes = readFileSync(join(fixtures, `${name}.m3u`));
    const fetchImpl = async (url) => (String(url) === want.playlist.url ? new Response(bytes, { status: 200 }) : new Response("no", { status: 404 }));
    // Read as the app reads the liveCategories answer (strict hosts, hideGroups normalised) first.
    const declared = checkOutput("liveCategories", [{ playlist: want.playlist }], m.manifest, want.servers).value.playlists;
    assert.equal(declared.length, 1, name);
    const s = await loadPlaylist(declared[0], { manifest: m.manifest, servers: want.servers, fetchImpl });
    assert.deepEqual([s.channels, s.skipped, s.hidden], [want.kept, want.skipped, want.hidden], name);
    assert.deepEqual(s.entries.map((e) => e.name), want.channels, name);
  }
});

test("the kit's M3U reader keeps the cap and counts the rest", () => {
  const text = "#EXTM3U\n" + Array.from({ length: 6000 }, (_, i) => `#EXTINF:-1,C${i}\nhttps://x.example.com/${i}.m3u8\n`).join("");
  const r = parseM3u(text, { maxEntries: 5000 });
  assert.equal(r.entries.length, 5000);
  assert.equal(r.total, 6000);
  assert.equal(r.entries[4999].name, "C4999");
  assert.equal(parseM3u(text).entries.length, contract.live.maxChannelsPerProvider);
});

test("the kit's M3U reader drops hidden and refused entries during the parse, never spending the cap", () => {
  const text = "#EXTM3U\n"
    + Array.from({ length: 10 }, (_, i) => `#EXTINF:-1 group-title="XXX",A${i}\nhttps://live.example.com/a${i}.m3u8\n`).join("")
    + Array.from({ length: 4 }, (_, i) => `#EXTINF:-1,E${i}\nhttps://evil.example.org/${i}.m3u8\n`).join("")
    + Array.from({ length: 5 }, (_, i) => `#EXTINF:-1,C${i}\nhttps://live.example.com/${i}.m3u8\n`).join("");
  const r = parseM3u(text, { maxEntries: 3, hide: (e) => e.group === "XXX", allow: (url) => !url.includes("evil") });
  assert.deepEqual(r.entries.map((e) => e.name), ["C0", "C1", "C2"]);
  assert.deepEqual([r.total, r.hidden, r.refused], [5, 10, 4]);
  assert.deepEqual(parseM3u(""), { entries: [], total: 0, skipped: 0 });
});

test("the kit's XMLTV reader gives the app's answer on every shared guide, plain and gzip, and refuses a DOCTYPE", (t) => {
  if (!needFixtures(t)) return;
  const iso = (g) => Object.fromEntries(Object.entries(g.programmes).map(([k, l]) =>
    [k, l.map((p) => ({ title: p.title, start: new Date(p.start).toISOString().replace(".000", ""), end: new Date(p.end).toISOString().replace(".000", ""), description: p.description }))]));
  const cases = [["guide.xml", "guide"], ["guide.xml.gz", "guide"], ["guide-latin1.xml", "guide-latin1"], ["guide-windows1252.xml", "guide-windows1252"], ["guide-xxe.xml", "guide-xxe"], ["guide-doctype-comment.xml", "guide-doctype-comment"]];
  for (const [file, expected] of cases) {
    const want = JSON.parse(readFileSync(join(fixtures, `${expected}.expected.json`), "utf8"));
    // The window and the wanted ids come from the fixture's own "query", as in the app's test.
    const q = want.query;
    const opts = { from: Date.parse(q.from), to: Date.parse(q.to), wantedIds: q.wantedIds ? new Set(q.wantedIds) : null };
    const g = parseXmltv(readFileSync(join(fixtures, file)), opts);
    assert.deepEqual(g.displayNames, want.displayNames, file);
    assert.deepEqual(iso(g), want.programmes, file);
    assert.equal(g.truncated, want.truncated, file);
  }
  const opts = { from: Date.parse("2026-09-27T00:00:00Z"), to: Date.parse("2026-09-28T00:00:00Z"), wantedIds: null };
  assert.deepEqual(parseXmltv(readFileSync(join(fixtures, "guide-xxe.xml")), opts).programmes, {});
  assert.equal(parseXmltv(readFileSync(join(fixtures, "guide-latin1.xml")), opts).programmes.n[0].title, "Niñez");
  // A channel wanted by its display name, accents and case folded.
  const byName = parseXmltv(readFileSync(join(fixtures, "guide.xml")), { ...opts, wantedIds: new Set(), wantedNames: new Set([normaliseName("CANAL UNO")]) });
  assert.deepEqual(Object.keys(byName.programmes), ["canal1.co"]);
  // A gzip cut in half is read as far as it goes, and says so.
  const gz = readFileSync(join(fixtures, "guide.xml.gz"));
  assert.equal(parseXmltv(gz.subarray(0, gz.length >> 1), opts).truncated, true);
  const corrupt = Buffer.from(gz); corrupt[2] = 1;
  assert.deepEqual(parseXmltv(corrupt, opts).programmes, {});
});

test("the kit's XMLTV reader: every DOCTYPE refused, caps, times and a broken tail as the app", () => {
  const from = Date.parse("2026-09-27T00:00:00Z");
  const to = Date.parse("2026-09-28T00:00:00Z");
  const prog = (ch, start, stop, title) => `<programme start="${start}" stop="${stop}" channel="${ch}"><title>${title}</title></programme>`;
  const padded = `<?xml version="1.0"?><!-- ${"x".repeat(8300)} --><!DOCTYPE tv [<!ENTITY s "LEAKED">]><tv>${prog("x", "20260927120000 +0000", "20260927130000 +0000", "&s;")}</tv>`;
  assert.deepEqual(parseXmltv(Buffer.from(padded), { from, to }), { displayNames: {}, programmes: {}, truncated: false, refused: true });
  const utf16 = Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from(`<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE tv SYSTEM "http://192.0.2.1/x.dtd"><tv>${prog("x", "20260927120000", "20260927130000", "Hi")}</tv>`, "utf16le")]);
  assert.deepEqual(parseXmltv(utf16, { from, to }).programmes, {});
  // The earliest per channel survive the cap, whatever the document order.
  const outOfOrder = `<tv>${[14, 13, 12, 11, 10].map((h) => prog("c", `20260927${h}0000 +0000`, `20260927${h}0100 +0000`, `P${h - 10}`)).join("")}</tv>`;
  assert.deepEqual(parseXmltv(Buffer.from(outOfOrder), { from, to, maxPerChannel: 3 }).programmes.c.map((p) => p.title), ["P0", "P1", "P2"]);
  // At most 5000 distinct channels when everything is wanted.
  const many = `<tv>${Array.from({ length: 5005 }, (_, i) => prog(`c${i}`, "20260927120000 +0000", "20260927130000 +0000", "P")).join("")}</tv>`;
  assert.equal(Object.keys(parseXmltv(Buffer.from(many), { from, to }).programmes).length, 5000);
  // A broken tail keeps what parsed, and says so.
  const broken = parseXmltv(Buffer.from(`<tv>${prog("c", "20260927120000", "20260927130000", "Uno")}<programme`), { from, to });
  assert.deepEqual([broken.programmes.c.map((p) => p.title), broken.truncated], [["Uno"], true]);
  // An unknown declared charset falls back to ISO-8859-1.
  const bogus = Buffer.from(`<?xml version="1.0" encoding="totally-bogus"?><tv>${prog("n", "20260927120000 +0000", "20260927130000 +0000", "Niñez")}</tv>`, "latin1");
  assert.equal(parseXmltv(bogus, { from, to }).programmes.n[0].title, "Niñez");
  // The byte cap: a gzip bomb is cut and reported.
  const body = `<tv>${Array.from({ length: 20000 }, (_, i) => prog(`c${i % 4000}`, "20260927120000 +0000", "20260927130000 +0000", "P")).join("")}</tv>`;
  const capped = parseXmltv(gzipSync(Buffer.from(body)), { from, to, maxBytes: 200_000 });
  assert.equal(capped.truncated, true);
  assert.ok(Object.keys(capped.programmes).length > 0 && Object.keys(capped.programmes).length < 4000);
  assert.equal(parseXmltvTime("20260927120000 -0500"), Date.parse("2026-09-27T17:00:00Z"));
  assert.equal(parseXmltvTime("202609271230 +0000"), Date.parse("2026-09-27T12:30:00Z"));
  assert.equal(parseXmltvTime("basura"), null);
  assert.equal(parseXmltvTime("20261399000000"), null);
});

test("run.mjs live playlist reports channels, groups and skipped entries", (t) => {
  if (!needFixtures(t)) return;
  const out = execFileSync(process.execPath, [join(here, "..", "run.mjs"), "live", "playlist", join(fixtures, "broken.m3u")], { encoding: "utf8" });
  assert.match(out, /3 canales/);
  assert.match(out, /5 entradas descartadas/);
  assert.match(out, /A › Bueno {2}https:\/\/live\.example\.com\/ok\.m3u8/);
});

test("run.mjs live playlist --epg shows what is on now, or sin guía", () => {
  const dir = mkdtempSync(join(tmpdir(), "kino-live-"));
  try {
    const now = Date.now();
    const stamp = (ms) => new Date(ms).toISOString().replace(/[-:T]/g, "").slice(0, 14) + " +0000";
    writeFileSync(join(dir, "l.m3u"), `#EXTM3U\n#EXTINF:-1 tvg-id="uno" group-title="Noticias",Canal Uno\nhttps://live.example.com/1.m3u8\n#EXTINF:-1 group-title="Adultos",Oculto\nhttps://live.example.com/x.m3u8\n#EXTINF:-1 group-title="Cine",Canal Dos\nhttps://live.example.com/2.m3u8\n`);
    writeFileSync(join(dir, "g.xml"), `<tv><channel id="uno"><display-name>Canal Uno</display-name></channel><programme start="${stamp(now - 600000)}" stop="${stamp(now + 600000)}" channel="uno"><title>Al aire</title></programme></tv>`);
    const out = execFileSync(process.execPath, [join(here, "..", "run.mjs"), "live", "playlist", join(dir, "l.m3u"), "--epg", join(dir, "g.xml")], { encoding: "utf8" });
    assert.match(out, /2 canales en 2 categorías; 0 entradas descartadas; 1 ocultas \(adultos\)/);
    assert.match(out, /Noticias › Canal Uno .*\n\s+ahora: Al aire/);
    assert.match(out, /Cine › Canal Dos .*\n\s+sin guía/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("summarisePlaylist groups as the app: adult and hideGroups hidden, blank group, a repeated tvg-id noticed", () => {
  const text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" group-title=\"XXX\",X\nhttps://c.example.com/x\n#EXTINF:-1 tvg-id=\"a\" group-title=\"Compras\",Y\nhttps://c.example.com/y\n#EXTINF:-1 tvg-id=\"d\",Z\nhttps://c.example.com/z\n#EXTINF:-1 tvg-id=\"d\",W\nhttps://c.example.com/w\n#EXTINF:-1,W\nhttps://c.example.com/w\n";
  const s = summarisePlaylist(text, { hideGroups: ["compras"] });
  assert.deepEqual([s.channels, s.hidden, s.skipped], [2, 2, 1]);
  assert.deepEqual(s.categories.map((c) => [c.title, c.count]), [["Sin categoría", 2]]);
  assert.deepEqual(s.duplicateTvgIds, ["d"]);
  assert.ok(ADULT_GROUPS.includes("xxx"));
});

test("summarisePlaylist keys a repeated tvg-id's first entry by the tvg-id, like the app's channel code", () => {
  // The first "d" keeps its tvg-id code, so the id-less copy of its url and name is a channel of its own;
  // the later "d" gets a url-and-name code, and the very same url and name again is the copy skipped.
  const text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"d\",Z\nhttps://c.example.com/z\n#EXTINF:-1 tvg-id=\"d\",W\nhttps://c.example.com/w\n#EXTINF:-1,Z\nhttps://c.example.com/z\n#EXTINF:-1,W\nhttps://c.example.com/w\n";
  const s = summarisePlaylist(text);
  assert.deepEqual([s.channels, s.skipped], [3, 1]);
  assert.deepEqual(s.duplicateTvgIds, ["d"]);
});

test("validate shows the consent lines, the channels line and liveStreamHosts any in red", async () => {
  const dir = mkdtempSync(join(tmpdir(), "kino-consent-"));
  try {
    writeFileSync(join(dir, "kino-plugin.json"), manifest({ apiVersion: 3, capabilities: ["home", "resolve", "channels"], liveStreamHosts: "any" }));
    writeFileSync(join(dir, "plugin.js"), "export async function home(){ return [] }\nexport async function resolve(){ throw kino.error('not_found') }\nexport async function liveCategories(){ return [] }\nexport async function liveChannels(){ return { items: [] } }");
    const r = await validate(dir);
    assert.deepEqual(r.consent, [
      { text: "Agrega canales en vivo a la pestaña En vivo", danger: false },
      { text: "Puede reproducir canales desde cualquier servidor que indique su lista", danger: true },
    ]);
    const err = (() => { try { execFileSync(process.execPath, [join(here, "..", "validate.mjs"), dir], { encoding: "utf8", stdio: "pipe" }); } catch (e) { return e; } return null; })();
    assert.equal(err, null);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("validate --run liveCategories downloads and parses each playlist, as the app would", async (t) => {
  if (!needFixtures(t)) return;
  const dir = mkdtempSync(join(tmpdir(), "kino-pl-"));
  const bytes = { "https://cdn.example.com/broken.m3u": readFileSync(join(fixtures, "broken.m3u")), "https://cdn.example.com/empty.m3u": Buffer.from("#EXTM3U\n") };
  const seen = [];
  const fetchImpl = async (url, init) => {
    seen.push([String(url), init.headers["User-Agent"]]);
    const b = bytes[String(url)];
    return b ? new Response(b, { status: 200 }) : new Response("no", { status: 404 });
  };
  try {
    // The list's streams are on live.example.com: without it declared every entry is refused.
    writeFileSync(join(dir, "kino-plugin.json"), manifest({ apiVersion: 3, hosts: ["cdn.example.com", "live.example.com"], capabilities: ["home", "resolve", "channels"] }));
    const cats = (urls) => `export async function home(){ return [] }\nexport async function resolve(){ return { url: 'https://cdn.example.com/a.m3u8' } }\nexport async function liveCategories(){ return ${JSON.stringify(urls.map((url) => ({ playlist: { url, format: "m3u", headers: { "User-Agent": "Kit/1" } } })))} }\nexport async function liveChannels(){ return { items: [] } }`;
    writeFileSync(join(dir, "plugin.js"), cats(["https://cdn.example.com/broken.m3u"]));
    const ok = await validate(dir, { run: "liveCategories", fetchImpl });
    assert.deepEqual(ok.problems, []);
    assert.ok(ok.drops.includes("playlist https://cdn.example.com/broken.m3u: 5 entradas descartadas"), ok.drops.join("\n"));
    assert.deepEqual(seen, [["https://cdn.example.com/broken.m3u", "Kit/1"]]);
    writeFileSync(join(dir, "kino-plugin.json"), manifest({ apiVersion: 3, hosts: ["cdn.example.com"], capabilities: ["home", "resolve", "channels"] }));
    const refusedHosts = await validate(dir, { run: "liveCategories", fetchImpl });
    assert.ok(refusedHosts.problems.some((p) => p.includes("0 canales (8 entradas descartadas")), refusedHosts.problems.join("\n"));
    writeFileSync(join(dir, "plugin.js"), cats(["https://cdn.example.com/empty.m3u", "https://cdn.example.com/missing.m3u"]));
    const bad = await validate(dir, { run: "liveCategories", fetchImpl });
    assert.equal(bad.ok, false);
    assert.ok(bad.problems.some((p) => p.startsWith("playlist https://cdn.example.com/empty.m3u") && p.includes("0 canales")), bad.problems.join("\n"));
    assert.ok(bad.problems.some((p) => p.startsWith("playlist https://cdn.example.com/missing.m3u") && p.includes("404")), bad.problems.join("\n"));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("liveStreamHosts any: a channel's inline stream may be on any public host, never a local one, its subtitles still strict", () => {
  const m = validateManifest(manifest({ apiVersion: 3, hosts: ["cdn.example.com"], capabilities: ["home", "resolve", "channels"], liveStreamHosts: "any" })).manifest;
  const page = checkOutput("liveChannels", { items: [
    { id: "a", title: "A", stream: { url: "http://8.8.8.8/a.m3u8", subtitles: [{ url: "https://subs.example.org/a.vtt", lang: "es" }] } },
    { id: "b", title: "B", stream: { url: "http://192.168.1.5/b.m3u8" } },
    { id: "c", title: "C", stream: { url: "https://iptv.example.org/c.m3u8" } },
  ] }, m);
  assert.deepEqual(page.value.items.map((c) => c.id), ["a", "c"]);
  assert.deepEqual(page.value.items[0].stream.subtitles, []);
  const strict = validateManifest(manifest({ apiVersion: 3, hosts: ["cdn.example.com"], capabilities: ["home", "resolve", "channels"] })).manifest;
  assert.deepEqual(checkOutput("liveChannels", { items: [{ id: "c", title: "C", stream: { url: "https://iptv.example.org/c.m3u8" } }] }, strict).value.items, []);
});

test("every guide in the shared corpus has its .expected.json and is checked here", (t) => {
  if (!needFixtures(t)) return;
  const guides = readdirSync(fixtures).filter((f) => /\.xml(\.gz)?$/.test(f)).sort();
  assert.deepEqual(guides, ["guide-doctype-comment.xml", "guide-latin1.xml", "guide-windows1252.xml", "guide-xxe.xml", "guide.xml", "guide.xml.gz"]);
});

test("run.mjs live playlist --epg says why a guide declaring a DOCTYPE shows nothing", (t) => {
  if (!needFixtures(t)) return;
  const out = execFileSync(process.execPath, [join(here, "..", "run.mjs"), "live", "playlist", join(fixtures, "basic.m3u"), "--epg", join(fixtures, "guide-xxe.xml")], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  assert.match(out, /La guía declara un DOCTYPE; Kino la rechaza por seguridad/);
});

// A token-per-channel plugin under liveStreamHosts "any": resolve() of a channel's ref answers a URL
// on any public host, which the app accepts because the ref is a live channel's.
function anyLivePlugin(resolveUrl) {
  const dir = mkdtempSync(join(tmpdir(), "kino-anylive-"));
  writeFileSync(join(dir, "kino-plugin.json"), manifest({ apiVersion: 3, hosts: ["api.example.com"], capabilities: ["home", "resolve", "channels"], liveStreamHosts: "any" }));
  writeFileSync(join(dir, "plugin.js"), `export async function home(){ return [] }
export async function liveCategories(){ return [{ id: "news", title: "Noticias" }] }
export async function liveChannels(){ return { items: [{ id: "c1", title: "Uno", ref: "r1" }, { id: "c2", title: "Dos", ref: "r2" }] } }
export async function resolve(ref){ return { url: ${JSON.stringify(resolveUrl)} + "?ref=" + ref } }`);
  return dir;
}
const runCli = (args) => {
  const r = spawnSync(process.execPath, [join(here, "..", "run.mjs"), ...args], { encoding: "utf8" });
  return { code: r.status, stdout: r.stdout, stderr: r.stderr };
};

test("checkOutput resolve: liveStreamHosts any applies only when the ref is a live channel's", () => {
  const m = validateManifest(manifest({ apiVersion: 3, hosts: ["api.example.com"], capabilities: ["home", "resolve", "channels"], liveStreamHosts: "any" })).manifest;
  assert.throws(() => checkOutput("resolve", { url: "http://8.8.8.8/a.m3u8" }, m), /no declaró|https/);
  assert.equal(checkOutput("resolve", { url: "http://8.8.8.8/a.m3u8" }, m, [], { liveChannel: true }).value.url, "http://8.8.8.8/a.m3u8");
  assert.throws(() => checkOutput("resolve", { url: "http://192.168.1.4/a.m3u8" }, m, [], { liveChannel: true }), /local/);
});

test("run.mjs resolve: refused by host under any gives the --live hint, and --live accepts it", () => {
  const dir = anyLivePlugin("http://8.8.8.8/live.m3u8");
  try {
    const plain = runCli([dir, "resolve", "r1"]);
    assert.equal(plain.code, 1);
    assert.match(plain.stderr, /si este ref es de un canal en vivo, prueba con --live/);
    const live = runCli([dir, "resolve", "r1", "--live"]);
    assert.equal(live.code, 0, live.stderr);
    assert.equal(JSON.parse(live.stdout).url, "http://8.8.8.8/live.m3u8?ref=r1");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("run.mjs live channels resolves the first channel's ref as a live channel", () => {
  const dir = anyLivePlugin("http://8.8.8.8/live.m3u8");
  try {
    const r = runCli([dir, "live", "channels", "news"]);
    assert.equal(r.code, 0, r.stderr);
    assert.match(r.stderr, /resolve\(r1\) → http:\/\/8\.8\.8\.8\/live\.m3u8\?ref=r1/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("validate --run liveChannels follows the first ref through resolve as a live channel", async () => {
  const ok = anyLivePlugin("http://8.8.8.8/live.m3u8");
  const local = anyLivePlugin("http://192.168.1.4/live.m3u8");
  try {
    assert.deepEqual((await validate(ok, { run: "liveChannels", args: ["news"] })).problems, []);
    const bad = await validate(local, { run: "liveChannels", args: ["news"] });
    assert.ok(bad.problems.some((p) => p.startsWith("resolve(r1)") && p.includes("local")), bad.problems.join("\n"));
  } finally {
    rmSync(ok, { recursive: true, force: true });
    rmSync(local, { recursive: true, force: true });
  }
});
