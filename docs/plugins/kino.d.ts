// TypeScript declarations for Kino plugins (apiVersion 1, SDK v1). Reference them from plugin.js
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
  kind: "movie" | "series";
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
  /** 1..1000 */
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

interface KinoEpisodes {
  series?: KinoSeriesInfo;
  episodes: KinoEpisode[];
}

interface KinoStream {
  /** https on a declared host, or the person's own server exactly as typed. */
  url: string;
  mime?: string;
  headers?: Record<string, string>;
  subtitles?: { lang: string; url: string; format?: "vtt" | "srt" }[];
  durationMs?: number;
  /** 30..86400: after that long, a failed playback calls resolve() once more. */
  expiresInSeconds?: number;
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

  /** Only to the manifest's hosts over https, or to the person's own server as typed. Never throws for a non-2xx status. */
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
    /** 256 KB in total for this plugin. */
    function get(key: string): string | null;
    function set(key: string, value: string): void;
    function remove(key: string): void;
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
}

// ---------- web globals Kino adds (QuickJS has none of them natively) ----------
// URL, URLSearchParams, atob, btoa, TextEncoder and TextDecoder (UTF-8 only) behave like the
// browser's, without IDN/punycode. Use the lib "dom" typings, or declare them in your editor.
