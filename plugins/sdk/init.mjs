#!/usr/bin/env node
// Scaffolds a new plugin: node sdk/init.mjs <folder> [--id my-plugin] [--name "Mi plugin"] [--host example.com]
// Writes kino-plugin.json, plugin.js (every function, commented), README.md and test/plugin.test.mjs
// (a replay-based test: record once with --record, then it runs offline). Never overwrites a file.
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export function scaffold(folder, { id, name, host = "example.com" } = {}) {
  const dir = resolve(folder);
  const pluginId = id || basename(dir).toLowerCase().replace(/[^a-z0-9-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 40) || "mi-plugin";
  const files = {
    "kino-plugin.json": JSON.stringify({
      id: pluginId,
      name: name || pluginId,
      version: "0.1.0",
      apiVersion: 1,
      entry: "plugin.js",
      description: "",
      hosts: [host],
      capabilities: ["search", "home", "browse", "resolve"],
    }, null, 2) + "\n",
    "plugin.js": `// ${name || pluginId}: a Kino plugin. Read GUIDE.md: every function is async, returns plain JSON,
// and must never throw before its first await (the engine can't catch that).
const BASE = "https://${host}";

export async function search(query) {
  // query: { q, type, year, season, episode, tmdbId, originalTitle, altTitles, cursor }
  const r = await kino.fetch(BASE + "/search?q=" + encodeURIComponent(query.q));
  if (r.status === 429) throw kino.error("rate_limited", "demasiadas peticiones");
  if (!r.ok) throw kino.error("unavailable", "el sitio respondió " + r.status);
  return r.json().results.map(toItem);
}

export async function home() {
  const r = await kino.fetch(BASE + "/latest");
  return [{ id: "latest", title: "Lo último", ref: "latest", items: r.json().results.map(toItem) }];
}

export async function browse(ref, cursor) {
  const r = await kino.fetch(BASE + "/" + encodeURIComponent(ref) + (cursor ? "?page=" + encodeURIComponent(cursor) : ""));
  const data = r.json();
  return { items: data.results.map(toItem), next: data.nextPage ? String(data.nextPage) : undefined };
}

export async function resolve(ref) {
  const r = await kino.fetch(BASE + "/watch/" + encodeURIComponent(ref));
  if (r.status === 404) throw kino.error("not_found", "ya no está");
  return { url: r.json().url, mime: "video/mp4" };
}

function toItem(x) {
  return { id: String(x.id), ref: String(x.id), title: x.title, kind: "movie", year: x.year, poster: x.poster };
}
`,
    "README.md": `# ${name || pluginId}\n\nA [Kino](https://github.com/kinotvapp/kino-plugin-archive) plugin. Install it in Kino (Ajustes ▸ Plugins) with this repository's \`owner/repo\`.\n\n## Develop\n\n\`\`\`\nnode sdk/validate.mjs .\nnode sdk/run.mjs --record test/fixtures.json . search "algo"\nnode --test test/plugin.test.mjs\n\`\`\`\n`,
    "test/plugin.test.mjs": `// Offline test: answers come from test/fixtures.json (record it once with
//   node sdk/run.mjs --record test/fixtures.json . search "algo").
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { validate } from "../sdk/validate.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const fixtures = join(root, "test", "fixtures.json");

test("Kino accepts the manifest and the exports", async () => {
  const r = await validate(root);
  assert.deepEqual(r.problems, []);
});

test("search answers offline, and Kino drops nothing", { skip: !existsSync(fixtures) && "record test/fixtures.json first" }, async () => {
  const r = await validate(root, { run: "search", args: ["algo"], replay: fixtures });
  assert.deepEqual(r.problems, []);
  assert.deepEqual(r.drops, []);
  assert.ok(r.output.items.length > 0);
});
`,
  };
  const written = [];
  for (const [path, content] of Object.entries(files)) {
    const file = join(dir, path);
    if (existsSync(file)) continue;
    mkdirSync(join(file, ".."), { recursive: true });
    writeFileSync(file, content);
    written.push(path);
  }
  return written;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [folder, ...rest] = process.argv.slice(2);
  if (!folder) {
    console.error('usage: node sdk/init.mjs <folder> [--id my-plugin] [--name "Mi plugin"] [--host example.com]');
    process.exitCode = 2;
  } else {
    const opt = (k) => { const i = rest.indexOf(k); return i === -1 ? undefined : rest[i + 1]; };
    const written = scaffold(folder, { id: opt("--id"), name: opt("--name"), host: opt("--host") });
    console.error(written.length ? `wrote ${written.join(", ")}` : "nothing written: every file already exists");
    console.error(`next: copy sdk/ (and contract.json) into ${folder}, then from inside it: node sdk/validate.mjs .`);
  }
}
