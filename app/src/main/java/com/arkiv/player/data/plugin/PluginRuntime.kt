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
            return withTimeout(timeoutMs) { job.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            throw PluginTimeoutException(function, timeoutMs)
        } catch (e: CancellationException) {
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
        /**
         * Loads [script] as an ES module. Fails with [PluginScriptException] on a syntax error or a
         * throw at module top level, and [PluginTimeoutException] if loading takes longer than
         * [PluginEnv.loadTimeoutMs] (a top-level infinite loop leaks that thread, see the class KDoc).
         */
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
                        code = "import * as p from 'plugin.js'; globalThis.__kinoExports = p;",
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

        private fun prelude(env: PluginEnv): String = """
            (() => {
              const n = globalThis.__kinoNative;
              const str = (x) => { if (typeof x === 'string') return x; try { return JSON.stringify(x); } catch (e) { return String(x); } };
              const line = (args) => args.map(str).join(' ');
              const kino = {
                apiVersion: ${env.apiVersion},
                appVersion: ${JSONObject.quote(env.appVersion)},
                lang: ${JSONObject.quote(env.lang)},
                async fetch(url, opts) {
                  const o = opts || {};
                  const raw = await n.fetch(JSON.stringify({
                    url: String(url), method: o.method || 'GET', headers: o.headers || {},
                    body: o.body == null ? null : String(o.body), timeoutMs: o.timeoutMs || 0,
                  }));
                  const r = JSON.parse(raw);
                  return { ok: r.ok, status: r.status, url: r.url, headers: r.headers,
                           text: () => r.body, json: () => JSON.parse(r.body) };
                },
                html: Object.freeze({ select: (html, css) => JSON.parse(n.select(String(html), String(css))) }),
                storage: Object.freeze({
                  get: (k) => { const v = n.storageGet(String(k)); return v == null ? null : v; },
                  set: (k, v) => { n.storageSet(String(k), String(v)); },
                  remove: (k) => { n.storageRemove(String(k)); },
                }),
                log: (...a) => n.log('info', line(a)),
              };
              globalThis.kino = Object.freeze(kino);
              globalThis.console = Object.freeze({
                log: (...a) => n.log('info', line(a)), info: (...a) => n.log('info', line(a)),
                warn: (...a) => n.log('warn', line(a)), error: (...a) => n.log('error', line(a)),
              });
              // quickjs-kt alpha13 aborts the whole call on a promise rejected before anyone
              // awaits it, even inside try/catch. Deferring Promise.reject by one job lets the
              // awaiting caller attach its handler first. See QuickJsSpikeTest.
              Promise.reject = (e) => Promise.resolve().then(() => { throw e; });
              globalThis.__kinoCall = async (name, argJson) => {
                const fn = globalThis.__kinoExports[name];
                if (typeof fn !== 'function') throw new Error('el plugin no exporta ' + name);
                const out = await fn(JSON.parse(argJson));
                return JSON.stringify(out === undefined ? null : out);
              };
            })();
        """.trimIndent()
    }
}
