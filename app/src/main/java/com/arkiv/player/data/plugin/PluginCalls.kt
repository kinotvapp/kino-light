package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.GatewayException
import kotlinx.coroutines.CancellationException

/**
 * One plugin call with the errors every screen expects: a timeout as "<name> no respondió a
 * tiempo", a typed error worded by [PluginErrors] (`auth_required` as
 * [PluginSetupRequiredException], `geo_blocked` as [GatewayBlockedException]), anything else as
 * "<name>: <message>". Shared by [PluginContentSource] and `data/live/PluginLiveProvider`.
 */
internal object PluginCalls {
    suspend fun callOrThrow(caller: PluginCaller, pluginId: String, name: String, function: String, argJson: String, timeoutMs: Long): String = try {
        caller.call(pluginId, function, argJson, timeoutMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PluginTimeoutException) {
        throw GatewayException("$name no respondió a tiempo", e)
    } catch (e: PluginErrorException) {
        throw typed(e, pluginId, name)
    } catch (e: Exception) {
        throw GatewayException("$name: ${e.message}", e)
    }

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
