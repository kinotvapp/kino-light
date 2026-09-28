#!/usr/bin/env node
// Runs one function of a Kino plugin under Node with the same `kino` API the app provides, then
// checks the answer the way the app does and prints what the app would keep.
//   node sdk/run.mjs ./plugin.js search "metropolis"      (KINO_TYPE=movie|series|any)
//   node sdk/run.mjs ./plugin.js search '{"q":"dragnet","type":"series","year":1951}'
//   node sdk/run.mjs ./plugin.js home
//   node sdk/run.mjs ./plugin.js browse '<ref>' ['<cursor>']
//   node sdk/run.mjs ./plugin.js episodes '<series ref>'
//   node sdk/run.mjs ./plugin.js resolve '<ref>'
// Live channels (apiVersion 3, the "channels" capability):
//   node sdk/run.mjs <plugin dir> live categories        (and downloads + groups each declared playlist)
//   node sdk/run.mjs <plugin dir> live channels <categoryId> [cursor]
//   node sdk/run.mjs <plugin dir> live guide <id,id>
//   node sdk/run.mjs live playlist <url|file> [--epg <url|file>]   (any M3U list, no plugin needed)
// Options (before the plugin path):
//   --config key=value     a setting's value (repeatable); also read from sdk/config.json
//   --record <file>        save every kino.fetch answer to <file> (JSON)
//   --replay <file>        answer kino.fetch from <file> only: offline and repeatable
//   --raw                  print the plugin's answer as it returned it, without the app's checks
//   --epg <url|file>       live playlist only: the XMLTV guide to show what is on now
// The first argument is the plugin's entry file or the folder that holds kino-plugin.json. The
// result goes to stdout as JSON; everything else (kino.log, console.*, dropped entries, errors)
// goes to stderr.
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { checkOutput, contract, validateManifest } from "./contract.mjs";
import { createKino } from "./kino-shim.mjs";
import { channelLines, download, guideFor, loadPlaylist, summarisePlaylist, summaryLines } from "./live-playlist.mjs";

const FUNCTIONS = ["search", "home", "browse", "episodes", "resolve"];
// `live <sub>` names one of the channels capability's exports.
const LIVE = { categories: "liveCategories", channels: "liveChannels", guide: "guide" };
const USAGE = "usage: node sdk/run.mjs [--config k=v] [--record f | --replay f] [--raw] <plugin.js | plugin folder> <search|home|browse|episodes|resolve> [argument] [cursor]\n"
  + "       node sdk/run.mjs [--config k=v] <plugin folder> live <categories | channels <categoryId> [cursor] | guide <id,id>>\n"
  + "       node sdk/run.mjs live playlist <url|file> [--epg <url|file>]";
const here = dirname(fileURLToPath(import.meta.url));

const stderr = console.error.bind(console);

function fail(message) {
  stderr(message);
  return 2;
}

export function parseArgs(argv) {
  const opts = { config: {}, record: null, replay: null, raw: false, epg: null };
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
    else if (a === "--epg") opts.epg = argv[++i];
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
  const { opts, rest: args } = parsed;
  if (args[0] === "live" && args[1] === "playlist") {
    if (!args[2]) return fail(USAGE);
    return livePlaylist(args[2], opts.epg);
  }
  let [targetArg, fn, ...rest] = args;
  if (fn === "live") {
    fn = LIVE[rest[0]];
    rest = rest.slice(1);
  }
  if (!targetArg || !(FUNCTIONS.includes(fn) || Object.values(LIVE).includes(fn))) return fail(USAGE);
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
  const capability = Object.values(LIVE).includes(fn) ? "channels" : fn;
  if (!manifest.capabilities.includes(capability)) return fail(`the manifest does not declare "${capability}" in capabilities`);

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
    // The app downloads each declared playlist itself: do the same, and say what it would show.
    let failed = false;
    for (const p of fn === "liveCategories" ? value.playlists : []) {
      try {
        const s = await loadPlaylist(p, { manifest, servers });
        stderr(`playlist ${p.url}:`);
        summaryLines(s).forEach((l) => stderr(`  ${l}`));
        s.categories.forEach((c) => stderr(`  categoría ${c.title} (${c.count})`));
        if (s.channels === 0) { failed = true; stderr("  ✗ 0 canales: Kino would show nothing from this list"); }
      } catch (e) {
        failed = true;
        stderr(`playlist ${p.url}: ✗ not downloaded: ${e.message}`);
      }
    }
    return failed ? 1 : 0;
  } catch (e) {
    saveTape();
    stderr(e && e.code ? `[${e.code}] ${e.message}` : e && e.stack ? e.stack : String(e));
    return 1;
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

/** Reads a local file, or downloads an http(s) URL under `maxBytes`. */
async function readSource(src, maxBytes) {
  if (/^https?:\/\//i.test(src)) return download(src, { maxBytes });
  return readFileSync(src);
}

/** `live playlist`: any M3U list (and, with --epg, its guide) read exactly as Kino would. */
async function livePlaylist(src, epg) {
  try {
    const s = summarisePlaylist(await readSource(src, contract.live.maxPlaylistBytes));
    const guide = epg ? guideFor(s, await readSource(epg, contract.live.maxEpgBytes)) : null;
    if (guide && guide.truncated) stderr("[guide] cut short (byte cap, a cut download or a broken tail): what was read is kept");
    process.stdout.write([...summaryLines(s), ...s.categories.map((c) => `categoría ${c.title} (${c.count})`), "", ...channelLines(s, { guide })].join("\n") + "\n");
    return s.channels ? 0 : 1;
  } catch (e) {
    return fail(`${src}: ${e.message}`);
  }
}

/** The argument each function gets, exactly as the app builds it. */
export async function call(plugin, fn, rest) {
  const arg = rest[0] === undefined ? "" : rest[0];
  if (fn === "home") return plugin.home(null);
  if (fn === "browse") return plugin.browse(arg, rest[1] === undefined ? null : rest[1]);
  // apiVersion 3's channels: the same arguments the app's PluginLiveProvider sends.
  if (fn === "liveCategories") return plugin.liveCategories(null);
  if (fn === "liveChannels") return plugin.liveChannels({ categoryId: arg, cursor: rest[1] === undefined ? null : rest[1] });
  if (fn === "guide") {
    const from = Date.now() - 2 * 3600 * 1000;
    return plugin.guide({ channelIds: arg ? arg.split(",") : [], from, to: from + contract.live.maxGuideWindowMs });
  }
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
