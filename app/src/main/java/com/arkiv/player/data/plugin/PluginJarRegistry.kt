package com.arkiv.player.data.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * Every plugin's live [PluginCookies], one per plugin id, shared between a runtime's own open/close
 * lifecycle and a settings change's forget. This is the whole fix for the highest-stakes race this
 * plan found (fix round 1, finding 1): a jar may be superseded ([put] — a fresh runtime opens, an
 * idle reopen or a runtime whose close is deferred behind an in-flight call it lost the race to
 * finish first, see `PluginRuntime.close` KDoc) or forgotten ([forget] — a settings change), and
 * EITHER of those is the only way an entry ever leaves this map.
 *
 * A runtime's own `close()` must NEVER touch this map, on purpose: the version this replaces also
 * had `close()` remove its jar (`pluginJars.remove(id, cookies)`), which raced `forget()` — closing
 * a runtime is asynchronous (`PluginRuntimePool.close` launches it), so if that removal won the race
 * it took the jar out from under `forget()` WITHOUT retiring it (a bare `remove`, not `retire()`).
 * `forget()`'s own `jars.remove(id)` then found nothing there to retire, and the orphaned jar — never
 * stopped — was free to write the OLD session's cookies back to disk any time after "forget" had
 * already deleted them: exactly the leak Review Focus item 2 exists to prevent. Removing that
 * operation from `close()` entirely (rather than trying to order it correctly against `forget()`)
 * closes the race structurally: nothing outside [put]/[forget] can ever touch this map, so there is
 * no third caller left to race either of them.
 */
class PluginJarRegistry {
    private val jars = ConcurrentHashMap<String, PluginCookies>()

    /**
     * A fresh runtime is opening for [id]: installs [cookies] as the live jar. Whatever it replaces
     * is stopped from ever writing again ([PluginCookies.stopWriting]) — not cleared, its session
     * hasn't changed, only superseded — so a write still in flight on it can't land over what the
     * new jar reads or writes to the same file.
     */
    fun put(id: String, cookies: PluginCookies) {
        jars.put(id, cookies)?.stopWriting()
    }

    /**
     * A settings change: retires (stops AND clears — [PluginCookies.retire]) whatever is CURRENTLY
     * the live jar for [id], so nothing of the old session can ever reach disk again, even from a
     * call that was still finishing when this runs.
     */
    fun forget(id: String) {
        jars.remove(id)?.retire()
    }

    /** For tests only: the live jar, if any. */
    internal fun current(id: String): PluginCookies? = jars[id]
}
