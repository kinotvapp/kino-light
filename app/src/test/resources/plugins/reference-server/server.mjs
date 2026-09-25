#!/usr/bin/env node
// A Jellyfin-like server for the reference-server plugin, for trying it on the emulator (the
// emulator reaches the Mac at 10.0.2.2). Not part of the app; nothing here ships.
//   node server.mjs --port 8096 --video /path/to/test.mp4 [--password s3cr3t]
// In Kino: Servidor http://10.0.2.2:8096, Usuario ana, Contraseña s3cr3t.
// Items "missing", "busy", "blocked" and "down" answer 404, 429, 451 and 503: each typed error.
import { createServer } from "node:http";
import { createReadStream, statSync } from "node:fs";

const arg = (name, fallback) => { const i = process.argv.indexOf("--" + name); return i === -1 ? fallback : process.argv[i + 1]; };
const port = Number(arg("port", "8096"));
const video = arg("video", null);
const password = arg("password", "s3cr3t");
const TOKEN = "t-" + Math.random().toString(36).slice(2);

const catalog = [
  ...Array.from({ length: 23 }, (_, i) => ({ id: "v" + (i + 1), title: "Video de prueba " + (i + 1), year: 2000 + i, genres: ["Prueba"] })),
  { id: "missing", title: "Error: no encontrado", year: 2024 },
  { id: "busy", title: "Error: limitado", year: 2024 },
  { id: "blocked", title: "Error: región", year: 2024 },
  { id: "down", title: "Error: no disponible", year: 2024 },
];
const errors = { missing: 404, busy: 429, blocked: 451, down: 503 };
// A 1x1 grey PNG: enough for a poster.
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGNgYAAAAAMAASsJTYQAAAAASUVORK5CYII=", "base64");

const json = (res, status, body) => { res.writeHead(status, { "Content-Type": "application/json" }); res.end(JSON.stringify(body)); };

createServer((req, res) => {
  const url = new URL(req.url, "http://x");
  console.log(new Date().toISOString(), req.method, req.url, req.headers["x-token"] ? "(token)" : "");
  if (req.method === "POST" && url.pathname === "/auth") {
    let body = "";
    req.on("data", (d) => { body += d; });
    req.on("end", () => {
      let p = {};
      try { p = JSON.parse(body); } catch {}
      if (p.password === password) json(res, 200, { token: TOKEN }); else json(res, 401, { error: "bad password" });
    });
    return;
  }
  if (url.pathname.startsWith("/img/")) { res.writeHead(200, { "Content-Type": "image/png" }); return res.end(PNG); }
  if (url.pathname.startsWith("/stream/")) {
    if (!video) return json(res, 404, { error: "start with --video" });
    const size = statSync(video).size;
    const range = /bytes=(\d+)-(\d*)/.exec(req.headers.range || "");
    if (range) {
      const start = Number(range[1]);
      const end = range[2] ? Number(range[2]) : size - 1;
      res.writeHead(206, { "Content-Type": "video/mp4", "Content-Range": `bytes ${start}-${end}/${size}`, "Content-Length": end - start + 1, "Accept-Ranges": "bytes" });
      return createReadStream(video, { start, end }).pipe(res);
    }
    res.writeHead(200, { "Content-Type": "video/mp4", "Content-Length": size, "Accept-Ranges": "bytes" });
    return createReadStream(video).pipe(res);
  }
  if (req.headers["x-token"] !== TOKEN) return json(res, 401, { error: "no session" });
  const one = /^\/items\/([^/]+)$/.exec(url.pathname);
  if (one) {
    const id = decodeURIComponent(one[1]);
    if (errors[id]) return json(res, errors[id], { error: id });
    return json(res, 200, { stream: "/stream/" + encodeURIComponent(id) + ".mp4" });
  }
  if (url.pathname === "/items") {
    const q = (url.searchParams.get("q") || "").toLowerCase();
    const list = q ? catalog.filter((x) => x.title.toLowerCase().includes(q)) : catalog;
    const cursor = Number(url.searchParams.get("cursor") || 0);
    const limit = Number(url.searchParams.get("limit") || 50);
    const next = cursor + limit < list.length ? String(cursor + limit) : null;
    return json(res, 200, { items: list.slice(cursor, cursor + limit), next });
  }
  json(res, 404, { error: "not found" });
}).listen(port, "0.0.0.0", () => console.log(`reference server on :${port} (password ${password})`));
