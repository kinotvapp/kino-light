package com.arkiv.player.ui.live

import android.content.Context
import android.content.SharedPreferences
import android.telephony.TelephonyManager
import com.arkiv.player.data.db.LiveChannelCacheDao
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.gateway.LiveCatalogGateway
import com.arkiv.player.data.gateway.LiveChannel
import java.util.Locale
import java.util.TimeZone

/**
 * Portal categories that represent a country, by ISO-3166 alpha-2 code. The names are what the
 * portal returns **as-is** (with accents: "México", "Perú", "Panamá") -- the match is by name and
 * not by id because the portal's ids are its own and could change; the name is what's visible and
 * what stays stable.
 *
 * Guatemala, Nicaragua and Belize have no category of their own in the portal: they fall to
 * "Centroamérica", the closest thing that exists. A country with no entry here simply adds no
 * channels to the row (see [countryChannelsForHome]): a short row beats another country's row.
 */
val CATEGORIES_BY_COUNTRY: Map<String, String> = mapOf(
    "CO" to "Colombia",
    "VE" to "Venezuela",
    "EC" to "Ecuador",
    "CL" to "Chile",
    "MX" to "México",
    "PE" to "Perú",
    "BO" to "Bolivia",
    "UY" to "Uruguay",
    "PY" to "Paraguay",
    "PA" to "Panamá",
    "PR" to "Puerto Rico",
    "ES" to "España",
    "CR" to "Costa Rica",
    "US" to "Estados Unidos",
    "HN" to "Honduras",
    "SV" to "El Salvador",
    "DO" to "República Dominicana",
    "GT" to "Centroamérica",
    "NI" to "Centroamérica",
    "BZ" to "Centroamérica",
)

/**
 * Device's country from three signals, in order of confidence. All of them are free and **none
 * asks for permissions**; returns the uppercase ISO alpha-2, or `null` if none work.
 *
 * 1. [sim]: the SIM's country. It's the most reliable where it exists, but a Fire TV or a tablet
 *    with no modem don't have it.
 * 2. [timeZoneRegion]: the time zone's region (ICU resolves "America/Bogota" → "CO"). Goes
 *    **before** the language on purpose: on a TV box the language is usually left at factory
 *    English, but the time zone gets configured on plugging it in -- if the language ruled, a
 *    Fire TV in Bogotá would show United States channels.
 * 3. [localeCountry]: the locale's country, last resort.
 *
 * Anything that isn't a two-letter code is discarded: ICU returns "001" (world) or "419" (Latin
 * America) for generic zones like "Etc/UTC", and that's not a country.
 */
fun countryFromSignals(
    sim: String?,
    timeZoneRegion: String?,
    localeCountry: String?,
): String? = listOf(sim, timeZoneRegion, localeCountry)
    .firstOrNull { it != null && it.length == 2 && it.all { c -> c.isLetter() } }
    ?.uppercase(Locale.ROOT)

/** [countryFromSignals] reading the device's real signals. */
fun deviceCountry(context: Context): String? {
    // getSystemService returns null where there's no telephony (Fire TV), and simCountryIso comes
    // back "" with the modem having no SIM: both cases fall through to the rest of the signals on their own.
    val sim = runCatching {
        (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
            ?.simCountryIso
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    val region = runCatching {
        android.icu.util.TimeZone.getRegion(TimeZone.getDefault().id)
    }.getOrNull()

    return countryFromSignals(sim, region, Locale.getDefault().country)
}

/**
 * The home's "Canales en vivo" row: first what was last watched (the order [recent] already
 * brings, not reordered here), then the country's channels, and **with no repeats** -- if Caracol
 * is among the recents, it doesn't show up again among the country's. The [limit] cap is on the
 * row's total, not on each part: the row is a shortcut, and the full grid is one tap away, on the
 * "Ver más canales" card whoever paints the row closes it with.
 *
 * The dedup is by `code` (the portal's identifier), not by name: two different channels can be
 * named similarly, and the same channel can come with different names depending on the category.
 */
fun homeChannelsRow(
    recent: List<LiveChannel>,
    fromCountry: List<LiveChannel>,
    limit: Int = HOME_ROW_LIMIT,
): List<LiveChannel> {
    val seen = HashSet<String>()
    // Recents first, already deduped among themselves just in case: `add` returns false on a repeat.
    val row = recent.filter { seen.add(it.code) } + fromCountry.filter { seen.add(it.code) }
    return row.take(limit)
}

/** Cap on the home row's cards, not counting "Ver más canales". */
const val HOME_ROW_LIMIT = 24

/**
 * The device's country's channels for the home row. Returns an empty list -- never throws -- if no
 * country is detected, if that country has no category in the portal, or if there's neither
 * network nor cache.
 *
 * Freshness: with a cache under [FRESHNESS_MS] old, it doesn't touch the network. It's a channel
 * catalog, not something that changes within the day, and this function runs on **every home opening**.
 *
 * The category's id is saved in [prefs] alongside the ISO that produced it. Without that,
 * `categories()` would have to be requested from the gateway just to know what to read from the
 * local cache, and the home's shortcut would stop working with no network. It goes in
 * SharedPreferences and not in `SettingsStore` because it isn't a user setting: it's derived
 * cache, reconstructible by asking the gateway for the categories.
 */
suspend fun countryChannelsForHome(
    context: Context,
    api: LiveCatalogGateway,
    cacheDao: LiveChannelCacheDao,
    prefs: SharedPreferences,
    nowMs: Long = System.currentTimeMillis(),
): List<LiveChannel> {
    val iso = deviceCountry(context) ?: return emptyList()
    val categoryName = CATEGORIES_BY_COUNTRY[iso] ?: return emptyList()

    val savedId = if (prefs.getString(KEY_COUNTRY_ISO, null) == iso) {
        prefs.getInt(KEY_COUNTRY_CATEGORY, 0).takeIf { it != 0 }
    } else {
        null
    }

    if (savedId != null) {
        val cached = cacheDao.byCategory(savedId)
        val fresh = cached.isNotEmpty() && cached.all { nowMs - it.guardadoAt < FRESHNESS_MS }
        if (fresh) return cached.map { LiveChannel(it.code, it.nombre, it.numero, it.logo) }
    }

    val freshOnes = runCatching {
        val id = savedId
            ?: api.categories().firstOrNull { it.name == categoryName }?.id
            ?: return emptyList()
        val channels = api.channels(id)
        if (channels.isNotEmpty()) {
            cacheDao.replace(id, channels.map {
                LiveChannelCacheEntity(it.code, id, it.name, it.number, it.logo, nowMs)
            })
            prefs.edit().putString(KEY_COUNTRY_ISO, iso).putInt(KEY_COUNTRY_CATEGORY, id).apply()
        }
        channels
    }.getOrNull()

    if (freshOnes != null && freshOnes.isNotEmpty()) return freshOnes

    // No network: the old cache still works -- an outdated channel catalog beats a half-empty row,
    // and channels that no longer exist will fail on opening, like any other.
    val id = savedId ?: return emptyList()
    return cacheDao.byCategory(id).map { LiveChannel(it.code, it.nombre, it.numero, it.logo) }
}

private const val KEY_COUNTRY_ISO = "live_pais_iso"
private const val KEY_COUNTRY_CATEGORY = "live_pais_categoria_id"

/** 24 h: a country's channels don't change within the day. */
private const val FRESHNESS_MS = 24L * 60 * 60 * 1000
