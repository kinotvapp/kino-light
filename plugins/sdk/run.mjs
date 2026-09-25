#!/usr/bin/env node
// Runs one function of a Kino plugin under Node with the same `kino` API the app provides, then
// checks the answer the way the app does and prints what the app would keep.
//   node sdk/run.mjs ./plugin.js search "metropolis"      (KINO_TYPE=movie|series|any)
//   node sdk/run.mjs ./plugin.js search '{"q":"dragnet","type":"series","year":1951}'
//   node sdk/run.mjs ./plugin.js home
//   node sdk/run.mjs ./plugin.js browse '<ref>' ['<cursor>']
//   node sdk/run.mjs ./plugin.js episodes '<series ref>'
//   node sdk/run.mjs ./plugin.js resolve '<ref>'
// Options (before the plugin path):
//   --config key=value     a setting's value (repeatable); also read from sdk/config.json
//   --record <file>        save every kino.fetch answer to <file> (JSON)
//   --replay <file>        answer kino.fetch from <file> only: offline and repeatable
//   --raw                  print the plugin's answer as it returned it, without the app's checks
// The first argument is the plugin's entry file or the folder that holds kino-plugin.json. The
// result goes to stdout as JSON; everything else (kino.log, console.*, dropped entries, errors)
// goes to stderr.
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { checkOutput, validateManifest } from "./contract.mjs";
import { createKino } from "./kino-shim.mjs";

const FUNCTIONS = ["search", "home", "browse", "episodes", "resolve"];
const here = dirname(fileURLToPath(import.meta.url));

const stderr = console.error.bind(console);

function fail(message) {
  stderr(message);
  return 2;
}

export function parseArgs(argv) {
  const opts = { config: {}, record: null, replay: null, raw: false };
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--config") {
      const kv = argv[++i] || "";
      const eq = kv.indexOf("=");
      if (eq <= 0) throw new Error("--config needs key=value");
      opts.config[kv.slice(0, eq)] = kv.slice(eq + 1);
    } else if (a === "--record") opts.record = argv[++i];
    else if (a === "--replay") opts.replay = argv[++i];
    else if (a === "--raw") opts.raw = true;
    else rest.push(a);
  }
  return { opts, rest };
}

async function main() {
  // Inside Kino, console.* goes to the log. Keep stdout clean so the JSON result can be piped.
  for (const level of ["log", "info", "warn", "error"]) {
    console[level] = (...args) => stderr(`[console.${level}]`, ...args);
  }
  let parsed;
  try { parsed = parseArgs(process.argv.slice(2)); } catch (e) { return fail(e.message); }
  const { opts, rest: [targetArg, fn, ...rest] } = parsed;
  if (!targetArg || !FUNCTIONS.includes(fn)) {
    return fail("usage: node sdk/run.mjs [--config k=v] [--record f | --replay f] [--raw] <plugin.js | plugin folder> <search|home|browse|episodes|resolve> [argument] [cursor]");
  }
  if (opts.record && opts.replay) return fail("--record and --replay can't be used together");
  const target = resolve(targetArg);
  let stat;
  try { stat = statSync(target); } catch { return fail(`not found: ${targetArg}`); }
  const dir = stat.isDirectory() ? target : dirname(target);
  let manifestText;
  try { manifestText = readFileSync(join(dir, "kino-plugin.json"), "utf8"); } catch (e) { return fail(`cannot read kino-plugin.json in ${dir}: ${e.message}`); }
  const checked = validateManifest(manifestText);
  if (!checked.ok) return fail(`kino-plugin.json: ${checked.field}: ${checked.message}`);
  const manifest = checked.manifest;
  const entryPath = resolve(dir, manifest.entry);
  if (!stat.isDirectory() && target !== entryPath) return fail(`${targetArg} is not the manifest's entry (${manifest.entry})`);
  if (!manifest.capabilities.includes(fn)) return fail(`the manifest does not declare "${fn}" in capabilities`);

  const configFile = join(here, "config.json");
  const config = { ...(existsSync(configFile) ? JSON.parse(readFileSync(configFile, "utf8")) : {}), ...opts.config };
  const { kino, servers, resetBudget, saveTape } = createKino(manifest, {
    storageFile: join(dir, ".kino-storage.json"),
    cookiesFile: join(dir, ".kino-cookies.json"),
    config,
    record: opts.record && resolve(opts.record),
    replay: opts.replay && resolve(opts.replay),
  });
  const missing = (manifest.settings || []).filter((s) => s.required && (kino.config.get(s.key) === undefined || kino.config.get(s.key) === ""));
  if (missing.length) {
    // The app doesn't run a plugin with a required setting empty: it fails with auth_required.
    return fail(`auth_required: set ${missing.map((s) => s.key).join(", ")} with --config key=value or sdk/config.json`);
  }
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
    resetBudget();
    const out = await call(plugin, fn, rest);
    saveTape();
    if (opts.raw) {
      process.stdout.write(JSON.stringify(out === undefined ? null : out, null, 2) + "\n");
      return 0;
    }
    const { value, drops } = checkOutput(fn, out, manifest, servers);
    drops.forEach((d) => stderr(`[dropped by Kino] ${d}`));
    process.stdout.write(JSON.stringify(value, null, 2) + "\n");
    return 0;
  } catch (e) {
    saveTape();
    stderr(e && e.code ? `[${e.code}] ${e.message}` : e && e.stack ? e.stack : String(e));
    return 1;
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

/** The argument each function gets, exactly as the app builds it. */
export async function call(plugin, fn, rest) {
  const arg = rest[0] === undefined ? "" : rest[0];
  if (fn === "home") return plugin.home(null);
  if (fn === "browse") return plugin.browse(arg, rest[1] === undefined ? null : rest[1]);
  if (fn !== "search") return plugin[fn](arg);
  const query = { q: "", type: process.env.KINO_TYPE || "any", season: 0, episode: 0, tmdbId: 0, year: 0, originalTitle: "", altTitles: [], cursor: null };
  if (arg.trimStart().startsWith("{")) {
    try { Object.assign(query, JSON.parse(arg)); }
    catch (e) { throw new Error(`the search argument starts with { but is not valid JSON: ${e.message}`); }
  } else {
    query.q = arg;
  }
  return plugin.search(query);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = await main();
}
