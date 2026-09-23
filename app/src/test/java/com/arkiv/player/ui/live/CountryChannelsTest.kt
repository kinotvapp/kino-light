package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryChannelsTest {

    private fun channel(code: String, name: String = code) =
        LiveChannel(code = code, name = name, number = 0, logo = null)

    // --- country detection ---

    @Test
    fun `the SIM overrides the time zone and the language`() {
        assertEquals("CO", countryFromSignals(sim = "co", timeZoneRegion = "US", localeCountry = "ES"))
    }

    @Test
    fun `with no SIM the time zone rules, not the language`() {
        // The Fire TV case: factory English language, time zone set to Bogota.
        assertEquals("CO", countryFromSignals(sim = null, timeZoneRegion = "CO", localeCountry = "US"))
        assertEquals("CO", countryFromSignals(sim = "", timeZoneRegion = "CO", localeCountry = "US"))
    }

    @Test
    fun `the language is the last resort`() {
        assertEquals("MX", countryFromSignals(sim = null, timeZoneRegion = null, localeCountry = "mx"))
    }

    @Test
    fun `discards regions that aren't a country`() {
        // ICU returns "001" (world) or "419" (Latin America) for generic zones like Etc/UTC.
        assertEquals("ES", countryFromSignals(sim = null, timeZoneRegion = "001", localeCountry = "ES"))
        assertEquals("ES", countryFromSignals(sim = null, timeZoneRegion = "419", localeCountry = "ES"))
        assertNull(countryFromSignals(sim = null, timeZoneRegion = "001", localeCountry = ""))
    }

    @Test
    fun `with no useful signal at all there's no country`() {
        assertNull(countryFromSignals(sim = null, timeZoneRegion = null, localeCountry = null))
    }

    // --- country -> portal category mapping ---

    @Test
    fun `the portal's countries map to their category with the exact name`() {
        assertEquals("Colombia", CATEGORIES_BY_COUNTRY["CO"])
        // With an accent, exactly as /v1/live/categories returns it: the match is by name.
        assertEquals("México", CATEGORIES_BY_COUNTRY["MX"])
        assertEquals("Perú", CATEGORIES_BY_COUNTRY["PE"])
    }

    @Test
    fun `a country with no category of its own doesn't invent one`() {
        // Brazil and Argentina aren't among the portal's categories.
        assertNull(CATEGORIES_BY_COUNTRY["BR"])
        assertNull(CATEGORIES_BY_COUNTRY["AR"])
    }

    // --- row order and deduplication ---

    @Test
    fun `recents come first and in their own order`() {
        val row = homeChannelsRow(
            recent = listOf(channel("c"), channel("b"), channel("a")),
            fromCountry = listOf(channel("x"), channel("y")),
        )
        assertEquals(listOf("c", "b", "a", "x", "y"), row.map { it.code })
    }

    @Test
    fun `a recent channel isn't repeated among the country's`() {
        // The concrete case: Caracol is both recently watched AND in Colombia's list.
        val row = homeChannelsRow(
            recent = listOf(channel("caracol", "Caracol"), channel("rcn", "RCN")),
            fromCountry = listOf(channel("citytv", "City TV"), channel("caracol", "Caracol HD"), channel("win", "Win")),
        )
        assertEquals(listOf("caracol", "rcn", "citytv", "win"), row.map { it.code })
        assertEquals(1, row.count { it.code == "caracol" })
        // And the recents' version wins, with the name it was seen under.
        assertEquals("Caracol", row.first { it.code == "caracol" }.name)
    }

    @Test
    fun `the limit caps the row's total, not each part`() {
        val row = homeChannelsRow(
            recent = (1..3).map { channel("r$it") },
            fromCountry = (1..50).map { channel("p$it") },
            limit = 10,
        )
        assertEquals(10, row.size)
        assertEquals(listOf("r1", "r2", "r3", "p1"), row.map { it.code }.take(4))
    }

    @Test
    fun `with no channels from the country the row is just the recents`() {
        val row = homeChannelsRow(listOf(channel("a")), emptyList())
        assertEquals(listOf("a"), row.map { it.code })
    }

    @Test
    fun `with no recents the row is just the country's channels`() {
        val row = homeChannelsRow(emptyList(), listOf(channel("x"), channel("y")))
        assertEquals(listOf("x", "y"), row.map { it.code })
    }

    @Test
    fun `with nothing at all the row is empty`() {
        assertTrue(homeChannelsRow(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `a code repeated within the same list isn't duplicated either`() {
        val row = homeChannelsRow(
            recent = listOf(channel("a"), channel("a")),
            fromCountry = listOf(channel("b"), channel("b")),
        )
        assertEquals(listOf("a", "b"), row.map { it.code })
    }
}
