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
Archive source), and `sdk/` is the Node kit.

1. [What a plugin is](#1-what-a-plugin-is)
2. [A first plugin](#2-a-first-plugin)
3. [The manifest](#3-the-manifest)
4. [The contract (apiVersion 1)](#4-the-contract-apiversion-1)
5. [The `kino` API](#5-the-kino-api)
6. [Limits and engine quirks](#6-limits-and-engine-quirks)
7. [Test it locally](#7-test-it-locally)
8. [Publishing your plugin](#8-publishing-your-plugin)
9. [What people see](#9-what-people-see)
10. [The reference plugin](#10-the-reference-plugin)

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
declares and the person approved on screen.

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
  "capabilities": ["search", "home", "episodes", "resolve"],
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
| `apiVersion` | Required. An integer, `1` today. A higher number is refused with "Este plugin necesita una versión más nueva de Kino". |
| `entry` | Required. Relative path of the JavaScript file: letters, digits, `.`, `_`, `-` and `/` only, no `..`, at most 200 characters, ends in `.js`. The file is at most 1 MB. |
| `hosts` | Required. 1 to 20 entries; each a lowercase DNS name (`archive.org`) or `*.` plus a DNS name (`*.archive.org`). Host names only: no scheme, port or path. No bare `*`, no IP addresses, no `localhost`, nothing ending in `.local`, `.lan`, `.internal`, `.localhost` or `.home.arpa`, and at least one dot. **`*.x` covers subdomains only, not `x` itself**: if you need both, list both. |
| `capabilities` | Required. A subset of `search`, `home`, `episodes`, `resolve`. Must include `resolve` and at least one of `search` or `home`. Every capability you declare must be an exported function of the entry file, or the install fails with "El plugin no carga: le falta ...". |
| `color` | Optional `#RRGGBB`: the accent of your plugin's tab and chips. A neutral color by default. |
| `icon` | Optional relative path to a square `.png`, at most 128 KB. An icon that is missing or too big is skipped without failing the install. |
| `description`, `author`, `homepage` | Optional strings. Trimmed and cut to 300, 60 and 200 characters. Kino shows the name, author, version and description when it asks the person to install. |

Other keys are ignored. `hosts` does three jobs: it is what the person approves, it is the only set
of sites `kino.fetch` can reach, and it is the set your stream and subtitle URLs must be on.

## 4. The contract (apiVersion 1)

Your entry file is one ES module that exports one `async` function for each capability you
declared, and nothing is called that you did not declare:

```js
export async function search(query) { /* -> Item[] */ }
export async function home() { /* -> Row[] */ }
export async function episodes(ref) { /* -> { series?: SeriesInfo, episodes: Episode[] } */ }
export async function resolve(ref) { /* -> Stream */ }
```

Use named exports (`export async function ...`). Data crosses into and out of your code as JSON, so
return plain data: strings, numbers, booleans, arrays and objects.

### Arguments

- `search(query)` gets `{ q, type, season, episode, tmdbId, year }`:
  - `q` is the text the person typed (it can be empty; return `[]`).
  - `type` is `"movie"` or `"series"` when Kino leans towards that kind, and `"any"` otherwise. It is
    a hint, not a filter: Kino derives it from TMDB's movie/tv split, which rarely lines up with a
    source's own catalogue, and a title can exist as both. Return every plausible match; use `type`
    at most to put the kind it names first.
  - `season` and `episode` are `0` unless Kino is looking for a specific episode; `tmdbId` and `year`
    are `0` when unknown.
- `home()` gets `null`.
- `episodes(ref)` gets the `ref` of a `series` item, as you returned it.
- `resolve(ref)` gets the `ref` of a `movie` item, or the `ref` of an episode.

### What you return

```ts
Item       = { id: string, ref: string, title: string, kind: "movie" | "series",
               year?: string, poster?: string, backdrop?: string, overview?: string,
               lang?: string, quality?: string }
Row        = { id: string, title: string, items: Item[] }
SeriesInfo = { title?: string, poster?: string, backdrop?: string, overview?: string,
               tmdbId?: number, imdbId?: string }
Episode    = { season: number, number: number, ref: string, title?: string,
               still?: string, overview?: string }
Stream     = { url: string, mime?: string, headers?: Record<string, string>,
               subtitles?: { lang: string, url: string, format?: "vtt" | "srt" }[],
               durationMs?: number }
```

**How the pieces connect.** A `movie` item's `ref` goes to `resolve`. A `series` item's `ref` goes to
`episodes`, and each episode's `ref` goes to `resolve`.

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
| `search` result | At most 50 items. |
| `home` result | At most 10 rows of at most 40 items each. A row needs a unique `id` (same pattern as an item id) and a non-blank `title`; rows with no valid items are dropped. Kino shows them after its own rows, labelled with your plugin's name, and caches them for 6 hours (stale rows show while it refreshes; an answer with no valid rows, or over 2 MB, is not cached and is asked again next time). If `home()` fails you contribute no rows and Home is not blocked. |
| `episodes` result | At most 2000 episodes. `number` is required and from 1 to 99999 (an episode numbered 0, such as a special, is dropped). `season` should be from 1 to 999; a missing or out-of-range season becomes 1. `ref` is required. A repeated season and number is dropped. Without a `title`, Kino shows "Capítulo N". |
| `id` | `^[A-Za-z0-9._~-]{1,128}$`. Anything else drops the item, so if your source's own ids have other characters (spaces, `/`, `:`, `%`), derive a stable id yourself, such as a slug. Repeated ids in one list are dropped. |
| `ref` | A non-empty string of at most 4096 characters. |
| `kind` | `"movie"` or `"series"`. A `series` item from a plugin that does not declare `episodes` is dropped: it could never be opened. |
| Text fields | `title` is required and non-blank, up to 200 characters. `overview` up to 2000; `lang` and `quality` up to 20 (for example `"es"`, `"1080p"`); `year` up to 10 (a number is accepted and converted). Longer text is cut; the text of `SeriesInfo` and `Episode` is cut the same way (200 characters for titles, 2000 for overviews). |
| Images | `poster`, `backdrop` and `still` must be `https` URLs of at most 2048 characters, or they are ignored. Images are loaded by Kino directly and are **not** checked against `hosts` (they are display only), and Kino does not send your headers or cookies with them. This is the one exception to the host rule, with one limit: an image on an IP address or a local name (`localhost`, `.local`, `.lan`, …) is ignored too. |

**The `Stream` rules.**

- `url` must be `https` and its host must be one of your `hosts`, and so must the host of every
  subtitle URL. A stream that breaks this is refused as a whole; a bad subtitle is dropped and the
  stream still plays.
- `mime` is optional, of the form `video/mp4` (anything else refuses the stream). When it is missing
  Kino's player detects HLS, DASH or a plain file from the URL and the content.
- **Everything the player fetches for the stream follows the `kino.fetch` host rules.** That covers the
  `url` itself, the variants, segments and `#EXT-X-KEY` keys an HLS manifest names, the `BaseURL`s of a
  DASH manifest, the subtitles, and every redirect hop of any of them: each must be `https` on one of
  your `hosts`, never an IP address or a local name, and a declared name that resolves inside the
  person's own network is refused. A request that breaks this fails before it leaves the device and
  playback stops with an error, so a manifest that points at another CDN needs that CDN in `hosts`.
- `headers` are sent with every one of those player requests (the stream, its manifest's segments and
  keys, its subtitles, and redirect hops, all on your `hosts`), and nowhere else. At most 20; names are letters, digits and
  hyphens; values are at most 4096 characters with no line breaks; `Host`, `Content-Length`,
  `Transfer-Encoding` and `Connection` are ignored.
- `subtitles`: at most 30, each `{ lang, url, format? }`. `lang` is a short language code such as
  `"es"` (up to 20 characters; blank becomes `"und"`), `format` is `"vtt"` or `"srt"`.
- `durationMs` is optional, in milliseconds.
- **No DRM.** A stream carrying any of `drm`, `license`, `licenseUrl`, `drmLicenseUrl`, `keySystem` or
  `widevine` is refused.

## 5. The `kino` API

`kino` is a global object, frozen, always there. Nothing else from the outside world is.

```js
kino.apiVersion   // 1
kino.appVersion   // the version of Kino, for example "1.42.0"
kino.lang         // "es-CO"
```

### `await kino.fetch(url, options?)`

```js
const r = await kino.fetch("https://archive.org/metadata/" + encodeURIComponent(id), {
  method: "GET",              // GET (default), POST, PUT, PATCH, DELETE or HEAD
  headers: { Accept: "application/json" },
  body: "a=1&b=2",            // a string, sent only with POST, PUT and PATCH
  timeoutMs: 20000,           // default 15000, at most 30000
});
r.ok        // true for 200 to 299
r.status    // the HTTP status
r.url       // the final URL, after redirects
r.headers   // { "content-type": "...", ... }: names in lowercase, repeated headers joined with ", "
r.text()    // the body as a string (already downloaded)
r.json()    // JSON.parse of the body
```

- **https only, and only your hosts.** The host of the request and of **every redirect hop** must
  match `hosts` (`*.x` matches subdomains of `x`, not `x`). A request to anything else fails before
  it leaves the device with a catchable `Error("host no permitido: <host>")`. An `http` URL, even on a
  declared host, fails with `Error("solo se permite https")`. An IP address or a local name
  (`localhost`, `.local`, …) is always refused with `host no permitido`. Kino also refuses a declared
  name that resolves to an address inside the person's own network (loopback, private, link-local,
  carrier-grade NAT, multicast).
- **Redirects** (301, 302, 303, 307, 308) are followed by Kino, up to 10 hops; each hop is checked
  and counted as a request. A 303, or a 301/302 after a POST, turns into a GET without a body.
- **A non-2xx answer does not throw**: check `r.ok`. Network failures, refused hosts, timeouts and
  bodies over 5 MB do throw, and the error is catchable like any other.
- **Limits:** 15 s per request by default (30 s at most), a body of at most 5 MB (decoded with the
  charset of its `Content-Type`, UTF-8 by default), and at most 60 requests in one call to your
  plugin, redirect hops included.
- **Headers you set** are sent as given, except `Host`, `Content-Length`, `Transfer-Encoding`,
  `Connection` and `Cookie2`. Unless you set `User-Agent`, Kino sends `Kino/<version> (plugin <id>)`.
  A `Content-Type` header sets the type of the body.
- **Cookies:** each plugin has its own cookie jar, kept in memory only, so it disappears when the
  plugin's sandbox restarts (see [section 6](#6-limits-and-engine-quirks)).

### `kino.html.select(html, css)`

Parses `html` and returns `[{ text, html, attrs }]` for every element matching the CSS selector
(Jsoup's selector syntax): `text` is its text, `html` its inner HTML, `attrs` an object of its
attributes. Only the first 2,000,000 characters of `html` are read, at most 500 elements come back,
and it throws if the combined text and HTML of the matches goes over 5,242,880 characters (5 MB). A
selector longer than 10,000 characters throws `Error("selector CSS demasiado largo (más de 10000 caracteres)")`. **It
exists only inside Kino**: the Node kit's version throws, so test anything that uses it in the app.

### `kino.storage`

```js
kino.storage.get("key")        // the string, or null
kino.storage.set("key", "v")   // values are converted to strings
kino.storage.remove("key")
```

Synchronous, private to your plugin, and it survives restarts of the sandbox and of the app. At most
64 KB in total (measured as the JSON of all keys and values); going over throws
`Error("almacenamiento del plugin lleno (64 KB)")`. It is deleted when the person uninstalls the plugin.

### `kino.log(...args)`

Also `console.log`, `console.info`, `console.warn` and `console.error`: they all go to the log
(tag `KinoPlugin` in `adb logcat`), objects are written as JSON, and a message is cut at 2000
characters. Under the Node kit they go to stderr.

## 6. Limits and engine quirks

### Every number in one place

| What | Limit |
| --- | --- |
| Manifest / entry file / icon | 16 KB / 1 MB / 128 KB |
| Memory / stack, per plugin | 64 MB / 1 MB |
| Time per call | `search` 15 s; `home`, `episodes`, `resolve` 20 s each, counting all your fetches together |
| Loading the module (its top level) | 10 s |
| Idle sandbox | closed after 5 minutes without calls |
| Consecutive timeouts | 3 in a row and Kino disables the plugin ("No responde — actívalo para volver a intentar") until the person re-enables it |
| App closed during a call | a plugin that makes the app die (a crash inside the engine, killed for lack of memory) twice in a row, with no call finishing normally in between, is switched off the same way ("No responde") at the next start. Kino can't tell which plugin was at fault when several were running at that moment, so healthy ones running alongside can be switched off with it; the person re-enables them in Ajustes ▸ Plugins |
| `kino.fetch` | https only; 15 s default, 30 s maximum; response body at most 5 MB; the request (URL, headers and body together) at most 1,048,576 characters, or it throws `Error("solicitud demasiado grande (más de 1 MB)")`; at most 60 requests per call; at most 10 redirects per request |
| What a function returns | at most 2,000,000 characters once turned into JSON, or the call fails with `Error("respuesta del plugin demasiado grande (más de 2 millones de caracteres)")` |
| `kino.storage` | 64 KB per plugin |
| `kino.log` / `console.*` | 2000 characters per message |
| Results | `search` 50 items; `home` 10 rows of 40; `episodes` 2000; `ref` 4096 characters; `id` matches `^[A-Za-z0-9._~-]{1,128}$` |
| `hosts` | 1 to 20 entries |

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

### The engine is not Node and not a browser

Plugins run in QuickJS. It handles modern JavaScript: `async`/`await`, classes with fields, `?.` and
`??`, regular expressions with lookbehind, named groups and `\p{L}` under the `u` flag, template
literals, spread, `replaceAll`, `Array.prototype.at` and `flat`, `Object.fromEntries`,
`Promise.allSettled`, `Map`, `Set`, `BigInt`. It does **not** have the platform around it:

- **Missing globals** (`typeof` is `"undefined"` inside Kino): `setTimeout`, `setInterval`,
  `setImmediate`, `queueMicrotask`, `URL`, `URLSearchParams`, `atob`, `btoa`, `TextEncoder`,
  `TextDecoder`, `Buffer`, `process`, `require`, `fetch`, `AbortController`, `structuredClone`,
  `performance`, `crypto`, `WeakRef` and `Intl`. There is no way to wait for a while, no `sleep`, no
  debounce. Build URLs with `encodeURIComponent` and string concatenation (as in the example above),
  and use `kino.fetch` instead of `fetch`. `console` does exist: Kino provides it and it writes to
  the log.
- **Node has almost all of those**, so code that runs fine under the Node kit can still fail in Kino.
  Before you publish, search your file for the names above.
- **Locale-aware methods do not localize:** `localeCompare` ignores its locale and options (so
  `{ numeric: true }` and `{ sensitivity: "base" }` do nothing; it compares code units), and
  `(1234.5).toLocaleString("es-CO")` gives `"1234.5"`. Write the comparison you need; the reference
  plugin has a small `natural()` for numbered names.
- **Keep function names short.** A function name of millions of characters makes the engine's
  native code crash the whole app. As a best-effort guard, `kino.*`, `console.*` and the other
  functions Kino provides are frozen, and on any function `Object.defineProperty`,
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
  `kino.fetch` (for example a refused host), and `await Promise.reject(e)` or
  `Promise.reject(e).catch(...)` (Kino delays `Promise.reject` by one tick so a handler can attach
  in time).
- If nobody catches the error anyway, it is harmless: the call fails with that error either way.

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

The Node kit is two files in `sdk/`: `run.mjs` and `kino-shim.mjs`. There is nothing to install.
It needs Node 18 or newer (checked on 18.20, 20.11 and 24.14).

```
node sdk/run.mjs ./plugin.js search "metropolis"
node sdk/run.mjs ./plugin.js home
node sdk/run.mjs ./plugin.js episodes 'Dragnet1951'
node sdk/run.mjs ./plugin.js resolve 'Dragnet1951|Dragnet/Season 1/Dragnet (1951) - S01E01 - The Human Bomb.mp4'
```

The first argument is your entry file (or the folder that holds `kino-plugin.json`), then the
function, then its argument: the text to search for, or the `ref` for `episodes` and `resolve`. The
runner reads your manifest, provides the `kino` global, calls that one function the way Kino does
and prints what it returned as JSON on stdout. Logs, `console.*` and errors go to stderr, so you can
pipe the result (`... | head -30`, `... | jq`). The exit code is 0 on success, 1 when your code
throws and 2 when the command is wrong. The runner only runs functions your manifest declares.

- `KINO_TYPE=movie|series|any` sets the `type` of the search (default `any`).
- To fill the other fields of the query, pass the whole query as JSON:
  `node sdk/run.mjs ./plugin.js search '{"q":"dragnet","type":"series","year":1951}'`
  (`season`, `episode`, `tmdbId` and `year` are `0` otherwise).
- Under the Node kit `kino.storage` is a file named `.kino-storage.json` next to your manifest. Add
  it to your `.gitignore`. Delete it to start from an empty storage.
- The `sdk/` folder does not have to live in your repository. Copy it anywhere and run
  `node /path/to/sdk/run.mjs ./plugin.js ...`.
- A stack trace names a temporary `plugin.mjs`: the runner loads a copy of your file so that Node
  treats it as an ES module whatever its version and `package.json` say. The line numbers are your
  `plugin.js`'s.

**What the Node kit does not reproduce.** Kino is the authority; the kit only approximates it so
you can iterate fast. Before you publish, install the plugin in the app and try it there. The
differences:

- `kino.html.select` throws (it uses Jsoup, which exists only in the app).
- The rejection trap of [section 6](#6-limits-and-engine-quirks): Node catches what Kino would not.
- Node has globals Kino lacks (`URL`, `setTimeout`, `fetch`, `Buffer`, ...): the plugin may pass
  under Node and fail in Kino.
- Nothing checks what you return. Kino would drop bad items, cut lists to the caps and refuse a
  stream whose URL is not `https` or not on your hosts; the runner prints exactly what you returned,
  so check the shapes of [section 4](#4-the-contract-apiversion-1) by eye.
- The host, redirect and request-count rules are the same, but there is no cookie jar, no refusal of
  names that resolve to private addresses, every HTTP method is passed through, bodies are always
  read as UTF-8, and the 15 s timeout covers the wait for the response but not the download.
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
   - If the new version does not add anything to `hosts` and needs a supported `apiVersion`, it is
     installed silently.
   - If `hosts` grows, Kino does **not** apply it: the plugin shows "Actualización disponible — requiere
     tu aprobación" and the person sees the new hosts (marked "nuevo") before accepting. Removing
     hosts needs no approval.
   - If the new version needs a higher `apiVersion` than the app supports, the check reports "Este
     plugin necesita una versión más nueva de Kino" and the installed version keeps working.
5. **Give it time.** GitHub serves raw files with a cache of about five minutes (measured:
   `cache-control: max-age=300`), so a change you just pushed can take that long to be visible to an
   install or an update check.
6. **Keep the `id` and the address.** An `id` that is already installed from a different address is
   refused ("Ya hay un plugin con ese id"), so renaming or moving your repository makes it a
   different plugin for the people who installed it.

Before you publish, check that:

- `node sdk/run.mjs ./plugin.js <function> ...` works for every capability you declare;
- every host your plugin talks to (and every stream and subtitle host) is in `hosts`, including the
  bare domain next to its `*.` form;
- there is no `throw` before the first `await` in a function that a caller wraps in `try`/`catch`;
- your file uses none of the missing globals of [section 6](#6-limits-and-engine-quirks);
- you installed it in Kino and it searches, lists episodes and plays.

## 9. What people see

- **The consent sheet.** When someone types your address, Kino shows "Instalar <name>", your version
  and author, the description, the list of hosts under "Se va a conectar con:", and the warning
  "Plugin no verificado: solo instálalo si confías en quien lo hizo." with "Instalar" and "Cancelar".
  Nothing of yours runs before they accept.
- **Search, Home and the library.** Your results appear in search under your plugin's name (with your
  `color`), next to the app's own sources; your `home` rows appear on Home after the app's own; your
  titles play in Kino's player and appear in "Continuar viendo" and the library. Not available for
  plugin titles in this version: downloads, Chromecast and DLNA.
- **Status of each plugin** in Ajustes > Plugins: "Activo", "Desactivado", "No responde — actívalo
  para volver a intentar" (three timeouts in a row; the person can re-enable it), "Actualización
  disponible — requiere tu aprobación", and "Archivos dañados, reinstálalo" (the installed file no
  longer matches what was installed).
- **Disable and uninstall.** A disabled plugin disappears from search and Home; its titles stay in
  the library and say "Activa el plugin <name> para ver esto". Uninstalling deletes the plugin's
  files, its storage and its cached Home rows immediately, but keeps the person's library titles and
  progress: opening one says "Esto venía del plugin <name>, que ya no está instalado", and installing
  the plugin again restores them. That is one more reason to keep `id` and `ref` handling stable.

## 10. The reference plugin

`kino-plugin.json` and `plugin.js` in this repository are the Internet Archive plugin, with all four
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
   `try`/`catch`, so one failing row does not lose the others; it reports it with `kino.log`.
5. `episodes` reads the item's file list, keeps the video originals in natural order (a small
   `natural()` comparator, because `localeCompare` cannot be trusted), numbers them from `S01E02`
   in the file name or 1, 2, 3, and uses `"<item>|<file name>"` as each episode's `ref`.
6. `resolve` picks the best playable file (an mp4 derived from the original, or the mp4/webm itself),
   turns sibling `.vtt`/`.srt` files into `subtitles`, and sets `durationMs`.
7. Every URL it builds is `https` on a declared host; posters use
   `https://archive.org/services/img/<id>` and are not host-checked.

`README.md` in this repository says what it does not do (a collection is exposed as a single movie,
episodes numbered 0 are dropped), so do not copy those as intended behavior.
