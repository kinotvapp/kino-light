#!/usr/bin/env node
// Checks a plugin the way Kino does, without installing it:
//   node sdk/validate.mjs <plugin folder>
//     the manifest (every rule in contract.json, the app's own Spanish messages), the entry file,
//     and that every declared capability is an exported function (the app refuses the install
//     otherwise).
//   node sdk/validate.mjs <plugin folder> --run <function> [argument] [cursor] [--config k=v] [--replay file]
//   A sealed entry (apiVersion 5's sealedEntry): the .kjs's structure and author signature are checked
//   (the kit can never open it), and the exports are checked on the unsealed source -- `--source <file>`,
//   by default the .kjs's sibling .js -- which must NOT be tracked by git next to the sealed file.
//     also runs one function and reports every entry the app would drop, and why. With
//     `--run liveCategories`, each declared playlist is downloaded and parsed as the app would.
// It also prints the consent sheet's extra lines, the red ones marked, as the person will read them.
// Exit code 0 = Kino would accept it; 1 = it wouldn't (the reasons are on stderr).
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join, relative, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { checkOutput, contract, kb, requiredExports, validateManifest } from "./contract.mjs";
import { createKino } from "./kino-shim.mjs";
import { call, parseArgs, resolveFirstLiveRef } from "./run.mjs";
import { loadPlaylist } from "./live-playlist.mjs";
import { defaultSourceFor, fingerprint, inspectSealedCode } from "./seal.mjs";

// Every return carries { ok, problems, drops, output, consent, notes } — even the early ones, before
// a `kino` even exists — so a caller (this file's own CLI included) never has to guess which fields
// are present.
const refused = (problems) => ({ ok: false, problems, drops: [], output: null, consent: [], notes: [] });

/**
 * The consent sheet's lines beyond the host list, as the app's PluginConsent.extraLines builds them
 * for a first install. `danger` lines are drawn in red.
 */
export function consentLines(m, { authorFingerprint = null } = {}) {
  const out = [];
  const line = (text, danger = false) => out.push({ text, danger });
  (m.permissions || []).forEach((p) => line(`Permiso: ${p}`));
  if ((m.settings || []).some((s) => s.type === "password")) line("Este plugin usa tu usuario y contraseña");
  if ((m.settings || []).some((s) => s.type === "url")) line("Se conectará a los servidores que escribas en su configuración");
  if (m.capabilities.includes("download")) line("Puede descargar videos para verlos sin conexión");
  if (m.capabilities.includes("drm")) line("Reproduce video protegido (DRM)");
  if (m.capabilities.includes("channels")) line("Agrega canales en vivo a la pestaña En vivo");
  if (m.secrets && Object.keys(m.secrets).length) line("Usa datos sellados por su autor");
  if (m.entrySealed) {
    line(contract.manifest.sealedEntry.consentLine);
    if (authorFingerprint) line(`Firmado por su autor con la clave ${authorFingerprint} (primera vez)`);
  }
  (m.insecureHosts || []).forEach((h) => line(`Conexión sin cifrar con ${h}`, true));
  if (m.liveStreamHostsAny) line("Puede reproducir canales desde cualquier servidor que indique su lista", true);
  if (m.streamHostsAny) line("Puede reproducir video desde cualquier servidor que indique", true);
  return out;
}

/** Files of [dir]'s git repository, among [paths], that git tracks (none when [dir] is not in a repository). */
function trackedByGit(dir, paths) {
  const r = spawnSync("git", ["ls-files", "--", ...paths], { cwd: dir, encoding: "utf8" });
  return r.status === 0 ? r.stdout.split("\n").filter(Boolean) : [];
}

export async function validate(dirArg, { run = null, args = [], config = {}, replay = null, fetchImpl = globalThis.fetch, source = null } = {}) {
  const problems = [];
  const dir = resolve(dirArg);
  const manifestFile = join(dir, "kino-plugin.json");
  if (!existsSync(manifestFile)) return refused([`no kino-plugin.json in ${dir}`]);
  const manifestText = readFileSync(manifestFile, "utf8");
  const checked = validateManifest(manifestText);
  if (!checked.ok) return refused([`kino-plugin.json: ${checked.field}: ${checked.message}`]);
  const m = checked.manifest;
  const notes = [];
  let authorFingerprint = null;
  if (!m.discoverable) notes.push("No aparecerá en la búsqueda de Kino");
  // Accepted from Kino 0.9.45 on; older apps still refuse the install, so the author is told. They
  // counted the raw entries (duplicates too), so this does as well.
  const legacy = contract.manifest.legacyMaxHosts;
  if (JSON.parse(manifestText).hosts.length > legacy.value) {
    notes.push(`Más de ${legacy.value} hosts: Kino ${legacy.refusedUpToApp} o anterior rechaza este plugin; necesita Kino ${legacy.noLimitFromApp} o superior`);
  }
  // The app honors fetchHosts only on a plugin it converted from a Nuvio scraper (never on one written by hand).
  if (m.fetchHostsAny) notes.push("fetchHosts solo tiene efecto en plugins convertidos desde Nuvio; en tu plugin se ignora");
  if (m.secrets && Object.keys(m.secrets).length) {
    notes.push("No se puede comprobar aquí para qué repositorio se sellaron los secretos: Kino lo comprueba al instalar. Además, solo se abren si la persona instala el plugin desde su rama principal, sin @rama.");
  }
  const sc = contract.manifest.sealedEntry;
  if (m.apiVersion >= sc.apiVersion) {
    notes.push(`apiVersion ${m.apiVersion}: requiere Kino ${sc.fromApp} o superior; las versiones anteriores lo rechazan con «Este plugin necesita una versión más nueva de Kino»`);
  }
  let entry = join(dir, m.entry);
  if (!existsSync(entry)) return { ...refused([`entry ${m.entry} not found`]), consent: consentLines(m), notes };
  if (statSync(entry).size > contract.manifest.entryMaxBytes) problems.push(`${m.entry} is bigger than ${kb(contract.manifest.entryMaxBytes)}: Kino refuses it`);
  let checkExports = true;
  if (m.entrySealed) {
    const inspected = inspectSealedCode(readFileSync(entry));
    if (!inspected.ok) problems.push(`${m.entry} ${inspected.problem}`);
    else authorFingerprint = fingerprint(inspected.authorKey);
    notes.push("No se puede comprobar aquí para qué repositorio y plugin se selló el código: Kino lo comprueba al instalar. Además, solo se abre si la persona instala el plugin desde su rama principal, sin @rama.");
    const src = source ? resolve(source) : join(dir, defaultSourceFor(m.entry));
    // The commonest real-world leak: the plain source (or the author key) committed next to the sealed file.
    const leaks = trackedByGit(dir, [relative(dir, src), "*.pem"].filter((p) => !p.startsWith("..")));
    leaks.forEach((f) => problems.push(`${f} is tracked by git next to the sealed ${m.entry}: anyone can read it on GitHub. Remove it (git rm --cached ${f}) and add it to .gitignore${f.endsWith(".pem") ? " -- and since the key leaked, make a new one" : ""}`));
    if (existsSync(src)) {
      entry = src;
      if (inspected.ok && Buffer.byteLength(readFileSync(src, "utf8"), "utf8") !== inspected.plainLength) {
        notes.push(`${basename(src)} no es lo que está sellado en ${m.entry} (otro tamaño): ¿olvidaste volver a sellarlo con node sdk/seal.mjs --code?`);
      }
    } else {
      checkExports = false;
      notes.push(`Sin el código sin sellar (${relative(dir, src) || src}; o --source <archivo>) no se comprueban sus funciones exportadas.`);
    }
  }
  const consent = consentLines(m, { authorFingerprint });
  if (m.icon && existsSync(join(dir, m.icon)) && statSync(join(dir, m.icon)).size > contract.manifest.iconMaxBytes) problems.push(`${m.icon} is bigger than ${kb(contract.manifest.iconMaxBytes)}: Kino skips it`);
  const scratch = mkdtempSync(join(tmpdir(), "kino-validate-"));
  const drops = [];
  let output = null;
  try {
    // Inside the try too: an invalid --replay path (or any other setup failure) must become a
    // problem, not an uncaught rejection.
    // The same local stand-in run.mjs reads: a plugin with `secrets` gets its plain values from it.
    if (!checkExports) return { ok: problems.length === 0, problems, drops, output, consent, notes };
    const { kino, servers } = createKino(m, { config, replay: replay && resolve(replay), fetchImpl, secretsFile: join(dir, ".kino-secrets.json") });
    globalThis.kino = kino;
    const copy = join(scratch, "plugin.mjs");
    writeFileSync(copy, readFileSync(entry));
    const plugin = await import(pathToFileURL(copy).href);
    // download/drm export nothing; channels exports liveCategories + liveChannels (guide optional).
    const missing = requiredExports(m.capabilities).filter((f) => typeof plugin[f] !== "function");
    if (missing.length) problems.push(`the plugin doesn't export ${missing.join(", ")}: Kino refuses the install ("le falta ${missing.sort().join(", ")}")`);
    if (run && !problems.length) {
      // A capability's exports are runnable too: channels runs liveCategories, liveChannels and guide.
      const runnable = new Set([...m.capabilities, ...m.capabilities.flatMap((c) => [...(contract.capabilities.exports[c] || []), ...(contract.capabilities.optionalExports[c] || [])])]);
      if (!runnable.has(run)) problems.push(`"${run}" isn't in the manifest's capabilities`);
      else {
        const checkedOut = checkOutput(run, await call(plugin, run, args), m, servers);
        output = checkedOut.value;
        drops.push(...checkedOut.drops);
        // Playing a listed channel sends its ref to resolve() as a live channel's: follow the first.
        if (run === "liveChannels") {
          const r = await resolveFirstLiveRef(plugin, output, m, servers);
          if (r && r.error) problems.push(`resolve(${r.ref}) ${r.error}`);
        }
        // The app downloads and parses each declared playlist itself: one that fails or comes out
        // empty would show nothing.
        for (const p of run === "liveCategories" ? output.playlists : []) {
          try {
            const s = await loadPlaylist(p, { manifest: m, servers, fetchImpl });
            if (s.channels === 0) problems.push(`playlist ${p.url}: 0 canales (${s.skipped} entradas descartadas, ${s.hidden} ocultas)`);
            if (s.skipped) drops.push(`playlist ${p.url}: ${s.skipped} entradas descartadas`);
          } catch (e) {
            problems.push(`playlist ${p.url}: not downloaded: ${e.message}`);
          }
        }
      }
    }
  } catch (e) {
    problems.push(e && e.code ? `[${e.code}] ${e.message}` : String(e && e.message ? e.message : e));
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
  return { ok: problems.length === 0, problems, drops, output, consent, notes };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { opts, rest } = parseArgs(process.argv.slice(2));
  const runAt = rest.indexOf("--run");
  const dir = rest[0];
  if (!dir) {
    console.error("usage: node sdk/validate.mjs <plugin folder> [--run <function> [argument] [cursor]] [--config k=v] [--replay file] [--source plugin.js]");
    process.exitCode = 2;
  } else {
    const result = await validate(dir, {
      run: runAt === -1 ? null : rest[runAt + 1],
      args: runAt === -1 ? [] : rest.slice(runAt + 2),
      config: opts.config,
      replay: opts.replay,
      source: opts.source,
    });
    if (result.consent.length) {
      // Red on a terminal, as on the consent sheet; "(en rojo)" either way so a log keeps it.
      const red = process.stderr.isTTY ? (t) => `\x1b[31m${t}\x1b[0m` : (t) => t;
      console.error("Consent sheet:");
      result.consent.forEach((c) => console.error(c.danger ? red(`  ! ${c.text} (en rojo)`) : `  · ${c.text}`));
    }
    result.drops.forEach((d) => console.error(`[dropped by Kino] ${d}`));
    result.problems.forEach((p) => console.error(`✗ ${p}`));
    result.notes.forEach((n) => console.error(`· ${n}`));
    if (result.ok) console.error("✓ Kino would accept this plugin" + (result.drops.length ? ` (${result.drops.length} entries dropped, see above)` : ""));
    process.exitCode = result.ok ? 0 : 1;
  }
}
