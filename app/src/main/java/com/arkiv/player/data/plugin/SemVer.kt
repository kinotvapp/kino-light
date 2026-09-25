package com.arkiv.player.data.plugin

/** `MAJOR.MINOR.PATCH`, no pre-release or build suffix: enough to order plugin updates. */
object SemVer {
    /** `internal`, not public API: exposed only so a test can pin `contract.json`'s `versionPattern` to it. */
    internal val RE = Regex("^(0|[1-9]\\d{0,5})\\.(0|[1-9]\\d{0,5})\\.(0|[1-9]\\d{0,5})$")

    fun isValid(v: String): Boolean = RE.matches(v)

    /** Negative if [a] < [b], zero if equal, positive if [a] > [b]. Both must be [isValid]. */
    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0..2) {
            val c = x[i].compareTo(y[i])
            if (c != 0) return c
        }
        return 0
    }

    private fun parts(v: String): List<Int> =
        RE.matchEntire(v)?.destructured?.toList()?.map { it.toInt() }
            ?: throw IllegalArgumentException("not a semver: $v")
}
