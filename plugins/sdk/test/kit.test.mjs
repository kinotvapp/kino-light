// node --test plugins/sdk/test/kit.test.mjs   (Node 18+)
// The kit against the same rules and vectors the app's JVM tests use.
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createServer } from "node:http";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { checkOutput, contract, validateManifest } from "../contract.mjs";
import { createKino } from "../kino-shim.mjs";
import { validate } from "../validate.mjs";
import { scaffold } from "../init.mjs";
import { call } from "../run.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const archive = join(here, "..", "..", "archive-org");

const manifest = (extra = {}) => JSON.stringify({
  id: "demo", name: "Demo", version: "1.0.0", apiVersion: 1, entry: "plugin.js",
  hosts: ["example.com"], capabilities: ["search", "resolve"], ...extra,
});

test("contract.json is the one the app pins", () => {
  assert.equal(contract.apiVersion, 1);
  assert.deepEqual(contract.capabilities.names, ["search", "home", "browse", "episodes", "resolve"]);
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
    [{ hosts: ["192.168.1.1"] }, "hosts", 'El dominio "192.168.1.1" no está permitido'],
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
