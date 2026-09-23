package com.arkiv.player.data.ditu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DituEntitlementTest {

    private fun userData(vararg flags: Pair<String, Boolean>): JSONObject {
        val ent = JSONObject()
        flags.forEach { (k, v) -> ent.put(k, v) }
        return JSONObject().put(
            "resultObj",
            JSONObject().put("containers", org.json.JSONArray().put(JSONObject().put("entitlement", ent))),
        )
    }

    @Test fun `with no flag at all there's no block`() {
        assertNull(DituEntitlement.block(userData("isGeoBlocked" to false)))
    }

    @Test fun `each flag has its own message`() {
        assertEquals("solo disponible en Colombia", DituEntitlement.block(userData("isGeoBlocked" to true)))
        assertEquals("requiere suscripción", DituEntitlement.block(userData("isChannelNotSubscribed" to true)))
        assertEquals("control parental activo", DituEntitlement.block(userData("isPCBlocked" to true)))
        assertEquals("contenido OOH bloqueado", DituEntitlement.block(userData("isContentOOHBlocked" to true)))
        assertEquals("geofence bloqueado", DituEntitlement.block(userData("isGeofencedBlocked" to true)))
        assertEquals("deportes en blackout", DituEntitlement.block(userData("isSportBlackoutBlocked" to true)))
        assertEquals("plataforma no permitida", DituEntitlement.block(userData("isPlatformBlacklisted" to true)))
    }

    /** With several active, the most informative wins, which is the first in the list. */
    @Test fun `with several blocks, geo wins`() {
        val d = userData("isPlatformBlacklisted" to true, "isGeoBlocked" to true)
        assertEquals("solo disponible en Colombia", DituEntitlement.block(d))
    }

    /**
     * A response with an unexpected shape is NOT a block. Treating it as one would say "requiere
     * suscripción" in the face of a network error, which sends the person looking for the problem
     * where it isn't.
     */
    @Test fun `a weird response doesn't block`() {
        assertNull(DituEntitlement.block(JSONObject()))
        assertNull(DituEntitlement.block(JSONObject().put("resultObj", JSONObject())))
        assertNull(DituEntitlement.block(userData()))
    }

    /** The flag as the string "true" doesn't count: only a real boolean. */
    @Test fun `only the real boolean true blocks`() {
        val d = JSONObject().put(
            "resultObj",
            JSONObject().put(
                "containers",
                org.json.JSONArray().put(JSONObject().put("entitlement", JSONObject().put("isGeoBlocked", "true"))),
            ),
        )
        assertNull(DituEntitlement.block(d))
    }
}
