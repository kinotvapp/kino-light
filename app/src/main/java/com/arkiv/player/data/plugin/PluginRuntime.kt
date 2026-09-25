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
         * `web.js` (URL, URLSearchParams, atob, btoa, TextEncoder, TextDecoder) then `prelude.js`
         * called with the environment and every limit it enforces. Both files live in
         * `app/src/main/resources/plugin/`: Java resources, read through the class loader both in
         * JVM unit tests and in the APK (measured on emulator-5554, Android 14).
         */
        private fun prelude(env: PluginEnv): String {
            val envJson = JSONObject()
                .put("apiVersion", env.apiVersion).put("appVersion", env.appVersion).put("lang", env.lang)
            return PluginPrelude.web + "\n;(" + PluginPrelude.prelude + ")(" + envJson + ", " + limits() + ");"
        }

        /** The numbers prelude.js enforces, all from their Kotlin owners (pinned to contract.json). */
        internal fun limits(): String = JSONObject()
            .put("maxFunctionNameChars", MAX_FUNCTION_NAME_CHARS)
            .put("maxLogChars", MAX_LOG_CHARS)
            .put("maxErrorChars", MAX_ERROR_CHARS)
            .put("maxResultChars", MAX_RESULT_CHARS)
            .put("maxRequestChars", MAX_REQUEST_CHARS)
            .put("maxSelectorChars", MAX_SELECTOR_CHARS)
            .put("maxHtmlChars", PluginHtml.MAX_HTML_CHARS)
            .put("storageMaxBytes", STORAGE_CHARS)
            .put("thrownFallback", THROWN_FALLBACK)
            .put("resultTooBig", RESULT_TOO_BIG)
            .toString()
    }
}
