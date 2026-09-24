package com.arkiv.player.data

/** The person's choice for the app's DECORATIVE motion (Ajustes): the drifting/zooming hero backdrop, its crossfade and the cards' focus zoom. */
enum class EffectsMode(val key: String) {
    /** Full effects unless the device proves too slow for them (see [EffectsPolicy]). */
    AUTO("auto"),
    FULL("full"),
    REDUCED("reduced"),
    ;

    /** The next option, for a single row that cycles Automático -> Completos -> Reducidos. */
    fun next(): EffectsMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromKey(key: String?): EffectsMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * When to turn the decorative effects off. Pure (no Android) so every threshold is tested on the JVM;
 * the measuring itself lives in `ui/VisualEffects.kt`.
 *
 * Two signals, on purpose, because neither is enough alone. Field data from GlitchTip (2026-09):
 *  - RAM separates the two device classes cleanly (TV boxes/sticks 0.9-2 GB on 4 cores, phones 2.7-11 GB)...
 *  - ...but the cheapest boxes REPORT nonsense: a "TVBOX" claimed 132,725 MB and a "PROJECTOR" 0. Those
 *    are exactly the slow ones, so a spec-based rule can't be trusted to catch them.
 * And the startup warm-up time is no proxy either: an S22 Ultra took 18 s, a budget Moto g15 6 s.
 * So the specs only give a HINT at the obvious extreme, and what really decides is how many frames the
 * device actually drops while the effects run.
 */
object EffectsPolicy {

    /**
     * At or under this much RAM the effects start reduced without waiting to measure. 2 GB because that
     * is where the field data puts the line: every TV box/stick reported 0.9-2.0 GB (Fire TV Stick 901 MB,
     * 1.7 GB sticks, 2 GB Google TV/MiBox) and the smallest phone 2.7 GB. `totalMem` is what the OS has
     * left after reserved memory, so a "2 GB" device reports ~1.9-2.0 GB and lands under this.
     * A 2 GB box that runs fine still has "Completos" in Ajustes.
     */
    const val LOW_RAM_HINT_MB = 2048L

    /** More RAM than this is a lie (no device we ship to has 64 GB): treat it like "unknown". */
    private const val IMPLAUSIBLE_RAM_MB = 65_536L

    /** Frames to ignore right after the screen appears: first draw, image decoding and layout are slow on EVERY device. */
    const val WARMUP_FRAMES = 60

    /** Frames the verdict is based on; fewer is not enough evidence to judge a device. */
    const val SAMPLE_FRAMES = 180

    /** A frame this many times over the frame budget was visibly dropped (2x = a 60 Hz frame taking 33 ms+). */
    const val DROPPED_FRAME_FACTOR = 2.0f

    /** Slow when at least this share of the sampled frames were dropped. */
    const val SLOW_SHARE = 0.20f

    /**
     * Separate slow launches needed before the effects are turned off for good. The measurement can be
     * contaminated (the credential warm-up runs at startup and takes 4-34 s on these boxes, on the same
     * few cores), so one slow sample isn't proof: it takes a second one on another launch.
     */
    const val STRIKES_TO_REDUCE = 2

    /**
     * Whether the specs alone say "low-end". [totalRamMb] <= 0 or absurdly large means the device
     * doesn't report it honestly: no hint either way, and the frame measurement decides.
     */
    fun staticHint(totalRamMb: Long, isLowRamDevice: Boolean): Boolean {
        if (isLowRamDevice) return true
        if (totalRamMb <= 0L || totalRamMb > IMPLAUSIBLE_RAM_MB) return false
        return totalRamMb <= LOW_RAM_HINT_MB
    }

    /**
     * Was the device too slow, judging by the frame times ([frameDurationsMs], one per drawn frame) against
     * the display's frame budget? `null` when there aren't [SAMPLE_FRAMES] to judge by yet.
     */
    fun isSlow(frameDurationsMs: List<Float>, budgetMs: Float): Boolean? {
        if (frameDurationsMs.size < SAMPLE_FRAMES || budgetMs <= 0f) return null
        val dropped = frameDurationsMs.count { it > budgetMs * DROPPED_FRAME_FACTOR }
        return dropped.toFloat() / frameDurationsMs.size >= SLOW_SHARE
    }

    /** Share of dropped frames, for the telemetry message. */
    fun droppedShare(frameDurationsMs: List<Float>, budgetMs: Float): Float =
        if (frameDurationsMs.isEmpty()) 0f
        else frameDurationsMs.count { it > budgetMs * DROPPED_FRAME_FACTOR }.toFloat() / frameDurationsMs.size

    /**
     * The effective answer: should the decorative effects be OFF right now?
     *
     * The person's explicit choice always wins over any detection. Only in [EffectsMode.AUTO] do the
     * automatic signals count: a previous measured verdict ([autoReduced]), the specs' [staticHint], or
     * the system having animations turned off ([systemAnimationsOff], the accessibility setting).
     */
    fun resolve(mode: EffectsMode, autoReduced: Boolean, staticHint: Boolean, systemAnimationsOff: Boolean): Boolean =
        when (mode) {
            EffectsMode.FULL -> false
            EffectsMode.REDUCED -> true
            EffectsMode.AUTO -> autoReduced || staticHint || systemAnimationsOff
        }
}
