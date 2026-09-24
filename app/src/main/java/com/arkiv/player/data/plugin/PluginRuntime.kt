package com.arkiv.player.data.plugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** What a plugin can reach: everything else (files, settings, other plugins, app clients) is absent. */
interface PluginHost {
    suspend fun fetch(requestJson: String): String
    fun select(html: String, css: String): String
    fun storageGet(key: String): String?
    fun storageSet(key: String, value: String)
    fun storageRemove(key: String)
    fun log(level: String, message: String)
}

data class PluginEnv(
    val appVersion: String,
    val lang: String = "es-CO",
    val apiVersion: Int = ManifestParser.SUPPORTED_API,
    val memoryLimitBytes: Long = 64L * 1024 * 1024,
    val maxStackBytes: Long = 1024L * 1024,
    val loadTimeoutMs: Long = 10_000,
)

open class PluginException(message: String, cause: Throwable? = null) : Exception(message, cause)
class PluginTimeoutException(function: String, ms: Long) :
    PluginException("${function.take(100)} no respondió en ${(ms + 999) / 1000} s") {
    /** The limit that was exceeded, rounded up to whole seconds: what the person is told. */
    val seconds: Long = (ms + 999) / 1000
}
class PluginScriptException(message: String, cause: Throwable? = null) : PluginException(message, cause)
class PluginDamagedException : PluginException("Archivos dañados, reinstálalo")

/** What `PluginRuntimePool` needs from a runtime; lets the pool be tested without QuickJS. */
interface ScriptRuntime {
    val exports: Set<String>
    val isDiscarded: Boolean
    suspend fun call(function: String, argJson: String, timeoutMs: Long): String
    fun close()
}

/**
 * One plugin's QuickJS sandbox (quickjs-kt 1.0.0-alpha13), on its own thread.
 *
 * Every QuickJS touch — creation, evaluation, async-binding resumptions, close — runs on that one
 * thread: the engine isn't thread-safe. A call is started on the runtime's scope and AWAITED under
 * `withTimeout`, so a synchronous infinite loop can't hold the caller: it gets
 * [PluginTimeoutException] and the runtime is discarded. The engine can't be interrupted, so the
 * stuck evaluation keeps its thread until it returns (forever, for `while(true){}`); the runtime is
 * closed only after the in-flight call completes, because closing mid-evaluation frees the context
 * the evaluation is about to read. See QuickJsSpikeTest for every engine quirk relied on here.
 *
 * Data crosses as JSON strings only: the prelude wraps each export as
 * `async (name, argJson) => JSON.stringify(await exports[name](JSON.parse(argJson)))`.
 */
class PluginRuntime private constructor(
    private val executor: ExecutorService,
    dispatcher: CoroutineDispatcher,
    private val js: QuickJs,
    override val exports: Set<String>,
) : ScriptRuntime {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private var inFlight: Deferred<String>? = null
    private var closing = false

    @Volatile override var isDiscarded = false
        private set

    override suspend fun call(function: String, argJson: String, timeoutMs: Long): String {
        val job = synchronized(lock) {
            if (isDiscarded) throw PluginScriptException("El plugin se reinició, vuelve a intentar")
            val code = "await __kinoCall(${JSONObject.quote(function)}, ${JSONObject.quote(argJson)})"
            scope.async { js.evaluate<String>(code) }.also { inFlight = it }
        }
        try {
            val result = withTimeout(timeoutMs) { job.await() }
            // The prelude's __kinoCall already refuses this in JS; this only keeps a bigger string
            // (should the prelude ever be bypassed) away from the JSON parsers downstream.
            if (result.length > MAX_RESULT_CHARS) throw PluginScriptException(RESULT_TOO_BIG)
            return result
        } catch (e: TimeoutCancellationException) {
            close()
            throw PluginTimeoutException(function, timeoutMs)
        } catch (e: CancellationException) {
            // The CALLER was cancelled (e.g. a superseded search), not our own timeout: `job` keeps
            // running on the runtime's thread regardless, since it's on `scope`, not the caller's.
            // Left alone, it would be an orphaned evaluation a later call queues behind on the same
            // single thread, so that call's own timeout clock would run against the orphan's work
            // instead of its own. Discard the runtime instead: close() is deferred until `job`
            // (still active) actually completes, so nothing here is torn down mid-evaluation.
            if (job.isActive) close()
            throw e
        } catch (e: QuickJsException) {
            // Out of memory (and similar engine-level failures) can leave the runtime unusable:
            // measured, the next evaluate threw "Result promise not found". Probe it; if it's
            // broken, discard it so the pool opens a fresh one.
            if (!isHealthy()) close()
            throw PluginScriptException(errorText(e.message, "Error del plugin"), boundedCause(e))
        } catch (e: Exception) {
            // A Kotlin exception from a binding (e.g. HostNotAllowedException) that JS didn't catch.
            throw PluginScriptException(errorText(e.message, e.javaClass.simpleName), boundedCause(e))
        } finally {
            synchronized(lock) { if (inFlight === job && job.isCompleted) inFlight = null }
        }
    }

    private suspend fun isHealthy(): Boolean =
        runCatching { withTimeout(1_000) { scope.async { js.evaluate<Long>("1") }.await() } == 1L }.getOrDefault(false)

    /** Idempotent. Frees the engine now if idle, otherwise right after the in-flight call returns. */
    override fun close() {
        val pending = synchronized(lock) {
            if (closing) return
            closing = true
            isDiscarded = true
            inFlight?.takeIf { it.isActive }
        }
        if (pending == null) {
            scope.launch { closeNow() }
        } else {
            pending.invokeOnCompletion { scope.launch { closeNow() } }
        }
    }

    private fun closeNow() {
        runCatching { js.close() }
        scope.cancel()
        executor.shutdown()
    }

    companion object {
        /** The longest JSON a capability call may return; checked in JS, before it crosses. */
        const val MAX_RESULT_CHARS = 2_000_000

        private const val RESULT_TOO_BIG = "respuesta del plugin demasiado grande (más de 2 millones de caracteres)"

        /** The longest `kino.fetch` request (URL, headers and body, as JSON); checked in JS. */
        const val MAX_REQUEST_CHARS = 1_048_576

        /** Any error text from a plugin (a thrown value, a load failure) is cut to this. */
        const val MAX_ERROR_CHARS = 2_000

        /**
         * Kotlin-side cut of any error text, whatever its origin: the prelude already shortens what
         * a call throws, but a module's top-level throw, the engine's own messages and binding
         * exceptions don't go through it. Never blank.
         */
        private fun errorText(message: String?, fallback: String): String =
            message?.takeIf { it.isNotBlank() }?.take(MAX_ERROR_CHARS) ?: fallback

        /**
         * [e] as a cause only if no message in its chain is longer than [MAX_ERROR_CHARS]: the
         * cause is logged with its whole chain (e.g. PlayerViewModel's `Log.w(..., failure)`), so
         * a huge engine message there would be copied again. Otherwise no cause at all; the
         * already-cut text is in the wrapping exception's own message.
         */
        private fun boundedCause(e: Throwable): Throwable? =
            e.takeIf { generateSequence(e) { it.cause }.take(10).all { (it.message?.length ?: 0) <= MAX_ERROR_CHARS } }

        /** The longest `name` a plugin may give a function (see the prelude's define guards). */
        const val MAX_FUNCTION_NAME_CHARS = 1_000

        /** What a thrown null, undefined, Symbol or unreadable value becomes. */
        private const val THROWN_FALLBACK = "el plugin falló sin decir por qué"

        /** `console.*` / `kino.log` lines are cut to this many characters in JS. */
        const val MAX_LOG_CHARS = 2_000

        /** The longest CSS selector `kino.html.select` accepts. */
        const val MAX_SELECTOR_CHARS = 10_000

        /** `kino.storage` holds 64 KB in total, so a key plus value longer than this can never fit. */
        private const val STORAGE_CHARS = 64 * 1024

        /**
         * Loads [script] as an ES module. Fails with [PluginScriptException] on a syntax error or a
         * throw at module top level, and [PluginTimeoutException] if loading takes longer than
         * [PluginEnv.loadTimeoutMs] (a top-level infinite loop leaks that thread, see the class KDoc).
         */
        @OptIn(ExperimentalCoroutinesApi::class) // Deferred.getCompleted(), read only after completion is confirmed
        suspend fun open(label: String, script: String, host: PluginHost, env: PluginEnv): PluginRuntime {
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "plugin-$label").apply { isDaemon = true } }
            val dispatcher = executor.asCoroutineDispatcher()
            val loading = CoroutineScope(SupervisorJob() + dispatcher).async {
                val js = QuickJs.create(jobDispatcher = dispatcher)
                try {
                    js.memoryLimit = env.memoryLimitBytes
                    js.maxStackSize = env.maxStackBytes
                    bind(js, host)
                    js.evaluate<Any?>(code = prelude(env), filename = "prelude.js", asModule = false)
                    js.addModule("plugin.js", script)
                    js.evaluate<Any?>(
                        code = "import * as p from 'plugin.js'; " +
                            "Object.defineProperty(globalThis, '__kinoExports', { value: p, writable: false, configurable: false });",
                        filename = "loader.js",
                        asModule = true,
                    )
                    // alpha13: the first global evaluate after a module evaluate always throws.
                    runCatching { js.evaluate<Any?>("0") }
                    val names = js.evaluate<String>(
                        "Object.keys(__kinoExports).filter(k => typeof __kinoExports[k] === 'function').join(',')",
                    )
                    PluginRuntime(executor, dispatcher, js, names.split(',').filter { it.isNotEmpty() }.toSet())
                } catch (e: Throwable) {
                    runCatching { js.close() }
                    throw e
                }
            }
            return try {
                withTimeout(env.loadTimeoutMs) { loading.await() }
            } catch (e: TimeoutCancellationException) {
                // `loading` runs on its own scope, independent of this withTimeout block, so it
                // keeps going after we give up on it — a script whose top level is merely slow, not
                // stuck, still finishes. Left alone that builds a PluginRuntime nobody holds (its
                // QuickJs and executor thread leaked forever) or, on failure, leaves the executor
                // never shut down (the async block below only closes `js` on its own failure path).
                // Attach a completion handler so whichever happens gets cleaned up once `loading`
                // actually finishes, instead of right now while it's still running.
                loading.invokeOnCompletion { t -> if (t == null) loading.getCompleted().close() else executor.shutdown() }
                throw PluginTimeoutException("La carga del plugin", env.loadTimeoutMs)
            } catch (e: CancellationException) {
                executor.shutdown()
                throw e
            } catch (e: QuickJsException) {
                executor.shutdown()
                throw PluginScriptException(errorText(e.message, "El plugin no carga"), boundedCause(e))
            } catch (e: Exception) {
                executor.shutdown()
                throw PluginScriptException(errorText(e.message, e.javaClass.simpleName), boundedCause(e))
            }
        }

        private fun bind(js: QuickJs, host: PluginHost) {
            js.define("__kinoNative") {
                asyncFunction("fetch") { args -> host.fetch(args[0] as String) }
                function("select") { args -> host.select(args[0] as String, args[1] as String) }
                function("storageGet") { args -> host.storageGet(args[0] as String) }
                function("storageSet") { args -> host.storageSet(args[0] as String, args[1] as String) }
                function("storageRemove") { args -> host.storageRemove(args[0] as String) }
                function("log") { args -> host.log(args[0] as String, args[1] as String) }
            }
        }

        /**
         * The JS side of the bridge. Everything that crosses into Kotlin is capped HERE, before it
         * crosses: the 64 MB memory limit bounds only the QuickJS heap, so a string that reached
         * Kotlin would already be copied onto the app's heap (a 40 MB `home()` answer was an OOM
         * on a TV box). The prelude captures the built-ins it relies on so a plugin can't swap
         * them, empties and freezes `__kinoNative` (only these wrappers hold its functions), and
         * defines `__kinoCall` non-writable and non-configurable.
         */
        private fun prelude(env: PluginEnv): String = """
            (() => {
              // quickjs-kt defines __kinoNative non-configurable, so it can't be deleted; its
              // functions can (they work unbound). Take them, then leave an empty frozen object.
              const native = globalThis.__kinoNative;
              const n = {};
              for (const k of Object.getOwnPropertyNames(native)) { n[k] = native[k]; delete native[k]; }
              Object.freeze(native);
              const S = String, E = Error, TE = TypeError, stringify = JSON.stringify, parse = JSON.parse;
              const slice = Function.prototype.call.bind(String.prototype.slice);
              const define = Object.defineProperty, freeze = Object.freeze;
              const defineAll = Object.defineProperties, reflectDefine = Reflect.defineProperty;
              const ownKeys = Reflect.ownKeys, apply = Reflect.apply, ownDescriptor = Object.getOwnPropertyDescriptor;
              const OP = Object.prototype, defineGetter = OP.__defineGetter__, defineSetter = OP.__defineSetter__;
              // A function's `name` is read by quickjs-kt's native code when an error is built or
              // a rejection is tracked in that function's frame; at ~20 MB the native side fails
              // an allocation and crashes the whole process (measured). BEST-EFFORT guard: these
              // define APIs refuse a long name, an accessor name, or making it writable. It is not
              // airtight -- `delete g.name` + `Object.setPrototypeOf` + assignment, or a huge
              // computed key, still produce a huge name -- and the crash sentinel
              // (PluginCrashSentinel) is the backstop. Everything else passes through.
              const nameRefused = () => new TE('el nombre de una función no puede cambiarse a más de $MAX_FUNCTION_NAME_CHARS caracteres');
              const safeNameDescriptor = (desc) => {
                if (desc === null || typeof desc !== 'object') return desc;
                const d = {};
                for (const k of ['value', 'writable', 'enumerable', 'configurable', 'get', 'set']) if (k in desc) d[k] = desc[k];
                // The flags are booleans the way the engine reads them: `writable: 1` is writable.
                for (const k of ['writable', 'enumerable', 'configurable']) if (k in d) d[k] = !!d[k];
                if ('get' in d || 'set' in d || d.writable === true) throw nameRefused();
                if (typeof d.value === 'string' && d.value.length > $MAX_FUNCTION_NAME_CHARS) throw nameRefused();
                return d;
              };
              const isName = (key) => typeof key !== 'symbol' && S(key) === 'name';
              Object.defineProperty = freeze(function defineProperty(o, key, desc) {
                if (typeof o === 'function' && isName(key)) return define(o, 'name', safeNameDescriptor(desc));
                return define(o, key, desc);
              });
              Object.defineProperties = freeze(function defineProperties(o, props) {
                if (typeof o !== 'function' || props === null || typeof props !== 'object') return defineAll(o, props);
                // Same keys as the native algorithm (own ENUMERABLE ones, string or symbol), each
                // descriptor read once; defined on the copy, never assigned, so "__proto__" stays
                // an ordinary key.
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
              const line = (args) => { let out = ''; for (let i = 0; i < args.length && out.length <= $MAX_LOG_CHARS; i++) out += (i ? ' ' : '') + str(args[i]); return cut(out, $MAX_LOG_CHARS); };
              const log = (level, args) => n.log(level, line(args));
              const kino = {
                apiVersion: ${env.apiVersion},
                appVersion: ${JSONObject.quote(env.appVersion)},
                lang: ${JSONObject.quote(env.lang)},
                fetch: freeze(async function fetch(url, opts) {
                  const o = opts || {};
                  const req = toStr(stringify({
                    url: toStr(url), method: o.method || 'GET', headers: o.headers || {},
                    body: o.body == null ? null : toStr(o.body), timeoutMs: o.timeoutMs || 0,
                  }));
                  if (req.length > $MAX_REQUEST_CHARS) {
                    // A throw before this async function's first await would abort the whole call
                    // in alpha13 even inside the plugin's try/catch; after one it's catchable.
                    await null;
                    throw new E('solicitud demasiado grande (más de 1 MB)');
                  }
                  const r = parse(await n.fetch(req));
                  return { ok: r.ok, status: r.status, url: r.url, headers: r.headers,
                           text: () => r.body, json: () => parse(r.body) };
                }),
                html: freeze({
                  select: freeze((html, css) => {
                    const selector = toStr(css);
                    if (selector.length > $MAX_SELECTOR_CHARS) throw new E('selector CSS demasiado largo (más de $MAX_SELECTOR_CHARS caracteres)');
                    return parse(n.select(cut(toStr(html), ${PluginHtml.MAX_HTML_CHARS}), selector));
                  }),
                }),
                storage: freeze({
                  get: freeze((k) => { const key = toStr(k); if (key.length > $STORAGE_CHARS) return null; const v = n.storageGet(key); return v == null ? null : v; }),
                  set: freeze((k, v) => {
                    const key = toStr(k), value = toStr(v);
                    if (key.length + value.length > $STORAGE_CHARS) throw new E('almacenamiento del plugin lleno (64 KB)');
                    n.storageSet(key, value);
                  }),
                  remove: freeze((k) => { const key = toStr(k); if (key.length <= $STORAGE_CHARS) n.storageRemove(key); }),
                }),
                log: freeze((...a) => log('info', a)),
              };
              globalThis.kino = freeze(kino);
              globalThis.console = freeze({
                log: freeze((...a) => log('info', a)), info: freeze((...a) => log('info', a)),
                warn: freeze((...a) => log('warn', a)), error: freeze((...a) => log('error', a)),
              });
              // quickjs-kt alpha13 aborts the whole call on a promise rejected before anyone
              // awaits it, even inside try/catch. Deferring Promise.reject by one job lets the
              // awaiting caller attach its handler first. See QuickJsSpikeTest.
              Promise.reject = freeze((e) => Promise.resolve().then(() => { throw e; }));
              // A thrown value's message reaches Kotlin (and the screen) as the exception text,
              // so it's rebuilt here: a short plain string, no stack. Every step can be hostile
              // (a throwing getter or toString, a Proxy, a Symbol, 30 MB of text), hence the
              // captured built-ins and the fixed fallback.
              // What __kinoCall throws carries an OWN, fixed name: native code formats an error
              // as "<name>: <message>" and would otherwise read a plugin-controlled
              // Error.prototype.name (30 MB, measured). The prototype itself is left alone so
              // `this.name = 'MyErr'` in a plugin's Error subclass keeps working.
              const kinoError = (message) => {
                const err = new E(message);
                define(err, 'name', { value: 'Error', writable: false, configurable: false, enumerable: false });
                return err;
              };
              const errorText = (e) => {
                try {
                  let m = e;
                  if (e !== null && (typeof e === 'object' || typeof e === 'function')) {
                    const own = e.message;
                    if (own !== undefined) m = own;
                  }
                  if (m === null || m === undefined || typeof m === 'symbol') return '$THROWN_FALLBACK';
                  const text = typeof m === 'string' ? m : S(m);
                  if (typeof text !== 'string' || text.length === 0) return '$THROWN_FALLBACK';
                  return cut(text, $MAX_ERROR_CHARS);
                } catch (_) {
                  return '$THROWN_FALLBACK';
                }
              };
              // Frozen too: its `name` is read natively when the rethrow below builds an error in
              // its frame (a plugin renamed it to 20 MB and crashed the process, measured).
              define(globalThis, '__kinoCall', {
                value: freeze(async (name, argJson) => {
                  let out;
                  try {
                    const fn = globalThis.__kinoExports[name];
                    if (typeof fn !== 'function') throw new E('el plugin no exporta ' + name);
                    out = stringify(await fn(parse(argJson)));
                  } catch (e) {
                    throw kinoError(errorText(e));
                  }
                  if (typeof out !== 'string') return 'null';
                  if (out.length > $MAX_RESULT_CHARS) throw kinoError('$RESULT_TOO_BIG');
                  return out;
                }),
                writable: false, configurable: false, enumerable: false,
              });
            })();
        """.trimIndent()
    }
}
