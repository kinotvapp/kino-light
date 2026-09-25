// Web globals for Kino plugins: URL, URLSearchParams, atob, btoa, TextEncoder, TextDecoder.
// QuickJS has none of them. Pure JS, no I/O, nothing here crosses into Kotlin: a plugin that
// breaks String.prototype only breaks its own URL parsing. Constructors and prototypes are frozen.
// Scope, measured against Node by the parity corpus (app/src/test/resources/plugin/web-corpus.json):
// WHATWG parsing for special schemes (http, https, ws, wss, ftp, file) and opaque ones (data:,
// mailto:), IPv4/IPv6 hosts, percent-encoding sets, dot segments, default ports. No IDN/punycode:
// a non-ASCII host throws TypeError. TextDecoder is UTF-8 only.
(() => {
  'use strict';
  const G = globalThis;
  const freezeClass = (C) => { Object.freeze(C.prototype); return Object.freeze(C); };
  const define = (name, value) =>
    Object.defineProperty(G, name, { value, writable: false, configurable: false, enumerable: false });

  // QuickJS copies the whole string on every `+=`: building 1 MB one character at a time took
  // 7.6 s (measured), so every output that can be long is built in short pieces joined once.
  const builder = () => {
    const parts = [];
    let cur = '';
    return {
      add(x) { cur += x; if (cur.length >= 1024) { parts.push(cur); cur = ''; } },
      done() { parts.push(cur); return parts.join(''); },
    };
  };

  // ---------- UTF-8 ----------
  // Into a Uint8Array, never a plain array: a JS array costs 16 bytes per element, so 5 MB of text
  // took 80 MB and ran out of the 64 MB heap (measured).
  const utf8Encode = (s) => {
    s = String(s);
    const out = new Uint8Array(s.length * 3);
    let n = 0;
    for (let i = 0; i < s.length; i++) {
      let c = s.charCodeAt(i);
      if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
        const d = s.charCodeAt(i + 1);
        if (d >= 0xdc00 && d <= 0xdfff) { c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00); i++; } else c = 0xfffd;
      } else if (c >= 0xd800 && c <= 0xdfff) c = 0xfffd;
      if (c < 0x80) out[n++] = c;
      else if (c < 0x800) { out[n++] = 0xc0 | (c >> 6); out[n++] = 0x80 | (c & 63); }
      else if (c < 0x10000) { out[n++] = 0xe0 | (c >> 12); out[n++] = 0x80 | ((c >> 6) & 63); out[n++] = 0x80 | (c & 63); }
      else { out[n++] = 0xf0 | (c >> 18); out[n++] = 0x80 | ((c >> 12) & 63); out[n++] = 0x80 | ((c >> 6) & 63); out[n++] = 0x80 | (c & 63); }
    }
    return out.slice(0, n);
  };
  // WHATWG UTF-8 decode: each maximal invalid subpart becomes one U+FFFD (or throws when fatal).
  const utf8Decode = (bytes, fatal) => {
    const out = builder();
    let i = 0;
    const n = bytes.length;
    const bad = () => { if (fatal) throw new TypeError('The encoded data was not valid for encoding utf-8'); out.add('�'); };
    while (i < n) {
      const b = bytes[i];
      if (b < 0x80) { out.add(String.fromCharCode(b)); i++; continue; }
      let need = 0, cp = 0, lower = 0x80, upper = 0xbf;
      if (b >= 0xc2 && b <= 0xdf) { need = 1; cp = b & 0x1f; }
      else if (b >= 0xe0 && b <= 0xef) { need = 2; cp = b & 0xf; if (b === 0xe0) lower = 0xa0; if (b === 0xed) upper = 0x9f; }
      else if (b >= 0xf0 && b <= 0xf4) { need = 3; cp = b & 7; if (b === 0xf0) lower = 0x90; if (b === 0xf4) upper = 0x8f; }
      else { bad(); i++; continue; }
      let j = i + 1, ok = true;
      for (let k = 0; k < need; k++, j++) {
        const c = j < n ? bytes[j] : -1;
        if (c < lower || c > upper) { ok = false; break; }
        lower = 0x80; upper = 0xbf;
        cp = (cp << 6) | (c & 0x3f);
      }
      if (!ok) { bad(); i = j; continue; }
      out.add(String.fromCodePoint(cp));
      i = j;
    }
    return out.done();
  };
  const toBytes = (input) => {
    if (input === undefined) return new Uint8Array(0);
    if (input instanceof ArrayBuffer) return new Uint8Array(input);
    if (ArrayBuffer.isView(input)) return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
    throw new TypeError('The "input" argument must be an ArrayBuffer or ArrayBufferView');
  };

  class TextEncoder {
    get encoding() { return 'utf-8'; }
    encode(input = '') { return utf8Encode(input); }
  }
  class TextDecoder {
    #fatal; #ignoreBOM;
    constructor(label = 'utf-8', options = {}) {
      const l = String(label).trim().toLowerCase();
      if (l !== 'utf-8' && l !== 'utf8' && l !== 'unicode-1-1-utf-8') throw new RangeError('The "' + label + '" encoding is not supported');
      this.#fatal = !!(options && options.fatal);
      this.#ignoreBOM = !!(options && options.ignoreBOM);
    }
    get encoding() { return 'utf-8'; }
    get fatal() { return this.#fatal; }
    get ignoreBOM() { return this.#ignoreBOM; }
    decode(input) {
      let bytes = toBytes(input);
      if (!this.#ignoreBOM && bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) bytes = bytes.subarray(3);
      return utf8Decode(bytes, this.#fatal);
    }
  }

  // ---------- base64 ----------
  const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const invalidChar = (msg) => { const e = new Error(msg); e.name = 'InvalidCharacterError'; return e; };
  function btoa(data) {
    if (arguments.length === 0) throw new TypeError('The "data" argument must be specified');
    const s = String(data);
    const out = builder();
    for (let i = 0; i < s.length; i += 3) {
      const a = s.charCodeAt(i), b = s.charCodeAt(i + 1), c = s.charCodeAt(i + 2);
      if (a > 255 || b > 255 || c > 255) throw invalidChar('Invalid character');
      const n = (a << 16) | ((b || 0) << 8) | (c || 0);
      out.add(B64[n >> 18] + B64[(n >> 12) & 63] + (i + 1 < s.length ? B64[(n >> 6) & 63] : '=') + (i + 2 < s.length ? B64[n & 63] : '='));
    }
    return out.done();
  }
  function atob(data) {
    if (arguments.length === 0) throw new TypeError('The "data" argument must be specified');
    let s = String(data).replace(/[\t\n\f\r ]/g, '');
    if (s.length % 4 === 0) s = s.replace(/==?$/, '');
    if (s.length % 4 === 1 || /[^A-Za-z0-9+/]/.test(s)) throw invalidChar('The string to be decoded is not correctly encoded.');
    const out = builder();
    let bits = 0, acc = 0;
    for (let i = 0; i < s.length; i++) {
      acc = (acc << 6) | B64.indexOf(s[i]);
      bits += 6;
      if (bits >= 8) { bits -= 8; out.add(String.fromCharCode((acc >> bits) & 0xff)); }
    }
    return out.done();
  }

  // ---------- percent-encoding ----------
  const HEX = '0123456789ABCDEF';
  const pct = (b) => '%' + HEX[b >> 4] + HEX[b & 15];
  const C0 = (c) => c < 0x20 || c > 0x7e;
  const FRAGMENT = (c) => C0(c) || c === 0x20 || c === 0x22 || c === 0x3c || c === 0x3e || c === 0x60;
  const QUERY = (c) => C0(c) || c === 0x20 || c === 0x22 || c === 0x23 || c === 0x3c || c === 0x3e;
  const SPECIAL_QUERY = (c) => QUERY(c) || c === 0x27;
  const PATH = (c) => QUERY(c) || c === 0x3f || c === 0x5e || c === 0x60 || c === 0x7b || c === 0x7d;
  const USERINFO = (c) => PATH(c) || c === 0x2f || c === 0x3a || c === 0x3b || c === 0x3d || c === 0x40 || (c >= 0x5b && c <= 0x5d) || c === 0x7c;
  const FORM = (c) => !((c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x5a) || (c >= 0x61 && c <= 0x7a) || c === 0x2a || c === 0x2d || c === 0x2e || c === 0x5f);
  const encodeSet = (s, inSet) => {
    const out = builder();
    for (const b of utf8Encode(s)) out.add(inSet(b) ? pct(b) : String.fromCharCode(b));
    return out.done();
  };
  const percentDecodeBytes = (s) => {
    const bytes = utf8Encode(s);
    const out = [];
    for (let i = 0; i < bytes.length; i++) {
      const b = bytes[i];
      if (b === 0x25 && i + 2 < bytes.length && isHex(bytes[i + 1]) && isHex(bytes[i + 2])) {
        out.push(parseInt(String.fromCharCode(bytes[i + 1], bytes[i + 2]), 16));
        i += 2;
      } else out.push(b);
    }
    return out;
  };
  const isHex = (b) => (b >= 0x30 && b <= 0x39) || (b >= 0x41 && b <= 0x46) || (b >= 0x61 && b <= 0x66);

  // ---------- URLSearchParams ----------
  const formParse = (input) => {
    const list = [];
    for (const part of input.split('&')) {
      if (part === '') continue;
      const eq = part.indexOf('=');
      const name = eq === -1 ? part : part.slice(0, eq);
      const value = eq === -1 ? '' : part.slice(eq + 1);
      list.push([utf8Decode(percentDecodeBytes(name.replace(/\+/g, ' ')), false), utf8Decode(percentDecodeBytes(value.replace(/\+/g, ' ')), false)]);
    }
    return list;
  };
  const formSerialize = (list) =>
    list.map(([k, v]) => encodeSet(k, FORM).replace(/%20/g, '+') + '=' + encodeSet(v, FORM).replace(/%20/g, '+')).join('&');
  const toUSV = (s) => utf8Decode(utf8Encode(String(s)), false);

  // The link between a URL and its searchParams, shared only inside this file.
  let attachParams, resetParams, setQuery;
  class URLSearchParams {
    #list = [];
    #url = null;
    static {
      attachParams = (params, url, query) => { params.#url = url; params.#list = formParse(query); };
      resetParams = (params, query) => { params.#list = formParse(query); };
    }
    constructor(init = '') {
      if (init instanceof URLSearchParams) { this.#list = init.#list.map((p) => [p[0], p[1]]); return; }
      if (init !== null && typeof init === 'object') {
        if (typeof init[Symbol.iterator] === 'function') {
          for (const pair of init) {
            const p = [...pair];
            if (p.length !== 2) throw new TypeError('Each query pair must be an iterable [name, value] tuple');
            this.#list.push([toUSV(p[0]), toUSV(p[1])]);
          }
        } else {
          for (const k of Object.keys(init)) this.#list.push([toUSV(k), toUSV(init[k])]);
        }
        return;
      }
      let s = toUSV(init);
      if (s.startsWith('?')) s = s.slice(1);
      this.#list = formParse(s);
    }
    #update() { if (this.#url) setQuery(this.#url, this.#list.length ? formSerialize(this.#list) : null); }
    get size() { return this.#list.length; }
    append(name, value) { this.#list.push([toUSV(name), toUSV(value)]); this.#update(); }
    delete(name, value) {
      const n = toUSV(name);
      const v = value === undefined ? undefined : toUSV(value);
      this.#list = this.#list.filter(([k, x]) => !(k === n && (v === undefined || x === v)));
      this.#update();
    }
    get(name) { const n = toUSV(name); const p = this.#list.find(([k]) => k === n); return p ? p[1] : null; }
    getAll(name) { const n = toUSV(name); return this.#list.filter(([k]) => k === n).map((p) => p[1]); }
    has(name, value) {
      const n = toUSV(name);
      const v = value === undefined ? undefined : toUSV(value);
      return this.#list.some(([k, x]) => k === n && (v === undefined || x === v));
    }
    set(name, value) {
      const n = toUSV(name), v = toUSV(value);
      const i = this.#list.findIndex(([k]) => k === n);
      if (i === -1) this.#list.push([n, v]);
      else { this.#list[i][1] = v; this.#list = this.#list.filter(([k], j) => k !== n || j === i); }
      this.#update();
    }
    sort() {
      // Stable, by UTF-16 code units, as the spec says.
      this.#list = this.#list.map((p, i) => [p, i]).sort((a, b) => (a[0][0] < b[0][0] ? -1 : a[0][0] > b[0][0] ? 1 : a[1] - b[1])).map((x) => x[0]);
      this.#update();
    }
    forEach(fn, thisArg) { for (const [k, v] of this.#list.slice()) fn.call(thisArg, v, k, this); }
    *entries() { for (const [k, v] of this.#list.slice()) yield [k, v]; }
    *keys() { for (const [k] of this.#list.slice()) yield k; }
    *values() { for (const [, v] of this.#list.slice()) yield v; }
    [Symbol.iterator]() { return this.entries(); }
    toString() { return formSerialize(this.#list); }
  }

  // ---------- URL ----------
  const SPECIAL = { 'ftp:': 21, 'file:': null, 'http:': 80, 'https:': 443, 'ws:': 80, 'wss:': 443 };
  const isSpecial = (scheme) => Object.prototype.hasOwnProperty.call(SPECIAL, scheme);
  const FORBIDDEN_HOST = /[\u0000\t\n\r #/:<>?@[\\\]^|]/;
  const FORBIDDEN_DOMAIN = /[\u0000-\u001f\t\n\r #%/:<>?@[\\\]^|\u007f]/;
  const invalid = (input) => new TypeError('Invalid URL: ' + String(input).slice(0, 200));

  const parseIPv4Number = (s) => {
    if (s === '') return NaN;
    let radix = 10;
    if (/^0[xX]/.test(s)) { s = s.slice(2); radix = 16; } else if (s.length > 1 && s[0] === '0') { s = s.slice(1); radix = 8; }
    if (s === '') return 0;
    const re = radix === 16 ? /^[0-9a-fA-F]+$/ : radix === 8 ? /^[0-7]+$/ : /^[0-9]+$/;
    return re.test(s) ? parseInt(s, radix) : NaN;
  };
  const endsInNumber = (host) => {
    const parts = host.split('.');
    if (parts[parts.length - 1] === '') { if (parts.length === 1) return false; parts.pop(); }
    const last = parts[parts.length - 1];
    if (last !== '' && /^[0-9]+$/.test(last)) return true;
    return !Number.isNaN(parseIPv4Number(last)) && /^0[xX]/.test(last);
  };
  const parseIPv4 = (host, input) => {
    const parts = host.split('.');
    if (parts[parts.length - 1] === '' && parts.length > 1) parts.pop();
    if (parts.length > 4) throw invalid(input);
    const nums = parts.map((p) => { const n = parseIPv4Number(p); if (Number.isNaN(n)) throw invalid(input); return n; });
    for (let i = 0; i < nums.length - 1; i++) if (nums[i] > 255) throw invalid(input);
    if (nums[nums.length - 1] >= 256 ** (5 - nums.length)) throw invalid(input);
    let ipv4 = nums[nums.length - 1];
    for (let i = 0; i < nums.length - 1; i++) ipv4 += nums[i] * 256 ** (3 - i);
    const out = [];
    for (let i = 0; i < 4; i++) { out.unshift(String(ipv4 % 256)); ipv4 = Math.floor(ipv4 / 256); }
    return out.join('.');
  };
  const parseIPv6 = (s, input) => {
    const pieces = [0, 0, 0, 0, 0, 0, 0, 0];
    let piece = 0, compress = null, i = 0;
    if (s[0] === ':') { if (s[1] !== ':') throw invalid(input); i = 2; piece = 1; compress = 1; }
    while (i < s.length) {
      if (piece === 8) throw invalid(input);
      if (s[i] === ':') { if (compress !== null) throw invalid(input); i++; piece++; compress = piece; continue; }
      let value = 0, length = 0;
      while (length < 4 && i < s.length && /[0-9a-fA-F]/.test(s[i])) { value = value * 16 + parseInt(s[i], 16); i++; length++; }
      if (s[i] === '.') {
        if (length === 0 || piece > 6) throw invalid(input);
        i -= length;
        const v4 = parseIPv4(s.slice(i), input).split('.').map(Number);
        if (!/^\d+\.\d+\.\d+\.\d+$/.test(s.slice(i))) throw invalid(input);
        pieces[piece] = v4[0] * 256 + v4[1];
        pieces[piece + 1] = v4[2] * 256 + v4[3];
        piece += 2;
        i = s.length;
        break;
      }
      if (s[i] === ':') { i++; if (i >= s.length) throw invalid(input); } else if (i < s.length) throw invalid(input);
      pieces[piece++] = value;
    }
    if (compress !== null) {
      let swaps = piece - compress;
      piece = 7;
      while (piece !== 0 && swaps > 0) { const t = pieces[compress + swaps - 1]; pieces[compress + swaps - 1] = pieces[piece]; pieces[piece] = t; piece--; swaps--; }
    } else if (piece !== 8) throw invalid(input);
    // Serialize: the longest run (length > 1) of zeros becomes "::".
    let best = -1, bestLen = 1;
    for (let a = 0; a < 8;) {
      if (pieces[a] !== 0) { a++; continue; }
      let b = a;
      while (b < 8 && pieces[b] === 0) b++;
      if (b - a > bestLen) { best = a; bestLen = b - a; }
      a = b;
    }
    let out = '';
    for (let k = 0; k < 8; k++) {
      if (k === best) { out += k === 0 ? '::' : ':'; k += bestLen - 1; continue; }
      out += pieces[k].toString(16) + (k < 7 ? ':' : '');
    }
    return '[' + out + ']';
  };
  const parseHost = (raw, special, input) => {
    if (raw.startsWith('[')) {
      if (!raw.endsWith(']')) throw invalid(input);
      return parseIPv6(raw.slice(1, -1), input);
    }
    if (!special) {
      if (FORBIDDEN_HOST.test(raw)) throw invalid(input);
      return encodeSet(raw, C0);
    }
    const domain = utf8Decode(percentDecodeBytes(raw), false).toLowerCase();
    if (domain === '' || /[^\u0000-\u007f]/.test(domain) || FORBIDDEN_DOMAIN.test(domain)) throw invalid(input);
    return endsInNumber(domain) ? parseIPv4(domain, input) : domain;
  };
  const shortenPath = (path, scheme) => {
    if (scheme === 'file:' && path.length === 1 && /^[A-Za-z]:$/.test(path[0])) return;
    path.pop();
  };
  const isSingleDot = (s) => s === '.' || s.toLowerCase() === '%2e';
  const isDoubleDot = (s) => ['..', '.%2e', '%2e.', '%2e%2e'].includes(s.toLowerCase());

  // Returns a record { scheme, username, password, host, port, path (array | string), query, fragment }.
  const parse = (input, base) => {
    let s = String(input).replace(/^[\u0000- ]+|[\u0000- ]+$/g, '').replace(/[\t\n\r]/g, '');
    const r = { scheme: '', username: '', password: '', host: null, port: null, path: [], query: null, fragment: null };
    const hashAt = s.indexOf('#');
    if (hashAt !== -1) { r.fragment = encodeSet(s.slice(hashAt + 1), FRAGMENT); s = s.slice(0, hashAt); }
    const m = /^([A-Za-z][A-Za-z0-9+.-]*):/.exec(s);
    let rest;
    if (m) {
      r.scheme = m[1].toLowerCase() + ':';
      rest = s.slice(m[0].length);
    } else {
      if (!base) throw invalid(input);
      if (typeof base.path === 'string') {
        if (s !== '' || hashAt === -1) throw invalid(input);
        return { ...base, fragment: r.fragment };
      }
      r.scheme = base.scheme;
      rest = null;
    }
    const special = isSpecial(r.scheme);
    const slash = (c) => c === '/' || (special && c === '\\');
    const splitQuery = (t) => {
      const q = t.indexOf('?');
      if (q === -1) return t;
      r.query = encodeSet(t.slice(q + 1), special ? SPECIAL_QUERY : QUERY);
      return t.slice(0, q);
    };
    const parsePath = (t, start) => {
      const path = start.slice();
      const segs = t.split(special ? /[/\\]/ : /\//);
      segs.forEach((seg, idx) => {
        const last = idx === segs.length - 1;
        if (isDoubleDot(seg)) { shortenPath(path, r.scheme); if (last) path.push(''); }
        else if (isSingleDot(seg)) { if (last) path.push(''); }
        else path.push(encodeSet(seg, PATH));
      });
      return path;
    };
    const authority = (t) => {
      // t starts after "//"
      let end = 0;
      while (end < t.length && !slash(t[end]) && t[end] !== '?') end++;
      let auth = t.slice(0, end);
      const at = auth.lastIndexOf('@');
      if (at !== -1) {
        const cred = auth.slice(0, at);
        auth = auth.slice(at + 1);
        const colon = cred.indexOf(':');
        r.username = encodeSet(colon === -1 ? cred : cred.slice(0, colon), USERINFO);
        r.password = colon === -1 ? '' : encodeSet(cred.slice(colon + 1), USERINFO);
      }
      let hostPart = auth, portPart = null;
      const close = auth.lastIndexOf(']');
      const colon = auth.lastIndexOf(':');
      if (colon > close) { hostPart = auth.slice(0, colon); portPart = auth.slice(colon + 1); }
      if (hostPart === '') {
        if (special && r.scheme !== 'file:') throw invalid(input);
        if (at !== -1 || portPart !== null) throw invalid(input);
        r.host = '';
      } else r.host = parseHost(hostPart, special, input);
      if (portPart !== null && portPart !== '') {
        if (!/^[0-9]+$/.test(portPart)) throw invalid(input);
        const port = parseInt(portPart, 10);
        if (port > 65535) throw invalid(input);
        r.port = SPECIAL[r.scheme] === port ? null : port;
      }
      if (r.scheme === 'file:' && r.host === 'localhost') r.host = '';
      return t.slice(end);
    };
    if (rest !== null) {
      if (!special) {
        if (rest.startsWith('//')) {
          const after = authority(rest.slice(2));
          r.path = parsePath(splitQuery(after).replace(/^\//, ''), []);
          if (after === '' || after.startsWith('?')) r.path = [];
        } else if (rest.startsWith('/')) {
          r.path = parsePath(splitQuery(rest).slice(1), []);
        } else {
          r.path = encodeSet(splitQuery(rest), C0); // opaque path
        }
        return r;
      }
      if (base && base.scheme === r.scheme && !slash(rest[0]) && r.scheme !== 'file:') {
        // "http:foo" relative to an http base
        return relative(rest, base, r, special, slash, splitQuery, parsePath);
      }
      const t = rest.replace(/^[/\\]*/, '');
      if (r.scheme === 'file:') {
        const two = /^[/\\]{2}/.test(rest);
        const after = two ? authority(rest.slice(2)) : rest;
        if (!two) r.host = '';
        r.path = parsePath(splitQuery(after).replace(/^[/\\]/, ''), []);
        return r;
      }
      const after = authority(t);
      r.path = parsePath(splitQuery(after).replace(/^[/\\]/, ''), []);
      return r;
    }
    return relative(s, base, r, special, slash, splitQuery, parsePath);
  };
  const relative = (s, base, r, special, slash, splitQuery, parsePath) => {
    r.scheme = base.scheme;
    if (slash(s[0]) && slash(s[1])) {
      return parse(r.scheme + s, null);
    }
    r.username = base.username; r.password = base.password; r.host = base.host; r.port = base.port;
    if (slash(s[0])) { r.path = parsePath(splitQuery(s).slice(1), []); return r; }
    if (s === '' || s[0] === '?') {
      r.path = base.path.slice();
      if (s === '') { r.query = base.query; } else splitQuery(s);
      return r;
    }
    const start = base.path.slice();
    shortenPath(start, r.scheme);
    r.path = parsePath(splitQuery(s), start);
    return r;
  };
  const serializePath = (r) => (typeof r.path === 'string' ? r.path : (r.host === null && r.path.length > 1 && r.path[0] === '' ? '/.' : '') + r.path.map((p) => '/' + p).join(''));
  const serialize = (r, excludeFragment) => {
    let out = r.scheme;
    if (r.host !== null) {
      out += '//';
      if (r.username !== '' || r.password !== '') out += r.username + (r.password !== '' ? ':' + r.password : '') + '@';
      out += r.host + (r.port !== null ? ':' + r.port : '');
    }
    out += serializePath(r);
    if (r.query !== null) out += '?' + r.query;
    if (!excludeFragment && r.fragment !== null) out += '#' + r.fragment;
    return out;
  };

  class URL {
    #r; #params;
    constructor(url, base) {
      const b = base === undefined ? null : parse(String(base), null);
      this.#r = parse(String(url), b);
      this.#params = new URLSearchParams();
      attachParams(this.#params, this, this.#r.query || '');
    }
    static canParse(url, base) { try { new URL(url, base); return true; } catch (e) { return false; } }
    static { setQuery = (url, query) => { url.#r.query = query; }; }
    get href() { return serialize(this.#r, false); }
    set href(v) { this.#r = parse(String(v), null); resetParams(this.#params, this.#r.query || ''); }
    get origin() {
      const s = this.#r.scheme;
      if (s === 'blob:') { try { return new URL(serializePath(this.#r)).origin; } catch (e) { return 'null'; } }
      if (!isSpecial(s) || s === 'file:') return 'null';
      return s + '//' + this.#r.host + (this.#r.port !== null ? ':' + this.#r.port : '');
    }
    get protocol() { return this.#r.scheme; }
    get username() { return this.#r.username; }
    get password() { return this.#r.password; }
    get host() { return this.#r.host === null ? '' : this.#r.host + (this.#r.port !== null ? ':' + this.#r.port : ''); }
    get hostname() { return this.#r.host === null ? '' : this.#r.host; }
    get port() { return this.#r.port === null ? '' : String(this.#r.port); }
    get pathname() { return serializePath(this.#r); }
    set pathname(v) {
      if (typeof this.#r.path === 'string') return;
      const special = isSpecial(this.#r.scheme);
      const t = String(v).replace(/[\t\n\r]/g, '');
      const fixed = this.#r.scheme + (this.#r.host !== null ? '//' + this.host : '') + (t.startsWith('/') || (special && t.startsWith('\\')) ? '' : '/') + t.replace(/[?#]/g, (c) => (c === '?' ? '%3F' : '%23'));
      const p = parse(fixed, null);
      this.#r.path = p.path;
    }
    get search() { return this.#r.query === null || this.#r.query === '' ? '' : '?' + this.#r.query; }
    set search(v) {
      let t = String(v).replace(/[\t\n\r]/g, '');
      if (t.startsWith('?')) t = t.slice(1);
      this.#r.query = t === '' && String(v) === '' ? null : encodeSet(t, isSpecial(this.#r.scheme) ? SPECIAL_QUERY : QUERY);
      resetParams(this.#params, this.#r.query || '');
    }
    get searchParams() { return this.#params; }
    get hash() { return this.#r.fragment === null || this.#r.fragment === '' ? '' : '#' + this.#r.fragment; }
    set hash(v) {
      let t = String(v).replace(/[\t\n\r]/g, '');
      if (t.startsWith('#')) t = t.slice(1);
      this.#r.fragment = t === '' && String(v) === '' ? null : encodeSet(t, FRAGMENT);
    }
    toString() { return this.href; }
    toJSON() { return this.href; }
  }
  for (const [name, value] of [['URL', freezeClass(URL)], ['URLSearchParams', freezeClass(URLSearchParams)],
    ['TextEncoder', freezeClass(TextEncoder)], ['TextDecoder', freezeClass(TextDecoder)],
    ['atob', Object.freeze(atob)], ['btoa', Object.freeze(btoa)]]) define(name, value);
})();
