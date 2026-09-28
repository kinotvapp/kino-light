package com.arkiv.player.ui.live

/**
 * Share of the circle's diameter left empty around a channel logo on each side. Logos are often wide
 * (NTN24, City, Canal RCN) and are fitted into the inner square; anything above ~0.147 keeps that
 * square's corners inside the circle, and the extra keeps a wide logo off the edge visually.
 */
internal const val CHANNEL_LOGO_INSET = 0.17f

/** Item width over circle diameter on the Home channel row: room for the name below and the badge. */
internal const val CHANNEL_ITEM_WIDTH_RATIO = 1.3f

/**
 * Sizes, in dp, of one circular channel on the Home "Canales en vivo" row: the circle, the gap down to
 * the name, the whole item's width and the logo's inner padding.
 */
internal data class ChannelCircleSpec(
    val diameterDp: Float,
    val nameGapDp: Float,
    val itemWidthDp: Float,
    val logoPaddingDp: Float,
)

/** A channel circle of [diameterDp] with the given [nameGapDp] below it. */
internal fun channelCircle(diameterDp: Float, nameGapDp: Float = 6f) = ChannelCircleSpec(
    diameterDp = diameterDp,
    nameGapDp = nameGapDp,
    itemWidthDp = diameterDp * CHANNEL_ITEM_WIDTH_RATIO,
    logoPaddingDp = diameterDp * CHANNEL_LOGO_INSET,
)

/**
 * The biggest circle that, with its name below, fills exactly [rowHeightDp] -- the TV Home's rows zone is
 * sized for exactly two rows of that height, so the live row cannot be taller than the poster rows. The
 * gap to the name absorbs what the focused circle grows downward (half of the zoom plus the [ringDp]
 * focus ring), so the zoomed circle never covers the name.
 */
internal fun channelCircleForRow(
    rowHeightDp: Float,
    nameHeightDp: Float,
    focusScale: Float,
    ringDp: Float,
): ChannelCircleSpec {
    val growth = (focusScale - 1f) / 2f
    val diameter = (rowHeightDp - nameHeightDp - ringDp) / (1f + growth)
    return channelCircle(diameterDp = diameter, nameGapDp = diameter * growth + ringDp)
}
