// The `kino` API a plugin sees, and the JS half of the bridge to Kotlin (PluginRuntime). Loaded
// after web.js, as `(<this file>)(env, L)`: env = { apiVersion, appVersion, lang }, L = the limits
// PluginRuntime passes (every number lives in Kotlin, pinned to docs/plugins/contract.json).
//
// Everything that crosses into Kotlin is capped HERE, before it crosses: the 64 MB memory limit
// bounds only the QuickJS heap, so a string that reached Kotlin would already be copied onto the
// app's heap (a 40 MB `home()` answer was an OOM on a TV box). The built-ins it relies on are
// captured at load so a plugin can't swap them; `__kinoNative` is emptied and frozen (only these
// wrappers hold its functions); every function here is frozen, so its `name` can't be changed.
(function (env, L) {
  'use strict';
  // quickjs-kt defines __kinoNative non-configurable, so it can't be deleted; its functions can
  // (they work unbound). Take them, then leave an empty frozen object.
  const native = globalThis.__kinoNative;
  const n = {};
  for (const k of Object.getOwnPropertyNames(native)) { n[k] = native[k]; delete native[k]; }
  Object.freeze(native);
  const S = String, E = Error, TE = TypeError, stringify = JSON.stringify, parse = JSON.parse;
  const slice = Function.prototype.call.bind(String.prototype.slice);
  const charCodeAt = Function.prototype.call.bind(String.prototype.charCodeAt);
  const define = Object.defineProperty, freeze = Object.freeze, keysOf = Object.keys;
  const defineAll = Object.defineProperties, reflectDefine = Reflect.defineProperty;
  const ownKeys = Reflect.ownKeys, apply = Reflect.apply, ownDescriptor = Object.getOwnPropertyDescriptor;
  const isInteger = Number.isInteger;
  const OP = Object.prototype, defineGetter = OP.__defineGetter__, defineSetter = OP.__defineSetter__;
  const hasOwn = Function.prototype.call.bind(OP.hasOwnProperty);

  // A function's `name` is read by quickjs-kt's native code when an error is built or a rejection
  // is tracked in that function's frame; at ~20 MB the native side fails an allocation and
  // crashes the whole process (measured). BEST-EFFORT guard: these define APIs refuse a long name,
  // an accessor name, or making it writable. It is not airtight -- `delete g.name` +
  // `Object.setPrototypeOf` + assignment, or a huge computed key, still produce a huge name -- and
  // the crash sentinel (PluginCrashSentinel) is the backstop. Everything else passes through.
  const nameRefused = () => new TE('el nombre de una función no puede cambiarse a más de ' + L.maxFunctionNameChars + ' caracteres');
  const safeNameDescriptor = (desc) => {
    if (desc === null || typeof desc !== 'object') return desc;
    const d = {};
    for (const k of ['value', 'writable', 'enumerable', 'configurable', 'get', 'set']) if (k in desc) d[k] = desc[k];
    // The flags are booleans the way the engine reads them: `writable: 1` is writable.
    for (const k of ['writable', 'enumerable', 'configurable']) if (k in d) d[k] = !!d[k];
    if ('get' in d || 'set' in d || d.writable === true) throw nameRefused();
    if (typeof d.value === 'string' && d.value.length > L.maxFunctionNameChars) throw nameRefused();
    return d;
  };
  const isName = (key) => typeof key !== 'symbol' && S(key) === 'name';
  Object.defineProperty = freeze(function defineProperty(o, key, desc) {
    if (typeof o === 'function' && isName(key)) return define(o, 'name', safeNameDescriptor(desc));
    return define(o, key, desc);
  });
  Object.defineProperties = freeze(function defineProperties(o, props) {
    if (typeof o !== 'function' || props === null || typeof props !== 'object') return defineAll(o, props);
    // Same keys as the native algorithm (own ENUMERABLE ones, string or symbol), each descriptor
    // read once; defined on the copy, never assigned, so "__proto__" stays an ordinary key.
    const copy = {};
    for (const k of ownKeys(props)) {
      const own = ownDescriptor(props, k);
      if (own === undefined || !own.enumerable) continue;
      const desc = props[k];
      define(copy, k, { value: isName(k) ? safeNameDescriptor(desc) : desc, enumerable: true, configurable: true, writable: true });
    }
    return defineAll(o, copy);
  });
  Reflect.defineProperty = freeze(function defineProperty(o, key, desc) {
    // A non-object descriptor goes straight to the native one, which throws TypeError.
    if (typeof o === 'function' && isName(key) && desc !== null && typeof desc === 'object') {
      let safe;
      try { safe = safeNameDescriptor(desc); } catch (e) { if (e instanceof TE) return false; throw e; }
      return reflectDefine(o, 'name', safe);
    }
    return reflectDefine(o, key, desc);
  });
  define(OP, '__defineGetter__', { value: freeze(function __defineGetter__(key, fn) {
    if (typeof this === 'function' && isName(key)) throw nameRefused();
    return apply(defineGetter, this, [key, fn]);
  }), writable: true, configurable: true, enumerable: false });
  define(OP, '__defineSetter__', { value: freeze(function __defineSetter__(key, fn) {
    if (typeof this === 'function' && isName(key)) throw nameRefused();
    return apply(defineSetter, this, [key, fn]);
  }), writable: true, configurable: true, enumerable: false });

  const toStr = (x) => (typeof x === 'string' ? x : S(x));
  const cut = (s, max) => (s.length > max ? slice(s, 0, max) : s);
  const str = (x) => { if (typeof x === 'string') return x; try { return toStr(stringify(x)); } catch (e) { return toStr(x); } };
  const line = (args) => { let out = ''; for (let i = 0; i < args.length && out.length <= L.maxLogChars; i++) out += (i ? ' ' : '') + str(args[i]); return cut(out, L.maxLogChars); };
  const log = (level, args) => n.log(level, line(args));

  // Typed errors. The name carries the code -- "KinoError_auth_required" -- because a throw
  // before an async function's first await never reaches __kinoCall (quickjs-kt alpha13 aborts the
  // whole evaluate with it): Kotlin then reads the code from the engine's "<name>: <message>" text.
  // Both parts are short by construction (validated code, message cut to L.maxErrorMessageChars),
  // so native code never formats a plugin-sized name; and the name is a valid JNI class name that
  // names no class, because quickjs-kt hands it to FindClass (a '[' aborted a debug build). See
  // PluginErrors.
  // [a-z_]{1,32}, checked without RegExp: RegExp.prototype.test looks up `exec` at call time, and
  // a plugin that replaced it could pass a 30 MB "code" into an error name.
  const isCode = (c) => {
    if (typeof c !== 'string' || c.length < 1 || c.length > 32) return false;
    for (let i = 0; i < c.length; i++) { const x = charCodeAt(c, i); if (!((x >= 97 && x <= 122) || x === 95)) return false; }
    return true;
  };
  const codedError = (code, message) => {
    const c = isCode(code) ? code : 'unknown';
    let m;
    try { m = message === undefined || message === null ? '' : toStr(message); } catch (e) { m = ''; }
    const err = new E(cut(m, L.maxErrorMessageChars));
    define(err, 'name', { value: 'KinoError_' + c, writable: false, configurable: false, enumerable: false });
    define(err, 'code', { value: c, writable: false, configurable: false, enumerable: true });
    return err;
  };

  // --- kino.fetch ---
  const fetch = async function fetch(url, opts) {
    const o = opts || {};
    const req = toStr(stringify({
      url: toStr(url), method: o.method || 'GET', headers: o.headers || {},
      body: o.body == null ? null : toStr(o.body), timeoutMs: o.timeoutMs || 0,
    }));
    if (req.length > L.maxRequestChars) {
      // A throw before this async function's first await would abort the whole call in alpha13
      // even inside the plugin's try/catch; after one it's catchable.
      await null;
      throw new E('solicitud demasiado grande (más de 1 MB)');
    }
    const r = parse(await n.fetch(req));
    return { ok: r.ok, status: r.status, url: r.url, headers: r.headers,
             text: () => r.body, json: () => parse(r.body) };
  };

  // --- kino.crypto (synchronous; errors carry code crypto_error) ---
  const ENC = ['utf8', 'hex', 'base64'];
  const maxCharsFor = (encoding) => (encoding === 'hex' ? L.cryptoMaxDataBytes * 2 : encoding === 'base64' ? (((L.cryptoMaxDataBytes + 2) / 3) | 0) * 4 + 4 : L.cryptoMaxDataBytes);
  const cryptoCall = (op) => {
    const req = {};
    // Index-based, not for...of: for...of on the array keysOf(op) resolves through the LIVE
    // Array.prototype[Symbol.iterator], not a captured built-in. A plugin that replaces it (see
    // the hostile test) would make this loop silently visit nothing, dropping every field instead
    // of refusing an oversized one -- the opposite of what this cap exists to do.
    const opKeys = keysOf(op);
    for (let i = 0; i < opKeys.length; i++) {
      const k = opKeys[i];
      const v = op[k];
      // A field the caller left out arrives as undefined; one set to null (e.g. `{ padding: null }`)
      // must fall back to its Kotlin default the same way, on every platform -- the two org.json
      // builds this app ships against (JVM unit tests vs. Android) disagree on what a literal JSON
      // null becomes on the other side of optString.
      if (v === undefined || v === null) continue;
      if (typeof v === 'string' && (k === 'in' || k === 'out' || k === 'keyEnc' || k === 'ivEnc' || k === 'aadEnc') && ENC.indexOf(v) === -1) {
        throw codedError('crypto_error', 'codificación desconocida: ' + cut(v, 20));
      }
      req[k] = v;
    }
    const fields = [['data', 'in'], ['key', 'keyEnc'], ['iv', 'ivEnc'], ['aad', 'aadEnc'], ['password', 'keyEnc'], ['salt', 'in']];
    for (let i = 0; i < fields.length; i++) {
      const field = fields[i][0], enc = fields[i][1];
      if (req[field] === undefined) continue;
      req[field] = toStr(req[field]);
      // "data" is the one field whose Kotlin-side default encoding depends on the operation:
      // encrypt defaults to utf8 in, decrypt to base64 in (it decrypts what encrypt just gave back).
      // A base64 5 MB payload is ~7 M characters, well past the utf8-sized cap -- using the wrong
      // default here refused a decrypt of ciphertext that came from a perfectly valid 5 MB encrypt.
      const defaultEnc = field === 'data' && req.op === 'decrypt' ? 'base64' : 'utf8';
      if (req[field].length > maxCharsFor(req[enc] || defaultEnc)) throw codedError('crypto_error', '"' + field + '" pasa de 5 MB');
    }
    // The per-field checks above give the clear message; this one is the cap that always holds.
    const json = toStr(stringify(req));
    if (json.length > L.cryptoMaxRequestChars) throw codedError('crypto_error', 'datos demasiado grandes (más de 5 MB)');
    const r = parse(n.crypto(json));
    if (hasOwn(r, 'error')) throw codedError('crypto_error', r.error);
    return r.ok;
  };
  const opts = (o) => (o === undefined || o === null ? {} : o);
  const crypto = freeze({
    hash: freeze(function hash(alg, data, o) {
      const p = opts(o);
      return cryptoCall({ op: 'hash', alg: toStr(alg), data, in: p.inputEncoding, out: p.outputEncoding });
    }),
    hmac: freeze(function hmac(alg, key, data, o) {
      const p = opts(o);
      return cryptoCall({ op: 'hmac', alg: toStr(alg), key, data, keyEnc: p.keyEncoding, in: p.inputEncoding, out: p.outputEncoding });
    }),
    encrypt: freeze(function encrypt(alg, o) {
      const p = opts(o);
      return cryptoCall({ op: 'encrypt', alg: toStr(alg), key: p.key, iv: p.iv, data: p.data, aad: p.aad, padding: p.padding, keyEnc: p.keyEncoding, ivEnc: p.ivEncoding, aadEnc: p.aadEncoding, in: p.inputEncoding, out: p.outputEncoding });
    }),
    decrypt: freeze(function decrypt(alg, o) {
      const p = opts(o);
      return cryptoCall({ op: 'decrypt', alg: toStr(alg), key: p.key, iv: p.iv, data: p.data, aad: p.aad, padding: p.padding, keyEnc: p.keyEncoding, ivEnc: p.ivEncoding, aadEnc: p.aadEncoding, in: p.inputEncoding, out: p.outputEncoding });
    }),
    pbkdf2: freeze(function pbkdf2(hash, password, salt, iterations, keyLength, o) {
      const p = opts(o);
      if (!isInteger(iterations) || !isInteger(keyLength)) throw codedError('crypto_error', 'iterations y keyLength deben ser enteros');
      return cryptoCall({ op: 'pbkdf2', hash: toStr(hash), password, salt, iterations, keyLength, keyEnc: p.keyEncoding, in: p.inputEncoding, out: p.outputEncoding });
    }),
    randomBytes: freeze(function randomBytes(count, outputEncoding) {
      if (!isInteger(count)) throw codedError('crypto_error', 'randomBytes necesita un entero');
      return cryptoCall({ op: 'random', n: count, out: outputEncoding });
    }),
    uuid: freeze(function uuid() { return cryptoCall({ op: 'uuid' }); }),
  });

  // --- kino.sleep ---
  const sleep = async function sleep(ms) {
    await null; // see kino.fetch: checks go after the first await
    if (!isInteger(ms) || ms < 0 || ms > L.sleepMaxMs) throw codedError('invalid_request', 'kino.sleep acepta de 0 a ' + L.sleepMaxMs + ' ms');
    await n.sleep(ms);
  };

  // --- kino.storage ---
  const storage = freeze({
    get: freeze(function get(k) { const key = toStr(k); if (key.length > L.storageMaxBytes) return null; const v = n.storageGet(key); return v == null ? null : v; }),
    set: freeze(function set(k, v) {
      const key = toStr(k), value = toStr(v);
      if (key.length + value.length > L.storageMaxBytes) throw new E('almacenamiento del plugin lleno (256 KB)');
      n.storageSet(key, value);
    }),
    remove: freeze(function remove(k) { const key = toStr(k); if (key.length <= L.storageMaxBytes) n.storageRemove(key); }),
    keys: freeze(function keys() { return parse(n.storageKeys()); }),
  });

  // --- kino.config: read once, read-only. Values are bounded by the manifest's settings schema. ---
  const configValues = freeze(parse(n.config()));
  const config = freeze({
    get: freeze(function get(key) { const k = toStr(key); return hasOwn(configValues, k) ? configValues[k] : undefined; }),
    all: freeze(function all() { const out = {}; for (const k of keysOf(configValues)) out[k] = configValues[k]; return out; }),
  });

  const kino = {
    apiVersion: env.apiVersion,
    appVersion: env.appVersion,
    lang: env.lang,
    fetch: freeze(fetch),
    html: freeze({
      select: freeze(function select(html, css) {
        const selector = toStr(css);
        if (selector.length > L.maxSelectorChars) throw new E('selector CSS demasiado largo (más de ' + L.maxSelectorChars + ' caracteres)');
        return parse(n.select(cut(toStr(html), L.maxHtmlChars), selector));
      }),
    }),
    storage,
    config,
    crypto,
    sleep: freeze(sleep),
    error: freeze(function error(code, message) { return codedError(code, message); }),
    log: freeze((...a) => log('info', a)),
  };
  globalThis.kino = freeze(kino);
  globalThis.console = freeze({
    log: freeze((...a) => log('info', a)), info: freeze((...a) => log('info', a)),
    warn: freeze((...a) => log('warn', a)), error: freeze((...a) => log('error', a)),
  });
  // quickjs-kt alpha13 aborts the whole call on a promise rejected before anyone awaits it, even
  // inside try/catch. Deferring Promise.reject by one job lets the awaiting caller attach its
  // handler first. See QuickJsSpikeTest.
  Promise.reject = freeze((e) => Promise.resolve().then(() => { throw e; }));
  // A thrown value's message reaches Kotlin (and the screen) as the exception text, so it's
  // rebuilt here: a short plain string, no stack. Every step can be hostile (a throwing getter or
  // toString, a Proxy, a Symbol, 30 MB of text), hence the captured built-ins and the fallback.
  // What __kinoCall throws carries an OWN, fixed name: native code formats an error as
  // "<name>: <message>" and would otherwise read a plugin-controlled Error.prototype.name (30 MB,
  // measured). The prototype itself is left alone so `this.name = 'MyErr'` in a plugin's Error
  // subclass keeps working.
  const kinoError = (message, code) => {
    const err = new E(message);
    define(err, 'name', { value: code ? 'KinoError_' + code : 'Error', writable: false, configurable: false, enumerable: false });
    return err;
  };
  const errorText = (e) => {
    try {
      let m = e;
      if (e !== null && (typeof e === 'object' || typeof e === 'function')) {
        const own = e.message;
        if (own !== undefined) m = own;
      }
      if (m === null || m === undefined || typeof m === 'symbol') return L.thrownFallback;
      const text = typeof m === 'string' ? m : S(m);
      if (typeof text !== 'string' || text.length === 0) return L.thrownFallback;
      return cut(text, L.maxErrorChars);
    } catch (_) {
      return L.thrownFallback;
    }
  };
  // The code of a typed error, read without running plugin code (own data property only).
  const errorCode = (e) => {
    try {
      if (e === null || typeof e !== 'object') return null;
      const d = ownDescriptor(e, 'code');
      return d && isCode(d.value) ? d.value : null;
    } catch (_) {
      return null;
    }
  };
  // Frozen too: its `name` is read natively when the rethrow below builds an error in its frame
  // (a plugin renamed it to 20 MB and crashed the process, measured).
  define(globalThis, '__kinoCall', {
    value: freeze(async (name, argJson) => {
      let out;
      try {
        const fn = globalThis.__kinoExports[name];
        if (typeof fn !== 'function') throw new E('el plugin no exporta ' + name);
        out = stringify(await fn(parse(argJson)));
      } catch (e) {
        const text = errorText(e);
        // A thrown value with no message leaves nothing to go on: log at least its type.
        if (text === L.thrownFallback) log('warn', ['a call failed with a value that has no message, of type', typeof e]);
        throw kinoError(text, errorCode(e));
      }
      if (typeof out !== 'string') return 'null';
      if (out.length > L.maxResultChars) throw kinoError(L.resultTooBig, null);
      return out;
    }),
    writable: false, configurable: false, enumerable: false,
  });
})
