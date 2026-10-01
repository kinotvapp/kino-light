package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.PluginColors
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.data.plugin.catalog.CatalogArt
import java.io.File
import kotlin.math.pow

/*
 * The pure decisions behind a recommended-plugin card: what letter to draw when there is no icon, what
 * colour the tile is, which text colour is readable on it, and the words on its button. Nothing here
 * touches Compose, so the phone and the TV share it and it is tested on the JVM.
 */

/** Near-black used for text on a light tile; softer than pure black, still far above the contrast a small label needs. */
private const val DARK_ON_TILE: Long = 0xFF1A1A1A

private const val WHITE_ON_TILE: Long = 0xFFFFFFFF

/** What [cardInitial] answers when the name has no letter or digit at all. */
private const val NO_INITIAL = "?"

/**
 * The letter drawn on a plugin's tile while it has no icon: the first letter or digit of [name], in
 * upper case. Leading spaces, punctuation and emoji are skipped, accents are kept (`"Ñandú"` gives
 * `"Ñ"`), and a name with nothing to show gives `"?"`, so the result is never empty.
 *
 * It walks code points, not chars, so a letter outside the basic plane (two chars in UTF-16) is never
 * cut in half. The upper-casing is the simple one-to-one mapping of [Character.toUpperCase], which
 * does not depend on the device's locale (a Turkish device does not turn `i` into a dotted capital)
 * and never widens the initial into several letters (`ß` stays `ß` instead of `SS`).
 */
internal fun cardInitial(name: String): String {
    var index = 0
    while (index < name.length) {
        val codePoint = name.codePointAt(index)
        if (Character.isLetterOrDigit(codePoint)) {
            return String(Character.toChars(Character.toUpperCase(codePoint)))
        }
        index += Character.charCount(codePoint)
    }
    return NO_INITIAL
}

/** The tile's colour: the plugin's own when its art declares a valid one, else [PluginColors.DEFAULT]. */
internal fun tileColor(art: CatalogArt?): Long = PluginColors.parse(art?.colorHex)

/**
 * Near-black or white, whichever reads better on a tile of colour [argb] (`0xAARRGGBB`; the alpha is
 * ignored, the tile is opaque). It compares the WCAG contrast ratio of each against the tile's relative
 * luminance instead of guessing a threshold, so a mid-tone colour gets whichever side is really better.
 */
internal fun onTileColor(argb: Long): Long {
    val tile = relativeLuminance(argb)
    val againstDark = contrastRatio(tile, relativeLuminance(DARK_ON_TILE))
    val againstWhite = contrastRatio(tile, relativeLuminance(WHITE_ON_TILE))
    return if (againstDark >= againstWhite) DARK_ON_TILE else WHITE_ON_TILE
}

/** WCAG 2 relative luminance of an sRGB colour, 0.0 (black) to 1.0 (white). */
private fun relativeLuminance(argb: Long): Double {
    val red = linearChannel(((argb shr 16) and 0xFF).toInt())
    val green = linearChannel(((argb shr 8) and 0xFF).toInt())
    val blue = linearChannel((argb and 0xFF).toInt())
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

private fun linearChannel(value: Int): Double {
    val c = value / 255.0
    return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

/** WCAG 2 contrast ratio between two relative luminances, 1.0 (none) to 21.0 (black on white). */
private fun contrastRatio(a: Double, b: Double): Double {
    val lighter = maxOf(a, b)
    val darker = minOf(a, b)
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * The words on the card's button. The accessibility description of the whole row keeps using
 * `catalogRowLabel` (verb plus the plugin's name), because a screen reader needs to know which plugin
 * the button acts on and the card's button does not say it.
 */
internal fun cardActionLabel(action: CatalogAction): String = when (action) {
    CatalogAction.INSTALL -> "Instalar"
    CatalogAction.CONFIGURE -> "Configurar"
    CatalogAction.ENABLE -> "Activar"
    CatalogAction.INSTALLED -> "Instalado"
}

/**
 * How many lines of description a card shows before it ends with an ellipsis: two, or one on a [compact]
 * TV card (~150 dp wide, five to a line, where every dp of height counts to fit two sections on one screen).
 */
internal fun cardDescriptionLines(compact: Boolean = false): Int = if (compact) 1 else 2

/**
 * How many lines a card's status takes, and how many a card without one reserves when its neighbour in
 * the grid line has one. The statuses a card can show ([cardStatusLabel] of a plugin whose button is not
 * "Instalado") are short, so one line is enough and a card with none is left with a one-line gap, not two.
 */
internal fun cardStatusLines(): Int = 1

/**
 * The status as a card writes it, on one line of a half-width card: the three long statuses get a short
 * wording of their own ("Archivos dañados, reinstálalo" is "Dañado", "No responde — actívalo para volver a
 * intentar" is "No responde", "Actualización disponible — requiere tu aprobación" is "Actualización
 * pendiente"; the card's own action, or its "Gestionar"/actions dialog, already says what to do). Every other
 * status, including one added later, reads as [pluginStatusText] says, which is also what the installed list
 * and the Plugins screen keep using.
 *
 * Every [PluginStatus] can reach a card now: the catalog's own cards only show one for a plugin whose action
 * isn't "Instalado" ([cardStatus]), but an installed plugin's own card (the Instalados tab) shows its status
 * whatever it is, [PluginStatus.ACTIVE] ("Activo") included.
 */
internal fun cardStatusLabel(status: PluginStatus): String = when (status) {
    PluginStatus.DAMAGED -> "Dañado"
    PluginStatus.UNRESPONSIVE -> "No responde"
    PluginStatus.UPDATE_PENDING -> "Actualización pendiente"
    else -> pluginStatusText(status)
}

/** Room kept free above and below a tile's icon or initial, so it never touches the pill above it or the tile's edge. */
private const val TILE_ART_MARGIN = 3f

/**
 * The side, in dp, of the icon (or the height of the initial) drawn in the [availableHeight] a tile has left
 * once its "Lo que ya usabas" pill has taken its row: [nominal] when there is room, otherwise what fits
 * with [TILE_ART_MARGIN] on each side, and never below zero. The tile is 16:9, so on a phone it is only
 * ~89 dp tall and a big font makes the pill row taller: the art has to give way instead of running under it.
 */
internal fun tileArtSize(availableHeight: Float, nominal: Float): Float =
    minOf(nominal, availableHeight - 2 * TILE_ART_MARGIN).coerceAtLeast(0f)

/** How many tag chips a card shows at most: two is what fits on one line of a phone's half-width card. */
private const val MAX_CARD_TAGS = 2

/**
 * The tags a card writes under the description: the first [MAX_CARD_TAGS] non-blank ones, trimmed, in the
 * order the catalog gave them. A blank tag is skipped instead of taking up one of the places.
 */
internal fun cardTags(tags: List<String>): List<String> =
    tags.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_CARD_TAGS)

/**
 * The icon an installed plugin's row draws: its own, from its installed files ([ownIcon], when that file is
 * still there), else the one its catalog repo ships ([art]), else none. A file that is not there (a stale
 * path, a folder) is no icon, so the row never hands Coil something it cannot open when the art could do.
 */
internal fun installedIconFile(ownIcon: File?, art: CatalogArt?): File? =
    ownIcon?.takeIf { it.isFile } ?: art?.iconFile

/**
 * The art for the installed plugin at [address] out of [art], which is keyed by each catalog entry's `repo`
 * exactly as the catalog spells it. An installed plugin's address is the canonical `owner/repo`, so the keys
 * are compared with [sameAddress], the same comparison that marks a catalog row as installed: the art is
 * found however the catalog wrote the repo.
 */
internal fun artForInstalled(art: Map<String, CatalogArt>, address: String): CatalogArt? =
    art.entries.firstOrNull { sameAddress(it.key, address) }?.value

/**
 * The pill on a card's tile: what the person already used, else "Firmado" for a plugin signed by its author
 * (apiVersion 5, catalog or community alike), else none. Community cards carry no other pill: they sit under the "De la
 * comunidad" header already, and installing one still shows "Plugin no verificado".
 */
internal fun cardPill(row: CatalogRow): String? = when {
    row.entry.legacyDefault -> "Lo que ya usabas"
    row.entry.signed -> SIGNED_PILL
    else -> null
}

/** The pill of an author-signed plugin's card (phone and TV). */
const val SIGNED_PILL = "Firmado"
