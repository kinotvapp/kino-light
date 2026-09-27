// TypeScript declarations for Kino plugins (apiVersion 1 and 2). Reference them from plugin.js
// with `/// <reference path="./kino.d.ts" />` for editor help; Kino itself runs plain JavaScript.
// The numbers in the comments come from contract.json, which is authoritative. The app checks that
// every `kino` member declared here exists in its runtime and nothing else does (KinoDtsTest).

// ---------- what your functions receive and return ----------

/** `search(query)`: `type` is a hint, never a filter. `cursor` is null on the first page. */
interface KinoSearchQuery {
  q: string;
  type: "movie" | "series" | "any";
  year: number;
  season: number;
  episode: number;
  tmdbId: number;
  /** TMDB's original title when it differs from `q`, else "". */
  originalTitle: string;
  /** Other known titles, at most 5, each at most 200 characters. */
  altTitles: string[];
  cursor: string | null;
}

interface KinoItem {
  /** ^[A-Za-z0-9._~-]{1,128}$, stable: the library keys the title by it. */
  id: string;
  /** Your own opaque reference, at most 4096 characters. */
  ref: string;
  title: string;
  /**
   * `"live"` needs `"apiVersion": 2` (a v1 plugin's live item is dropped): a live channel, whose
   * `ref` goes to `resolve` and plays as live straight from its card, with an "EN VIVO" badge; it
   * has no `runtimeMinutes` (ignored) and no episodes, is never saved to the library, never resumed
   * and never downloaded.
   */
  kind: "movie" | "series" | "live";
  year?: string | number;
  /** https, at most 2048 characters; never an IP or a local name (except the person's own server). */
  poster?: string;
  backdrop?: string;
  overview?: string;
  originalTitle?: string;
  /** At most 5, each at most 30 characters. */
  genres?: string[];
  /** 0..10 */
  rating?: number;
  /** 1..1000; ignored on a `live` item. */
  runtimeMinutes?: number;
  ids?: { tmdb?: number; /** ^tt\d{5,10}$ */ imdb?: string };
  lang?: string;
  quality?: string;
  /** At most 3, each at most 20 characters; shown as chips. */
  badges?: string[];
  /** true: never shown (no plugin section behind the 18+ lock exists yet). */
  adult?: boolean;
}

/** A Home row. `ref` needs the `browse` capability: the row gets "Ver más", which calls browse(ref, null). */
interface KinoRow {
  id: string;
  title: string;
  items: KinoItem[];
  ref?: string;
}

/** `next` (at most 2048 characters, opaque) needs the `browse` capability; the app passes it back as the cursor. */
interface KinoPage {
  items: KinoItem[];
  next?: string;
}

interface KinoEpisode {
  /** 1..999, default 1. */
  season?: number;
  /** 1..99999 */
  number: number;
  ref: string;
  title?: string;
  still?: string;
  overview?: string;
  /** YYYY-MM-DD */
  airDate?: string;
  runtimeMinutes?: number;
}

interface KinoSeriesInfo {
  title?: string;
  poster?: string;
  backdrop?: string;
  overview?: string;
  ids?: { tmdb?: number; imdb?: string };
  genres?: string[];
  year?: string | number;
}

/**
 * One season of a series, for a source that keeps each season as its own `series` item: that item's
 * `id` and `ref` (`episodes(ref)` lists it). `title` is what the season selector shows ("Temporada 2");
 * `current` marks the season whose episodes came in the same answer (Kino also recognizes it by `id`).
 */
interface KinoSeason {
  /** The season's own item id, same pattern as an item id. */
  id: string;
  /** The season's own series ref, at most 4096 characters. */
  ref: string;
  title: string;
  /** 1..999; leave it out when the source has no numbering. */
  number?: number;
  current?: boolean;
}

interface KinoEpisodes {
  series?: KinoSeriesInfo;
  episodes: KinoEpisode[];
  /**
   * Only when each season is a separate item: every season of the show, this one included, at most
   * 50. Leave it out when `episodes` already holds every season (Kino reads the seasons from them).
   */
  seasons?: KinoSeason[];
}

interface KinoStream {
  /** https on a declared host (http only on one declared `insecureHttp`), or the person's own server exactly as typed. */
  url: string;
  mime?: string;
  /**
   * Sent with every request the player makes for this stream (and, for a plugin that declares the
   * `download` capability, with the request that saves it to the device). At most 20.
   */
  headers?: Record<string, string>;
  subtitles?: { lang: string; url: string; format?: "vtt" | "srt" }[];
  /**
   * Separately-hosted audio tracks (a dub, an alternate mix), at most 8: Kino plays your video with
   * each merged in as its own track, offered and auto-picked by the person's audio-language
   * preference exactly like the container's own. `lang` is a short code like `subtitles`' (up to 16
   * characters; blank becomes `"und"`); `label`, if given (up to 40 characters), is shown verbatim
   * instead of a name guessed from `lang`. Checked the same way as `subtitles`: https on a declared
   * host, or the person's own server exactly as typed; a bad entry is dropped and the rest survive,
   * and so is a `url` already listed (the first entry wins). Ignored for a `live` item's stream: a
   * channel's other languages go inside its manifest.
   */
  audioTracks?: { lang: string; url: string; label?: string }[];
  /** Ignored for a `live` item's stream: a channel has no length. */
  durationMs?: number;
  /** 30..86400: after that long, a failed playback calls resolve() once more. */
  expiresInSeconds?: number;
  /**
   * apiVersion 2, and only with the `drm` capability declared: the stream is Widevine-protected and
   * Kino fetches its license from `licenseUrl` (checked exactly like `url`: https on a declared host, http only on one declared `insecureHttp`)
   * sending `licenseHeaders` (filtered like `headers`, at most 20) with the license request only.
   * Without the capability any DRM-shaped key refuses the stream. A protected title never downloads.
   * Kino negotiates Widevine at security level L3 (software), and only when the device confirms L3:
   * the license server must allow it. `audioTracks` next to `drm` are played clear (no license for
   * them): plain, unencrypted files only.
   */
  drm?: { type: "widevine"; licenseUrl: string; licenseHeaders?: Record<string, string> };
}

/** Your module's exports. `resolve` is required, and at least one of `search`/`home`. */
interface KinoPlugin {
  search?(query: KinoSearchQuery): Promise<KinoItem[] | KinoPage>;
  home?(): Promise<KinoRow[]>;
  browse?(ref: string, cursor: string | null): Promise<KinoPage>;
  episodes?(ref: string): Promise<KinoEpisodes>;
  resolve(ref: string): Promise<KinoStream>;
}

// ---------- the kino API ----------

type KinoErrorCode = "auth_required" | "not_found" | "geo_blocked" | "rate_limited" | "unavailable";
type KinoFetchErrorCode = "host_not_allowed" | "timeout" | "network" | "too_large" | "invalid_request";
type KinoEncoding = "utf8" | "hex" | "base64";

interface KinoError extends Error {
  /** `KinoError_<code>` (e.g. `KinoError_not_found`). */
  readonly name: string;
  readonly code: KinoErrorCode | KinoFetchErrorCode | "crypto_error" | "unknown";
}

interface KinoFetchOptions {
  method?: "GET" | "HEAD" | "POST" | "PUT" | "PATCH" | "DELETE";
  headers?: Record<string, string>;
  /** A string, or JSON, a form, or raw bytes as base64. URL + headers + body at most 1,048,576 characters. */
  body?: string | { json: unknown } | { form: Record<string, string | number | boolean> } | { base64: string };
  /** "follow" (default, at most 10 hops, each host-checked) or "manual" (returns the 3xx). */
  redirect?: "follow" | "manual";
  /** true (default): send and store cookies from the plugin's jar. */
  cookies?: boolean;
  /** Default 15000, at most 30000. */
  timeoutMs?: number;
}

interface KinoResponse {
  readonly ok: boolean;
  readonly status: number;
  readonly url: string;
  /** Lower-cased names; repeated headers joined with ", "; never set-cookie. */
  readonly headers: Readonly<Record<string, string>>;
  text(): string;
  json(): any;
  /** The body bytes (at most 5 MB) as base64. */
  base64(): string;
}

interface KinoHtmlMatch {
  text: string;
  html: string;
  attrs: Record<string, string>;
}

interface KinoCipherOptions {
  key: string;
  iv?: string;
  data: string;
  /** CBC/ECB only. Default "pkcs7". */
  padding?: "pkcs7" | "none";
  /** GCM only: additional authenticated data. */
  aad?: string;
  /** Default utf8 to encrypt, base64 to decrypt. */
  inputEncoding?: KinoEncoding;
  /** Default base64 from encrypt, utf8 from decrypt. */
  outputEncoding?: KinoEncoding;
  keyEncoding?: KinoEncoding;
  ivEncoding?: KinoEncoding;
  aadEncoding?: KinoEncoding;
}

type KinoCipher =
  | "aes-128-cbc" | "aes-192-cbc" | "aes-256-cbc"
  | "aes-128-ecb" | "aes-192-ecb" | "aes-256-ecb"
  | "aes-128-ctr" | "aes-192-ctr" | "aes-256-ctr"
  | "aes-128-gcm" | "aes-192-gcm" | "aes-256-gcm"
  | "des-ede3-cbc" | "des-ede3-ecb";

declare namespace kino {
  const apiVersion: number;
  const appVersion: string;
  const lang: string;

  /** Only to the manifest's hosts over https (http only on a host declared `insecureHttp`), or to the person's own server as typed. Never throws for a non-2xx status. */
  function fetch(url: string, options?: KinoFetchOptions): Promise<KinoResponse>;

  /** `throw kino.error("not_found", "…")`: the app words the message; yours is a detail of at most 200 characters. */
  function error(code: KinoErrorCode, message?: string): KinoError;

  /** 0..5000 ms, counts inside the call's own timeout. */
  function sleep(ms: number): Promise<void>;

  /** Writes to Kino's log (and console.* does the same); lines are cut at 2000 characters. */
  function log(...args: unknown[]): void;

  namespace html {
    /** Jsoup CSS selectors; at most 500 matches. Only inside Kino. */
    function select(html: string, css: string): KinoHtmlMatch[];
  }

  namespace storage {
    /** 256 KB in total for this plugin. Returns `null` once the entry has expired (see `set`). */
    function get(key: string): string | null;
    /**
     * `options.ttlMs` makes the entry expire: after that many milliseconds, `get` returns `null` and
     * `keys()` leaves it out, even across a restart of the app. A whole number greater than 0,
     * at most 2,592,000,000 ms (30 days); anything else throws before the entry is touched. Leave
     * out `options` (or `ttlMs`) for a permanent entry, exactly as before this option existed. An
     * expired entry never counts against the 256 KB cap: it is dropped the next time your plugin
     * reads or writes storage.
     */
    function set(key: string, value: string, options?: { ttlMs?: number }): void;
    function remove(key: string): void;
    /** Expired keys are already gone. */
    function keys(): string[];
  }

  namespace config {
    /** A setting's value (string, or boolean for a toggle); undefined when unset with no default (a `url` setting never has one). Read-only. */
    function get(key: string): string | boolean | undefined;
    function all(): Record<string, string | boolean>;
  }

  namespace cookies {
    /** The value of cookie `name` for `url` (a host the plugin may reach), or null. */
    function get(url: string, name: string): string | null;
    /** Forgets every cookie of this plugin (they also go when its settings change). */
    function clear(): void;
  }

  namespace crypto {
    /** Default input utf8, output hex. Data at most 5 MB. Errors carry code "crypto_error". */
    function hash(alg: "md5" | "sha1" | "sha256" | "sha512", data: string, options?: { inputEncoding?: KinoEncoding; outputEncoding?: KinoEncoding }): string;
    function hmac(alg: "md5" | "sha1" | "sha256" | "sha512", key: string, data: string, options?: { keyEncoding?: KinoEncoding; inputEncoding?: KinoEncoding; outputEncoding?: KinoEncoding }): string;
    /** GCM appends the 16-byte tag to the ciphertext. */
    function encrypt(alg: KinoCipher, options: KinoCipherOptions): string;
    /** GCM expects the 16-byte tag appended. */
    function decrypt(alg: KinoCipher, options: KinoCipherOptions): string;
    /** iterations at most 100000, keyLength at most 64 bytes. Password uses keyEncoding, salt inputEncoding. */
    function pbkdf2(hash: "sha1" | "sha256" | "sha512", password: string, salt: string, iterations: number, keyLength: number, options?: { keyEncoding?: KinoEncoding; inputEncoding?: KinoEncoding; outputEncoding?: KinoEncoding }): string;
    /** 1..1024 bytes, hex by default. */
    function randomBytes(n: number, outputEncoding?: KinoEncoding): string;
    /** A random (v4) UUID. */
    function uuid(): string;
  }

  namespace rank {
    /**
     * The title's HEAD, up to its first `:`, `,`, `|`, en dash or em dash -- for a search backend
     * that ranks a short query better than a long one. A one- or two-letter head identifies
     * nothing, so the whole (trimmed) text comes back instead; a plain "-" is never a cut point (it
     * would split a hyphenated word like "Spider-Man").
     */
    function shortQuery(query: string): string;
    /**
     * Reorders `items` so the ones sharing the most words with `query` come first; ties keep
     * `items`' own order. `query` is a title, or several forms of one (try `query.q`,
     * `query.originalTitle` and `query.altTitles` together: a backend may only know a title in one
     * language). `getTitle` reads a title off an item, string or array of them; it defaults to
     * `(item) => item.title`. Never throws: `items` not an array answers `[]`; an item with no
     * usable title (missing, not a string, or `getTitle` itself failing) sorts after every item
     * that has one, in `items`' own order among themselves.
     */
    function sortBySimilarity(items: any[], query: string | string[], getTitle?: (item: any) => string | string[]): any[];
    /**
     * Drops items sharing too few words with `query` (under 60% of its distinctive words of 3+
     * letters), and any item with no usable title along with them. Never throws: `items` not an
     * array answers `[]`. Reordering alone (`sortBySimilarity`) still shows a full page of near-misses when
     * the title genuinely is not on the backend, so an absent title comes back with 0 results
     * instead.
     */
    function filterRelevant(items: any[], query: string | string[], getTitle?: (item: any) => string | string[]): any[];
  }
}

// ---------- web globals Kino adds (QuickJS has none of them natively) ----------
// URL, URLSearchParams, atob, btoa, TextEncoder and TextDecoder (UTF-8 only) behave like the
// browser's, without IDN/punycode. Use the lib "dom" typings, or declare them in your editor.
