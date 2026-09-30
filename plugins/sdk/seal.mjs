#!/usr/bin/env node
// Seals a plugin secret for Kino's v1 format (spec: docs/superpowers/specs/2026-09-29-plugin-sealed-
// secrets-design.md §3, §6): X25519 (ephemeral) + HKDF-SHA256 + AES-256-GCM against the app's public
// key. The kit can never open a seal -- only Kino, with the matching private key, can -- so this is
// the only place a plugin author needs to run outside the app.
//
//   node sdk/seal.mjs --repo owner/repo[/path] --name apiKey
//
// The value is read from stdin when it is piped, or from a hidden prompt otherwise -- NEVER from a
// command-line argument, which would land in shell history and process listings. One line goes to
// stdout: `kino-sealed:v1:...`; paste it into the manifest's `secrets` field.
//
// KINO_SEAL_PUBLIC_KEY=<hex> overrides the embedded production public key. Tests only: it lets the
// kit and the app's Kotlin tests agree on a fixture without the production private key ever leaving
// the app's native sources. Never use it to seal a secret for a real, published plugin.
import { createCipheriv, createPublicKey, diffieHellman, generateKeyPairSync, hkdfSync, randomBytes } from "node:crypto";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { contract } from "./contract.mjs";

/** Kino's v1 production public key (spec §3); the private half lives only in the app's native library. */
export const PRODUCTION_PUBLIC_KEY_HEX = "b13ecf6d231a75bf57ca21d977075c74f914b4416653cf89940897a29393e65c";

// The fixed 12-byte ASN.1 SubjectPublicKeyInfo prefix for a raw 32-byte X25519 point (RFC 8410).
const SPKI_PREFIX = Buffer.from("302a300506032b656e032100", "hex");
const NAME = new RegExp(contract.manifest.secrets.namePattern);

/** owner/repo (+ /path), lowercased, with any `@ref` stripped: a seal never binds to a branch. */
export function normalizeBinding(repo) {
  return String(repo).split("@")[0].toLowerCase();
}

/**
 * The v1 seal for [value], bound to [binding] and [name]: `kino-sealed:v1:` + base64url (no padding)
 * of `ephemeralPublicKey(32) || nonce(12) || ciphertext || GCM tag(16)`. [publicKeyHex] defaults to
 * the embedded production key (or `KINO_SEAL_PUBLIC_KEY`, tests only).
 */
export function seal(value, binding, name, publicKeyHex = process.env.KINO_SEAL_PUBLIC_KEY || PRODUCTION_PUBLIC_KEY_HEX) {
  if (!NAME.test(String(name))) throw new Error(`invalid secret name: "${String(name).slice(0, 40)}" (expected ${contract.manifest.secrets.namePattern})`);
  const binding0 = normalizeBinding(binding);
  if (!binding0 || !binding0.includes("/")) throw new Error('invalid --repo: expected "owner/repo" or "owner/repo/path"');
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

function readStdin() {
  return new Promise((resolvePromise, reject) => {
    const chunks = [];
    process.stdin.on("data", (c) => chunks.push(c));
    process.stdin.on("end", () => resolvePromise(Buffer.concat(chunks).toString("utf8").replace(/\r?\n$/, "")));
    process.stdin.on("error", reject);
  });
}

/** A hidden prompt on the TTY itself: nothing echoed, not even asterisks. */
function readHiddenPrompt(query) {
  return new Promise((resolvePromise, reject) => {
    const stdin = process.stdin;
    process.stderr.write(query);
    stdin.setRawMode(true);
    stdin.resume();
    stdin.setEncoding("utf8");
    let value = "";
    const onData = (ch) => {
      if (ch === "\u0003") { done(new Error("cancelled")); return; }
      if (ch === "\r" || ch === "\n") { done(null, value); return; }
      if (ch === "\u007f" || ch === "\b") { value = value.slice(0, -1); return; }
      value += ch;
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
  const repo = opt("--repo");
  const name = opt("--name");
  if (!repo || !name) {
    console.error("usage: node sdk/seal.mjs --repo owner/repo[/path] --name secretName   (value read from stdin or a hidden prompt, never argv)");
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

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = await main(process.argv.slice(2));
}
