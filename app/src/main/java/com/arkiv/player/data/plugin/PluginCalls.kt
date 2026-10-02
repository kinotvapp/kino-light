package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.GatewayException
import kotlinx.coroutines.CancellationException

/**
 * One plugin call with the errors every screen expects: a timeout as "<name> no respondió a
 * tiempo", a typed error worded by [PluginErrors] (`auth_required` as
 * [PluginSetupRequiredException], `geo_blocked` as [GatewayBlockedException]), an error the script
 * threw as [PluginErrorText]'s short sentence (never its raw text or stack), Kino's own errors as
 * "<name>: <message>" -- unless Kino knows the real reason ([PluginFailureText]: a host refused, a
 * site that didn't answer, the Nuvio adapter's "no results" or "only torrents"), which wins. Every
 * attempt is logged under `KinoPlugin` with its function, duration and outcome (and the call's
 * [PluginCallTrace]). Shared by [PluginContentSource] and `data/live/PluginLiveProvider`.
 */
internal object PluginCalls {
    suspend fun callOrThrow(
        caller: PluginCaller,
        pluginId: String,
        name: String,
        function: String,
        argJson: String,
        timeoutMs: Long,
        log: (String) -> Unit = { android.util.Log.i("KinoPlugin", it) },
    ): String {
        val t0 = System.nanoTime()
        fun elapsed() = (System.nanoTime() - t0) / 1_000_000
        val failure: RuntimeException = try {
            return caller.call(pluginId, function, argJson, timeoutMs).also { log("[$pluginId] $function ok after ${elapsed()} ms") }
        } catch (e: CancellationException) {
            log("[$pluginId] $function cancelled after ${elapsed()} ms: nobody waits for it any more")
            throw e
        } catch (e: Exception) {
            failureOf(e, pluginId, name, function)
        }
        val cause = failure.cause as? PluginException
        val trace = cause?.trace?.summary()?.takeIf { it.isNotEmpty() }
        // The plugin's own text, stack and all, stays in the log whenever the person reads another one.
        val raw = cause?.message?.takeIf { failure.message?.contains(it) != true }
        // Wall time since the call was asked for: it includes waiting for the plugin's previous call
        // (the pool's per-plugin lock), loading the runtime and any host question on screen, none of
        // which counts toward the call's own limit (PluginCallClock).
        log("[$pluginId] $function failed after ${elapsed()} ms: ${failure.message}" + (trace?.let { " [$it]" } ?: "") + (raw?.let { " -- plugin said: $it" } ?: ""))
        throw failure
    }

    /**
     * What the screens get for a failed call: the real reason when Kino knows one
     * ([PluginFailureText]), except for the typed errors a plugin throws on purpose (`auth_required`,
     * `geo_blocked`, `rate_limited`), which keep their meaning; otherwise the sentences it always had.
     */
    fun failureOf(e: Exception, pluginId: String, name: String, function: String): RuntimeException {
        val known = explained(e, name)
        return when {
            known != null -> GatewayException(known, e)
            e is PluginTimeoutException -> GatewayException("$name no respondió a tiempo", e)
            e is PluginErrorException -> typed(e, pluginId, name, function)
            e is PluginThrownException -> GatewayException(PluginErrorText.of(name, function, e.message), e)
            else -> GatewayException("$name: ${e.message}", e)
        }
    }

    /** [PluginFailureText]'s sentence for [e], unless it is a typed error whose meaning the plugin chose. */
    fun explained(e: Exception, name: String): String? =
        (e as? PluginException)?.takeUnless { it is PluginErrorException && it.code in PLUGIN_CHOSEN }?.let { PluginFailureText.of(it, name) }

    /** Typed errors whose meaning the plugin chose, never reworded from the trace. */
    private val PLUGIN_CHOSEN = setOf(PluginErrors.AUTH_REQUIRED, PluginErrors.GEO_BLOCKED, PluginErrors.RATE_LIMITED)

    /** A typed error as the screens expect it; an unknown code gets [PluginErrorText]'s sentence. */
    fun typed(e: PluginErrorException, pluginId: String, name: String, function: String): RuntimeException {
        // A sentence Kino's own Xuper bridge wrote for the person (a gone chapter, a linked account to re-link):
        // shown as it is, in the "No se puede reproducir" dialog, never reworded to the code's generic line.
        e.message?.takeIf { it in XUPER_HOST_SENTENCES }?.let { return GatewayBlockedException(it) }
        val message = PluginErrors.userMessage(e.code, name) ?: return GatewayException(PluginErrorText.of(name, function, e.message), e)
        return when (e.code) {
            PluginErrors.AUTH_REQUIRED -> PluginSetupRequiredException(pluginId, message)
            PluginErrors.GEO_BLOCKED -> GatewayBlockedException(message)
            else -> GatewayException(message, e)
        }
    }
}
