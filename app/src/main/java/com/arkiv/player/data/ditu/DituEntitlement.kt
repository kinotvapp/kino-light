package com.arkiv.player.data.ditu

import org.json.JSONObject

/**
 * Why Caracol won't let you watch something.
 *
 * The API answers with seven different flags and each one sends the person to a different place:
 * a geoblock gets fixed with a VPN or doesn't, a subscription gets bought, parental control gets
 * turned off on the device. Collapsing them into "couldn't play" turns any of the seven into what
 * looks like an app bug.
 *
 * Order matters: with several active, the first one wins, since it's the most informative.
 */
internal object DituEntitlement {

    private val BLOCKS = linkedMapOf(
        "isGeoBlocked" to "solo disponible en Colombia",
        "isChannelNotSubscribed" to "requiere suscripción",
        "isPCBlocked" to "control parental activo",
        "isContentOOHBlocked" to "contenido OOH bloqueado",
        "isGeofencedBlocked" to "geofence bloqueado",
        "isSportBlackoutBlocked" to "deportes en blackout",
        "isPlatformBlacklisted" to "plataforma no permitida",
    )

    /**
     * The first active block's message, or `null` if there's none.
     *
     * A response that doesn't have the expected shape returns `null` on purpose: not knowing
     * whether there's a block isn't the same as there being one, and asserting it would send
     * someone looking for the problem where it isn't.
     */
    fun block(userData: JSONObject): String? {
        val containers = userData.optJSONObject("resultObj")?.optJSONArray("containers") ?: return null
        val entitlement = containers.optJSONObject(0)?.optJSONObject("entitlement") ?: return null
        for ((flag, message) in BLOCKS) {
            // `opt` and not `optBoolean`: optBoolean("x", false) returns true for the string
            // "true", and a string isn't what the API sends when it truly blocks.
            if (entitlement.opt(flag) == true) return message
        }
        return null
    }
}
