// Node stand-in for the `kino` global Kino gives plugins (see the authoring guide). Same shapes,
// same host check, same caps. Node 18+ (global fetch). kino.html.select exists only in the app.
//
// Kino's own code is authoritative: the Kotlin `PluginHttp` / `PluginHostGate` inside the app decide
// what a plugin may really do. This file only APPROXIMATES their host, redirect and request-cap
// rules so you can develop locally; if the two ever disagree, the app is right. Known differences:
// no cookie jar, any HTTP method is passed through, response bodies are always decoded as UTF-8,
// the 15 s timeout covers the headers but not the download, and a host that resolves to a private
// address is not refused. Nothing here enforces the per-call time or memory limits either.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

// Captured at load: the runner later replaces console.error to keep stdout clean, and kino.log
// must not be routed through that replacement (it would print two prefixes).
const writeErr = console.error.bind(console);

export function hostAllowed(host, patterns) {
  const h = String(host).toLowerCase().replace(/\.$/, "");
  return patterns.some((p) => (p.startsWith("*.") ? h.endsWith("." + p.slice(2)) : h === p));
}

export function createKino(manifest, { appVersion = "sdk", lang = "es-CO", storageFile = null } = {}) {
  const storage = storageFile && existsSync(storageFile) ? JSON.parse(readFileSync(storageFile, "utf8")) : {};
  const save = () => {
    if (!storageFile) return;
    mkdirSync(dirname(storageFile), { recursive: true });
    writeFileSync(storageFile, JSON.stringify(storage));
  };
  let requests = 0;

  async function fetchGated(url, opts = {}) {
    let current = new URL(String(url));
    let method = String(opts.method || "GET").toUpperCase();
    let body = opts.body == null ? undefined : String(opts.body);
    for (let hop = 0; hop <= 10; hop++) {
      if (!hostAllowed(current.hostname, manifest.hosts)) throw new Error("host no permitido: " + current.hostname);
      if (current.protocol !== "https:") throw new Error("solo se permite https");
      if (++requests > 60) throw new Error("demasiadas solicitudes en una sola llamada (máximo 60)");
      const headers = { ...(opts.headers || {}) };
      if (!Object.keys(headers).some((k) => k.toLowerCase() === "user-agent")) {
        headers["User-Agent"] = `Kino/${appVersion} (plugin ${manifest.id})`;
      }
      const controller = new AbortController();
      // Same rule as the app: a missing, non-numeric or non-positive timeout means the 15 s default.
      const requested = Math.trunc(Number(opts.timeoutMs));
      const timeoutMs = Number.isFinite(requested) && requested > 0 ? Math.min(requested, 30000) : 15000;
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      let r;
      try {
        r = await fetch(current, { method, headers, body, redirect: "manual", signal: controller.signal });
      } finally {
        clearTimeout(timer);
      }
      const location = r.headers.get("location");
      if ([301, 302, 303, 307, 308].includes(r.status) && location) {
        if (r.status === 303 || ((r.status === 301 || r.status === 302) && method === "POST")) {
          method = "GET";
          body = undefined;
        }
        current = new URL(location, current);
        continue;
      }
      const bytes = Buffer.from(await r.arrayBuffer());
      if (bytes.length > 5 * 1024 * 1024) throw new Error("respuesta demasiado grande (más de 5 MB)");
      const text = bytes.toString("utf8");
      const responseHeaders = {};
      r.headers.forEach((v, k) => { responseHeaders[k] = v; });
      return { ok: r.ok, status: r.status, url: current.toString(), headers: responseHeaders, text: () => text, json: () => JSON.parse(text) };
    }
    throw new Error("demasiadas redirecciones");
  }

  const kino = Object.freeze({
    apiVersion: 1,
    appVersion,
    lang,
    fetch: fetchGated,
    html: Object.freeze({
      select() {
        throw new Error("kino.html.select only exists inside Kino (it uses Jsoup): test it by installing the plugin in the app");
      },
    }),
    storage: Object.freeze({
      get: (k) => (Object.prototype.hasOwnProperty.call(storage, String(k)) ? storage[String(k)] : null),
      set: (k, v) => {
        const previous = storage[String(k)];
        storage[String(k)] = String(v);
        if (Buffer.byteLength(JSON.stringify(storage)) > 64 * 1024) {
          if (previous === undefined) delete storage[String(k)]; else storage[String(k)] = previous;
          throw new Error("almacenamiento del plugin lleno (64 KB)");
        }
        save();
      },
      remove: (k) => { delete storage[String(k)]; save(); },
    }),
    log: (...args) => writeErr("[kino.log]", ...args),
  });

  return { kino, resetBudget: () => { requests = 0; } };
}
