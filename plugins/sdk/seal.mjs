#!/usr/bin/env node
// Seals a plugin secret for Kino's v1 format (the sealed-secrets design, §3 and §6; for authors:
// "Sealed secrets" in the plugin guide): X25519 (ephemeral) + HKDF-SHA256 + AES-256-GCM against the
// app's public key. The kit can never open a seal -- only Kino, with the matching private key, can -- so this is
// the only place a plugin author needs to run outside the app.
//
//   node sdk/seal.mjs --repo owner/repo[/path] --name apiKey
//
// Sealed CODE (apiVersion 5's `sealedEntry`, Kino 0.9.46+): the whole entry script, sealed to Kino
// and signed with the author's own Ed25519 key:
//   node sdk/seal.mjs --keygen [--key kino-author-key.pem]          (once; keep the key, never commit it)
//   node sdk/seal.mjs --code --repo owner/repo[/path] [--manifest kino-plugin.json] [--in plugin.js]
//                    [--out <sealedEntry>] [--key kino-author-key.pem]
// Kino pins the key at the first install: every update must be signed with the SAME key, or Kino
// refuses it -- losing the key means everyone has to uninstall and install the plugin again.
//
// The value is read from stdin when it is piped, or from a hidden prompt otherwise -- NEVER from a
// command-line argument, which would land in shell history and process listings. One line goes to
// stdout: `kino-sealed:v1:...`; paste it into the manifest's `secrets` field.
//
// KINO_SEAL_PUBLIC_KEY=<hex> overrides the embedded production public key (the CLI warns on stderr
// when it does). Tests only: it lets the kit and the app's Kotlin tests agree on a fixture without
// the production private key ever leaving the app's native sources. Never use it to seal a secret
// for a real, published plugin.
import { createCipheriv, createHash, createPrivateKey, createPublicKey, diffieHellman, generateKeyPairSync, hkdfSync, randomBytes, sign as cryptoSign, verify as cryptoVerify } from "node:crypto";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";
import { deflateRawSync } from "node:zlib";
import { contract } from "./contract.mjs";

/** Kino's v1 production public key (spec §3); the private half lives only in the app's native library. */
export const PRODUCTION_PUBLIC_KEY_HEX = "b13ecf6d231a75bf57ca21d977075c74f914b4416653cf89940897a29393e65c";

// The fixed 12-byte ASN.1 SubjectPublicKeyInfo prefix for a raw 32-byte X25519 point (RFC 8410).
const SPKI_PREFIX = Buffer.from("302a300506032b656e032100", "hex");
const NAME = new RegExp(contract.manifest.secrets.namePattern);

// The app's PluginAddress rules: an owner is a GitHub user or organization name, a repo name and
// each folder of the path are 1..100 of letters, digits, ".", "_" and "-" (never "." or "..").
const OWNER = /^[A-Za-z0-9][A-Za-z0-9-]{0,38}$/;
const PART = /^[A-Za-z0-9._-]{1,100}$/;
const REPO_USAGE = 'expected "owner/repo" or "owner/repo/path"';

/**
 * `owner/repo[/path]`, lowercased: what a seal binds to, exactly as the app computes it from the
 * address the plugin is installed from. A trailing `/` and a repo's `.git` are dropped like the app
 * drops them. A URL and an `@ref` are refused rather than guessed at: the binding never names a ref,
 * and the app only opens a seal at all when the plugin is installed with NO explicit `@ref` (its
 * default branch, HEAD) -- any explicit ref, branch, tag or commit alike, refuses it.
 */
export function normalizeBinding(repo) {
  const s = String(repo ?? "").trim().replace(/\/+$/, "");
  if (/^[A-Za-z][A-Za-z0-9+.-]*:\/\//.test(s) || /^(www\.)?github\.com\//i.test(s)) {
    throw new Error(`invalid --repo: ${REPO_USAGE}, not a URL (for https://github.com/owner/repo use --repo owner/repo)`);
  }
  if (s.includes("@")) {
    throw new Error(`invalid --repo: ${REPO_USAGE}, without an @ref -- the binding never names one, and the app only opens seals when the plugin is installed from its default branch (no @ref at all)`);
  }
  const [owner, repoRaw = "", ...path] = s.split("/");
  const name = repoRaw.replace(/\.git$/, "");
  const part = (x) => PART.test(x) && x !== "." && x !== "..";
  if (!OWNER.test(owner) || !part(name) || !path.every(part)) throw new Error(`invalid --repo: ${REPO_USAGE}`);
  return [owner, name, ...path].join("/").toLowerCase();
}

/**
 * The v1 seal for [value], bound to [binding] and [name]: `kino-sealed:v1:` + base64url (no padding)
 * of `ephemeralPublicKey(32) || nonce(12) || ciphertext || GCM tag(16)`. [publicKeyHex] defaults to
 * the embedded production key (or `KINO_SEAL_PUBLIC_KEY`, tests only).
 */
export function seal(value, binding, name, publicKeyHex = process.env.KINO_SEAL_PUBLIC_KEY || PRODUCTION_PUBLIC_KEY_HEX) {
  if (!NAME.test(String(name))) throw new Error(`invalid secret name: "${String(name).slice(0, 40)}" (expected ${contract.manifest.secrets.namePattern})`);
  const binding0 = normalizeBinding(binding);
  const plain = Buffer.from(String(value), "utf8");
  if (plain.length < 1 || plain.length > contract.manifest.secrets.maxValueBytes) {
    throw new Error(`the secret value must be 1..${contract.manifest.secrets.maxValueBytes} bytes (UTF-8), got ${plain.length}`);
  }
  const recipientPub = Buffer.from(String(publicKeyHex), "hex");
  if (recipientPub.length !== 32) throw new Error("the public key must be 32 bytes of hex");

  const recipientPublicKey = createPublicKey({ key: Buffer.concat([SPKI_PREFIX, recipientPub]), format: "der", type: "spki" });
  const { privateKey: ephPrivate, publicKey: ephPublic } = generateKeyPairSync("x25519");
  const ephPublicRaw = ephPublic.export({ format: "der", type: "spki" }).subarray(SPKI_PREFIX.length);
  const shared = diffieHellman({ privateKey: ephPrivate, publicKey: recipientPublicKey });
  const key = Buffer.from(hkdfSync("sha256", shared, Buffer.concat([ephPublicRaw, recipientPub]), "kino-sealed:v1", 32));
  const nonce = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  cipher.setAAD(Buffer.from(`kino-sealed:v1|${binding0}|${name}`, "utf8"));
  const ciphertext = Buffer.concat([cipher.update(plain), cipher.final()]);
  const tag = cipher.getAuthTag();
  const raw = Buffer.concat([ephPublicRaw, nonce, ciphertext, tag]);
  return `${contract.manifest.secrets.prefix}${raw.toString("base64url")}`;
}

// ---------- sealed code (apiVersion 5's sealedEntry; app: SealedCode.kt) ----------
//
// KSC1 file: "KSC1" | version 1 | alg 1 | compression (1 = raw DEFLATE, 0 = none) | flags 1 (signed)
// | plaintext length (uint32 BE) | author Ed25519 public key (32) | ephemeral X25519 public key (32)
// | nonce (12) | AES-256-GCM ciphertext | tag (16) | Ed25519 signature (64) over
// "kino-sealed-code-sig:v1" || every byte before it. The AAD is "kino-sealed-code:v1|" || the first
// 44 bytes || "|" || binding || "|" || plugin id, so the author key is authenticated too.

const SC = contract.manifest.sealedEntry;
const ED25519_SPKI_PREFIX = Buffer.from("302a300506032b6570032100", "hex");
const SIG_PREFIX = Buffer.from("kino-sealed-code-sig:v1", "utf8");
const SIGNATURE_BYTES = 64;

/** The raw 32-byte Ed25519 public key of a KeyObject (public or private). */
export function rawAuthorKey(key) {
  const pub = key.type === "private" ? createPublicKey(key) : key;
  return pub.export({ format: "der", type: "spki" }).subarray(ED25519_SPKI_PREFIX.length);
}

/** What the person reads on the consent sheet for an author key: `ABCD-EF01-2345-6789` (first 8 bytes of its SHA-256). */
export function fingerprint(rawKey) {
  return createHash("sha256").update(rawKey).digest().subarray(0, 8).toString("hex").toUpperCase().match(/.{4}/g).join("-");
}

/** A new author key pair, the private half as PKCS#8 PEM (keep it secret, never commit it). */
export function generateAuthorKey() {
  const { privateKey } = generateKeyPairSync("ed25519");
  return { pem: privateKey.export({ format: "pem", type: "pkcs8" }), raw: rawAuthorKey(privateKey) };
}

/**
 * Seals [source] (the plugin's ES module, UTF-8) for [pluginId] installed from [binding] and signs it
 * with [authorPrivateKeyPem]: the bytes of a `.kjs` file.
 */
export function sealCode(source, binding, pluginId, authorPrivateKeyPem, publicKeyHex = process.env.KINO_SEAL_PUBLIC_KEY || PRODUCTION_PUBLIC_KEY_HEX) {
  const binding0 = normalizeBinding(binding);
  if (!new RegExp(contract.manifest.idPattern).test(String(pluginId))) throw new Error(`invalid plugin id: "${String(pluginId).slice(0, 40)}"`);
  const plain = Buffer.from(String(source), "utf8");
  if (plain.length < 1 || plain.length > SC.maxPlainBytes) throw new Error(`the script must be 1..${SC.maxPlainBytes} bytes (UTF-8), got ${plain.length}`);
  const recipientPub = Buffer.from(String(publicKeyHex), "hex");
  if (recipientPub.length !== 32) throw new Error("the public key must be 32 bytes of hex");
  const author = createPrivateKey(authorPrivateKeyPem);
  if (author.asymmetricKeyType !== "ed25519") throw new Error("the author key must be an Ed25519 private key (node sdk/seal.mjs --keygen)");

  const body = deflateRawSync(plain, { level: 9 });
  const header = Buffer.alloc(SC.headerBytes);
  header.write(SC.magic, 0, "latin1");
  header[4] = SC.formatVersion; header[5] = SC.alg; header[6] = 1; header[7] = SC.flags;
  header.writeUInt32BE(plain.length, 8);
  rawAuthorKey(author).copy(header, 12);

  const recipientPublicKey = createPublicKey({ key: Buffer.concat([SPKI_PREFIX, recipientPub]), format: "der", type: "spki" });
  const { privateKey: ephPrivate, publicKey: ephPublic } = generateKeyPairSync("x25519");
  const ephPublicRaw = ephPublic.export({ format: "der", type: "spki" }).subarray(SPKI_PREFIX.length);
  const shared = diffieHellman({ privateKey: ephPrivate, publicKey: recipientPublicKey });
  const key = Buffer.from(hkdfSync("sha256", shared, Buffer.concat([ephPublicRaw, recipientPub]), "kino-sealed-code:v1", 32));
  const nonce = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  cipher.setAAD(Buffer.concat([Buffer.from("kino-sealed-code:v1|", "utf8"), header, Buffer.from(`|${binding0}|${pluginId}`, "utf8")]));
  const ciphertext = Buffer.concat([cipher.update(body), cipher.final()]);
  const unsigned = Buffer.concat([header, ephPublicRaw, nonce, ciphertext, cipher.getAuthTag()]);
  return Buffer.concat([unsigned, cryptoSign(null, Buffer.concat([SIG_PREFIX, unsigned]), author)]);
}

/**
 * The structure of a `.kjs` file, checked like the app's SealedCode.header -- the kit can never open
 * one: `{ ok, problem?, plainLength, compression, authorKey, signatureValid }`.
 */
export function inspectSealedCode(buf) {
  const bad = (problem) => ({ ok: false, problem });
  if (buf.length < SC.minBytes) return bad(`is ${buf.length} bytes, smaller than any sealed entry (${SC.minBytes})`);
  if (buf.subarray(0, 4).toString("latin1") !== SC.magic) return bad(`does not start with "${SC.magic}": not a Kino sealed entry`);
  if (buf[4] !== SC.formatVersion || buf[5] !== SC.alg) return bad(`has format version ${buf[4]} / alg ${buf[5]}; Kino reads version ${SC.formatVersion}, alg ${SC.alg}`);
  if (!SC.compressions.includes(buf[6])) return bad(`has an unknown compression (${buf[6]})`);
  if (buf[7] !== SC.flags) return bad("is not signed by its author (seal it again with node sdk/seal.mjs --code)");
  const plainLength = buf.readUInt32BE(8);
  if (plainLength < 1 || plainLength > SC.maxPlainBytes) return bad(`declares a ${plainLength}-byte script; Kino accepts 1..${SC.maxPlainBytes}`);
  const authorKey = buf.subarray(12, SC.headerBytes);
  const signed = buf.length - SIGNATURE_BYTES;
  let signatureValid = false;
  try {
    const pub = createPublicKey({ key: Buffer.concat([ED25519_SPKI_PREFIX, authorKey]), format: "der", type: "spki" });
    signatureValid = cryptoVerify(null, Buffer.concat([SIG_PREFIX, buf.subarray(0, signed)]), pub, buf.subarray(signed));
  } catch { signatureValid = false; }
  if (!signatureValid) return bad("has an invalid author signature: Kino refuses it");
  return { ok: true, plainLength, compression: buf[6], authorKey: Buffer.from(authorKey), signatureValid };
}

/** A syntax check of [source] as an ES module (the way Kino loads it), run by Node itself. Returns null or the error text. */
export function moduleSyntaxError(source) {
  const dir = mkdtempSync(join(tmpdir(), "kino-seal-"));
  try {
    const file = join(dir, "plugin.mjs");
    writeFileSync(file, source);
    const r = spawnSync(process.execPath, ["--check", file], { encoding: "utf8" });
    return r.status === 0 ? null : (r.stderr || "syntax error").trim().split("\n").slice(0, 6).join("\n");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** The unsealed source a sealedEntry is made from, by default: the same path with .js instead of .kjs. */
export function defaultSourceFor(sealedEntry) {
  return sealedEntry.replace(/\.kjs$/, ".js");
}

function readStdin() {
  return new Promise((resolvePromise, reject) => {
    const chunks = [];
    process.stdin.on("data", (c) => chunks.push(c));
    process.stdin.on("end", () => resolvePromise(Buffer.concat(chunks).toString("utf8").replace(/\r?\n$/, "")));
    process.stdin.on("error", reject);
  });
}

/**
 * The hidden prompt's line editing, fed the raw-mode chunks as they arrive. A chunk is whatever the
 * terminal delivered at once -- a single key, or a whole paste with its Enter -- so it is walked
 * character by character: the line ends at the first Enter (`\r` or `\n`) and anything after it is
 * dropped. Backspace (DEL or ^H) removes the last character (a whole one, even past the BMP), ^U the
 * whole line; ^C cancels; ^D ends the input (an empty line cancels). An escape sequence (an arrow or
 * function key) and any other control character are dropped, a tab is kept. `feed` answers
 * `{ done: false }` until the line is over, then `{ done: true, value }` or
 * `{ done: true, cancelled: true }` -- the same answer for any later chunk.
 */
export function hiddenLineReader() {
  let chars = [];
  let escape = null; // null, "esc" (just saw ESC), "csi" (inside ESC [ ... final byte)
  let result = null;
  const finish = (r) => { result = r; chars = []; return r; };
  return {
    feed(chunk) {
      if (result) return result;
      for (const ch of String(chunk)) {
        const code = ch.codePointAt(0);
        if (escape === "esc") { escape = ch === "[" ? "csi" : ch === "O" ? "ss3" : null; continue; }
        if (escape === "ss3") { escape = null; continue; }
        if (escape === "csi") { if (code >= 0x40 && code <= 0x7e) escape = null; continue; }
        if (ch === "\r" || ch === "\n") return finish({ done: true, value: chars.join("") });
        if (ch === "\u0003") return finish({ done: true, cancelled: true });
        if (ch === "\u0004") return finish(chars.length ? { done: true, value: chars.join("") } : { done: true, cancelled: true });
        if (ch === "\u007f" || ch === "\b") { chars.pop(); continue; }
        if (ch === "\u0015") { chars = []; continue; }
        if (ch === "\u001b") { escape = "esc"; continue; }
        if ((code < 0x20 && ch !== "\t") || (code >= 0x80 && code < 0xa0)) continue;
        chars.push(ch);
      }
      return { done: false };
    },
  };
}

/** A hidden prompt on the TTY itself: nothing echoed, not even asterisks. */
function readHiddenPrompt(query) {
  return new Promise((resolvePromise, reject) => {
    const stdin = process.stdin;
    process.stderr.write(query);
    stdin.setRawMode(true);
    stdin.resume();
    stdin.setEncoding("utf8");
    const reader = hiddenLineReader();
    const onData = (chunk) => {
      const r = reader.feed(chunk);
      if (!r.done) return;
      if (r.cancelled) done(new Error("cancelled")); else done(null, r.value);
    };
    const done = (err, val) => {
      stdin.setRawMode(false);
      stdin.pause();
      stdin.removeListener("data", onData);
      process.stderr.write("\n");
      if (err) reject(err); else resolvePromise(val);
    };
    stdin.on("data", onData);
  });
}

/** The value to seal: stdin when it's piped (not a TTY), a hidden prompt otherwise. Never argv. */
export async function readValue() {
  if (!process.stdin.isTTY) return readStdin();
  return readHiddenPrompt("Secret value (hidden, not echoed): ");
}

async function main(argv) {
  const opt = (name) => { const i = argv.indexOf(name); return i === -1 ? undefined : argv[i + 1]; };
  if (argv.includes("--keygen")) return keygen(opt("--key") || "kino-author-key.pem");
  if (argv.includes("--code")) return mainCode(opt);
  const repo = opt("--repo");
  const name = opt("--name");
  if (!repo || !name) {
    console.error("usage: node sdk/seal.mjs --repo owner/repo[/path] --name secretName   (value read from stdin or a hidden prompt, never argv)");
    return 2;
  }
  if (process.env.KINO_SEAL_PUBLIC_KEY) {
    console.error("warning: KINO_SEAL_PUBLIC_KEY replaces Kino's own public key -- this seal will NOT open in Kino (tests only)");
  }
  try {
    normalizeBinding(repo);
  } catch (e) {
    console.error(e.message);
    return 2;
  }
  let value;
  try {
    value = await readValue();
  } catch (e) {
    console.error(e.message);
    return 1;
  }
  if (!value) {
    console.error("empty value: nothing to seal");
    return 2;
  }
  try {
    console.log(seal(value, repo, name));
    return 0;
  } catch (e) {
    console.error(e.message);
    return 1;
  }
}

function keygen(keyFile) {
  if (existsSync(keyFile)) {
    console.error(`${keyFile} already exists: not overwritten. Kino pins your key at the first install; a new key means everyone must reinstall.`);
    return 2;
  }
  const { pem, raw } = generateAuthorKey();
  writeFileSync(keyFile, pem, { mode: 0o600 });
  console.error(`author key written to ${keyFile} (fingerprint ${fingerprint(raw)})`);
  console.error("keep it safe and private: add it to .gitignore, never commit or share it. Every update must be signed with it.");
  return 0;
}

function mainCode(opt) {
  const repo = opt("--repo");
  if (!repo) {
    console.error("usage: node sdk/seal.mjs --code --repo owner/repo[/path] [--manifest kino-plugin.json] [--in plugin.js] [--out <sealedEntry>] [--key kino-author-key.pem]");
    return 2;
  }
  if (process.env.KINO_SEAL_PUBLIC_KEY) {
    console.error("warning: KINO_SEAL_PUBLIC_KEY replaces Kino's own public key -- this code will NOT open in Kino (tests only)");
  }
  try {
    normalizeBinding(repo);
    const manifestFile = opt("--manifest") || "kino-plugin.json";
    const m = JSON.parse(readFileSync(manifestFile, "utf8"));
    if (!(m.apiVersion >= SC.apiVersion) || typeof m.sealedEntry !== "string") {
      throw new Error(`${manifestFile} needs "apiVersion": ${SC.apiVersion} and "sealedEntry": "plugin${SC.extension}" (and no "entry")`);
    }
    const base = dirname(manifestFile);
    const input = opt("--in") || join(base, defaultSourceFor(m.sealedEntry));
    const out = opt("--out") || join(base, m.sealedEntry);
    const keyFile = opt("--key") || "kino-author-key.pem";
    if (!existsSync(keyFile)) throw new Error(`no author key at ${keyFile}: create one once with node sdk/seal.mjs --keygen --key ${keyFile}`);
    const source = readFileSync(input, "utf8");
    const syntax = moduleSyntaxError(source);
    if (syntax) throw new Error(`${input} does not parse as an ES module (nobody can debug it once sealed):\n${syntax}`);
    const sealed = sealCode(source, repo, m.id, readFileSync(keyFile, "utf8"));
    writeFileSync(out, sealed);
    const plainBytes = Buffer.byteLength(source, "utf8");
    console.error(`sealed ${input} (${plainBytes} bytes) -> ${out} (${sealed.length} bytes), signed by ${fingerprint(sealed.subarray(12, 44))}`);
    if (plainBytes > SC.recommendedMaxPlainBytes) console.error(`warning: over ${SC.recommendedMaxPlainBytes / 1048576} MB of script: the slowest TV boxes need about a second to load it`);
    if (sealed.length > contract.manifest.entryMaxBytes) console.error(`error: ${out} is bigger than ${contract.manifest.entryMaxBytes} bytes: Kino refuses it`);
    console.error(`requires Kino ${SC.fromApp} or newer; never commit ${input} next to ${out} (node sdk/validate.mjs checks it)`);
    return sealed.length > contract.manifest.entryMaxBytes ? 1 : 0;
  } catch (e) {
    console.error(e.message);
    return 2;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = await main(process.argv.slice(2));
}
