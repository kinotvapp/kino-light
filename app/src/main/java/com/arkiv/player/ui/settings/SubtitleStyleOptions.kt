package com.arkiv.player.ui.settings

import com.arkiv.player.data.subtitles.PlaybackPrefs

/**
 * The preset values the TV offers for each subtitle style knob (a remote has no slider, so each row
 * cycles through these on OK). Labels are what the person reads, hence Spanish.
 */
internal object SubtitleStyleOptions {
    class Option<T>(val value: T, val label: String)

    val SIZES = listOf(80, 100, 120, 150, 200)

    val COLORS = listOf(
        Option(0xFFFFFFFFL, "Blanco"),
        Option(0xFFFFEB3BL, "Amarillo"),
        Option(0xFF00E5FFL, "Cian"),
        Option(0xFF00E676L, "Verde"),
    )

    val BACKGROUNDS = listOf(
        Option(0xCC000000L, "Caja negra"),
        Option(0x80000000L, "Semitransparente"),
        Option(0x00000000L, "Sin fondo"),
    )

    val EDGES = listOf(
        Option(PlaybackPrefs.EDGE_OUTLINE, "Contorno"),
        Option(PlaybackPrefs.EDGE_SHADOW, "Sombra"),
        Option(PlaybackPrefs.EDGE_NONE, "Ninguno"),
    )

    /** The next preset above [current]; past the biggest, back to the smallest. */
    fun nextSize(current: Int): Int = SIZES.firstOrNull { it > current } ?: SIZES.first()

    /** The option after [current] in [options]; a value not in the list goes to the first one. */
    fun <T> next(options: List<Option<T>>, current: T): T {
        val i = options.indexOfFirst { it.value == current }
        return options[(i + 1) % options.size].value
    }

    fun <T> label(options: List<Option<T>>, value: T): String =
        options.firstOrNull { it.value == value }?.label ?: "Personalizado"
}
