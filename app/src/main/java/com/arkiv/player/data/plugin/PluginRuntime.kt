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
class PluginTimeoutException(function: String, ms: Long) : PluginException("$function no respondió en ${(ms + 999) / 1000} s")
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
            throw PluginScriptException(e.message ?: "Error del plugin", e)
        } catch (e: Exception) {
            // A Kotlin exception from a binding (e.g. HostNotAllowedException) that JS didn't catch.
            throw PluginScriptException(e.message ?: e.javaClass.simpleName, e)
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
                throw PluginScriptException(e.message ?: "El plugin no carga", e)
            } catch (e: Exception) {
                executor.shutdown()
                throw PluginScriptException(e.message ?: e.javaClass.simpleName, e)
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
              const S = String, E = Error, stringify = JSON.stringify, parse = JSON.parse;
              const slice = Function.prototype.call.bind(String.prototype.slice);
              const define = Object.defineProperty, freeze = Object.freeze;
              const toStr = (x) => (typeof x === 'string' ? x : S(x));
              const cut = (s, max) => (s.length > max ? slice(s, 0, max) : s);
              const str = (x) => { if (typeof x === 'string') return x; try { return toStr(stringify(x)); } catch (e) { return toStr(x); } };
              const line = (args) => { let out = ''; for (let i = 0; i < args.length && out.length <= $MAX_LOG_CHARS; i++) out += (i ? ' ' : '') + str(args[i]); return cut(out, $MAX_LOG_CHARS); };
              const log = (level, args) => n.log(level, line(args));
              const kino = {
                apiVersion: ${env.apiVersion},
                appVersion: ${JSONObject.quote(env.appVersion)},
                lang: ${JSONObject.quote(env.lang)},
                async fetch(url, opts) {
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
                },
                html: freeze({
                  select: (html, css) => {
                    const selector = toStr(css);
                    if (selector.length > $MAX_SELECTOR_CHARS) throw new E('selector CSS demasiado largo (más de $MAX_SELECTOR_CHARS caracteres)');
                    return parse(n.select(cut(toStr(html), ${PluginHtml.MAX_HTML_CHARS}), selector));
                  },
                }),
                storage: freeze({
                  get: (k) => { const key = toStr(k); if (key.length > $STORAGE_CHARS) return null; const v = n.storageGet(key); return v == null ? null : v; },
                  set: (k, v) => {
                    const key = toStr(k), value = toStr(v);
                    if (key.length + value.length > $STORAGE_CHARS) throw new E('almacenamiento del plugin lleno (64 KB)');
                    n.storageSet(key, value);
                  },
                  remove: (k) => { const key = toStr(k); if (key.length <= $STORAGE_CHARS) n.storageRemove(key); },
                }),
                log: (...a) => log('info', a),
              };
              globalThis.kino = freeze(kino);
              globalThis.console = freeze({
                log: (...a) => log('info', a), info: (...a) => log('info', a),
                warn: (...a) => log('warn', a), error: (...a) => log('error', a),
              });
              // quickjs-kt alpha13 aborts the whole call on a promise rejected before anyone
              // awaits it, even inside try/catch. Deferring Promise.reject by one job lets the
              // awaiting caller attach its handler first. See QuickJsSpikeTest.
              Promise.reject = (e) => Promise.resolve().then(() => { throw e; });
              define(globalThis, '__kinoCall', {
                value: async (name, argJson) => {
                  const fn = globalThis.__kinoExports[name];
                  if (typeof fn !== 'function') throw new E('el plugin no exporta ' + name);
                  const out = stringify(await fn(parse(argJson)));
                  if (typeof out !== 'string') return 'null';
                  if (out.length > $MAX_RESULT_CHARS) throw new E('$RESULT_TOO_BIG');
                  return out;
                },
                writable: false, configurable: false, enumerable: false,
              });
            })();
        """.trimIndent()
    }
}
