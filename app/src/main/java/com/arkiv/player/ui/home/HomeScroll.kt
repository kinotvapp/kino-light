package com.arkiv.player.ui.home

/**
 * Snapshot of which top sections of the home list currently render content: the hero, the
 * "continue watching" row, "live channels" and "my library" -- the four sections that sit above
 * the keyed remote rows and used to be conditionally emitted, unkeyed `item {}` blocks.
 *
 * Counts rather than plain booleans, so a section that changes size while already visible (not
 * just appears/disappears) is also picked up by [shouldSnapHomeToTop]'s caller, in case a future
 * change ever makes one of these rows' height depend on its item count.
 */
internal data class TopSectionsSignature(
    val heroVisible: Boolean,
    val continueWatchingCount: Int,
    val channelsCount: Int,
    val libraryCount: Int,
)

/**
 * Whether the home `LazyColumn` should be snapped back to the top.
 *
 * This used to happen by accident: the top sections were unkeyed, conditionally-emitted items, so
 * when one of them got data after the first frame Compose inserted a new item ABOVE the
 * already-visible, stably-keyed remote rows, and `LazyColumn`'s key-based anchoring kept that row
 * pinned on screen -- landing the person mid-list. Making every top section an always-present item
 * with a stable key (see `HomeScreen`) removes the insertion; this function is the remaining
 * safety net, evaluated whenever the top sections' shape changes.
 *
 * True only when all of the following hold:
 * - the person has never scrolled the list by hand ([userScrolled] false) -- once they have,
 *   nothing here should ever move the list again;
 * - the top sections just changed shape ([signatureChanged]) -- no point re-checking on every
 *   recomposition;
 * - the list isn't already sitting at the top -- no point issuing a no-op scroll.
 */
internal fun shouldSnapHomeToTop(
    userScrolled: Boolean,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    signatureChanged: Boolean,
): Boolean {
    if (userScrolled) return false
    if (!signatureChanged) return false
    return firstVisibleItemIndex != 0 || firstVisibleItemScrollOffset != 0
}
