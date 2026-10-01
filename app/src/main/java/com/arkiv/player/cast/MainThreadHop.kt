package com.arkiv.player.cast

/**
 * Bounces work that must run on the main thread (anything touching the Cast SDK) when the caller
 * is somewhere else.
 *
 * Kept free of Android types so the decision can be unit-tested: the caller says whether it is on
 * the main thread and how to post to it.
 */
internal object MainThreadHop {
    /**
     * Returns true when [block] was handed to [post] (the caller was off the main thread and must
     * stop now), false when the caller is already on the main thread and should just carry on.
     */
    fun run(onMain: Boolean, post: (Runnable) -> Unit, block: () -> Unit): Boolean {
        if (onMain) return false
        post(Runnable { block() })
        return true
    }
}
