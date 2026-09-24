#!/usr/bin/env node
// Runs one function of a Kino plugin under Node with the same `kino` API the app provides.
//   node sdk/run.mjs ./plugin.js search "metropolis"     (KINO_TYPE=movie|series|any)
//   node sdk/run.mjs ./plugin.js search '{"q":"dragnet","type":"series","year":1951}'   (the whole query)
//   node sdk/run.mjs ./plugin.js home
//   node sdk/run.mjs ./plugin.js episodes '<series ref>'
//   node sdk/run.mjs ./plugin.js resolve '<ref>'
// The first argument is the plugin's entry file or the folder that holds kino-plugin.json. The
// result is printed to stdout as JSON; everything else (kino.log, console.*, errors) goes to stderr.
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { createKino } from "./kino-shim.mjs";

const FUNCTIONS = ["search", "home", "episodes", "resolve"];
const [, , targetArg, fn, ...rest] = process.argv;

// Inside Kino, console.* goes to the log. Keep stdout clean so the JSON result can be piped.
const stderr = console.error.bind(console);
for (const level of ["log", "info", "warn", "error"]) {
  console[level] = (...args) => stderr(`[console.${level}]`, ...args);
}

function fail(message) {
  stderr(message);
  return 2;
}

async function main() {
  if (!targetArg || !FUNCTIONS.includes(fn)) {
    return fail("usage: node sdk/run.mjs <plugin.js | plugin folder> <search|home|episodes|resolve> [argument]");
  }
  const target = resolve(targetArg);
  let stat;
  try {
    stat = statSync(target);
  } catch {
    return fail(`not found: ${targetArg}`);
  }
  const dir = stat.isDirectory() ? target : dirname(target);
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(join(dir, "kino-plugin.json"), "utf8"));
  } catch (e) {
    return fail(`cannot read kino-plugin.json in ${dir}: ${e.message}`);
  }
  if (!Array.isArray(manifest.hosts) || !Array.isArray(manifest.capabilities) || typeof manifest.entry !== "string") {
    return fail("kino-plugin.json needs \"entry\", \"hosts\" and \"capabilities\"");
  }
  const entryPath = resolve(dir, manifest.entry);
  if (!stat.isDirectory() && target !== entryPath) {
    return fail(`${targetArg} is not the manifest's entry (${manifest.entry})`);
  }
  if (!manifest.capabilities.includes(fn)) {
    return fail(`the manifest does not declare "${fn}" in capabilities`);
  }

  const { kino, resetBudget } = createKino(manifest, { storageFile: join(dir, ".kino-storage.json") });
  globalThis.kino = kino;

  // Kino loads the entry as an ES module. Node decides that from the extension and the nearest
  // package.json (Node 18 and 20 treat a plain .js file as CommonJS), so load a copy named .mjs.
  // Stack traces name that copy; its line numbers are the entry's.
  const scratch = mkdtempSync(join(tmpdir(), "kino-plugin-"));
  try {
    const copy = join(scratch, "plugin.mjs");
    writeFileSync(copy, readFileSync(entryPath));
    const plugin = await import(pathToFileURL(copy).href);
    if (typeof plugin[fn] !== "function") return fail(`${manifest.entry} does not export ${fn}()`);
    const arg = rest.join(" ");
    let input = fn === "home" ? null : arg;
    if (fn === "search") {
      input = { q: "", type: process.env.KINO_TYPE || "any", season: 0, episode: 0, tmdbId: 0, year: 0 };
      if (arg.trimStart().startsWith("{")) {
        try {
          Object.assign(input, JSON.parse(arg));
        } catch (e) {
          return fail(`the search argument starts with { but is not valid JSON: ${e.message}`);
        }
      } else {
        input.q = arg;
      }
    }
    resetBudget();
    const out = await plugin[fn](input);
    process.stdout.write(JSON.stringify(out === undefined ? null : out, null, 2) + "\n");
    return 0;
  } catch (e) {
    stderr(e && e.stack ? e.stack : String(e));
    return 1;
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

process.exitCode = await main();
