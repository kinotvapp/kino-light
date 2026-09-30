# Writing a Kino plugin

A Kino plugin is a video source that anyone can publish as a small GitHub repository: one JSON
manifest and one JavaScript file. A person types `owner/repo` in Kino, sees which sites the plugin
will talk to, accepts, and from then on the plugin is one more source: its results show up in
search and on Home, and its titles open, list episodes, play in Kino's player, keep progress and
appear in "Continuar viendo" and the library like any other title.

You can write, run and test a plugin on your computer with Node before you ever touch the app. This
guide has everything you need: the file layout, the manifest, the contract your code must meet, the
API Kino gives you, every limit, the quirks of the JavaScript engine, and how to publish.

This repository is itself the reference plugin (`kino-plugin.json` + `plugin.js`, an Internet
Archive source), and `sdk/` is the Node kit. Two more files describe the contract for machines:
`contract.json` holds every number and rule the app enforces (the tables in this guide are generated
from it, and the app's tests pin its own constants to it), and `kino.d.ts` declares the whole `kino`
API for your editor (`/// <reference path="./kino.d.ts" />` at the top of `plugin.js`).

1. [What a plugin is](#1-what-a-plugin-is)
2. [A first plugin](#2-a-first-plugin)
3. [The manifest](#3-the-manifest)
4. [The contract (apiVersion 1 to 4)](#4-the-contract-apiversion-1-to-4)
5. [The `kino` API](#5-the-kino-api)
6. [Limits and engine quirks](#6-limits-and-engine-quirks)
7. [Test it locally](#7-test-it-locally)
8. [Publishing your plugin](#8-publishing-your-plugin)
9. [What people see](#9-what-people-see)
10. [The reference plugin](#10-the-reference-plugin)
11. [Cookbook](#11-cookbook)

## 1. What a plugin is

A public GitHub repository, or a folder inside one, with:

```
kino-plugin.json   the manifest (required)
plugin.js          the code: a single ES module (required; its name is set by "entry")
icon.png           optional, square, at most 128 KB
README.md          for humans
```

Kino runs your code in a sandbox: no filesystem, no timers, no other plugins, no access to the
person's data. The only way out is `kino.fetch`, which can reach only the hosts your manifest
declares and the person approved on screen, plus the servers the person typed in your plugin's
settings (see [section 3](#3-the-manifest)).

Kino loads exactly one JavaScript file, so there is nothing for an `import` to resolve to. If you
use a build step or a library, bundle everything into that single file.

**How people install it.** In Kino, Ajustes > Plugins, they type the address of your repository:

| They type | Kino reads |
| --- | --- |
| `owner/repo` | the repository root, default branch |
| `owner/repo/sub/dir` | a folder inside the repository |
| `owner/repo@v1.2.0` | a branch, tag or commit (the name cannot contain `/`); also works with a folder |
| `https://github.com/owner/repo` or `.../tree/<ref>/<path>` | the same, pasted from the browser |

Kino downloads `kino-plugin.json`, your entry file and the icon from `raw.githubusercontent.com`,
which is why the repository has to be public.

## 2. A first plugin

Two files. `kino-plugin.json`:

```json
{
  "id": "hello-archive",
  "name": "Hola Archive",
  "version": "0.1.0",
  "apiVersion": 1,
  "entry": "plugin.js",
  "description": "Películas de archive.org, en veinte líneas",
  "hosts": ["archive.org", "*.archive.org"],
  "capabilities": ["search", "resolve"]
}
```

`plugin.js`:

```js
const BASE = "https://archive.org";

export async function search(query) {
  const q = "title:(" + query.q + ") AND mediatype:(movies)";
  const url = BASE + "/advancedsearch.php?q=" + encodeURIComponent(q) +
    "&fl%5B%5D=identifier&fl%5B%5D=title&rows=10&output=json";
  const r = await kino.fetch(url);
  if (!r.ok) throw new Error("archive.org respondió " + r.status);
  return r.json().response.docs.map((d) => ({
    id: d.identifier,
    ref: d.identifier,
    title: String(d.title),
    kind: "movie",
    poster: BASE + "/services/img/" + encodeURIComponent(d.identifier),
  }));
}

export async function resolve(ref) {
  const r = await kino.fetch(BASE + "/metadata/" + encodeURIComponent(ref));
  const file = r.json().files.find((f) => f.name.endsWith(".mp4"));
  if (!file) throw new Error("este item no tiene un mp4");
  const path = file.name.split("/").map(encodeURIComponent).join("/");
  return { url: BASE + "/download/" + encodeURIComponent(ref) + "/" + path };
}
```

Run it (needs Node 18 or newer; see [section 7](#7-test-it-locally)):

```
node sdk/run.mjs ./plugin.js search "metropolis"
node sdk/run.mjs ./plugin.js resolve TheGiantOfMetropolis1961
```

Or let the kit write the skeleton for you: `node sdk/init.mjs my-plugin --host example.com` creates
`my-plugin/` with a manifest, a `plugin.js` with every function, a README and a replay-based test.

This one is deliberately naive (a query with a `/` or a lone `AND` makes archive.org answer with
an error, and nothing checks the shape of the reply). The reference plugin in this repository is
the robust version of the same idea; read [section 10](#10-the-reference-plugin) before you build
on it.

## 3. The manifest

`kino-plugin.json`, at most 16 KB:

```json
{
  "id": "archive-org",
  "name": "Internet Archive",
  "version": "1.0.0",
  "apiVersion": 1,
  "entry": "plugin.js",
  "description": "Películas de dominio público y televisión clásica de archive.org",
  "author": "kinotvapp",
  "homepage": "https://github.com/kinotvapp/kino-plugin-archive",
  "hosts": ["archive.org", "*.archive.org"],
  "capabilities": ["search", "home", "browse", "episodes", "resolve"],
  "color": "#E0A030",
  "icon": "icon.png"
}
```

If a rule below is broken, Kino refuses to install the plugin and shows a message in Spanish that
names the field.

| Field | Rule |
| --- | --- |
| `id` | Required. `^[a-z0-9][a-z0-9-]{1,39}$` (2 to 40 lowercase letters, digits or hyphens, not starting with a hyphen). Not one of `magis`, `ditu`, `live`, `local`, `unknown`, `plugin`. It is the plugin's identity: never change it once people have installed it. |
| `name` | Required. 1 to 40 characters. |
| `version` | Required. `MAJOR.MINOR.PATCH` and nothing else (no `-beta`, no `+build`), each number up to 6 digits and without leading zeros. |
| `apiVersion` | Required. `1`, `2`, `3` or `4`. A higher number than Kino supports is refused with "Este plugin necesita una versión más nueva de Kino". Declare the lowest number that has what you use, so your plugin also runs on older Kino builds. |
| `entry` | Required. Relative path of the JavaScript file: letters, digits, `.`, `_`, `-` and `/` only, no `..`, at most 200 characters, ends in `.js`. The file is at most 1 MB. |
| `hosts` | Required. 1 to 20 entries (from apiVersion 2 it may be empty, `[]`, when the plugin has a `url` setting: see [The person's own servers](#the-persons-own-servers)); each a lowercase DNS name (`archive.org`), `*.` plus a DNS name (`*.archive.org`), or (apiVersion 2 only) an object `{ "host": "…", "insecureHttp": true }` (below). Host names only: no scheme, port or path. No bare `*`, no IP addresses, no `localhost`, nothing ending in `.local`, `.lan`, `.internal`, `.localhost` or `.home.arpa`, and at least one dot. **`*.x` covers subdomains only, not `x` itself**: if you need both, list both. |
| `capabilities` | Required. A subset of `search`, `home`, `browse`, `episodes`, `resolve`, `download`, `drm`, `channels`. Must include `resolve` and at least one of `search` or `home`. `search`, `home`, `browse`, `episodes` and `resolve` must each be an exported function of the entry file, or the install fails with "El plugin no carga: le falta ...". `download` and `drm` need `apiVersion: 2` and are declarative flags instead — the app acts on them, not your code, so nothing extra to export; declaring one shows its consent line ("Puede descargar videos para verlos sin conexión" / "Reproduce video protegido (DRM)") and needs approval again on an update that adds it. `download` gives your titles offline downloads (see [Downloads](#downloads-apiversion-2)); `drm` lets a `Stream` carry a Widevine license (see [A Widevine-protected stream](#a-widevine-protected-stream-apiversion-2)). `channels` needs `apiVersion: 3` and the exports `liveCategories` and `liveChannels` (see [Channels in the En vivo tab](#channels-in-the-en-vivo-tab-apiversion-3)). |
| `settings` | Optional. What the person fills in on your plugin's "Configurar" screen: see below. |
| `permissions` | Optional. A list of names from the closed list in `contract.json`. **The list is empty in this version**: any name is refused with "permiso desconocido: …". It exists so a later version can add permissions (each one shown on the consent screen) without a new `apiVersion`. |
| `color` | Optional `#RRGGBB`: the accent of your plugin's tab and chips. A neutral color by default. |
| `icon` | Optional relative path to a square `.png`, at most 128 KB. An icon that is missing or too big is skipped without failing the install. |
| `discoverable` | Optional `true` or `false` (default `true`), at every `apiVersion`. `false` keeps the plugin out of Kino's community search (see [Get found](#get-found)); people can still install it by typing its address. Any other value is refused with "El campo \"discoverable\" debe ser true o false". |
| `description`, `author`, `homepage` | Optional strings. Trimmed and cut to 300, 60 and 200 characters. Kino shows the name, author, version and description when it asks the person to install. |

Other keys are ignored. `hosts` does three jobs: it is what the person approves, it is the only set
of sites `kino.fetch` can reach, and it is the set a `Stream`'s URLs must be on: the video, its
subtitles, its `audioTracks` and a `drm` block's `licenseUrl` (besides the person's own server).

### Settings

`settings` is a list of at most 12 entries. Each one becomes a field on the plugin's "Configurar"
screen (Ajustes ▸ Plugins), and your code reads its value with `kino.config.get(key)`:

```json
"settings": [
  { "key": "server", "label": "Servidor", "type": "url", "required": true, "hint": "http://192.168.1.10:8096" },
  { "key": "user", "label": "Usuario", "type": "text", "required": true },
  { "key": "password", "label": "Contraseña", "type": "password", "required": true },
  { "key": "quality", "label": "Calidad", "type": "select", "default": "hd",
    "options": [{ "value": "hd", "label": "Alta" }, { "value": "sd", "label": "Normal" }] },
  { "key": "subs", "label": "Subtítulos", "type": "toggle", "default": true }
]
```

<!-- contract:settings:start -->
| type | value | can be `required` | can have a `default` | longest value |
| --- | --- | --- | --- | --- |
| `text` | text | yes | yes | 500 characters |
| `url` | text | yes | no (use `hint` for an example) | 2,048 characters |
| `password` | text | yes | yes | 500 characters |
| `toggle` | `true` / `false` | no (always has a value) | yes | — |
| `select` | one of the `options` values | no (always has a value) | yes | — |
| `list` | a list of entries, each an object of the list's `fields` | yes | no | — |
<!-- contract:settings:end -->

- `key` matches `^[a-z][a-zA-Z0-9_]{0,31}$` and is unique; `label` is 1 to 40 characters; `hint`
  (the example under the field) at most 80.
- `select` needs `options` (1 to 20, each a `value` and a `label` of at most 40 characters); its
  `default` must be one of the values. A `toggle` default is `true` or `false`.
- `list` (apiVersion 4) is a list the person builds with an "Agregar" button: each entry is a
  text line with an "Editar" button, and the dialog to add or edit one shows the list's `fields`
  (1 to 4, each with a `key`, `label`, a `type` of `text` or `url`, and optionally `hint` and
  `required`; no `default`). `max` (1 to 50, default 20) caps the entries. `kino.config.get(key)`
  returns an array of objects `{ [field.key]: string }`, trimmed, without all-blank entries; an
  empty list is `undefined`. A `required` list needs at least one entry. The `url` fields of the
  entries become hosts your plugin may reach, exactly like a `url` setting (so `hosts` may be `[]`).
  ```json
  { "key": "sources", "label": "Direcciones", "type": "list", "max": 30,
    "fields": [ { "key": "url", "label": "Dirección", "type": "url", "required": true },
                { "key": "category", "label": "Categoría", "type": "text" } ] }
  ```
- **A `url` setting has no `default`**: a server the person types becomes a host your plugin may
  reach, so only the person can choose it. A manifest with a `default` on a `url` setting is
  refused; put an example address in `hint` instead.
- **A `required` setting with no value** stops every call to your plugin before it runs: the plugin
  shows "Falta configurar", its Home rows are not asked for, and anything the person opens from it
  says "Configura <name> en Ajustes ▸ Plugins" with a button to that screen.
- **Passwords** are stored encrypted on the device. Your code can read them (it has to send them),
  which is why the consent screen says "Este plugin usa tu usuario y contraseña". Kino never writes
  any setting to its log; do not do it yourself.
- **Changing any setting** closes your plugin's sandbox, deletes its cookies and its cached Home
  rows, so the next call starts a new session with the new values. `kino.storage` is **not** cleared:
  if you keep a token there, key it by the user and server it belongs to (the cookbook does).
- Uninstalling deletes the settings, passwords included.

### The person's own servers

A `url` setting is how a plugin talks to a server that is not on the internet: a media server at
home, for instance. **The server the person types becomes one more host your plugin may reach**,
exactly as typed: its scheme (`http` is allowed here, because home servers rarely have a
certificate), host and port. Nothing else on that machine or network is allowed, redirects from it
may only go to the same server or to your declared `hosts`, and your stream and image URLs may point
at it. The consent screen warns "Se conectará a los servidores que escribas en su configuración", and
Ajustes lists what each plugin reaches ("Se conectará a: …").

A plugin whose **only** reach is that server (it never calls a site of its own) declares
`"hosts": []` from `"apiVersion": 2`, as long as it has at least one `url` setting: the consent
screen then lists no host at all, only the line about the servers the person types, and Ajustes says
"Se conectará solo a los servidores que escribas en su configuración" until one is typed. An empty
`hosts` with no `url` setting is refused (`El campo "hosts" solo puede estar vacío si el plugin
tiene un ajuste de tipo "url"`), and on `"apiVersion": 1` it is refused as always. (Kino never lists
a host under the reserved `.invalid` domain either, the placeholder older manifests used.)

Only the scheme, host and port count: any path on that server is reachable, and
`kino.config.get` returns the value as typed. Kino refuses, with a message under the field, a value
that is not an `http`/`https` URL, or whose host is `localhost`, a loopback address (`127.0.0.1`,
`::1`), a link-local one (`169.254.x.x`, `fe80::`) or `0.0.0.0`. Addresses in the person's own network (`192.168.x.x`,
`10.x.x.x`, a `.local` name) are allowed: that is the point.

### Declaring an insecure host (apiVersion 2)

A `hosts` entry can also be an object, for a site of yours that has no certificate:

```json
"hosts": ["archive.org", { "host": "cdn.example.org", "insecureHttp": true }]
```

This needs `"apiVersion": 2`. `insecureHttp: true` is the only thing it can carry beyond `host`, and
it marks the only *declared* hosts (not the person's own server, above) allowed over plain `http`:
`kino.fetch`, a `Stream`'s `url`, its `subtitles`, its `audioTracks` and a `drm` block's `licenseUrl`
all accept `http://cdn.example.org/…` once it is declared this way, and every redirect hop is judged
by the same rule. Every other declared host stays https-only, `https` keeps working on the insecure
one, and the host is matched exactly: `sub.cdn.example.org` is not covered. The same rules as a plain
string still apply (public DNS name, no `*`, no IP, nothing private/LAN; a name that resolves into
the person's own network is still refused) plus one more: **no `*.` wildcard** — an insecure host is
named exactly. The consent screen shows it in red, "Conexión sin cifrar con cdn.example.org", and an
update that newly marks a host this way waits for approval like a brand new host would. See
[A site of yours without a certificate](#a-site-of-yours-without-a-certificate-apiversion-2).

### Downloads (apiVersion 2)

Declare `"download"` in `capabilities` (with `"apiVersion": 2`) and Kino offers your titles for
offline viewing: "Descargar" on the info page and "Guardar en el dispositivo" in the library, on
phones (Kino never downloads on a TV). Nothing extra to export. When the person saves a title, Kino
calls your `resolve(ref)` when the download actually runs, exactly as playing would, and saves the
`Stream` as **one file**, with your `headers` on every request, through the same host gate as the
player for that stream (https on your `hosts` or the person's own server -- or any public host under
the broad video permission / `streamHosts: "any"` -- every redirect hop checked, never the home
network). Your `subtitles` are saved next to it. `audioTracks` are **not** saved: the offline copy has
only the audio inside the video file, so a source that dubs through separate tracks is heard in its
main audio when offline.

What downloads, and what does not:

- A progressive file (`mp4`, `mkv`, `webm`, `ts`, …) downloads. The saved file takes its extension
  from your `mime` when you give one, else from the URL, else `mp4`; the player sniffs the bytes anyway.
- An HLS VOD stream (`.m3u8`, an `mpegurl` `mime`, or a response that turns out to be a playlist)
  downloads too, saved as one file: MPEG-TS segments become a `.ts`, fMP4 ones (`EXT-X-MAP`) an
  `.mp4`. From a master playlist Kino takes the highest variant up to 1080p whose audio is inside the
  video; AES-128 keys, byte ranges and discontinuities are handled, and your `headers` go on the
  playlists, the key and every segment. A retry resumes at the first missing segment.
- What cannot be saved ends as "Este video no se puede descargar", a final state with no
  "Reintentar" (it would refuse the same way) that the person can only remove: a DASH or Smooth
  manifest (`.mpd`, `application/dash+xml`, …), a live HLS playlist (no `EXT-X-ENDLIST`), SAMPLE-AES
  or any DRM key, a master whose every video variant needs a separate audio rendition (Kino does not
  save a silent video), a DRM-protected stream and a live channel. Subtitle renditions inside the
  playlist are not saved (your `subtitles` are). There is no separate "resolve for download" call.
- The queue downloads one title at a time, so a `ref` may wait a while before `resolve` is called:
  keep something stable in it and look the fresh link up inside `resolve` (as recommended above). A
  retry resumes the partial file even when your URL changed. A `resolve` the queue makes that times
  out fails that download only: it does not count toward the three timeouts in a row that switch
  your plugin off ("No responde"), which only calls made for the person on screen do.
- A plugin that is disabled, waiting for its settings, or uninstalled downloads nothing: its titles
  show no download button, and a title already queued fails with "Este plugin ya no puede descargar
  videos". Files already downloaded keep playing offline and stay removable in Descargas, whatever
  happens to the plugin afterwards.

Declaring `download` shows "Puede descargar videos para verlos sin conexión" on the consent sheet,
and an update that newly declares it waits for the person's approval ([section 8](#8-publishing-your-plugin)).

### Live channels (apiVersion 2)

With `"apiVersion": 2` an item may be a live channel: `kind: "live"`, in any `home` row, `browse`
page or `search` result, next to your movies and series. Nothing to declare beyond the version.

```js
export async function home() {
  return [{
    id: "en-vivo", title: "En vivo",
    items: [
      { id: "canal-1", ref: "live:1", title: "Canal Uno", kind: "live", poster: "https://cdn.example.org/canal-1.png" },
    ],
  }];
}

export async function resolve(ref) {
  if (ref.startsWith("live:")) {
    const url = await freshPlaylistUrlFor(ref); // look the live link up here, never in home()
    return { url, mime: "application/vnd.apple.mpegurl" };
  }
  // ...movies and episodes as before
}
```

What Kino does with a `live` item:

- Its card wears an "EN VIVO" badge (Home, "Ver más", search, phone and TV), and tapping it goes
  **straight to the player**: no info page, nothing to read or pick. `resolve(ref)` gets the item's
  `ref`, exactly as for a movie.
- The `Stream` plays as live: an HLS or DASH live manifest (`.m3u8`/`.mpd`) is what the player
  expects; a progressive file plays too but reads as a channel (no seek bar, no length). `headers`,
  `subtitles` and `expiresInSeconds` work as for any stream; `durationMs` and `audioTracks` are
  ignored (a separate audio file cannot follow a live window: put a channel's other languages inside
  its manifest, e.g. HLS `EXT-X-MEDIA` renditions, and the player's audio menu offers them).
- The player shows the live overlay (no progress bar, no seeking, no "next") and starts at the live
  edge. If it falls behind the live window, or the playlist resets or stalls, it re-joins the live
  edge in place without calling you (a few times a minute). On any other cut, or when your URL
  stops working, it calls `resolve` again with the same `ref` after 2 s, then 4 s, then 8 s: three
  reopens, replenished once the channel has played for five seconds. Only after the third failed
  reopen does the person read "Se cortó la señal de <canal> y no volvió". `expiresInSeconds` plays
  no part for a channel: a cut always re-resolves.
- A channel is never saved: no library row, no resume position, never in "Continuar viendo", and
  never downloadable (a plugin that declares `download` gets "Este video no se puede descargar"
  for it). `runtimeMinutes` on the item is ignored; a channel has no `episodes`.

Limits: a `live` item from a plugin on `"apiVersion": 1` is dropped silently, like any invalid
item (and a row left with no items disappears), so declare `2` before you return one. A channel
still counts against the same row and page sizes as any item. These channels appear in your rows,
with your plugin's name; to put channels in Kino's En vivo tab and its "Canales en vivo" row, use
the apiVersion 3 `channels` capability ([below](#channels-in-the-en-vivo-tab-apiversion-3)).

### Channels in the En vivo tab (apiVersion 3)

Declare `"apiVersion": 3` and the capability `"channels"`, and export `liveCategories()` and
`liveChannels({ categoryId, cursor })` (and, optionally, `guide(...)`, see §4). Your channels then
appear in Kino's own En vivo tab, TV guide, channel drawer and Home "Canales en vivo" row, in a
section with your plugin's name. `channels` does not replace `search`/`home`: the manifest still
needs one of them (a plugin with only channels exports a `home()` that returns `[]`). Items of kind
`"live"` in your rows keep working; a plugin can do both. On install, and on an update that adds it,
the person reads and approves "Agrega canales en vivo a la pestaña En vivo".

### Playing from any server (`streamHosts`, apiVersion 4)

Some sources serve their video from CDNs whose domains you cannot list (they change, or sit on bare
TLDs, which a `*.xyz` entry can never cover). With `"apiVersion": 4` a plugin may add:

```json
"apiVersion": 4,
"streamHosts": "any"
```

`"any"` is the only value and no capability is needed; an older manifest ignores the field. It lets
**what the plugin plays** be on **any public host**, over `http` or `https`:

- a movie or an episode: exactly the rule of the
  [broad video permission](#what-you-return) -- it is the same rule, asked for by you instead of
  granted by the person. **In the player only**, the `url` `resolve` returns, everything its manifest
  names, every redirect hop, **and** the `subtitles` and `audioTracks` you return may be on any public
  host. A download of that movie or episode follows the same rule;
- a live channel: the rule of [`liveStreamHosts: "any"`](#channels-from-any-server-livestreamhosts-apiversion-3)
  (the stream, its manifest and redirects; your `subtitles` and `audioTracks` stay on your `hosts`).

It changes nothing else: `kino.fetch` (and so every [sealed secret](#sealed-secrets-apiversion-4)),
images and DRM license servers stay on your declared `hosts`, and local or private addresses are
still refused. The consent screen shows it in red ("Puede reproducir video desde cualquier servidor
que indique"), and an update that adds it waits for the person to approve again. Prefer listing the
real domains when you can: people trust a narrow list more.

### Channels from any server (`liveStreamHosts`, apiVersion 3)

IPTV lists name their streams on servers you cannot know ahead of time, often plain `http` and
often a bare public IP. For that, and only that, a `channels` plugin may add:

```json
"apiVersion": 3,
"capabilities": ["home", "resolve", "channels"],
"liveStreamHosts": "any"
```

It is read only with `"apiVersion": 3` (an older manifest ignores it, like any field it does not
know). There, `"any"` is the only value and it needs the `channels` capability: otherwise the
manifest is refused with `El campo "liveStreamHosts" solo admite "any"` or
`"liveStreamHosts" necesita la capacidad "channels"`.

What it allows: **a live channel's stream** (the `url` of a channel's inline `stream`, or the `url`
`resolve` returns for a channel; items you mark as live are treated as channels) may be on **any
public host**, over `http` or `https`, a public IPv4 address included (not an IPv6 literal). The player then fetches that manifest and
its variants, segments and keys, and follows their redirects, under the same rule. The audio and
subtitle renditions the HLS manifest itself lists (`#EXT-X-MEDIA`) are part of that stream and follow
the same rule too; the `subtitles` and `audioTracks` you return in a `Stream` do not (see below).

What it never allows:

- the home network: private, loopback, link-local and carrier-grade NAT addresses, IPv6 literals,
  `localhost` and local names (`.local`, `.lan`, …), and a public name that resolves into any of
  them (refused when the player connects);
- other ports or schemes of a server the person typed: that server is reached exactly as typed,
  never "any";
- `kino.fetch`: your own requests still reach only your `hosts` and the person's servers;
- the playlist and XMLTV downloads a `{ playlist }` declaration asks Kino to make: those URLs must
  still be on your `hosts` (or the person's server);
- subtitles, audio tracks and a `drm` block's `licenseUrl`: still your `hosts` only, and every
  redirect they make is judged the same way;
- movies and episodes: a non-live `Stream` is checked exactly as before (unless your plugin declares
  [`streamHosts: "any"`](#playing-from-any-server-streamhosts-apiversion-4) or the person granted it the broad video permission, see
  [The `Stream` rules](#what-you-return));
- images: the poster rule (http or https, never local) does not change.

The consent sheet shows it in red, "Puede reproducir canales desde cualquier servidor que indique su
lista", and an update that newly adds it waits for the person's approval, like a new host.

### Sealed secrets (apiVersion 4)

A plugin that ships a fixed key (an API token baked into a site's own client, a per-tenant secret its
author owns) can seal it instead of writing it into the manifest as plain text:

```
node sdk/seal.mjs --repo owner/repo --name apiKey
```

(`owner/repo/path` for a plugin that lives in a subfolder.) `--repo` follows the same rules as the
address people install from: a trailing `/` and a `.git` are dropped, but a URL
(`https://github.com/...`) and an `@ref` are refused rather than guessed at. The value is read from a
hidden prompt or piped on stdin — never as a command-line argument, which would land in shell
history. It must be 1 to 4,096 bytes (UTF-8); the tool prints one line, `kino-sealed:v1:...`, to paste
into the manifest:

```json
"apiVersion": 4,
"secrets": { "apiKey": "kino-sealed:v1:AbC123..." }
```

- Up to 16 secrets; each name matches `^[A-Za-z][A-Za-z0-9_]{0,31}$`. `secrets` needs
  `"apiVersion": 4`; below that the field is ignored (the plugin installs with no secrets, and
  `kino.secret` throws for every name), and a device running an older Kino refuses the whole install
  with "Este plugin necesita una versión más nueva de Kino" — the release that includes sealed
  secrets (apiVersion 4).
- A seal is bound to the repository (and subfolder) you passed `seal.mjs`, lowercased, **never to a
  ref**. But it only OPENS when the plugin is installed with no explicit `@ref` at all — its default
  branch (`HEAD`); see below. At install and at every update Kino opens each seal once against the
  address the person is installing from, only to check it belongs there; each run of the plugin opens
  them again, in memory, for that run alone. A seal made for a different repository, path or name, or
  one that was corrupted, is refused with "Los datos sellados de este plugin no son para este
  repositorio o están dañados"; a build that cannot open seals at all (no native X25519) refuses with
  "Este Kino no puede abrir datos sellados".
- **Only from the default branch, never an explicit `@ref`.** GitHub serves any commit reachable in a
  repository's fork network — a fork's or a pull request's — through the parent repository's own
  address, and not only for an obvious SHA: a short hex prefix or a git-describe ref
  (`v1-0-g<sha>`) resolves the same way. So `owner/repo@<anything>` can be someone else's manifest,
  with their own `hosts`, while the seal still reads `owner/repo`. A plugin with secrets installed or
  updated with any explicit `@ref` — branch, tag, commit, whatever it's named — is refused with "Los
  datos sellados solo funcionan si instalas el plugin desde su rama principal, sin @rama", and a run at
  such an address gets no secrets. Only `HEAD` (no `@ref` at all) works.
- A seal trusts the repository's *name*: if its owner is renamed or deleted and someone else
  registers that name, their repository opens your seals. Seal again for the new name, and rotate the
  value if the old one was worth protecting.
- Declaring any secret adds "Usa datos sellados por su autor" to the consent sheet; an update that
  brings secrets to a plugin that had none asks again, exactly like a new host. Adding, changing or
  removing a secret in a plugin that already declared some does not.

**What this protects, and what it does not.** This is obfuscation, not secrecy: the private key that
opens a seal ships inside every copy of Kino. Sealing a value keeps it out of your manifest and your
repository's history — it does not stop someone from pulling Kino's key apart and opening the seal
themselves, any more than it stops the site you call from seeing the plain value on its own end.
Don't bother sealing a value that is already public: a key already sitting in that site's own player
JavaScript gains nothing from being sealed in yours.

**`kino.secret(name)`** answers a marker string for a name your manifest declares (any other name
throws). Carry the marker wherever you would carry the value — it is not the value itself, just a
placeholder Kino recognizes later:

```js
const key = kino.secret("apiKey");
await kino.fetch(`https://api.example.org/v1/list?key=${key}`);
```

Kino swaps a marker for the real value **only inside `kino.fetch`**: in the URL's path and query
(percent-encoded, so the value can't split a segment or add a parameter), inside a JSON body
(JSON-escaped), and as is in headers, a text body or a form field. A marker in the URL's scheme,
userinfo, host, port or fragment is left as text — a value never becomes part of the host Kino
connects to. A header whose value would carry a control character once the secret is in it is
refused rather than sent.

**The request then reaches only a host your manifest's `hosts` lists, over `https`, on every redirect
hop** — never a host approved reactively while the plugin runs, never a server the person typed into
your settings, and the broad `liveStreamHosts: "any"` permission does not extend to it either. A hop
anywhere else fails as `host_not_allowed`: "este plugin no puede enviar datos sellados a `<host>`" for
a host you did not declare, "... sin https a `<host>`" for plain `http` even on a declared one.

**`kino.crypto`.** A secret's marker may be the *entire* `key` of an AES `encrypt`/`decrypt` —
exactly one marker, nothing else in the string — or be part of a longer HMAC `key` or PBKDF2
`password`/`salt`. It is always refused, with "no se puede usar un dato sellado aquí", as `data`, `iv`
or `aad`, as part of a longer cipher `key`, and as the key of a non-AES cipher (`des-ede3-*`: read
under a `keyEncoding` you choose, each of its bytes can carry little entropy, which puts a 3DES key
in reach of a search). That is not an arbitrary line: a known `iv` or `aad` under a sealed key
lets a cipher be turned into a way to compute the key back (the same block decrypted under CBC with
the known iv, XORed with the same block under ECB, is the iv itself — and with the key's own marker
used as the iv, that recovers the key), and a key padded out with known bytes shrinks the search down
to the unknown part alone. HMAC and PBKDF2 mix their whole input through a hash before anything comes
out, so a known prefix or suffix next to the secret never splits it back out.

**Redaction.** Anything Kino hands back to your code that could carry a sealed value — `r.text()`,
`r.url`, header values, a text body's `r.base64()`, `kino.cookies.get`, an error message, a
`kino.crypto` answer, and every `kino.log` line — has the value swapped back for its marker first,
whether or not this run has used the secret yet (a cookie set in an earlier run, or a server echoing
the value to a request that never carried it, is caught too). The forms caught: raw, URL
percent-encoding (strict, the `+`-for-space form, and the `%20`-for-space form), JSON-escaped —
including with `/` written as `\/` and non-ASCII as `\uXXXX` in either hex case, as PHP's
`json_encode` and Python's `json.dumps` write them — and base64/base64url. A URL the server returns
with the value inside it comes back with the marker instead, so a `Stream` built from it won't play:
markers are swapped only in `kino.fetch` requests, never in what your plugin returns to Kino. What it
does **not** catch: a binary response (`r.base64()` of something that was
never text) is not scanned, a response header's *name* is not checked (only its value), a lowercase
`%xx` a server happens to echo is not one of the forms above, and a server that transforms the value
on purpose (hashes it, reverses it, ...) leaves nothing left to recognize. A `kino.crypto` error can
still say how many bytes a sealed key was, or whether it was valid hex or base64 — metadata, never the
value. Prefer values of at least 8 bytes: a shorter one is still masked wherever it shows up inside
unrelated text, which gets noisier the shorter it is.

**Testing.** The Node kit can never open a seal — it has no private key — so it reads the plain value
straight from `.kino-secrets.json` next to your manifest (`{ "apiKey": "..." }`; the scaffold's
`.gitignore` already keeps it out of git) and simulates every rule above: markers, substitution, the
host-and-https check, the `kino.crypto` restrictions and redaction. `--record` never writes the plain
value to a fixtures file either — a canonical placeholder stands in for it, so a committed recording
never carries a secret however it is replayed later. See [section 7](#7-test-it-locally).

## 4. The contract (apiVersion 1 to 4)

Your entry file is one ES module that exports one `async` function for each capability you
declared, and nothing is called that you did not declare:

```js
export async function search(query) { /* -> Item[] or Page */ }
export async function home() { /* -> Row[] */ }
export async function browse(ref, cursor) { /* -> Page */ }
export async function episodes(ref) { /* -> { series?: SeriesInfo, episodes: Episode[], seasons?: Season[] } */ }
export async function resolve(ref) { /* -> Stream */ }
export async function liveCategories() { /* -> Array<LiveCategory | Playlist> or Playlist */ }
export async function liveChannels({ categoryId, cursor }) { /* -> { items: LiveChannel[], next? } */ }
export async function guide({ channelIds, from, to }) { /* -> GuideEntry[] */ }
```

(`kino.d.ts` has the same shapes as TypeScript declarations.)

Use named exports (`export async function ...`). Data crosses into and out of your code as JSON, so
return plain data: strings, numbers, booleans, arrays and objects.

### Arguments

- `search(query)` gets `{ q, type, season, episode, tmdbId, year, originalTitle, altTitles, cursor }`:
  - `q` is the text the person typed (it can be empty; return `[]`).
  - `type` is `"movie"` or `"series"` when Kino leans towards that kind, and `"any"` otherwise. It is
    a hint, not a filter: Kino derives it from TMDB's movie/tv split, which rarely lines up with a
    source's own catalogue, and a title can exist as both. Return every plausible match; use `type`
    at most to put the kind it names first.
  - `season` and `episode` are `0` unless Kino is looking for a specific episode; `tmdbId` and `year`
    are `0` when unknown.
  - `originalTitle` is TMDB's original title when it differs from `q` (else `""`), and `altTitles` up
    to 5 other titles Kino knows for the work (each at most 200 characters): try them when `q` finds
    nothing on a source that names things in another language.
  - `cursor` is `null`, except when the person asked for more results and your previous page said
    where to continue (see `Page` below).
- `home()` gets `null`.
- `browse(ref, cursor)` gets the `ref` of one of your Home rows (or a `ref` a previous page gave),
  and `cursor` `null` for the first page or the `next` of the page before.
- `episodes(ref)` gets the `ref` of a `series` item, as you returned it.
- `resolve(ref)` gets the `ref` of a `movie` item, the `ref` of an episode, or (apiVersion 2) the
  `ref` of a `live` item.

### What you return

```ts
Item       = { id: string, ref: string, title: string, kind: "movie" | "series" | "live",
               year?: string, poster?: string, backdrop?: string, overview?: string,
               lang?: string, quality?: string, originalTitle?: string,
               genres?: string[], rating?: number, runtimeMinutes?: number,
               ids?: { tmdb?: number, imdb?: string }, badges?: string[], adult?: boolean }
Row        = { id: string, title: string, items: Item[], ref?: string, genre?: Genre }
Genre      = "peliculas" | "series" | "anime" | "infantil" | "documentales" | "deportes" | "noticias" | "musica" | "entretenimiento" | "otros"
Page       = { items: Item[], next?: string }
SeriesInfo = { title?: string, poster?: string, backdrop?: string, overview?: string,
               ids?: { tmdb?: number, imdb?: string }, genres?: string[], year?: string }
Episode    = { season: number, number: number, ref: string, title?: string,
               still?: string, overview?: string, airDate?: string, runtimeMinutes?: number }
Season     = { id: string, ref: string, title: string, number?: number, current?: boolean }
Stream     = { url: string, mime?: string, headers?: Record<string, string>,
               subtitles?: { lang: string, url: string, format?: "vtt" | "srt" }[],
               audioTracks?: { lang: string, url: string, label?: string }[],
               durationMs?: number, expiresInSeconds?: number,
               drm?: { type: "widevine", licenseUrl: string, licenseHeaders?: Record<string, string> } }
```

**How the pieces connect.** A `movie` item's `ref` goes to `resolve`. A `series` item's `ref` goes to
`episodes`, and each episode's `ref` goes to `resolve`. A `live` item's `ref` (apiVersion 2, see
[Live channels](#live-channels-apiversion-2)) goes to `resolve` too, and its Stream plays as live. A
row's `ref` goes to `browse`, and so does each page's `next`.

**Seasons.** Two shapes, and your `episodes` answer says which. When every season of a show is in
one list, give each episode its `season` and leave `seasons` out: Kino reads the seasons from the
episodes and shows a selector that only filters the list. When your source keeps each season as its
own `series` item (its own `id` and `ref`, as a search would list it), return only that season's
episodes and list every season of the show in `seasons`, the one you are answering for included:
`{ id, ref, title, number?, current? }`, with `title` what the selector shows ("Temporada 2") and
`current: true` on the season being listed (Kino also recognizes it by `id`). Kino shows the seasons
as chips; choosing another one calls `episodes` with that season's `ref` and opens it as that title,
with its own progress in the library. `seasons` is optional and new in this revision of apiVersion 1:
a plugin that never returns it keeps working exactly as before.

**Genre (Categorías and the En vivo filter).** A Home `Row`, a live `LiveCategory` and a `playlist` may carry an optional
`genre` from a closed list of ten ids: `peliculas`, `series`, `anime`, `infantil`, `documentales`, `deportes`, `noticias`,
`musica`, `entretenimiento`, `otros` (Kino shows their Spanish names). It is what lets Kino line up categories from
different plugins: the Categorías tab groups the browsable Home rows (those with a `ref`, when you declare `browse`) of
every plugin by genre, and En vivo can be filtered by genre across all providers. A value outside the list is ignored,
never an error, and without a `genre` Kino guesses from the row's or group's title ("Deportes", "Noticias Colombia",
"Kids", "Películas de acción"…), so setting it is worth it when your titles do not say it. On a `playlist` the genre is
the default for every group of the list (a guess from each group's own title is used when the playlist has none).
Kino versions before this field ignore it.

**Paging ("Ver más").** If you declare `browse`, a Home row with a `ref` gets a "Ver más" card that
opens a grid: Kino calls `browse(ref, null)`, then `browse(ref, next)` while the person scrolls and
you keep returning a `next`. `search` may also return a `Page`; its `next` puts "Ver más resultados
de <name>" under your results, and Kino calls `search` again with the same query and `cursor: next`.
A `next` (and a row's `ref`) is only kept when you declare `browse`; without it Kino drops them with a
line in the log. Cursors are opaque to Kino: a page number, an offset, a URL, at most 2048
characters.

**`id` is stable, `ref` may change.** `id` is the identity of a title: the person's library,
progress and "Continuar viendo" hang off it, so it must be the same every time the same title comes
back, in every search and on every Home refresh. `ref` is opaque to Kino: it is just what your
`episodes`/`resolve` need to find the title again. It may differ from one call to the next (sources
re-issue links), and Kino can hand you a `ref` you returned earlier, for example the one saved with a
title in the person's library. So make refs that keep working; if your source's links expire, put
something stable in the `ref` (an id) and look the fresh link up inside `resolve`.

**Kino is strict, and forgiving with lists.** Every list is checked entry by entry: a bad entry is
dropped (with a line in the log) and the rest survive; anything over a cap is cut. A `Stream` is
all or nothing.

| Thing | Rules |
| --- | --- |
| `search` result | At most 100 items (an `Item[]`, or a `Page`). |
| `browse` result | A `Page` of at most 100 items. |
| `home` result | At most 20 rows of at most 60 items each. A row needs a unique `id` (same pattern as an item id) and a non-blank `title`; rows with no valid items are dropped. Kino shows them after its own rows, labelled with your plugin's name, and caches them for 6 hours (stale rows show while it refreshes; an answer with no valid rows, or over 2 MB, is not cached and is asked again next time). If `home()` fails you contribute no rows and Home is not blocked. |
| `episodes` result | At most 5000 episodes. `number` is required and from 1 to 99999 (an episode numbered 0, such as a special, is dropped). `season` should be from 1 to 999; a missing or out-of-range season becomes 1. `ref` is required. A repeated season and number is dropped. Without a `title`, Kino shows "Capítulo N". |
| `seasons` (in the `episodes` result) | Optional; at most 50. Each needs an `id` (same pattern as an item id; a repeated one is dropped), a non-empty `ref` of at most 4096 characters and a non-blank `title` (up to 200 characters), or it is dropped. `number` from 1 to 999 and `current` a boolean; a wrong one is ignored, not the season. Anything that is not a list is ignored. |
| `id` | `^[A-Za-z0-9._~-]{1,128}$`. Anything else drops the item, so if your source's own ids have other characters (spaces, `/`, `:`, `%`), derive a stable id yourself, such as a slug. Repeated ids in one list are dropped. |
| `ref` | A non-empty string of at most 4096 characters. |
| `kind` | `"movie"`, `"series"` or (apiVersion 2) `"live"`. A `series` item from a plugin that does not declare `episodes` is dropped: it could never be opened; a `live` item from an apiVersion 1 plugin is dropped too (see [Live channels](#live-channels-apiversion-2)). |
| Text fields | `title` is required and non-blank, up to 200 characters. `overview` up to 2000; `lang` and `quality` up to 20 (for example `"es"`, `"1080p"`); `year` up to 10 (a number is accepted and converted). Longer text is cut; the text of `SeriesInfo` and `Episode` is cut the same way (200 characters for titles, 2000 for overviews). |
| Extra item fields | All optional; a wrong one is ignored, not the item. `genres` at most 5, each at most 30 characters; `badges` (shown as chips, e.g. `"HD"`, `"Latino"`) at most 3 of at most 20; `rating` from 0 to 10; `runtimeMinutes` from 1 to 1000; `ids.tmdb` a positive integer (Kino uses it to match your title with TMDB, to find it again from search, and to enrich its info page -- see below); `ids.imdb` matches `^tt\d{5,10}$` (also enriches a movie's info page when you have no `ids.tmdb`). An episode's `airDate` is `YYYY-MM-DD`. |
| `adult` | An item with `adult: true` is dropped: Kino has no place behind its 18+ lock for plugin titles yet. |
| Images | `poster`, `backdrop` and `still` must be `http` or `https` URLs of at most 2048 characters, or they are ignored. Images are loaded by Kino directly and are **not** checked against `hosts` (they are display only), and Kino does not send your headers or cookies with them. This is the one exception to the host rule, with one limit: an image on the home network, a private or reserved IP address, or a local name (`localhost`, `.local`, `.lan`, …) is ignored too, unless it is on a server the person typed in your settings. A public IPv4 address is fine. |

**`ids.tmdb` enriches the info page, not only matching.** When TMDB has this exact title (matched by
`ids.tmdb`, or by `ids.imdb` on a movie when you gave no `ids.tmdb`), opening it adds three kinds of
field, each filled in differently:

- **Only TMDB has these, so they always come from it:** a tagline, the director or (for a series)
  creator, the cast and the age rating.
- **TMDB wins whenever it has an answer; yours is only the fallback for what TMDB left blank:** the
  year and the genres. A title with its own year or genres still shows TMDB's once matched, not its
  own.
- **Yours wins when you gave one; TMDB only fills the gap:** the synopsis (only replaced if yours was
  empty), the rating (only if you left it out), and a movie's runtime (only if you left it unset --
  a series' runtime is never touched either way, TMDB's included; it prints per episode, not for the
  whole show).

It does **not** add a poster, a backdrop or seasons from TMDB -- those stay exactly what your
`Item`/`SeriesInfo`/`episodes` answer gave, or blank if you left them out.

**The `Stream` rules.**

- `url` must be `https` and its host must be one of your `hosts`, and so must the host of every
  subtitle URL, or it must be on a server the person typed in your settings (exactly that scheme,
  host and port). The one other way to plain `http` is a host you declared
  `{ "host": "…", "insecureHttp": true }` (apiVersion 2, [above](#declaring-an-insecure-host-apiversion-2)):
  that host, exactly, accepts `http` for the stream, its subtitles, its audio tracks and its
  license. A stream that breaks this is refused as a whole; a bad subtitle is dropped and the
  stream still plays.
- **A host you forgot may be asked about, once.** When the person opens a title in the player and
  the only thing wrong with your `Stream` is that a URL (the video, its license, a subtitle or an
  audio track) is on an `https` host you did not declare, Kino asks them in the moment ("El video
  está en `<host>`, un servidor nuevo para este plugin. ¿Permitir?"), the same dialog a `kino.fetch`
  to an undeclared host gets. "Permitir" adds that host to your plugin's approved hosts (within the
  20-host limit) and the video plays; "Rechazar" (or Back) is remembered for your plugin -- the
  video fails as described above, a subtitle or audio track is dropped -- and that host is never
  asked about again until the person chooses "Olvidar rechazos de host". An IP address, a local
  name, plain `http` or a stream broken in any other way is never asked about, and nothing is
  asked when nobody is watching (a download resolves exactly as before). Don't rely on it: declare
  the hosts your streams use.
- **The person may let your video come from any server** (the *broad video permission*). The
  video, subtitle and audio dialogs of a movie or an episode (at resolve time, and when the player
  meets a new host mid-playback) have a third choice, "Permitir video de cualquier servidor". It is
  the person's own grant, shown and revocable in Ajustes ▸ Plugins ("Puede reproducir video desde
  cualquier servidor", "Quitar permiso de video amplio"); the one way for you to ask for the same rule
  up front is [`streamHosts: "any"`](#playing-from-any-server-streamhosts-apiversion-4) (apiVersion
  4), approved on the consent sheet, and with it no video host is ever asked about.
  An update or a reinstall keeps it; uninstalling drops it. While it is on, **in the player only**,
  your movie or episode `Stream` is checked the way a live channel's is under
  [`liveStreamHosts: "any"`](#channels-from-any-server-livestreamhosts-apiversion-3) -- the same
  single rule: its `url`, everything its manifest names, every redirect hop, **and** its
  `subtitles` and `audioTracks` may be on any public host, over `http` or `https`, a public IPv4
  address included -- and no video host is ever asked about again for your plugin. It never
  covers: the home network (private, loopback, link-local and CGNAT addresses, IPv6 literals,
  local names, a public name that resolves into the LAN); a `drm` block's `licenseUrl` (still your
  hosts only, asked about as above); `kino.fetch` (your own code still reaches only your hosts and
  the ones approved one by one); live channels (their own rule). A download of a movie or an episode
  follows the player's rule, so it is covered too. It exists for sources whose hosters change domain per video
  or mid-playback; a plugin with a fixed CDN should still declare it.
- `mime` is optional, of the form `video/mp4` (anything else refuses the stream). When it is missing
  Kino's player detects HLS, DASH or a plain file from the URL and the content.
- **Everything the player fetches for the stream follows the `kino.fetch` host rules.** That covers the
  `url` itself, the variants, segments and `#EXT-X-KEY` keys an HLS manifest names, the `BaseURL`s of a
  DASH manifest, the subtitles, and every redirect hop of any of them: each must be `https` on one of
  your `hosts` (or `http` on one you declared `insecureHttp`), never an IP address or a local name,
  and a declared name that resolves inside the person's own network is refused. A request that breaks this fails before it leaves the device and
  playback stops with an error, so a manifest that points at another CDN needs that CDN in `hosts`.
- `headers` are sent with every one of those player requests (the stream, its manifest's segments and
  keys, its subtitles, and redirect hops, all on your `hosts`) and, if you declare `download`, with
  the request that saves the stream to the device — and nowhere else. At most 20; names are letters, digits and
  hyphens; values are at most 4096 characters with no line breaks; `Host`, `Content-Length`,
  `Transfer-Encoding` and `Connection` are ignored.
- `subtitles`: at most 30, each `{ lang, url, format? }`. `lang` is a short language code such as
  `"es"` (up to 20 characters; blank becomes `"und"`), `format` is `"vtt"` or `"srt"`.
- `audioTracks`: at most 8, each `{ lang, url, label? }` -- a dub or an alternate mix your source
  serves as its own file, separate from the video. Checked exactly like a subtitle: `url` must be
  `https` on a declared host, or the person's own server exactly as typed; a bad entry is dropped and
  the rest of the stream still plays, and so is a `url` already listed (the first entry wins). `lang` up to 16 characters (blank becomes `"und"`); `label`, up
  to 40 characters, is shown in the audio menu verbatim when given, instead of a name guessed from
  `lang`. Kino merges each one into the video and offers it, auto-picked by the person's audio
  preference, in the same menu as the container's own embedded tracks. A stream with no `audioTracks`
  plays exactly as it always has. Example, a source that dubs into two languages:
  ```js
  return {
    url: videoUrl,
    audioTracks: [
      { lang: "es-419", url: dubUrl("es"), label: "Español (Latinoamérica)" },
      { lang: "en", url: dubUrl("en") },
    ],
  };
  ```
- `durationMs` is optional, in milliseconds.
- `expiresInSeconds` (30 to 86400) says when your URL may stop working. If playback fails after that
  long, Kino calls `resolve` once more and continues where the person was.
- **DRM only when declared.** A stream carrying any of `drm`, `license`, `licenseUrl`, `drmLicenseUrl`,
  `keySystem` or `widevine` is refused ("El video tiene DRM y los plugins no lo soportan") -- unless
  your manifest declares the `drm` capability (apiVersion 2) and the only such key is a `drm` block
  `{ type: "widevine", licenseUrl, licenseHeaders? }`: then Kino plays it as Widevine. `licenseUrl`
  is checked exactly like `url` (https on one of your `hosts`, or the person's own server), and
  `licenseHeaders` are filtered like `headers` (at most 20) and sent with the license request only.
  The other five keys are refused even next to a valid `drm` block. See
  [A Widevine-protected stream](#a-widevine-protected-stream-apiversion-2).

#### Live channels (apiVersion 3)

With the `channels` capability ([§3](#channels-in-the-en-vivo-tab-apiversion-3)) Kino calls three
more functions. Their arguments:

- `liveCategories()` gets `null`.
- `liveChannels({ categoryId, cursor })` gets the `id` of one of your categories, and `cursor` `null`
  for the first page or the `next` of the page before.
- `guide({ channelIds, from, to })` gets at most 50 of your channel ids and a window of at most
  24 hours: `from` and `to` are epoch milliseconds.

They return:

```ts
LiveCategory = { id: string, title: string, country?: string, adult?: boolean, genre?: Genre }
Playlist     = { playlist: { url: string, format: "m3u", headers?: Record<string, string>,
                             streamHeaders?: Record<string, string>, genre?: Genre,
                             epg?: { url: string, format: "xmltv" }, refreshHours?: number,
                             hideGroups?: string[], resolve?: boolean } }
LiveChannel  = { id: string, title: string, categoryId?: string, ref?: string, stream?: Stream,
                 logo?: string, number?: number, adult?: boolean }
GuideEntry   = { channelId: string, title: string, start: number, end: number, description?: string }
```

A plugin can give its channels in three ways, and mix them:

1. **A channel with a `ref`.** The `ref` goes to `resolve(ref)` when the person plays it, exactly
   like a `live` item's, and its Stream plays as live.
2. **A channel with an inline `stream`.** A `Stream` checked by the same rules as `resolve()`'s
   answer; it plays with no call to your plugin. A channel whose `stream` is refused is dropped. With
   both `ref` and `stream`, the stream plays and the `ref` is only the fallback. A channel with
   neither is dropped. Some channels only answer a known player: give the `Stream` a `headers` with
   the `User-Agent` (or `Referer`) it insists on, and the player sends it with every request for that channel.
3. **A playlist.** Put `{ playlist: { ... } }` entries next to your categories in the
   `liveCategories()` answer (or return one alone). Kino downloads the M3U list itself, and its XMLTV
   guide from `epg.url`, and groups the entries into categories. Both URLs must be `https` on one of
   your `hosts` (or `http` on one declared `insecureHttp`, or a server the person typed), always:
   a playlist on another host is dropped, and an `epg` on another host only loses the guide.
   `headers` go with those downloads. `streamHeaders` are what the **player** sends for every channel of
   the list, for the channels that only answer a known `User-Agent` (or a `Referer`): they are filtered
   like a Stream's `headers` and kept apart from `headers` on purpose, because those carry your list's own
   credentials and go only to the list's host, never to the many hosts the channels are on. A header an
   M3U entry names itself (`#EXTVLCOPT:http-user-agent=...`) wins. Kino versions before the one that added
   `streamHeaders` ignore the field, so the list plays without it. `refreshHours` is 1 to 168 (default 12); `hideGroups` lists
   group titles not to show (case doesn't matter, at most 50). With `resolve: true`, each entry plays
   through your `resolve(<entry url>)`, for lists whose links need a fresh token. At most 10 per
   answer.

   Each entry gets a channel code, the key of favourites and recents: its `tvg-id` when that is a
   valid id and the entry is the **first of the list to use it**, else one made from its URL and
   name. A later entry repeating a `tvg-id` never moves the first one's code, but it gets a
   URL-and-name code itself, which changes (and its favourites and recents stop matching) when its
   URL does; a copy inserted *before* the first one takes the `tvg-id` code over. Give every entry a
   stable, unique `tvg-id`; `node sdk/run.mjs live playlist <list>` lists the repeated ones.

The rules:

- Times are epoch milliseconds.
- At most 200 categories (playlists don't count), and at most 500 channels per `liveChannels` page.
  `id` follows the item `id` pattern; an `id` starting with `~` is reserved for Kino's own playlist
  entries and dropped. A repeated `id` in one answer is dropped. `title` is required.
- `country` is an ISO 3166 two-letter code (`"CO"`), informational; anything else is ignored.
  `number` is 1 to 9999 (anything else counts as no number); `logo` follows the poster rules;
  `categoryId` is optional and informational (a channel is listed under the category
  `liveChannels` was asked for); one that is not a valid id becomes empty.
- Kino pages `liveChannels` until `next` is missing, repeats, or brings nothing new, at most 10
  pages per category.
- Kino caches your categories and channels for 1 hour and your guide for 30 minutes.
- `guide` is optional. Kino keeps entries for the channels it asked for, with `end` after `start`,
  inside the window, at most 100 per channel and one per start time. A `guide` that fails or is not
  exported is simply not asked again for 30 minutes: your channels still list.
- An `adult: true` category or channel is dropped.

### Errors people understand

A plain `throw new Error("…")` reaches the person as a generic failure of your plugin. When the
failure is one of the usual ones, throw a typed error instead and Kino says it properly, in Spanish, with your plugin's
name:

```js
if (r.status === 401) throw kino.error("auth_required", "la sesión venció");
```

<!-- contract:errors:start -->
| `kino.error` code | What the person sees |
| --- | --- |
| `auth_required` | "Configura {plugin} en Ajustes ▸ Plugins", with a button to its Configurar screen |
| `not_found` | "No se encontró en {plugin}" |
| `geo_blocked` | "Este contenido no está disponible en tu región" |
| `rate_limited` | "{plugin} está limitando las peticiones; intenta en unos minutos" |
| `unavailable` | "{plugin} no está disponible ahora" |
<!-- contract:errors:end -->

Your message is a detail for the log (cut at 200 characters); the person reads Kino's sentence. An
unknown code becomes a plain error.

## 5. The `kino` API

`kino` is a global object, frozen, always there. Nothing else from the outside world is.

```js
kino.apiVersion   // 2 -- the highest apiVersion this build of Kino understands, not your manifest's
kino.appVersion   // the version of Kino, for example "1.42.0"
kino.lang         // "es-CO"
```

Kino also provides the web globals QuickJS lacks, written in JavaScript and frozen: `URL`,
`URLSearchParams`, `atob`, `btoa`, `TextEncoder` and `TextDecoder` (UTF-8 only). They behave like the
browser's (checked against Node on a corpus of cases), except that `URL` does not convert
international domain names to punycode.

### `await kino.fetch(url, options?)`

```js
const r = await kino.fetch("https://archive.org/metadata/" + encodeURIComponent(id), {
  method: "GET",              // GET (default), POST, PUT, PATCH, DELETE or HEAD
  headers: { Accept: "application/json" },
  body: "a=1&b=2",            // see "Bodies" below; sent only with POST, PUT and PATCH
  redirect: "follow",         // or "manual": get the 3xx itself, with its Location header
  cookies: true,              // false: neither send nor store cookies for this request
  timeoutMs: 20000,           // default 15000, at most 30000
});
r.ok        // true for 200 to 299
r.status    // the HTTP status
r.url       // the final URL, after redirects
r.headers   // { "content-type": "...", ... }: names in lowercase, repeated headers joined with ", "
r.text()    // the body as a string (already downloaded)
r.json()    // JSON.parse of the body
r.base64()  // the body bytes as base64: for anything that is not text
```

Kino hands your code either the text or the bytes of a body, depending on its `Content-Type`; the
other form is converted inside the engine when you ask for it, which for a body of several MB takes
seconds of your call's time. Ask for the form the content is.

- **Bodies.** A string is sent as is (`text/plain` unless you set `Content-Type`).
  `{ json: value }` sends `JSON.stringify(value)` as `application/json`; `{ form: { a: 1 } }` sends
  `application/x-www-form-urlencoded`; `{ base64: "…" }` sends those bytes.
- **https only, and only your hosts.** The host of the request and of **every redirect hop** must
  match `hosts` (`*.x` matches subdomains of `x`, not `x`), or be a server the person typed in your
  settings, exactly as typed. A request to anything else fails before it leaves the device. An `http`
  URL on a declared host fails too, unless you declared that host `{ "host": "…", "insecureHttp": true }`
  (apiVersion 2, [section 3](#declaring-an-insecure-host-apiversion-2)). An IP address or a local name (`localhost`, `.local`, …) is always
  refused unless the person typed it. Kino also refuses a declared name that resolves to an address
  inside the person's own network (loopback, private, link-local, carrier-grade NAT, multicast).
- **Redirects** (301, 302, 303, 307, 308) are followed by Kino, up to 10 hops; each hop is checked
  and counted as a request. A 303, or a 301/302 after a POST, turns into a GET without a body. With
  `redirect: "manual"` you get the 3xx answer instead (a login form usually answers 302 on success).
- **A host you forgot may be asked about, during `resolve` and `episodes` only.** When one of those
  calls fetches an `https` host you did not declare (a redirect hop included), Kino asks the person
  ("Quiere conectarse por primera vez a `<host>`. ¿Permitir?"). Your call's time limit stops while
  they decide, and the fetch goes on after "Permitir"; "Rechazar" or Back fails it as
  `host_not_allowed` and is remembered. The question comes down unanswered, with nothing remembered,
  if your call ends first (it failed, timed out, or the person left). `search`, `home`, `browse`, the
  live lists, a download and a call that is already over never ask: the fetch just fails as
  `host_not_allowed`. Don't rely on it: declare your hosts.
- **A non-2xx answer does not throw**: check `r.ok`. Everything else that goes wrong throws an error
  with a `code` you can test (`e.code === "timeout"`):

<!-- contract:fetchErrors:start -->
| `e.code` | When |
| --- | --- |
| `host_not_allowed` | the host (or a redirect hop) is not one you declared or the person typed, or it is `http` on a declared host not marked `insecureHttp` |
| `timeout` | no complete answer within `timeoutMs` |
| `network` | the connection failed, or too many redirects |
| `too_large` | the request over the size cap, or a body over 5 MB |
| `invalid_request` | a bad URL, method, `redirect` or `body`, or more requests than a call allows |
<!-- contract:fetchErrors:end -->

- **Limits:** 15 s per request by default (30 s at most), a body of at most 5 MB (decoded with the
  charset of its `Content-Type`, UTF-8 by default), and at most 60 requests in one call to your
  plugin, redirect hops included.
- **Headers you set** are sent as given, except `Host`, `Content-Length`, `Transfer-Encoding`,
  `Connection`, `Cookie2` and `Accept-Encoding` (Kino asks for gzip itself and always hands you the
  body decompressed; a copied browser `Accept-Encoding` would get you compressed bytes instead). Unless you set `User-Agent`, Kino sends `Kino/<version> (plugin <id>)`.
  A `Content-Type` header sets the type of the body.
- **Cookies:** each plugin has its own cookie jar. Kino stores what your hosts set (`Set-Cookie`
  never reaches your code) and sends it back on later requests, following the usual rules (domain,
  path, `Secure`, expiry). The jar is saved on the device, so a login survives the sandbox and the app
  restarting; it is deleted when the person changes your settings or uninstalls the plugin.
- **A marker from `kino.secret`** in the URL, a header, or the body is swapped for its real value
  right before the request goes out, and that request is then held to a stricter rule than the one
  above: only your manifest's `hosts`, over `https`, on every hop — see
  [Sealed secrets](#sealed-secrets-apiversion-4).

### `kino.secret(name)` (apiVersion 4)

```js
const key = kino.secret("apiKey");   // a marker, not the value; any other name throws
await kino.fetch(`https://api.example.org/v1/list?key=${key}`);
```

A placeholder for a value sealed in your manifest's `secrets` field. Kino opens each seal at most
once per run and swaps the marker for the plain value only where `kino.fetch` sends it (and inside
`kino.crypto`'s key-like fields); nowhere else in your code ever sees it, and anything that comes
back — the response, a cookie, an error, a log line — has it swapped back for the marker, even
before your code first asks for it. Full rules, the
`seal.mjs` tool and the security level: [Sealed secrets](#sealed-secrets-apiversion-4).

### `kino.cookies`

```js
kino.cookies.get("https://site.example/", "session")  // the value, or null
kino.cookies.clear()                                  // forget every cookie of this plugin
```

`get` only answers for URLs your plugin may reach. At most 50 cookies per domain and 64 KB in total.

### `kino.crypto`

Synchronous functions for what sites do to hide their links. Every string argument is text in an
encoding you choose (`utf8`, `hex` or `base64`); errors carry `code: "crypto_error"`.

```js
kino.crypto.hash("sha256", "hola")                        // hex by default
kino.crypto.hmac("sha1", "key", "data", { outputEncoding: "base64" })
kino.crypto.decrypt("aes-128-cbc", { key: "0123456789abcdef", iv: "abcdef9876543210", data: b64 })
kino.crypto.encrypt("aes-256-gcm", { key: k, keyEncoding: "hex", iv: n, ivEncoding: "hex", data: "hola" })
kino.crypto.pbkdf2("sha256", "password", "salt", 10000, 32)   // hex
kino.crypto.randomBytes(16)                               // hex
kino.crypto.uuid()
```

- `encrypt` takes text (`utf8`) and returns `base64`; `decrypt` takes `base64` and returns text.
  Change either with `inputEncoding` / `outputEncoding`; keys, IVs and GCM's `aad` take
  `keyEncoding`, `ivEncoding`, `aadEncoding` (default `utf8`).
- CBC and ECB use PKCS#7 padding unless you pass `padding: "none"`. GCM appends its 16-byte tag to
  the ciphertext, and expects it there to decrypt (as most sites send it).
- A wrong key size, a bad padding or a failed GCM tag throws; it never returns garbage silently.
- A `kino.secret` marker is only accepted as the whole `key` of an AES `encrypt`/`decrypt`, or as
  part of an HMAC `key` or `pbkdf2`'s `password`/`salt` — never in `data`, `iv` or `aad`, nor as a
  `des-ede3` key. See
  [Sealed secrets](#sealed-secrets-apiversion-4) for why.

<!-- contract:crypto:start -->
| Function | Algorithms |
| --- | --- |
| `hash`, `hmac` | `md5`, `sha1`, `sha256`, `sha512` |
| `encrypt`, `decrypt` | `aes-128-cbc`, `aes-192-cbc`, `aes-256-cbc`, `aes-128-ecb`, `aes-192-ecb`, `aes-256-ecb`, `aes-128-ctr`, `aes-192-ctr`, `aes-256-ctr`, `aes-128-gcm`, `aes-192-gcm`, `aes-256-gcm`, `des-ede3-cbc`, `des-ede3-ecb` |
| `pbkdf2` | `sha1`, `sha256`, `sha512` |
| encodings | `utf8`, `hex`, `base64` |
<!-- contract:crypto:end -->

### `kino.sleep(ms)` and `kino.error(code, message?)`

`await kino.sleep(1500)` waits 0 to 5000 ms (for a site that rate-limits you); the time counts
inside the call's own limit. `kino.error` builds the typed errors of
[section 4](#errors-people-understand).

### `kino.config`

```js
kino.config.get("server")   // a setting's value: a string, or true/false for a toggle
kino.config.all()           // every setting that has a value, as an object
```

Read-only: the values the person saved, or the `default` of a setting they left alone. A setting
with no value and no default is `undefined`.

### `kino.html.select(html, css)`

Parses `html` and returns `[{ text, html, attrs }]` for every element matching the CSS selector
(Jsoup's selector syntax): `text` is its text, `html` its inner HTML, `attrs` an object of its
attributes. Only the first 2,000,000 characters of `html` are read, at most 500 elements come back,
and it throws if the combined text and HTML of the matches goes over 5,242,880 characters (5 MB). A
selector longer than 10,000 characters throws `Error("selector CSS demasiado largo (más de 10000 caracteres)")`. **It
exists only inside Kino**: the Node kit's version throws, so test anything that uses it in the app.

### `kino.storage`

```js
kino.storage.get("key")                          // the string, or null
kino.storage.set("key", "v")                     // values are converted to strings
kino.storage.set("key", "v", { ttlMs: 3600000 }) // expires after that many milliseconds
kino.storage.remove("key")
kino.storage.keys()                              // every key, as an array (expired keys are already gone)
```

Synchronous, private to your plugin, and it survives restarts of the sandbox and of the app. At most
256 KB in total (measured as the JSON of all keys and values); going over throws
`Error("almacenamiento del plugin lleno (256 KB)")`. It is deleted when the person uninstalls the
plugin, and it is **not** cleared when they change your settings.

`set`'s third argument is optional: leave it out for a permanent entry, exactly as before this option
existed. Give `{ ttlMs }` to make the entry expire -- after that many milliseconds `get` returns `null`
and `keys()` no longer lists it, even across a restart of the app. `ttlMs` must be a whole number
greater than 0 and at most 2,592,000,000 (30 days); anything else throws before your entry is
touched, the same way an oversized value already does. An expired entry never counts against the
256 KB cap: it is dropped the next time your plugin reads or writes storage. Example, a Home row
cached for an hour:

```js
export async function home() {
  const cached = kino.storage.get("home-rows");
  if (cached) return JSON.parse(cached);
  const rows = await buildHomeRows();
  kino.storage.set("home-rows", JSON.stringify(rows), { ttlMs: 60 * 60 * 1000 });
  return rows;
}
```

### `kino.log(...args)`

Also `console.log`, `console.info`, `console.warn` and `console.error`: they all go to the log
(tag `KinoPlugin` in `adb logcat`), objects are written as JSON, and a message is cut at 2000
characters. Under the Node kit they go to stderr.

### `kino.rank`

For a search backend that only matches a loose bag of shared words rather than a title as a whole:
asking it a long title can return twenty unrelated results that merely share one common word, with
the real match buried on page two. These three pure functions make a backend like that behave like a
title search, without touching its own JSON shape.

```js
kino.rank.shortQuery(query)
kino.rank.sortBySimilarity(items, query, getTitle?)
kino.rank.filterRelevant(items, query, getTitle?)
```

- **`shortQuery(query)`** returns the title's HEAD, up to its first `:`, `,`, `|`, en dash or em
  dash: ask your backend that instead of the whole title, so its own ranking has less noise to sort
  through. A one- or two-letter head ("El", "A") identifies nothing, so the whole (trimmed) text
  comes back instead; a plain `-` is never a cut point (it would split "Spider-Man"). Try it against
  your backend first -- some do worse with a short query, not better.
- **`sortBySimilarity(items, query, getTitle?)`** reorders `items` so the ones sharing the most words
  with `query` come first; ties keep the backend's own order.
- **`filterRelevant(items, query, getTitle?)`** drops items that only share a stray word with
  `query`. Reordering alone still shows a full page of near-misses when the title genuinely is not on
  the backend; this makes an absent title come back with 0 results instead.

`query` is a title, or an array of several forms of one worth trying together --
`[query.q, query.originalTitle, ...query.altTitles]`, since a backend may only know a title in one
language. `getTitle` reads a title off one of your own `items`; it defaults to
`(item) => item.title`, and may itself return an array the same way `query` can, when an item keeps
a title in more than one field or language (every form's words are combined). Matching folds accents
and case and ignores words of 1-2 letters (the "el", "de", "of" that make unrelated titles look
alike); `filterRelevant` keeps an item once it shares at least 60% of a requested title's distinctive
words.

**Bad input never throws.** Unlike `kino.fetch`/`kino.crypto`/`kino.sleep`, these three never raise a
`kino.error` for a malformed argument: `items` that is not an array answers `[]` from either
function. An item with no usable title -- `null`, `undefined`, `getTitle` returning something that is
not a string (or an array with none in it), or `getTitle` itself throwing -- is treated as "no title"
rather than crashing your call: `filterRelevant` drops it like an actual near-miss, and
`sortBySimilarity` sorts it after every item that does have one, in your list's own order among
themselves.

```js
export async function search(query) {
  const titles = [query.q, query.originalTitle, ...query.altTitles];
  const r = await kino.fetch(BASE + "/search?q=" + encodeURIComponent(kino.rank.shortQuery(query.q)));
  const found = r.json().results; // whatever shape your backend answers with
  const relevant = kino.rank.filterRelevant(found, titles, (x) => x.name);
  return kino.rank.sortBySimilarity(relevant, titles, (x) => x.name).map(toItem);
}
```

If your backend already ranks a full title well, skip `shortQuery` and run only
`filterRelevant`/`sortBySimilarity`, on what it gives you for `query.q` as typed.

Two things left out on purpose. Neither retries with the full title: if `shortQuery`'s head happens
to be a common word (e.g. "Love, Death & Robots" -> "Love") and the backend returns nothing relevant
for it, retry `search` with the full title yourself when the short one comes back empty. And neither
does anything with season numbers or ordering: how a backend spells "season 2" in its own titles
("T2", "Temporada 2", …) is specific to that backend, not something these can fold in.

## 6. Limits and engine quirks

### Every number in one place

<!-- contract:limits:start -->
| What | Limit |
| --- | --- |
| Manifest / entry file / icon | 16 KB / 1 MB / 128 KB |
| Memory / stack, per plugin | 64 MB / 1 MB |
| Time per call | `search` 15 s; `home`, `browse`, `episodes`, `resolve` 20 s each (`resolve` of a plugin converted from a Nuvio scraper: 45 s); `liveCategories`, `liveChannels`, `guide` 20 s each; counting all your fetches and sleeps together, but not the time the person spends answering a host question for that call |
| Loading the module (its top level) | 10 s |
| Idle sandbox | closed after 5 minutes without calls |
| Consecutive timeouts | 3 in a row and Kino disables the plugin ("No responde") |
| `kino.fetch` | https only (or the person's own server as typed, or `http` on a host declared `insecureHttp`); 15 s default, 30 s maximum; response body at most 5 MB; the request (URL, headers and body) at most 1,048,576 characters; at most 60 requests per call; at most 10 redirects per request |
| Cookies | 50 per domain, 64 KB in total per plugin |
| `kino.storage` | 256 KB per plugin; an entry's optional `ttlMs` is 1..2,592,000,000 ms (30 days) |
| `kino.sleep` | 0 to 5,000 ms per call |
| `kino.crypto` | data at most 5 MB per call; PBKDF2 at most 100,000 iterations and 64-byte keys; `randomBytes` at most 1,024 |
| `kino.log` / `console.*` | 2,000 characters per message |
| What a function returns | at most 2,000,000 characters once turned into JSON |
| Results | `search` 100 items; `home` 20 rows of 60; `browse` 100 per page; `episodes` 5,000 (and 50 `seasons`); `ref` 4,096 characters; `next` 2,048 characters; `id` matches `^[A-Za-z0-9._~-]{1,128}$` |
| Live channels (apiVersion 3) | `liveCategories` 200; `liveChannels` 500 per page and 10 pages per category; `guide` 50 channels and 24 h per call, 100 entries per channel; `number` 1..9999 |
| Settings | at most 12; `text` 500, `url` 2,048, `password` 500 characters |
| Error messages | your `kino.error` message is shown as a detail, cut at 200 characters |
| `hosts` | 1 to 20 entries; from apiVersion 2, none (`[]`) when a `url` setting exists |
| `secrets` (apiVersion 4) | at most 16; names match `^[A-Za-z][A-Za-z0-9_]{0,31}$`; a value is 1..4,096 bytes |
<!-- contract:limits:end -->

### How your code lives

- **One call at a time.** Calls to the same plugin run one after another. The sandbox is reused
  between calls, but Kino throws it away after 5 idle minutes, after a timeout, when a call is
  cancelled (for example a newer search replaces an older one), and when the plugin is updated or
  disabled. Module-level variables are a cache at best: keep anything that must survive in
  `kino.storage`.
- **Load-time code.** When it installs your plugin, Kino loads the module once in a throwaway sandbox
  without network access, to check that every declared capability is an exported function. Keep the
  top level to declarations: a network call there fails, and the install with it.
- **Errors reach people.** If your function throws, that call fails and the person sees an error that
  names your plugin, and the text of your `Error` can be part of it. Write those messages for a
  person, in Spanish, short.
- **Runaway code.** Running out of memory or stack fails the call. A synchronous infinite loop
  (`while (true) {}`) **cannot be interrupted**: at the time limit Kino stops waiting for the call
  and discards the sandbox, but the loop keeps spinning on its own thread until it ends, which for a
  real infinite loop means until the app is closed. Three timeouts in a row disable the plugin.
- **App closed during a call.** A crash inside the engine, or being killed for memory, can take the
  whole app down mid-call, and nothing in-process can catch that. Kino notices at the next start:
  whichever plugins were mid-call at that moment each get an unclean exit counted against them —
  **including a healthy plugin that simply happened to be running at the same time**, not only the
  one that actually caused the crash. Two unclean exits in a row for the same plugin, with no call
  finishing normally in between, switch it off ("No responde") exactly like three timeouts in a
  row; a call that completes normally resets its count.

### The engine is not Node and not a browser

Plugins run in QuickJS. It handles modern JavaScript: `async`/`await`, classes with fields, `?.` and
`??`, regular expressions with lookbehind, named groups and `\p{L}` under the `u` flag, template
literals, spread, `replaceAll`, `Array.prototype.at` and `flat`, `Object.fromEntries`,
`Promise.allSettled`, `Map`, `Set`, `BigInt`. It does **not** have the platform around it:

- **Missing globals** (`typeof` is `"undefined"` inside Kino): `setTimeout`, `setInterval`,
  `setImmediate`, `queueMicrotask`, `Buffer`, `process`, `require`, `fetch`, `AbortController`,
  `structuredClone`, `performance`, `crypto`, `WeakRef` and `Intl`. Use `kino.sleep` to wait,
  `kino.fetch` instead of `fetch` and `kino.crypto` instead of `crypto`. `URL`, `URLSearchParams`,
  `atob`, `btoa`, `TextEncoder`, `TextDecoder` and `console` do exist: Kino provides them.
- **Node has almost all of those**, so code that runs fine under the Node kit can still fail in Kino.
  Before you publish, search your file for the names above.
- **Locale-aware methods do not localize:** `localeCompare` ignores its locale and options (so
  `{ numeric: true }` and `{ sensitivity: "base" }` do nothing; it compares code units), and
  `(1234.5).toLocaleString("es-CO")` gives `"1234.5"`. Write the comparison you need; the reference
  plugin has a small `natural()` for numbered names.
- **Keep function names short.** A function name of millions of characters makes the engine's
  native code crash the whole app. As a best-effort guard, `kino.*`, `console.*`, the web globals and
  the other functions Kino provides are frozen, and on any function `Object.defineProperty`,
  `Object.defineProperties`, `Reflect.defineProperty` and `__defineGetter__`/`__defineSetter__`
  refuse to set `name` to a string longer than 1000 characters, to a getter or setter, or to make
  it writable: they throw a `TypeError` (`Reflect.defineProperty` returns `false`). The guard is not
  airtight (a huge computed key still names a function); a plugin that crashes the app anyway is
  switched off (see "App closed during a call" above). Setting `name` on ordinary objects, and
  `this.name = "MyError"` in an `Error` subclass, work as usual.

### The trap: a rejection nobody is listening to yet

The engine aborts the **whole call** when a promise is rejected before anything has a handler on it,
even if your code is inside `try`/`catch`. The Node kit cannot show you this, so learn the rules:

- **Aborts the call:** a `throw` inside an `async` function **before its first `await`**, while the
  caller is wrapped in `try`/`catch`. A `.catch()` on that call, or `Promise.all`/`Promise.allSettled`
  around it, do not rescue it either. Also aborts: `new Promise((_, reject) => reject(e))` rejected
  right away, and `return Promise.reject(e)` from an `async` function.
- **Is caught normally:** a `throw` after any `await` (even `await null;`), a rejection coming from
  `kino.fetch` or `kino.sleep` (for example a refused host), and `await Promise.reject(e)` or
  `Promise.reject(e).catch(...)` (Kino delays `Promise.reject` by one tick so a handler can attach
  in time).
- If nobody catches the error anyway, it is harmless: the call fails with that error either way, and
  a `kino.error` code still reaches the person correctly.
- **Nuvio-converted scrapers get a workaround:** when Kino converts a Nuvio scraper it rewrites the
  async helpers bundlers emit (esbuild's `__async`, TypeScript's `__awaiter`, Babel's
  `_asyncToGenerator`) so a transpiled function's body starts one tick later, and a throw before its
  first `await` is caught normally. A native `async` function (yours, or an untranspiled scraper's)
  still needs the `await` before anything that can throw.

So in a helper that a caller may wrap in `try`/`catch`, do the `await` first and validate afterwards:

```js
// Wrong: in Kino this throw is NOT caught by the caller's try/catch; it aborts the whole call.
async function getJson(url) {
  if (!url.startsWith("https://")) throw new Error("dirección inválida");
  const r = await kino.fetch(url);
  return r.json();
}

// Right: the first await comes before anything that can throw.
async function getJson(url) {
  const r = await kino.fetch(url);
  if (!r.ok) throw new Error("archive.org respondió " + r.status);
  return r.json();
}
```

(If a helper has nothing to await, start it with `await null;`, or check the input in the caller
before it calls the helper.)

## 7. Test it locally

The Node kit is the `sdk/` folder: `run.mjs` (run one function), `validate.mjs` (check a plugin the
way Kino does), `init.mjs` (scaffold a new one), `kino-shim.mjs` (the `kino` API in Node),
`contract.mjs` (the rules, read from `contract.json`) and `guide-tables.mjs` (regenerates this
guide's tables). There is nothing to install. It needs Node 18 or newer (checked on 18.20, 20.11
and 24.14); `node --test sdk/test/kit.test.mjs` runs its own tests.

```
node sdk/run.mjs ./plugin.js search "metropolis"
node sdk/run.mjs ./plugin.js home
node sdk/run.mjs ./plugin.js browse films 2
node sdk/run.mjs ./plugin.js episodes 'Dragnet1951'
node sdk/run.mjs ./plugin.js resolve 'Dragnet1951|Dragnet/Season 1/Dragnet (1951) - S01E01 - The Human Bomb.mp4'
```

The first argument is your entry file (or the folder that holds `kino-plugin.json`), then the
function, then its argument: the text to search for, the `ref` for `episodes` and `resolve`, or the
`ref` and an optional cursor for `browse`. The runner reads your manifest, provides the `kino`
global, calls that one function the way Kino does, **checks the answer with the app's rules** and
prints what Kino would keep as JSON on stdout; every entry Kino would drop is reported on stderr with
the reason (`--raw` prints your answer untouched). Logs, `console.*` and errors go to stderr, so you can
pipe the result (`... | head -30`, `... | jq`). The exit code is 0 on success, 1 when your code
throws and 2 when the command is wrong. The runner only runs functions your manifest declares.

- `--config key=value` (repeatable) sets a setting; the runner also reads `sdk/config.json`
  (`{ "server": "http://192.168.1.10:8096", "user": "ana" }`; keep it out of git). A required setting
  with no value stops the run with `auth_required`, as in the app.
- `--record fixtures.json` saves every `kino.fetch` answer; `--replay fixtures.json` answers from that
  file only, with no network. Record once, then your tests run offline and always the same (the
  scaffold's `test/plugin.test.mjs` does exactly that).
- `KINO_TYPE=movie|series|any` sets the `type` of the search (default `any`).
- To fill the other fields of the query, pass the whole query as JSON:
  `node sdk/run.mjs ./plugin.js search '{"q":"dragnet","type":"series","year":1951}'`
  (`season`, `episode`, `tmdbId` and `year` are `0` otherwise).
- Under the Node kit `kino.storage` is a file named `.kino-storage.json` and the cookie jar
  `.kino-cookies.json`, both next to your manifest. Add them to your `.gitignore`. Delete them to
  start from scratch. A plugin with `secrets` also reads `.kino-secrets.json`
  (`{ "<name>": "<plain value>" }`) from the same folder: see
  [Sealed secrets](#sealed-secrets-apiversion-4). `node sdk/init.mjs` already lists all three in the
  scaffold's `.gitignore`.
- `node sdk/validate.mjs <folder>` checks the manifest with every rule of section 3 (the same
  Spanish messages the app shows) and that each declared capability is exported, and prints the
  consent sheet's extra lines as the person will read them (the red ones, an `insecureHttp` host or
  `"liveStreamHosts": "any"`, marked "(en rojo)");
  `--run <function> [argument]` also runs it and lists what Kino would drop. With
  `--run liveCategories`, every declared playlist is downloaded and parsed too: one that cannot be
  downloaded or parses to 0 channels is a problem, and its discarded entries are listed. Exit code 0
  means Kino would accept it.
- The `sdk/` folder does not have to live in your repository. Copy it anywhere and run
  `node /path/to/sdk/run.mjs ./plugin.js ...`.
- A stack trace names a temporary `plugin.mjs`: the runner loads a copy of your file so that Node
  treats it as an ES module whatever its version and `package.json` say. The line numbers are your
  `plugin.js`'s.

**Live channels** (apiVersion 3). The `channels` exports run through `live`, with the plugin folder
first:

```
node sdk/run.mjs . live categories
node sdk/run.mjs . live channels noticias
node sdk/run.mjs . live channels noticias 2
node sdk/run.mjs . live guide canal1,canal2
node sdk/run.mjs live playlist https://iptv-org.github.io/iptv/countries/co.m3u
node sdk/run.mjs live playlist ./lista.m3u --epg ./guia.xml.gz
```

- `live categories` calls `liveCategories()` and prints what Kino keeps. Then, for each `{ playlist }`
  in the answer, it downloads the list as the app would (your `headers`, your `hosts` or the person's
  server only, every redirect too) and prints, on stderr, the same summary as `live playlist` and
  the list's groups as the categories people will see.
- `live channels <categoryId> [cursor]` calls `liveChannels({ categoryId, cursor })`, then plays the
  first channel that has a `ref` and no `stream` the way Kino would: it sends that `ref` to
  `resolve()` and checks the answer as a live channel's (so `"liveStreamHosts": "any"` applies). With
  `validate.mjs --run liveChannels`, a refused answer there is a problem.
- `resolve <ref> --live` checks a `resolve()` answer as a live channel's. Without `--live` the kit
  cannot know the `ref` is a channel's and applies the strict rule; when only that stops the URL
  and your manifest has `"liveStreamHosts": "any"`, it says "si este ref es de un canal en vivo,
  prueba con --live".
- `live guide <id,id>` calls `guide()` with those ids and a 24-hour window starting two hours ago.
- `live playlist <url|file>` needs no plugin: it reads any M3U list with Kino's own rules and prints
  `N canales en M categorías; K entradas descartadas; L ocultas (adultos)`, the categories, and the
  first 20 channels as `group › name  url`. With `--epg <url|file>` it also shows what each of those
  20 has on now, or "sin guía". A guide that declares a DOCTYPE is refused, as in the app, and the
  command says so: "La guía declara un DOCTYPE; Kino la rechaza por seguridad". Use it on a list
  before you write a line of plugin.

The kit reads lists and guides with `sdk/live-playlist.mjs`, a copy of the app's readers pinned to
the same test files (`docs/plugins/fixtures/live` in Kino's repository): what it keeps is what Kino
keeps.

**What the Node kit does not reproduce.** Kino is the authority; the kit only approximates it so
you can iterate fast. Before you publish, install the plugin in the app and try it there. The
differences:

- `kino.html.select` throws (it uses Jsoup, which exists only in the app).
- The kit's XMLTV reader is a tolerant regex walk, not the app's XML parser. It gives the app's answer
  on every shared test guide, but on malformed XML in mid-document it may keep more than the app
  (which stops at the first error and keeps what it read up to there).
- The rejection trap of [section 6](#6-limits-and-engine-quirks): Node catches what Kino would not.
- Node has globals Kino lacks (`setTimeout`, `fetch`, `Buffer`, ...): the plugin may pass under Node
  and fail in Kino. Kino's `URL` has no punycode.
- The host, redirect and request-count rules are the same, and so are the cookie rules as far as
  Node's own parsing goes, but there is no refusal of names that resolve to private addresses, bodies
  are always read as UTF-8, and the 15 s timeout covers the wait for the response but not the
  download.
- The per-call time limits, the memory limit and the size caps on requests, answers and selectors
  are not enforced.

## 8. Publishing your plugin

1. **Create a public GitHub repository** and put `kino-plugin.json` and your entry file (for
   example `plugin.js`) at its root, plus an optional `icon.png` and a `README.md`. Add
   `.kino-storage.json` to `.gitignore`. (A plugin can also live in a subfolder; people then type
   `owner/repo/sub/dir`.)
2. **People install it** in Kino from Ajustes > Plugins, typing `owner/repo` in the field
   "usuario/repositorio" and pressing "Agregar". To point at a release, they type `owner/repo@v1.0.0`.
   Tag your releases so that people can pin them.
3. **A private repository cannot be installed.** Kino reads your files from
   `raw.githubusercontent.com` without any credentials, and GitHub answers a private repository with
   "not found". Make the repository public, or the plugin cannot be installed.
4. **To ship an update, raise `version`** (a strictly higher `MAJOR.MINOR.PATCH`; an unchanged or
   lower number is treated as "already up to date", so a fix without a version bump never reaches
   anyone). Kino checks for updates at most once a day per plugin, and when the person taps
   "Buscar actualización".
   - If the new version does not add anything to `hosts`, `permissions`, `download`, `drm` or an
     `insecureHttp` host, and needs a supported `apiVersion`, it is installed silently.
   - If `hosts` or `permissions` grow, or the manifest newly declares `download`, `drm`, or marks an
     already-approved host `insecureHttp`, Kino does **not** apply it: the plugin shows "Actualización
     disponible — requiere tu aprobación" and the person sees the new ones (marked "nuevo") before
     accepting. Removing them needs no approval.
   - A new **required** setting does not block the update: it installs and the plugin shows "Falta
     configurar" until the person fills it in.
   - If the new version needs a higher `apiVersion` than the app supports, the check reports "Este
     plugin necesita una versión más nueva de Kino" and the installed version keeps working.
5. **Give it time.** GitHub serves raw files with a cache of about five minutes (measured:
   `cache-control: max-age=300`), so a change you just pushed can take that long to be visible to an
   install or an update check.
6. **Keep the `id` and the address.** An `id` that is already installed from a different address is
   refused ("Ya hay un plugin con ese id"), so renaming or moving your repository makes it a
   different plugin for the people who installed it.

Before you publish, check that:

- `node sdk/validate.mjs . --run <function> ...` passes for every capability you declare;
- every host your plugin talks to (and every stream and subtitle host) is in `hosts`, including the
  bare domain next to its `*.` form;
- there is no `throw` before the first `await` in a function that a caller wraps in `try`/`catch`;
- your file uses none of the missing globals of [section 6](#6-limits-and-engine-quirks);
- you installed it in Kino and it searches, lists episodes and plays.

### Get found

Kino lists community plugins by searching GitHub for public repositories with the topic
`kino-plugin` (forks are left out).

**Without the `kino-plugin` topic, Kino will not find your plugin.** It is the only way the app
discovers a plugin: a perfect manifest, a public repository and a thousand stars change nothing if the
topic is missing. Put it on **the repository that contains `kino-plugin.json`** (a common mistake:
adding it to another repository by the same author that only holds data, such as an `.m3u` playlist).
Check it: `curl -s https://api.github.com/repos/OWNER/REPO | tr -d ' \n' | grep -o '"topics":\[[^]]*\]'`
must print `"kino-plugin"` inside the list.

**Descriptions.** The card shows the `description` of your **manifest** (up to 300 characters; empty
leaves the card without text), so write one. The GitHub repository description (About) is not read by
the app and does not affect discovery, but set it too:
`gh repo edit OWNER/REPO --add-topic kino-plugin --description "What your plugin does"` does both.

To be listed:

1. On your repository's GitHub page, add the topic `kino-plugin` (About ▸ ⚙ ▸ Topics).
2. Keep `kino-plugin.json` at the root of the repository: Kino reads it to show your plugin's name,
   description, colour and icon, and skips a repository whose manifest is missing or invalid, needs a
   newer `apiVersion` than the person's Kino, or says `"discoverable": false`. A plugin in a subfolder
   can be installed by address but is not searched.
3. Kino keeps the 30 most-starred matches, searches at most every 12 hours per device (and when the
   person taps "Actualizar"), and shows them after the recommended plugins, labelled "De la comunidad".
   Installing one goes through the same consent sheet as any other plugin.

To stay out of the search while keeping the topic, set `"discoverable": false`;
`node sdk/validate.mjs .` then prints "No aparecerá en la búsqueda de Kino".

## 9. What people see

- **The consent sheet.** When someone types your address, Kino shows "Instalar <name>", your version
  and author, the description, the list of hosts under "Se va a conectar con:" (left out when
  `hosts` is empty), and the warning
  "Plugin no verificado: solo instálalo si confías en quien lo hizo." with "Instalar" and "Cancelar".
  If your manifest has a `password` setting it adds "Este plugin usa tu usuario y contraseña"; a `url`
  setting adds "Se conectará a los servidores que escribas en su configuración". Declaring `download`
  adds "Puede descargar videos para verlos sin conexión", `drm` adds "Reproduce video protegido (DRM)",
  `channels` adds "Agrega canales en vivo a la pestaña En vivo", each `insecureHttp` host adds, in red, "Conexión sin cifrar con <host>", and `liveStreamHosts: "any"` adds, in red, "Puede reproducir canales desde cualquier servidor que indique su lista". Nothing of yours runs
  before they accept.
- **Host dialogs.** A `kino.fetch` to an undeclared host during `resolve`/`episodes`, and an
  undeclared host of the video the player opens or meets mid-playback, ask the person ("Rechazar" /
  "Permitir"; the focus starts on "Rechazar"). For a movie's or episode's video, subtitles or audio
  the dialog also offers "Permitir video de cualquier servidor" (see [The `Stream` rules](#what-you-return));
  once chosen, the plugin's details say "Puede reproducir video desde cualquier servidor" next to
  "Quitar permiso de video amplio".
- **When a stream can't play**, the player says why in Spanish, never the player's own English: for
  example "Este aparato no puede reproducir este formato de video (4K/HEVC)", "El servidor del video
  respondió con un error" or "No se pudo reproducir este video". While `resolve` runs it shows
  "Resolviendo fuente <name>…", and after 5 s "… buscando enlaces (N s)".
- **Configurar.** A plugin with `settings` has a "Configurar" button in Ajustes ▸ Plugins. Until
  every required setting has a value its status is "Falta configurar" and nothing of it runs.
- **Ver más.** A Home row with a `ref` ends in a "Ver más" card, and a search page with a `next`
  shows "Ver más resultados de <name>": both open a grid that asks you for the next page as the
  person scrolls.
- **Search, Home and the library.** Your results appear in search under your plugin's name (with your
  `color`), next to the app's own sources; your `home` rows appear on Home after the app's own; your
  titles play in Kino's player and appear in "Continuar viendo" and the library. Titles of a plugin
  that declares `download` can be saved for offline viewing ([section 3](#downloads-apiversion-2));
  Chromecast and DLNA are not available for plugin titles in this version. A `live` item's card
  says "EN VIVO" and plays on tap, with no info page; a channel never enters "Continuar viendo" or
  the library ([Live channels](#live-channels-apiversion-2)). A plugin found through the `kino-plugin`
  topic carries the label "De la comunidad" on its card.
- **Status of each plugin** in Ajustes > Plugins: "Activo", "Desactivado", "Falta configurar", "No
  responde — actívalo para volver a intentar" (three timeouts in a row; the person can re-enable it),
  "Actualización
  disponible — requiere tu aprobación", and "Archivos dañados, reinstálalo" (the installed file no
  longer matches what was installed).
- **Disable and uninstall.** A disabled plugin disappears from search and Home; its titles stay in
  the library and say "Activa el plugin <name> para ver esto". Uninstalling deletes the plugin's
  files, its storage and its cached Home rows immediately, but keeps the person's library titles and
  progress: opening one says "Esto venía del plugin <name>, que ya no está instalado", and installing
  the plugin again restores them. That is one more reason to keep `id` and `ref` handling stable.
  Titles already downloaded keep playing offline and can be removed from Descargas.

## 10. The reference plugin

`kino-plugin.json` and `plugin.js` in this repository are the Internet Archive plugin, with all five
capabilities. It reads about like this:

1. It declares `archive.org` **and** `*.archive.org`: a download URL on `archive.org` redirects to a
   storage node such as `dn720705.ca.archive.org`, and the wildcard does not cover the bare domain.
2. `getJson` does the `await` first and throws afterwards (the rule of section 6).
3. `search` cleans what the person typed: archive.org answers 200 with an error body when the query
   has a stray `/`, `-`, `&` or `'` or a dangling `AND`/`OR`/`NOT`, so it keeps letters, digits and
   apostrophes inside words, drops the operator words, and asks both collections (films and classic
   TV) whatever `type` says, using it only to decide which group comes first; an item that is in both
   is listed once.
4. `home` builds three rows (films, classic TV, classic animation) and wraps each row in its own
   `try`/`catch`, so one failing row does not lose the others; it reports it with `kino.log`. Each row
   carries its own id as `ref`, and `browse(ref, cursor)` pages through the same query 50 at a time
   with the page number as the cursor (`"2"`, `"3"`, …), throwing `kino.error("not_found")` for a row
   it does not know.
5. `episodes` reads the item's file list, keeps the video originals in natural order (a small
   `natural()` comparator, because `localeCompare` cannot be trusted), numbers them from `S01E02`
   in the file name or 1, 2, 3, and uses `"<item>|<file name>"` as each episode's `ref`.
6. `resolve` picks the best playable file (an mp4 derived from the original, or the mp4/webm itself),
   turns sibling `.vtt`/`.srt` files into `subtitles`, and sets `durationMs`.
7. Every URL it builds is `https` on a declared host; posters use
   `https://archive.org/services/img/<id>` and are not host-checked.

`README.md` in this repository says what it does not do (a collection is exposed as a single movie,
episodes numbered 0 are dropped), so do not copy those as intended behavior.

## 11. Cookbook

Three complete shapes, then two short recipes for the apiVersion 2 powers that need a line on the
consent sheet, and three for live channels (apiVersion 3). The first and the third shapes are, nearly line for line, the two reference plugins
Kino's own tests run end to end against a fake server.

### An HTML site with a login and hidden links

The site has a login form, keeps the session in a cookie, lists titles as HTML with a "next" link,
and hides each video URL with AES-128-CBC. The person's user and password are settings.

```json
{
  "id": "mi-sitio", "name": "Mi sitio", "version": "1.0.0", "apiVersion": 1, "entry": "plugin.js",
  "hosts": ["sitio.example", "cdn.example.com"],
  "capabilities": ["search", "home", "browse", "resolve"],
  "settings": [
    { "key": "user", "label": "Usuario", "type": "text", "required": true },
    { "key": "password", "label": "Contraseña", "type": "password", "required": true }
  ]
}
```

```js
const BASE = "https://sitio.example";
const KEY = "0123456789abcdef";
const IV = "abcdef9876543210";

// The cookie jar keeps the session between calls (and across restarts): log in only when needed.
async function login() {
  const probe = await kino.fetch(BASE + "/session", { redirect: "manual" });
  if (probe.status === 200) return;
  const r = await kino.fetch(BASE + "/login", {
    method: "POST",
    body: { form: { user: kino.config.get("user"), password: kino.config.get("password") } },
    redirect: "manual",
  });
  if (r.status === 401) throw kino.error("auth_required", "usuario o contraseña incorrectos");
  if (r.status !== 302) throw kino.error("unavailable", "el sitio respondió " + r.status);
}

function cards(html) {
  return kino.html.select(html, "a.card").map((a) => ({
    id: a.attrs["data-id"], ref: a.attrs["data-link"], title: a.text, kind: "movie",
  }));
}

async function page(path) {
  await login();
  const r = await kino.fetch(BASE + path);
  if (r.status === 429) throw kino.error("rate_limited", "demasiadas peticiones");
  if (!r.ok) throw kino.error("unavailable", "el sitio respondió " + r.status);
  const html = r.text();
  const next = kino.html.select(html, "a.next").map((a) => a.attrs.href)[0];
  return { items: cards(html), next: next || undefined };
}

export async function search(query) {
  return (await page("/buscar?q=" + encodeURIComponent(query.q))).items;
}

export async function home() {
  const first = await page("/catalogo");
  return [{ id: "catalogo", title: "Catálogo", ref: "/catalogo", items: first.items }];
}

export async function browse(ref, cursor) {
  return page(cursor || ref);
}

export async function resolve(ref) {
  await null;
  const url = kino.crypto.decrypt("aes-128-cbc", { key: KEY, iv: IV, data: ref });
  return { url, mime: "video/mp4" };
}
```

`kino.html.select` exists only in the app, so test this one in Kino (or with `--replay` for the
parts that do not parse HTML).

### A JSON API with a token

The API wants a token it gives out for an API key. Keep the token in `kino.storage`, keyed by the
key it came from, and fetch a new one when the API says it expired.

```js
const API = "https://api.example.com/v1";
const tokenKey = () => "token:" + kino.config.get("apiKey");

async function token() {
  await null;
  const saved = kino.storage.get(tokenKey());
  if (saved) return saved;
  const r = await kino.fetch(API + "/token", { method: "POST", body: { json: { key: kino.config.get("apiKey") } } });
  if (r.status === 401) throw kino.error("auth_required", "la clave no sirve");
  if (!r.ok) throw kino.error("unavailable", "la API respondió " + r.status);
  const t = r.json().token;
  kino.storage.set(tokenKey(), t);
  return t;
}

async function api(path) {
  const r = await kino.fetch(API + path, { headers: { Authorization: "Bearer " + (await token()) } });
  if (r.status === 401) { kino.storage.remove(tokenKey()); throw kino.error("auth_required", "el token venció"); }
  if (r.status === 404) throw kino.error("not_found");
  if (r.status === 429) throw kino.error("rate_limited");
  if (r.status === 451) throw kino.error("geo_blocked");
  if (!r.ok) throw kino.error("unavailable", "la API respondió " + r.status);
  return r.json();
}

export async function search(query) {
  const p = await api("/search?q=" + encodeURIComponent(query.q) + (query.cursor ? "&page=" + query.cursor : ""));
  return {
    items: p.results.map((x) => ({ id: String(x.id), ref: String(x.id), title: x.title, kind: "movie", ids: { tmdb: x.tmdb } })),
    next: p.nextPage ? String(p.nextPage) : undefined,
  };
}

export async function resolve(ref) {
  const s = await api("/play/" + encodeURIComponent(ref));
  return { url: s.url, expiresInSeconds: 3600 };
}
```

Manifest: `"hosts": ["api.example.com"]`, `"capabilities": ["search", "browse", "resolve"]` (a
`next` in a search page needs `browse`), and one setting
`{ "key": "apiKey", "label": "Clave de la API", "type": "password", "required": true }`. Since
`browse` is declared it must be exported too; `export async function browse(ref, cursor) { throw
kino.error("not_found"); }` is enough when only search pages.

### The person's own server

A media server at home (Jellyfin, Emby, a NAS…): the person types its address, user and password.
The address becomes an allowed host for that install, `http` and a LAN address included; streams,
posters and stills may point at it. This is the published demo plugin **Tu servidor**
([kinotvapp/kino-plugin-own-server](https://github.com/kinotvapp/kino-plugin-own-server), with a
reference server to run it against), which uses every apiVersion 3 feature a server of your own
can: seasons, `download`, `audioTracks`, `live` items, a `kino.storage` TTL, `kino.rank`,
`ids.tmdb`, and `channels` in all three shapes (channels with a `ref`, channels with an inline
`stream`, and an M3U playlist with an XMLTV guide).

```json
{
  "id": "own-server", "name": "Tu servidor", "version": "1.2.0", "apiVersion": 3, "entry": "plugin.js",
  "hosts": [],
  "capabilities": ["search", "home", "browse", "episodes", "resolve", "download", "channels"],
  "settings": [
    { "key": "server", "label": "Servidor", "type": "url", "required": true, "hint": "http://192.168.1.10:8096" },
    { "key": "user", "label": "Usuario", "type": "text", "required": true },
    { "key": "password", "label": "Contraseña", "type": "password", "required": true },
    { "key": "hd", "label": "Solo HD", "type": "toggle" }
  ]
}
```

`hosts` is empty: the plugin reaches only the server the person types (allowed from apiVersion 2
with a `url` setting, see [The person's own servers](#the-persons-own-servers)). Up to 1.1.1 the
demo declared the placeholder `"tu-servidor.invalid"` for Kino builds from before that rule; 1.2.0
is apiVersion 3, which those builds refuse anyway, so it declares none. Every channel list, guide
and stream is on that same server, so it needs no `"liveStreamHosts": "any"`. Then:

```js
const base = () => String(kino.config.get("server")).replace(/\/+$/, "");

// Everything cached in kino.storage belongs to one user on one server: storage survives a change
// in Configurar, so a key without them would hand the old server's answers to the new one.
const scope = () => kino.config.get("user") + "@" + base();

// The token does NOT change when only the password changes for the same user@server -- a
// still-valid token keeps working, exactly like a real session would, until the server rejects it.
const tokenKey = () => "token:" + scope();

async function token() {
  await null;
  const saved = kino.storage.get(tokenKey());
  if (saved) return saved;
  const r = await kino.fetch(base() + "/auth", {
    method: "POST",
    body: { json: { user: kino.config.get("user"), password: kino.config.get("password") } },
  });
  if (r.status === 401) throw kino.error("auth_required", "usuario o contraseña incorrectos");
  if (!r.ok) throw kino.error("unavailable", "el servidor respondió " + r.status);
  const t = r.json().token;
  kino.storage.set(tokenKey(), t);
  return t;
}

// Every request goes through here, so a token invalidated server-side (expired, revoked, or a
// stale one from before a real password change) is forgotten and asked for again on the next call.
async function api(path) {
  const r = await kino.fetch(base() + path, { headers: { "X-Token": await token() } });
  if (r.status === 401) { kino.storage.remove(tokenKey()); throw kino.error("auth_required", "la sesión venció"); }
  if (r.status === 404) throw kino.error("not_found");
  if (r.status === 429) throw kino.error("rate_limited");
  if (r.status === 451) throw kino.error("geo_blocked");
  if (!r.ok) throw kino.error("unavailable", "el servidor respondió " + r.status);
  return r.json();
}

// Artwork lives on the same typed server, so `http` and a LAN address are fine here too. Posters
// are 2:3 (the cards), backdrops 16:9 (the info page's background, and each episode's still).
const art = (shape, id) => base() + "/img/" + shape + "/" + encodeURIComponent(id) + ".png";
const poster = (id) => art("poster", id);
const backdrop = (id) => art("backdrop", id);

// `kind` comes from the server: "movie", "series" (one season of a show) or "live" (apiVersion 2).
// `ids.tmdb` only when the server knows it: Kino then matches the title with TMDB and fills in its
// info page (cast, director, tagline...).
const item = (x) => ({
  id: x.id,
  ref: x.id,
  title: x.title,
  kind: x.kind,
  year: x.year,
  poster: poster(x.id),
  backdrop: backdrop(x.id),
  ids: x.tmdb ? { tmdb: x.tmdb } : undefined,
});

// Home rows, one per kind; each row's ref is the kind, which browse() pages through.
const ROWS = [
  { id: "novedades", title: "Novedades", kind: "movie" },
  { id: "series", title: "Series", kind: "series" },
  { id: "en-vivo", title: "En vivo", kind: "live" },
];

// Home asks the server three times; the answer is kept for 15 minutes with a storage TTL, so
// opening Kino again right away costs no request. An expired entry reads as null by itself.
const HOME_TTL_MS = 15 * 60 * 1000;

export async function home() {
  const key = "home:" + scope();
  const cached = kino.storage.get(key);
  if (cached) return JSON.parse(cached);
  const rows = [];
  for (const row of ROWS) {
    const p = await api("/items?limit=10&kind=" + row.kind);
    if (p.items.length) rows.push({ id: row.id, title: row.title, ref: row.kind, items: p.items.map(item) });
  }
  kino.storage.set(key, JSON.stringify(rows), { ttlMs: HOME_TTL_MS });
  return rows;
}

export async function browse(ref, cursor) {
  const p = await api("/items?limit=10&kind=" + encodeURIComponent(ref) + (cursor ? "&cursor=" + encodeURIComponent(cursor) : ""));
  return { items: p.items.map(item), next: p.next || undefined };
}

// The server matches ANY word of the query, so "Serie de prueba" also brings "Video de prueba 1".
// kino.rank turns that into a title search: ask with the title's head, drop the stray-word hits,
// best match first -- trying every form of the title Kino knows.
export async function search(query) {
  if (!query.q.trim()) return [];
  const titles = [query.q, query.originalTitle, ...(query.altTitles || [])].filter(Boolean);
  const found = (await api("/items?limit=50&q=" + encodeURIComponent(kino.rank.shortQuery(query.q)))).items;
  const relevant = kino.rank.filterRelevant(found, titles);
  return kino.rank.sortBySimilarity(relevant, titles).map(item);
}

// Each season is its own title on this server, so the answer lists every season of the show in
// `seasons` (the one being answered marked `current`): Kino shows them as chips and calls
// episodes() again with the chosen season's ref.
export async function episodes(ref) {
  const x = await api("/items/" + encodeURIComponent(ref));
  if (x.kind !== "series") throw kino.error("not_found");
  return {
    series: { title: x.show.title, overview: x.show.overview, poster: poster(x.id), backdrop: backdrop(x.id) },
    episodes: x.episodes.map((e) => ({ season: x.season, number: e.number, ref: e.id, title: e.title, still: backdrop(e.id) })),
    seasons: x.seasons.map((s) => ({
      id: s.id,
      ref: s.id,
      title: "Temporada " + s.number,
      number: s.number,
      current: s.id === x.id,
    })),
  };
}

// Movies and episodes are progressive mp4 files, so with `download` declared Kino can save them;
// the live channel is HLS and plays as live (never downloadable). A movie with a separate audio
// file gets it as an `audioTracks` entry, merged by the player and picked in its audio menu.
export async function resolve(ref) {
  const x = await api("/items/" + encodeURIComponent(ref));
  if (x.kind === "live") return { url: base() + x.stream, mime: "application/vnd.apple.mpegurl" };
  const hd = kino.config.get("hd");
  const stream = {
    url: base() + x.stream + (hd ? "?quality=hd" : ""),
    mime: "video/mp4",
    // The stream URL is short-lived on the reference server: resolve again once it is stale.
    expiresInSeconds: 600,
  };
  if (x.audio && x.audio.length) {
    stream.audioTracks = x.audio.map((a) => ({ lang: a.lang, label: a.label, url: base() + a.stream }));
  }
  return stream;
}

// channels (apiVersion 3), all three shapes in one answer: Noticias with a `ref` (played through
// resolve() above), Deportes with an inline `stream` (no plugin call on play), and a playlist Kino
// downloads and parses itself, sent with the token and hiding one group.
export async function liveCategories() {
  const categories = await api("/channels/categories");
  return [
    ...categories,
    { playlist: {
      url: base() + "/lista.m3u", format: "m3u",
      headers: { "X-Token": await token() },
      epg: { url: base() + "/guia.xml.gz", format: "xmltv" },
      refreshHours: 1,
      hideGroups: ["Compras"],
    } },
  ];
}

export async function liveChannels({ categoryId }) {
  const page = await api("/channels?category=" + encodeURIComponent(categoryId));
  return {
    items: page.items.map((c) => {
      const channel = { id: c.id, title: c.title, number: c.number, categoryId: c.categoryId, logo: poster(c.id) };
      if (categoryId === "deportes") channel.stream = { url: base() + "/live/" + c.id + ".m3u8", mime: "application/vnd.apple.mpegurl" };
      else channel.ref = c.id;
      return channel;
    }),
  };
}

export async function guide({ channelIds, from, to }) {
  return api("/channels/guide?ids=" + encodeURIComponent(channelIds.join(",")) + "&from=" + from + "&to=" + to);
}
```

Try it under Node with `--config server=http://192.168.1.10:8096 --config user=ana --config
password=…` (or `sdk/config.json`, kept out of git), from your computer's LAN address, not
`127.0.0.1`: a loopback address is refused even as the person's own server. `node sdk/run.mjs .
live categories` then shows the two categories and the playlist as Kino reads it ("3 canales en 1
categorías; 0 entradas descartadas; 2 ocultas (adultos)").

### A Widevine-protected stream (apiVersion 2)

Your source serves DASH or HLS encrypted with Widevine and hands out a license from its own server.
Declare `"apiVersion": 2` and `"drm"` in `capabilities`, list the license server in `hosts`, and
return a `drm` block with the `Stream`:

```json
{
  "id": "mi-servicio", "name": "Mi servicio", "version": "1.0.0", "apiVersion": 2, "entry": "plugin.js",
  "hosts": ["api.example.com", "cdn.example.com", "license.example.com"],
  "capabilities": ["search", "resolve", "drm"]
}
```

```js
export async function resolve(ref) {
  const s = await api("/play/" + encodeURIComponent(ref)); // { mpd, licenseToken }
  return {
    url: s.mpd, // https://cdn.example.com/…/manifest.mpd
    mime: "application/dash+xml",
    drm: {
      type: "widevine",
      licenseUrl: "https://license.example.com/widevine",
      licenseHeaders: { Authorization: "Bearer " + s.licenseToken },
    },
    expiresInSeconds: 3600,
  };
}
```

What Kino does with it, and what it does not:

- `licenseUrl` must pass the same check as `url`: `https` on one of your `hosts` (or the person's own
  server as typed), never an IP or a local name; the license request itself goes through the same
  host gate as the segments, with `licenseHeaders` (filtered like `headers`, at most 20) on it and
  nothing else. `headers` are not sent to the license server, and `licenseHeaders` are not sent to
  the CDN.
- `type` must be `"widevine"`: PlayReady, FairPlay and ClearKey are not offered. Without the `drm`
  capability, or with any other DRM-shaped key (`license`, `licenseUrl`, `drmLicenseUrl`, `keySystem`,
  `widevine`) in the `Stream`, the stream is refused as it always was.
- Kino asks Widevine for security level **L3** (software) so the same player, surface and decoder
  as a clear stream are used, and plays **only if the device confirms L3**: a device that stays at
  L1 (or won't say) opens no session at all and shows the message below. A license server that
  refuses L3, or grants it only SD, gives the person SD or that same message: check your server's
  policy before you ship.
- `audioTracks` next to `drm`: the video is protected, the side audio files are played **clear** --
  no license is requested for them, so they must be plain, unencrypted files (an encrypted side
  file fails the whole playback with the message below). `subtitles` and `headers` work as always.
- When the license is refused, unreachable or expired, or the device has no Widevine (or no L3), the
  person reads "No se pudo abrir este video protegido" (after one more `resolve` if `expiresInSeconds`
  had passed, like any stream). A protected live channel reads the same at once on a device with no
  L3; its other license failures are cuts, re-resolved like any other (see Live channels). A protected title is **never downloadable** ("Este video no se puede
  descargar"), even with `download` declared, and cannot be sent to a Chromecast (no plugin title can).
- The consent sheet adds "Reproduce video protegido (DRM)" when `drm` is declared, and an update that
  newly declares it waits for the person's approval ([section 8](#8-publishing-your-plugin)).

To test without a real service, a public Widevine test stream works: the manifest at
`https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd` with the license server
`https://proxy.uat.widevine.com/proxy?provider=widevine_test` (declare `storage.googleapis.com` and
`proxy.uat.widevine.com` in `hosts`; no `licenseHeaders` needed).

### A site of yours without a certificate (apiVersion 2)

Your videos sit on a server of yours that only speaks plain `http` -- a CDN box with no certificate,
an old media server on a public name. Declare `"apiVersion": 2` and mark that one host
`insecureHttp` in `hosts`; nothing changes in your code beyond the scheme:

```json
{
  "id": "mi-cdn", "name": "Mi CDN", "version": "1.0.0", "apiVersion": 2, "entry": "plugin.js",
  "hosts": ["api.example.com", { "host": "cdn.example.com", "insecureHttp": true }],
  "capabilities": ["search", "resolve"]
}
```

```js
export async function resolve(ref) {
  const s = await api("/play/" + encodeURIComponent(ref)); // over https, api.example.com
  return {
    url: "http://cdn.example.com/videos/" + s.file,           // plain http: only because cdn.example.com is insecureHttp
    subtitles: s.subs.map((x) => ({ lang: x.lang, url: "http://cdn.example.com/subs/" + x.file })),
  };
}
```

What the flag does, and what it does not:

- Only `cdn.example.com`, exactly, accepts `http`: for `kino.fetch`, a `Stream`'s `url`, `subtitles`,
  `audioTracks` and a `drm` block's `licenseUrl`, and for every redirect hop that lands on it.
  `api.example.com` stays https-only, and so does `sub.cdn.example.com` (no wildcard, no subdomains).
  `https://cdn.example.com/…` keeps working too.
- Everything else about a declared host holds: a public DNS name (no IP, no `localhost`, nothing
  `.local`/`.lan`), and a name that resolves into the person's own network is refused at request
  time. For a server at home the person types in a `url` setting instead (see
  [The person's own server](#the-persons-own-server)): that path takes `http` without this flag.
- The consent sheet adds, in red, "Conexión sin cifrar con cdn.example.com", so the person knows
  that traffic can be read on the way; an update that newly marks an already-approved host
  `insecureHttp` waits for approval ([section 8](#8-publishing-your-plugin)). Prefer `https` whenever
  the server can: the flag is for the host that cannot.

### Live channels: three recipes (apiVersion 3)

Three ways to fill the En vivo tab, from the least code to the most control. Each is a complete
plugin (see [Channels in the En vivo tab](#channels-in-the-en-vivo-tab-apiversion-3) and
[Live channels](#live-channels-apiversion-3) for the rules).

**1. A plain M3U list the person types.** The person pastes the address of their list (and, if they
have one, of its guide) in Configurar; Kino downloads it, groups it and plays each entry itself.

```json
{
  "id": "mi-lista", "name": "Mi lista", "version": "1.0.0", "apiVersion": 3, "entry": "plugin.js",
  "hosts": [],
  "capabilities": ["home", "resolve", "channels"],
  "liveStreamHosts": "any",
  "settings": [
    { "key": "lista", "label": "Lista M3U", "type": "url", "required": true },
    { "key": "guia", "label": "Guía XMLTV", "type": "url" }
  ]
}
```

`"hosts": []` is enough: the list and the guide are on servers the person typed. Their streams are
not: an IPTV list points at dozens of servers nobody can declare ahead of time, which is what
`"liveStreamHosts": "any"` is for ([Channels from any server](#channels-from-any-server-livestreamhosts-apiversion-3)).
The person sees it on the consent sheet, in red: "Puede reproducir canales desde cualquier servidor
que indique su lista". Leave it out when every stream is on hosts you can declare.

```js
// Kino downloads the list (and the guide), groups it and plays each entry by itself.
export async function liveCategories() {
  const guia = kino.config.get("guia");
  return [{
    playlist: {
      url: kino.config.get("lista"),
      format: "m3u",
      epg: guia ? { url: guia, format: "xmltv" } : undefined,
      hideGroups: ["Compras"],
    },
  }];
}

// Every channel comes from the list: no categories of your own to page.
export async function liveChannels() {
  return { items: [] };
}

// The manifest needs search or home; a plugin with only channels has an empty home.
export async function home() {
  return [];
}

// A direct list never calls resolve: its entries play as they are.
export async function resolve() {
  await null;
  throw kino.error("not_found");
}
```

```
node sdk/run.mjs . --config lista=https://iptv-org.github.io/iptv/countries/co.m3u live categories
```

**2. A token per channel.** Your API lists the channels, and each play needs a freshly signed URL.
`liveChannels` returns `{ id, title, ref }` items; `resolve(ref)` signs the URL when the person
plays it. A channel's `expiresInSeconds` is ignored: when a live stream is cut, Kino simply calls
`resolve` again.

```json
{
  "id": "mi-tv", "name": "Mi TV", "version": "1.0.0", "apiVersion": 3, "entry": "plugin.js",
  "hosts": ["api.example.com", "cdn.example.com"],
  "capabilities": ["home", "resolve", "channels"],
  "settings": [{ "key": "token", "label": "Código de acceso", "type": "password", "required": true }]
}
```

```js
const API = "https://api.example.com";

async function api(path) {
  const r = await kino.fetch(API + path, { headers: { Authorization: "Bearer " + kino.config.get("token") } });
  if (r.status === 401) throw kino.error("auth_required", "código de acceso inválido");
  if (!r.ok) throw kino.error("unavailable", "la API respondió " + r.status);
  return r.json();
}

export async function home() {
  return [];
}

export async function liveCategories() {
  const cats = await api("/categorias"); // [{ slug, nombre }]
  return cats.map((c) => ({ id: c.slug, title: c.nombre }));
}

// One page of a category. The ref is only the channel's id: the signed URL is made on play.
export async function liveChannels({ categoryId, cursor }) {
  const page = await api("/canales?categoria=" + encodeURIComponent(categoryId) + (cursor ? "&pagina=" + encodeURIComponent(cursor) : ""));
  return {
    items: page.canales.map((c) => ({ id: c.id, title: c.nombre, categoryId, ref: c.id, logo: c.logo, number: c.numero })),
    next: page.siguiente || undefined,
  };
}

// Called on every play, and again when the stream is cut: always a fresh token.
export async function resolve(ref) {
  const s = await api("/firmar/" + encodeURIComponent(ref)); // { url: "https://cdn.example.com/…?token=…" }
  return { url: s.url, mime: "application/vnd.apple.mpegurl" };
}
```

```
node sdk/run.mjs . --config token=... live channels noticias
```

**3. Mixed.** Your own "Destacados" category with inline `stream` items (they play with no call to
your plugin, so zapping through them is instant), plus the provider's full list declared with
`resolve: true`: Kino downloads and groups it, and each of its entries plays through your
`resolve(<entry url>)`, which appends a token.

```json
{
  "id": "mi-mezcla", "name": "Mi mezcla", "version": "1.0.0", "apiVersion": 3, "entry": "plugin.js",
  "hosts": ["api.example.com", "live.example.com", "listas.example.com"],
  "capabilities": ["home", "resolve", "channels"]
}
```

```js
// Your own featured channels: inline streams, played with no call to the plugin (fast zapping).
const DESTACADOS = [
  { id: "noticias24", title: "Noticias 24", number: 1, url: "https://live.example.com/noticias24/index.m3u8" },
  { id: "deportes", title: "Deportes", number: 2, url: "https://live.example.com/deportes/index.m3u8" },
];

export async function home() {
  return [];
}

export async function liveCategories() {
  return [
    { id: "destacados", title: "Destacados" },
    // The provider's full list: Kino downloads and groups it; each entry plays through resolve().
    {
      playlist: {
        url: "https://listas.example.com/todos.m3u",
        format: "m3u",
        epg: { url: "https://listas.example.com/guia.xml.gz", format: "xmltv" },
        resolve: true,
      },
    },
  ];
}

export async function liveChannels({ categoryId }) {
  if (categoryId !== "destacados") return { items: [] };
  return {
    items: DESTACADOS.map((c) => ({ id: c.id, title: c.title, number: c.number, categoryId, stream: { url: c.url } })),
  };
}

// Only the list's entries get here (resolve: true), with the entry's URL as the ref.
export async function resolve(url) {
  const r = await kino.fetch("https://api.example.com/token");
  if (!r.ok) throw kino.error("unavailable", "no hay token");
  const { token } = r.json();
  return { url: url + (url.includes("?") ? "&" : "?") + "token=" + encodeURIComponent(token) };
}
```

The list's streams must be on your `hosts` here (`live.example.com`), since this manifest does not
declare `"liveStreamHosts": "any"`; `live categories` counts the entries that are not as discarded.

```
node sdk/run.mjs . live categories
node sdk/run.mjs . live channels destacados
```
