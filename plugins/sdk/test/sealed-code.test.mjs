// Sealed code (apiVersion 5's sealedEntry): seal.mjs --code/--keygen, validate.mjs and run.mjs.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createDecipheriv, createPublicKey, diffieHellman, generateKeyPairSync, hkdfSync } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { inflateRawSync } from "node:zlib";
import { contract, validateManifest } from "../contract.mjs";
import { consentLines, validate } from "../validate.mjs";
import { fingerprint, generateAuthorKey, inspectSealedCode, moduleSyntaxError, sealCode } from "../seal.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const SDK = join(here, "..");
const SPKI_X25519 = Buffer.from("302a300506032b656e032100", "hex");
const SOURCE = "export async function search(q) { return [] }\nexport async function resolve(ref) { return { url: 'https://example.com/' + ref } }\n";

function testRecipient() {
  const { privateKey, publicKey } = generateKeyPairSync("x25519");
  return { privateKey, hex: publicKey.export({ format: "der", type: "spki" }).subarray(12).toString("hex") };
}

/** An independent opener, from the format table (not from seal.mjs): what Kino does with the private key. */
function openWith(privateKey, recipientHex, blob, binding, id) {
  const eph = blob.subarray(44, 76);
  const shared = diffieHellman({ privateKey, publicKey: createPublicKey({ key: Buffer.concat([SPKI_X25519, eph]), format: "der", type: "spki" }) });
  const key = Buffer.from(hkdfSync("sha256", shared, Buffer.concat([eph, Buffer.from(recipientHex, "hex")]), "kino-sealed-code:v1", 32));
  const d = createDecipheriv("aes-256-gcm", key, blob.subarray(76, 88));
  d.setAAD(Buffer.concat([Buffer.from("kino-sealed-code:v1|"), blob.subarray(0, 44), Buffer.from(`|${binding}|${id}`)]));
  const end = blob.length - 64;
  d.setAuthTag(blob.subarray(end - 16, end));
  const body = Buffer.concat([d.update(blob.subarray(88, end - 16)), d.final()]);
  return (blob[6] === 1 ? inflateRawSync(body) : body).toString("utf8");
}

function sealedPlugin({ source = SOURCE, gitTrack = [], key = generateAuthorKey(), recipient = testRecipient().hex } = {}) {
  const dir = mkdtempSync(join(tmpdir(), "kino-sealed-"));
  const manifest = { id: "demo", name: "Demo", version: "1.0.0", apiVersion: 5, sealedEntry: "plugin.kjs", hosts: ["example.com"], capabilities: ["search", "resolve"] };
  writeFileSync(join(dir, "kino-plugin.json"), JSON.stringify(manifest));
  writeFileSync(join(dir, "plugin.js"), source);
  writeFileSync(join(dir, "plugin.kjs"), sealCode(source, "o/r", "demo", key.pem, recipient));
  writeFileSync(join(dir, "kino-author-key.pem"), key.pem);
  if (gitTrack.length) {
    const git = (...a) => spawnSync("git", a, { cwd: dir, encoding: "utf8" });
    git("init", "-q"); git("add", ...gitTrack);
  }
  return { dir, key };
}

test("sealCode: the format table, DEFLATE inside, the author key in the header, and it opens only for its binding and id", () => {
  const r = testRecipient();
  const { pem, raw } = generateAuthorKey();
  const blob = sealCode(SOURCE, "Owner/Repo/Sub", "demo", pem, r.hex);
  assert.equal(blob.subarray(0, 4).toString("latin1"), "KSC1");
  assert.deepEqual([...blob.subarray(4, 8)], [1, 1, 1, 1]);
  assert.equal(blob.readUInt32BE(8), Buffer.byteLength(SOURCE));
  assert.deepEqual(blob.subarray(12, 44), raw);
  assert.equal(openWith(r.privateKey, r.hex, blob, "owner/repo/sub", "demo"), SOURCE);
  assert.throws(() => openWith(r.privateKey, r.hex, blob, "owner/repo", "demo"));
  assert.throws(() => openWith(r.privateKey, r.hex, blob, "owner/repo/sub", "other"));
  // A fresh ephemeral key and nonce every time.
  assert.notDeepEqual(sealCode(SOURCE, "o/r", "demo", pem, r.hex), sealCode(SOURCE, "o/r", "demo", pem, r.hex));
});

test("inspectSealedCode: a valid file names its author; a flipped bit, another signer or junk is refused", () => {
  const { pem, raw } = generateAuthorKey();
  const blob = sealCode(SOURCE, "o/r", "demo", pem, testRecipient().hex);
  const ok = inspectSealedCode(blob);
  assert.equal(ok.ok, true);
  assert.equal(fingerprint(ok.authorKey), fingerprint(raw));
  assert.match(fingerprint(raw), /^[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}$/);
  for (const i of [0, 4, 7, 9, 20, 50, 100, blob.length - 1]) {
    const bad = Buffer.from(blob); bad[i] ^= 1;
    assert.equal(inspectSealedCode(bad).ok, false, `byte ${i}`);
  }
  assert.equal(inspectSealedCode(Buffer.from("export function x(){}")).ok, false);
});

test("sealCode refuses an empty or oversize script, a bad repo and a non-Ed25519 key", () => {
  const { pem } = generateAuthorKey();
  const r = testRecipient().hex;
  assert.throws(() => sealCode("", "o/r", "demo", pem, r), /1\.\.4194304 bytes/);
  assert.throws(() => sealCode("x".repeat(contract.manifest.sealedEntry.maxPlainBytes + 1), "o/r", "demo", pem, r), /bytes/);
  assert.throws(() => sealCode(SOURCE, "o/r@main", "demo", pem, r), /@ref/);
  const rsa = generateKeyPairSync("rsa", { modulusLength: 1024 }).privateKey.export({ format: "pem", type: "pkcs8" });
  assert.throws(() => sealCode(SOURCE, "o/r", "demo", rsa, r), /Ed25519/);
});

test("manifest: sealedEntry is apiVersion 5, a .kjs, never beside entry; below 5 it is ignored", () => {
  const base = { id: "demo", name: "Demo", version: "1.0.0", hosts: ["example.com"], capabilities: ["search", "resolve"] };
  const ok = validateManifest(JSON.stringify({ ...base, apiVersion: 5, sealedEntry: "dist/plugin.kjs" }));
  assert.equal(ok.ok, true);
  assert.equal(ok.manifest.entrySealed, true);
  assert.equal(ok.manifest.entry, "dist/plugin.kjs");
  assert.deepEqual(validateManifest(JSON.stringify({ ...base, apiVersion: 5, sealedEntry: "p.kjs", entry: "p.js" })), { ok: false, field: "entry", message: 'Usa "entry" o "sealedEntry", no los dos' });
  assert.deepEqual(validateManifest(JSON.stringify({ ...base, apiVersion: 5, sealedEntry: "p.js" })), { ok: false, field: "sealedEntry", message: 'El campo "sealedEntry" debe ser una ruta relativa a un archivo .kjs' });
  // Below apiVersion 5: unknown, ignored -- the manifest object is exactly what it always was.
  const v4 = validateManifest(JSON.stringify({ ...base, apiVersion: 4, entry: "plugin.js", sealedEntry: "p.kjs" }));
  assert.equal(v4.ok, true);
  assert.equal(v4.manifest.entry, "plugin.js");
  assert.equal("entrySealed" in v4.manifest, false);
  assert.equal(validateManifest(JSON.stringify({ ...base, apiVersion: 4, sealedEntry: "p.kjs" })).field, "entry");
  assert.equal(validateManifest(JSON.stringify({ ...base, apiVersion: 6, sealedEntry: "p.kjs" })).message, "Este plugin necesita una versión más nueva de Kino");
});

test("validate: a sealed plugin passes with its consent lines, the author's fingerprint and the Kino version note", async () => {
  const { dir, key } = sealedPlugin();
  try {
    const r = await validate(dir);
    assert.deepEqual(r.problems, []);
    assert.equal(r.ok, true);
    assert.deepEqual(r.consent.map((c) => c.text), ["El código de este plugin está cifrado", `Firmado por su autor con la clave ${fingerprint(key.raw)} (primera vez)`]);
    assert.ok(r.notes.some((n) => n.includes(`requiere Kino ${contract.manifest.sealedEntry.fromApp} o superior`)), r.notes.join("\n"));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("validate: the unsealed plugin.js or the author key tracked by git next to the .kjs fails", async () => {
  for (const tracked of [["plugin.js"], ["kino-author-key.pem"]]) {
    const { dir } = sealedPlugin({ gitTrack: ["kino-plugin.json", "plugin.kjs", ...tracked] });
    try {
      const r = await validate(dir);
      assert.equal(r.ok, false, tracked[0]);
      assert.ok(r.problems.some((p) => p.startsWith(`${tracked[0]} is tracked by git`)), r.problems.join("\n"));
    } finally { rmSync(dir, { recursive: true, force: true }); }
  }
  // Only the manifest and the sealed file in git: fine.
  const { dir } = sealedPlugin({ gitTrack: ["kino-plugin.json", "plugin.kjs"] });
  try { assert.equal((await validate(dir)).ok, true); } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("validate: a tampered .kjs fails; a missing source only skips the exports check; a stale seal is noted", async () => {
  const { dir } = sealedPlugin();
  try {
    writeFileSync(join(dir, "plugin.js"), SOURCE + "// changed after sealing\n");
    let r = await validate(dir);
    assert.equal(r.ok, true);
    assert.ok(r.notes.some((n) => n.includes("olvidaste volver a sellarlo")), r.notes.join("\n"));
    rmSync(join(dir, "plugin.js"));
    r = await validate(dir);
    assert.equal(r.ok, true);
    assert.ok(r.notes.some((n) => n.includes("no se comprueban sus funciones exportadas")));
    const blob = readFileSync(join(dir, "plugin.kjs")); blob[60] ^= 1;
    writeFileSync(join(dir, "plugin.kjs"), blob);
    r = await validate(dir);
    assert.equal(r.ok, false);
    assert.match(r.problems.join("\n"), /invalid author signature/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("validate: a source that misses a declared export is caught, through --source too", async () => {
  const { dir } = sealedPlugin({ source: "export async function search(){ return [] }\n" });
  try {
    const r = await validate(dir);
    assert.equal(r.ok, false);
    assert.match(r.problems.join("\n"), /doesn't export resolve/);
    writeFileSync(join(dir, "other.js"), SOURCE);
    assert.equal((await validate(dir, { source: join(dir, "other.js") })).ok, true);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("run.mjs runs a sealed plugin's unsealed source and says so", () => {
  const { dir } = sealedPlugin();
  try {
    const r = spawnSync(process.execPath, [join(SDK, "run.mjs"), dir, "search", "x"], { encoding: "utf8" });
    assert.equal(r.status, 0, r.stderr);
    assert.match(r.stderr, /ejecutando el código sin sellar/);
    assert.deepEqual(JSON.parse(r.stdout), { items: [], next: null });
    rmSync(join(dir, "plugin.js"));
    const missing = spawnSync(process.execPath, [join(SDK, "run.mjs"), dir, "search", "x"], { encoding: "utf8" });
    assert.notEqual(missing.status, 0);
    assert.match(missing.stderr, /--source/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("seal.mjs --keygen writes a private key once; --code seals and signs with it, refusing a syntax error", () => {
  const dir = mkdtempSync(join(tmpdir(), "kino-sealcli-"));
  const cli = (...a) => spawnSync(process.execPath, [join(SDK, "seal.mjs"), ...a], { cwd: dir, encoding: "utf8", env: { ...process.env, KINO_SEAL_PUBLIC_KEY: testRecipient().hex } });
  try {
    writeFileSync(join(dir, "kino-plugin.json"), JSON.stringify({ id: "demo", name: "Demo", version: "1.0.0", apiVersion: 5, sealedEntry: "plugin.kjs", hosts: ["example.com"], capabilities: ["search", "resolve"] }));
    writeFileSync(join(dir, "plugin.js"), SOURCE);
    assert.equal(cli("--code", "--repo", "o/r").status, 2); // no key yet
    const k = cli("--keygen");
    assert.equal(k.status, 0, k.stderr);
    assert.match(readFileSync(join(dir, "kino-author-key.pem"), "utf8"), /BEGIN PRIVATE KEY/);
    assert.equal(cli("--keygen").status, 2); // never overwritten
    const s = cli("--code", "--repo", "o/r");
    assert.equal(s.status, 0, s.stderr);
    assert.match(s.stderr, /requires Kino 0\.9\.46 or newer/);
    assert.equal(inspectSealedCode(readFileSync(join(dir, "plugin.kjs"))).ok, true);
    writeFileSync(join(dir, "plugin.js"), "export function (){");
    const bad = cli("--code", "--repo", "o/r");
    assert.equal(bad.status, 2);
    assert.match(bad.stderr, /does not parse as an ES module/);
    assert.equal(moduleSyntaxError(SOURCE), null);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("backward compatibility: the example plugin validates exactly as before, with no sealed-code note", async () => {
  const r = await validate(join(here, "..", "..", "archive-org"));
  assert.equal(r.ok, true);
  assert.deepEqual(r.consent.map((c) => c.text), ["Se conectará a los servidores que escribas en su configuración", "Puede descargar videos para verlos sin conexión"]);
  assert.deepEqual(r.notes, []);
  assert.equal(existsSync(join(here, "..", "..", "archive-org", "plugin.kjs")), false);
  // consentLines without the new option is the old function.
  const m = validateManifest(readFileSync(join(here, "..", "..", "archive-org", "kino-plugin.json"), "utf8")).manifest;
  assert.deepEqual(consentLines(m), r.consent);
});
