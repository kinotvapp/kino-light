#!/usr/bin/env node
// Checks a plugin the way Kino does, without installing it:
//   node sdk/validate.mjs <plugin folder>
//     the manifest (every rule in contract.json, the app's own Spanish messages), the entry file,
//     and that every declared capability is an exported function (the app refuses the install
//     otherwise).
//   node sdk/validate.mjs <plugin folder> --run <function> [argument] [cursor] [--config k=v] [--replay file]
//     also runs one function and reports every entry the app would drop, and why.
// Exit code 0 = Kino would accept it; 1 = it wouldn't (the reasons are on stderr).
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { checkOutput, contract, kb, validateManifest } from "./contract.mjs";
import { createKino } from "./kino-shim.mjs";
import { call, parseArgs } from "./run.mjs";

// Every return carries { ok, problems, drops, output } — even the early ones, before a `kino` even
// exists — so a caller (this file's own CLI included) never has to guess which fields are present.
const refused = (problems) => ({ ok: false, problems, drops: [], output: null });

export async function validate(dirArg, { run = null, args = [], config = {}, replay = null } = {}) {
  const problems = [];
  const dir = resolve(dirArg);
  const manifestFile = join(dir, "kino-plugin.json");
  if (!existsSync(manifestFile)) return refused([`no kino-plugin.json in ${dir}`]);
  const checked = validateManifest(readFileSync(manifestFile, "utf8"));
  if (!checked.ok) return refused([`kino-plugin.json: ${checked.field}: ${checked.message}`]);
  const m = checked.manifest;
  const entry = join(dir, m.entry);
  if (!existsSync(entry)) return refused([`entry ${m.entry} not found`]);
  if (statSync(entry).size > contract.manifest.entryMaxBytes) problems.push(`${m.entry} is bigger than ${kb(contract.manifest.entryMaxBytes)}: Kino refuses it`);
  if (m.icon && existsSync(join(dir, m.icon)) && statSync(join(dir, m.icon)).size > contract.manifest.iconMaxBytes) problems.push(`${m.icon} is bigger than ${kb(contract.manifest.iconMaxBytes)}: Kino skips it`);
  const scratch = mkdtempSync(join(tmpdir(), "kino-validate-"));
  const drops = [];
  let output = null;
  try {
    // Inside the try too: an invalid --replay path (or any other setup failure) must become a
    // problem, not an uncaught rejection.
    const { kino, servers } = createKino(m, { config, replay: replay && resolve(replay) });
    globalThis.kino = kino;
    const copy = join(scratch, "plugin.mjs");
    writeFileSync(copy, readFileSync(entry));
    const plugin = await import(pathToFileURL(copy).href);
    const missing = m.capabilities.filter((c) => typeof plugin[c] !== "function");
    if (missing.length) problems.push(`the plugin doesn't export ${missing.join(", ")}: Kino refuses the install ("le falta ${missing.sort().join(", ")}")`);
    if (run && !problems.length) {
      if (!m.capabilities.includes(run)) problems.push(`"${run}" isn't in the manifest's capabilities`);
      else {
        const checkedOut = checkOutput(run, await call(plugin, run, args), m, servers);
        output = checkedOut.value;
        drops.push(...checkedOut.drops);
      }
    }
  } catch (e) {
    problems.push(e && e.code ? `[${e.code}] ${e.message}` : String(e && e.message ? e.message : e));
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
  return { ok: problems.length === 0, problems, drops, output };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { opts, rest } = parseArgs(process.argv.slice(2));
  const runAt = rest.indexOf("--run");
  const dir = rest[0];
  if (!dir) {
    console.error("usage: node sdk/validate.mjs <plugin folder> [--run <function> [argument] [cursor]] [--config k=v] [--replay file]");
    process.exitCode = 2;
  } else {
    const result = await validate(dir, {
      run: runAt === -1 ? null : rest[runAt + 1],
      args: runAt === -1 ? [] : rest.slice(runAt + 2),
      config: opts.config,
      replay: opts.replay,
    });
    result.drops.forEach((d) => console.error(`[dropped by Kino] ${d}`));
    result.problems.forEach((p) => console.error(`✗ ${p}`));
    if (result.ok) console.error("✓ Kino would accept this plugin" + (result.drops.length ? ` (${result.drops.length} entries dropped, see above)` : ""));
    process.exitCode = result.ok ? 0 : 1;
  }
}
