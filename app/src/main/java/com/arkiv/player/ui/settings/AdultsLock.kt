package com.arkiv.player.ui.settings

/**
 * When the 18+ section shows, with what code, and on which device.
 *
 * The code is chosen by the person in Ajustes and saved on the device. While none is chosen,
 * [DEFAULT_CODE] rules, which the screen ANNOUNCES -- on purpose: the APK is distributed, and a
 * code known only to whoever built it would leave the section closed to everyone else. The
 * announcement disappears as soon as there's a code of one's own.
 *
 * Unlocks ONLY the device it was typed on: it saves locally, doesn't travel in sync, and
 * reinstalling the app turns it off.
 *
 * The scope, stated plainly: this stops someone with the remote control, not someone who
 * decompiles the APK or hand-builds the HTTP request. The original app's lock has exactly the
 * same strength -- its parental key is validated client-side and the portal doesn't even look at
 * it (`RestrictedStatusBean` carries no password, verified in the decompile).
 */
object AdultsLock {

    /** The code any install starts with, and the only one the screen announces. */
    const val DEFAULT_CODE = "0000"

    /**
     * The way out for someone who forgot the code they set: typing it into the field returns the
     * lock to [DEFAULT_CODE]. Not announced anywhere -- the sign that it worked is that the
     * default's notice shows up again on its own.
     */
    const val RESET_CODE = "9999"

    /**
     * The code that actually rules. Nothing saved -- or saved blank -- falls back to the default:
     * the real code arriving empty is exactly the edge [unlocks] exists to cover.
     */
    fun effectiveCode(saved: String?): String =
        saved?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CODE

    /**
     * Whether the code anyone knows still rules, and therefore has to be announced. Picking
     * `0000` by hand still counts as default: what the notice says isn't "you didn't choose", it's
     * "this code protects nothing".
     */
    fun isDefault(saved: String?): Boolean = effectiveCode(saved) == DEFAULT_CODE

    /** Whether what's typed in the field is the reset request rather than an attempt to get in. */
    fun requestsReset(entered: String): Boolean = entered.trim() == RESET_CODE

    /**
     * [RESET_CODE] can't be chosen as one's own code. If it could, the reset would be blocked by
     * it: whoever typed it to get in would end up erasing their own code without opening anything.
     */
    fun isReserved(newCode: String): Boolean = newCode.trim() == RESET_CODE

    /** Four digits, like the default: the field uses the TV's numeric keyboard. */
    fun isValidFormat(newCode: String): Boolean =
        newCode.trim().length == 4 && newCode.trim().all { it.isDigit() }

    /**
     * Whether [entered] unlocks the lock.
     *
     * An empty [actualCode] NEVER unlocks, not even against an empty attempt. [effectiveCode]
     * already keeps it from arriving empty, but the guard stays as a last line: with a naive
     * "empty == empty" comparison it would open the section to anyone who hits OK without typing
     * anything.
     */
    fun unlocks(entered: String, actualCode: String): Boolean {
        val real = actualCode.trim()
        if (real.isEmpty()) return false
        return entered.trim() == real
    }

    /**
     * Without unlocking, NOTHING of the section shows: not the button, not a lock icon, not a
     * grayed-out row. A disabled button announces that something exists, and announcing it is
     * half the problem.
     */
    fun shouldShowSection(unlocked: Boolean): Boolean = unlocked

    /**
     * The field to type the code is always there while it hasn't been entered. There's no longer
     * a case of "the build ships with no code": there's always one, even if it's the default.
     */
    fun shouldShowField(unlocked: Boolean): Boolean = !unlocked
}
