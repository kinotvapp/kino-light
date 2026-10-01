// Signed plugins (apiVersion 5's signature): seal.mjs --keygen/--sign and validate.mjs.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash, createPublicKey, verify as cryptoVerify } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { contract, validateManifest } from "../contract.mjs";
import { bindingFromGit, consentLines, validate } from "../validate.mjs";
import { fingerprint, generateAuthorKey, signEntry, verifyEntry } from "../seal.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const SDK = join(here, "..");
const SOURCE = "export async function search(q) { return [] }\nexport async function resolve(ref) { return { url: 'https://example.com/' + ref } }\n";
const MANIFEST = { id: "demo", name: "Demo", version: "1.0.0", apiVersion: 5, entry: "plugin.js", hosts: ["example.com"], capabilities: ["search", "resolve"] };

function signedPlugin({ source = SOURCE, key = generateAuthorKey(), binding = "o/r", gitTrack = [], manifest = MANIFEST } = {}) {
  const dir = mkdtempSync(join(tmpdir(), "kino-signed-"));
  writeFileSync(join(dir, "plugin.js"), source);
  writeFileSync(join(dir, "kino-plugin.json"), JSON.stringify({ ...manifest, signature: signEntry(Buffer.from(source), binding, manifest.id, manifest.version, key.pem) }));
  writeFileSync(join(dir, "kino-author-key.pem"), key.pem);
  if (gitTrack.length) {
    const git = (...a) => spawnSync("git", a, { cwd: dir, encoding: "utf8" });
    git("init", "-q"); git("add", ...gitTrack);
  }
  return { dir, key };
}

test("signEntry: an Ed25519 signature over the documented message, checked independently", () => {
  const { pem, raw } = generateAuthorKey();
  const sig = signEntry(Buffer.from(SOURCE), "Owner/Repo/Sub", "demo", "1.2.3", pem);
  assert.equal(sig.authorKey, raw.toString("hex"));
  assert.match(sig.value, /^[0-9a-f]{128}$/);
  // Written from the guide's format, not from seal.mjs.
  const digest = createHash("sha256").update(SOURCE).digest("hex");
  const message = Buffer.from(`kino-signed-entry:v1\nowner/repo/sub\ndemo\n1.2.3\n${digest}`);
  const pub = createPublicKey({ key: Buffer.concat([Buffer.from("302a300506032b6570032100", "hex"), raw]), format: "der", type: "spki" });
  assert.equal(cryptoVerify(null, message, pub, Buffer.from(sig.value, "hex")), true);
  assert.match(fingerprint(raw), /^[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}$/);
});

test("verifyEntry refuses another script, repo, folder, id, version or key", () => {
  const { pem } = generateAuthorKey();
  const sig = signEntry(Buffer.from(SOURCE), "o/r", "demo", "1.0.0", pem);
  assert.equal(verifyEntry(sig, Buffer.from(SOURCE), "O/R", "demo", "1.0.0"), true);
  assert.equal(verifyEntry(sig, Buffer.from(SOURCE + " "), "o/r", "demo", "1.0.0"), false);
  assert.equal(verifyEntry(sig, Buffer.from(SOURCE), "o/other", "demo", "1.0.0"), false);
  assert.equal(verifyEntry(sig, Buffer.from(SOURCE), "o/r/sub", "demo", "1.0.0"), false);
  assert.equal(verifyEntry(sig, Buffer.from(SOURCE), "o/r", "other", "1.0.0"), false);
  assert.equal(verifyEntry(sig, Buffer.from(SOURCE), "o/r", "demo", "1.0.1"), false);
  assert.equal(verifyEntry({ ...sig, authorKey: generateAuthorKey().raw.toString("hex") }, Buffer.from(SOURCE), "o/r", "demo", "1.0.0"), false);
  assert.throws(() => signEntry(Buffer.from(SOURCE), "o/r", "Bad Id", "1.0.0", pem), /invalid plugin id/);
  assert.throws(() => signEntry(Buffer.from(SOURCE), "https://github.com/o/r", "demo", "1.0.0", pem), /not a URL/);
});

test("manifest: signature is apiVersion 5, well-formed lowercase hex; below 5 it is ignored", () => {
  const sig = { authorKey: "ab".repeat(32), value: "cd".repeat(64) };
  const ok = validateManifest(JSON.stringify({ ...MANIFEST, signature: sig }));
  assert.equal(ok.ok, true);
  assert.deepEqual(ok.manifest.signature, sig);
  for (const bad of ["x", null, [], { authorKey: "ab".repeat(32) }, { ...sig, extra: 1 }, { ...sig, authorKey: "AB".repeat(32) }, { ...sig, value: "cd" }]) {
    const r = validateManifest(JSON.stringify({ ...MANIFEST, signature: bad }));
    assert.equal(r.ok, false, JSON.stringify(bad));
    assert.equal(r.field, "signature");
    assert.equal(r.message, contract.manifest.signature.badFieldMessage);
  }
  const old = validateManifest(JSON.stringify({ ...MANIFEST, apiVersion: 4, signature: "x" }));
  assert.equal(old.ok, true);
  assert.equal("signature" in old.manifest, false);
  assert.equal(validateManifest(JSON.stringify({ ...MANIFEST, apiVersion: 6 })).message, "Este plugin necesita una versión más nueva de Kino");
});

test("validate: a signed plugin passes with the author's fingerprint and the Kino version note", async () => {
  const { dir, key } = signedPlugin();
  try {
    const r = await validate(dir, { repo: "o/r" });
    assert.equal(r.ok, true, r.problems.join("\n"));
    assert.deepEqual(r.consent.map((c) => c.text), ["Firmado por su autor"]);
    assert.ok(r.notes.includes(`Clave del autor: ${fingerprint(key.raw)} (Kino la muestra en los detalles del plugin, no en la ventana de instalación)`));
    assert.ok(r.notes.some((n) => /requiere Kino 0\.9\.46/.test(n)));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("validate: a script changed after signing, or the wrong repo, fails", async () => {
  const { dir } = signedPlugin();
  try {
    assert.equal((await validate(dir, { repo: "o/other" })).ok, false);
    writeFileSync(join(dir, "plugin.js"), SOURCE + "// changed\n");
    const r = await validate(dir, { repo: "o/r" });
    assert.equal(r.ok, false);
    assert.match(r.problems.join("\n"), /La firma del autor no es válida.*seal\.mjs --sign/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("validate: without --repo the binding comes from the GitHub origin, or the check is skipped with a note", async () => {
  const { dir } = signedPlugin({ binding: "owner/kino-plugin-x" });
  try {
    const noRepo = await validate(dir);
    assert.equal(noRepo.ok, true);
    assert.ok(noRepo.notes.some((n) => /--repo/.test(n)));
    const git = (...a) => spawnSync("git", a, { cwd: dir, encoding: "utf8" });
    git("init", "-q"); git("remote", "add", "origin", "git@github.com:Owner/kino-plugin-x.git");
    assert.equal(bindingFromGit(dir), "owner/kino-plugin-x");
    const r = await validate(dir);
    assert.equal(r.ok, true, r.problems.join("\n"));
    assert.ok(!r.notes.some((n) => /--repo/.test(n)));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("validate: the author key tracked by git fails", async () => {
  const { dir } = signedPlugin({ gitTrack: ["kino-author-key.pem"] });
  try {
    const r = await validate(dir, { repo: "o/r" });
    assert.equal(r.ok, false);
    assert.match(r.problems.join("\n"), /kino-author-key\.pem is tracked by git/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("seal.mjs --keygen writes a private key once; --sign writes the signature into the manifest", () => {
  const dir = mkdtempSync(join(tmpdir(), "kino-signcli-"));
  const cli = (...a) => spawnSync(process.execPath, [join(SDK, "seal.mjs"), ...a], { cwd: dir, encoding: "utf8" });
  try {
    writeFileSync(join(dir, "kino-plugin.json"), JSON.stringify(MANIFEST));
    writeFileSync(join(dir, "plugin.js"), SOURCE);
    assert.equal(cli("--sign", "--repo", "o/r").status, 2); // no key yet
    const k = cli("--keygen");
    assert.equal(k.status, 0, k.stderr);
    assert.match(readFileSync(join(dir, "kino-author-key.pem"), "utf8"), /BEGIN PRIVATE KEY/);
    assert.equal(cli("--keygen").status, 2); // never overwritten
    const s = cli("--sign", "--repo", "o/r");
    assert.equal(s.status, 0, s.stderr);
    assert.match(s.stderr, /requires Kino 0\.9\.46 or newer/);
    const m = JSON.parse(readFileSync(join(dir, "kino-plugin.json"), "utf8"));
    assert.equal(verifyEntry(m.signature, readFileSync(join(dir, "plugin.js")), "o/r", "demo", "1.0.0"), true);
    // The script itself is untouched: still the readable source.
    assert.equal(readFileSync(join(dir, "plugin.js"), "utf8"), SOURCE);
    writeFileSync(join(dir, "kino-plugin.json"), JSON.stringify({ ...MANIFEST, apiVersion: 4 }));
    assert.equal(cli("--sign", "--repo", "o/r").status, 2); // apiVersion 5 needed
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("backward compatibility: the example plugin validates exactly as before, with no signature note", async () => {
  const r = await validate(join(here, "..", "..", "archive-org"));
  assert.equal(r.ok, true);
  assert.deepEqual(r.consent.map((c) => c.text), ["Se conectará a los servidores que escribas en su configuración", "Puede descargar videos para verlos sin conexión"]);
  assert.deepEqual(r.notes, []);
  const m = validateManifest(readFileSync(join(here, "..", "..", "archive-org", "kino-plugin.json"), "utf8")).manifest;
  assert.equal("signature" in m, false);
  assert.deepEqual(consentLines(m), r.consent);
  assert.equal(existsSync(join(here, "..", "..", "archive-org", "kino-author-key.pem")), false);
});
