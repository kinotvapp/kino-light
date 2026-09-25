#!/usr/bin/env node
// Writes the guide's tables from contract.json, so the numbers people read are the numbers the app
// enforces (a test in the app pins contract.json to its code).
//   node sdk/guide-tables.mjs <guide.md>           rewrite every <!-- contract:NAME:start/end --> block
//   node sdk/guide-tables.mjs <guide.md> --check   exit 1 if a block is out of date
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { contract, kb } from "./contract.mjs";

const c = contract;
const n = (x) => x.toLocaleString("en-US");
const table = (head, rows) => [`| ${head.join(" | ")} |`, `| ${head.map(() => "---").join(" | ")} |`, ...rows.map((r) => `| ${r.join(" | ")} |`)].join("\n");

export const TABLES = {
  limits: () => table(["What", "Limit"], [
    ["Manifest / entry file / icon", `${kb(c.manifest.maxBytes)} / ${kb(c.manifest.entryMaxBytes)} / ${kb(c.manifest.iconMaxBytes)}`],
    ["Memory / stack, per plugin", `${kb(c.runtime.memoryBytes)} / ${kb(c.runtime.stackBytes)}`],
    ["Time per call", `\`search\` ${c.timeoutsMs.search / 1000} s; \`home\`, \`browse\`, \`episodes\`, \`resolve\` ${c.timeoutsMs.home / 1000} s each, counting all your fetches and sleeps together`],
    ["Loading the module (its top level)", `${c.timeoutsMs.load / 1000} s`],
    ["Idle sandbox", `closed after ${c.runtime.idleCloseMs / 60000} minutes without calls`],
    ["Consecutive timeouts", `${c.runtime.timeoutsBeforeUnresponsive} in a row and Kino disables the plugin ("No responde")`],
    ["`kino.fetch`", `https only (or the person's own server as typed); ${c.fetch.defaultTimeoutMs / 1000} s default, ${c.fetch.maxTimeoutMs / 1000} s maximum; response body at most ${kb(c.fetch.maxBodyBytes)}; the request (URL, headers and body) at most ${n(c.fetch.maxRequestChars)} characters; at most ${c.fetch.maxRequestsPerCall} requests per call; at most ${c.fetch.maxRedirects} redirects per request`],
    ["Cookies", `${c.cookies.maxPerHost} per domain, ${kb(c.cookies.maxTotalBytes)} in total per plugin`],
    ["`kino.storage`", `${kb(c.storage.maxTotalBytes)} per plugin`],
    ["`kino.sleep`", `0 to ${n(c.sleep.maxMs)} ms per call`],
    ["`kino.crypto`", `data at most ${kb(c.crypto.maxDataBytes)} per call; PBKDF2 at most ${n(c.crypto.pbkdf2MaxIterations)} iterations and ${c.crypto.pbkdf2MaxKeyBytes}-byte keys; \`randomBytes\` at most ${n(c.crypto.randomMaxBytes)}`],
    ["`kino.log` / `console.*`", `${n(c.runtime.maxLogChars)} characters per message`],
    ["What a function returns", `at most ${n(c.output.maxResultChars)} characters once turned into JSON`],
    ["Results", `\`search\` ${c.output.maxSearchItems} items; \`home\` ${c.output.maxHomeRows} rows of ${c.output.maxRowItems}; \`browse\` ${c.output.maxBrowseItems} per page; \`episodes\` ${n(c.output.maxEpisodes)}; \`ref\` ${n(c.output.maxRefChars)} characters; \`next\` ${n(c.output.maxCursorChars)} characters; \`id\` matches \`${c.output.itemIdPattern}\``],
    ["Settings", `at most ${c.settings.max}; \`text\` ${c.settings.types.text.maxChars}, \`url\` ${n(c.settings.types.url.maxChars)}, \`password\` ${c.settings.types.password.maxChars} characters`],
    ["Error messages", `your \`kino.error\` message is shown as a detail, cut at ${c.errors.maxMessageChars} characters`],
    ["`hosts`", `${c.manifest.minHosts} to ${c.manifest.maxHosts} entries`],
  ]),
  settings: () => table(["type", "value", "can be `required`", "can have a `default`", "longest value"], Object.entries(c.settings.types).map(([t, v]) => [
    `\`${t}\``,
    t === "toggle" ? "`true` / `false`" : t === "select" ? "one of the `options` values" : "text",
    v.canBeRequired ? "yes" : "no (always has a value)",
    v.canHaveDefault ? "yes" : "no (use `hint` for an example)",
    v.maxChars ? `${n(v.maxChars)} characters` : "—",
  ])),
  crypto: () => table(["Function", "Algorithms"], [
    ["`hash`, `hmac`", c.crypto.hashes.map((x) => `\`${x}\``).join(", ")],
    ["`encrypt`, `decrypt`", c.crypto.ciphers.map((x) => `\`${x}\``).join(", ")],
    ["`pbkdf2`", c.crypto.pbkdf2Hashes.map((x) => `\`${x}\``).join(", ")],
    ["encodings", c.crypto.encodings.map((x) => `\`${x}\``).join(", ")],
  ]),
  errors: () => table(["`kino.error` code", "What the person sees"], [
    ["`auth_required`", "\"Configura {plugin} en Ajustes ▸ Plugins\", with a button to its Configurar screen"],
    ["`not_found`", "\"No se encontró en {plugin}\""],
    ["`geo_blocked`", "\"Este contenido no está disponible en tu región\""],
    ["`rate_limited`", "\"{plugin} está limitando las peticiones; intenta en unos minutos\""],
    ["`unavailable`", "\"{plugin} no está disponible ahora\""],
  ].filter(([code]) => c.errors.codes.includes(code.replace(/`/g, "")))),
  fetchErrors: () => table(["`e.code`", "When"], [
    ["`host_not_allowed`", "the host (or a redirect hop) is not one you declared or the person typed, or it is `http` on a declared host"],
    ["`timeout`", "no complete answer within `timeoutMs`"],
    ["`network`", "the connection failed, or too many redirects"],
    ["`too_large`", "the request over the size cap, or a body over 5 MB"],
    ["`invalid_request`", "a bad URL, method, `redirect` or `body`, or more requests than a call allows"],
  ].filter(([code]) => c.fetch.errorCodes.includes(code.replace(/`/g, "")))),
};

export function render(text) {
  return text.replace(/<!-- contract:(\w+):start -->[\s\S]*?<!-- contract:\1:end -->/g, (block, name) => {
    if (!TABLES[name]) throw new Error(`unknown table ${name}`);
    return `<!-- contract:${name}:start -->\n${TABLES[name]()}\n<!-- contract:${name}:end -->`;
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [file, flag] = process.argv.slice(2);
  if (!file) {
    console.error("usage: node sdk/guide-tables.mjs <guide.md> [--check]");
    process.exitCode = 2;
  } else {
    const text = readFileSync(file, "utf8");
    const out = render(text);
    if (flag === "--check") {
      if (out !== text) { console.error(`${file}: the contract tables are out of date; run node sdk/guide-tables.mjs ${file}`); process.exitCode = 1; }
    } else {
      writeFileSync(file, out);
    }
  }
}
