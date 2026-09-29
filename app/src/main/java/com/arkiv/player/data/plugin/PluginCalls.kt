package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.GatewayException
import kotlinx.coroutines.CancellationException

/**
 * One plugin call with the errors every screen expects: a timeout as "<name> no respondió a
 * tiempo", a typed error worded by [PluginErrors] (`auth_required` as
 * [PluginSetupRequiredException], `geo_blocked` as [GatewayBlockedException]), anything else as
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
            failureOf(e, pluginId, name)
        }
        val trace = (failure.cause as? PluginException)?.trace?.summary()?.takeIf { it.isNotEmpty() }
        log("[$pluginId] $function failed after ${elapsed()} ms: ${failure.message}" + (trace?.let { " [$it]" } ?: ""))
        throw failure
    }

    /**
     * What the screens get for a failed call: the real reason when Kino knows one
     * ([PluginFailureText]), except for the typed errors a plugin throws on purpose (`auth_required`,
     * `geo_blocked`, `rate_limited`), which keep their meaning; otherwise the sentences it always had.
     */
    fun failureOf(e: Exception, pluginId: String, name: String): RuntimeException {
        val known = explained(e, name)
        return when {
            known != null -> GatewayException(known, e)
            e is PluginTimeoutException -> GatewayException("$name no respondió a tiempo", e)
            e is PluginErrorException -> typed(e, pluginId, name)
            else -> GatewayException("$name: ${e.message}", e)
        }
    }

    /** [PluginFailureText]'s sentence for [e], unless it is a typed error whose meaning the plugin chose. */
    fun explained(e: Exception, name: String): String? =
        (e as? PluginException)?.takeUnless { it is PluginErrorException && it.code in PLUGIN_CHOSEN }?.let { PluginFailureText.of(it, name) }

    /** Typed errors whose meaning the plugin chose, never reworded from the trace. */
    private val PLUGIN_CHOSEN = setOf(PluginErrors.AUTH_REQUIRED, PluginErrors.GEO_BLOCKED, PluginErrors.RATE_LIMITED)

    /** A typed error as the screens expect it; an unknown code keeps the generic "<name>: <message>". */
    fun typed(e: PluginErrorException, pluginId: String, name: String): RuntimeException {
        val message = PluginErrors.userMessage(e.code, name) ?: return GatewayException("$name: ${e.message}", e)
        return when (e.code) {
            PluginErrors.AUTH_REQUIRED -> PluginSetupRequiredException(pluginId, message)
            PluginErrors.GEO_BLOCKED -> GatewayBlockedException(message)
            else -> GatewayException(message, e)
        }
    }
}
