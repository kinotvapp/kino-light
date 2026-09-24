package com.arkiv.player.data

/**
 * A tiny fixed CPU workload, timed, to tell a slow core from a fast one without depending on what the
 * spec sheet claims (the cheapest TV boxes report nonsense RAM, and the startup warm-up time turned out
 * to be no proxy: a flagship phone took 18 s, a budget one 6 s). Pure, so the tests can drive it with a
 * fake clock; the Android side ([com.arkiv.player.StartupProfiler]) decides when to run it.
 *
 * TELEMETRY ONLY for now: it is reported next to the model and RAM so a real threshold can be chosen
 * from field data. Nothing in the app changes behavior because of it yet.
 */
object CpuBench {

    /** ~20-40 ms on a modern core, a few hundred on the weakest boxes. */
    const val ITERATIONS = 20_000_000

    /** Timed runs; the fastest counts. */
    const val RUNS = 3

    /** Last checksum, kept so the compiler/JIT can't prove the loop's result unused and delete it. */
    @Volatile
    private var lastChecksum = 0L

    /**
     * xorshift64: integer-only and register-bound, so it measures raw core speed and not memory. Returns
     * a checksum of everything it computed, deterministic for a given [iterations].
     */
    fun workload(iterations: Int): Long {
        var x = 88172645463325252L
        var acc = 0L
        for (i in 0 until iterations) {
            x = x xor (x shl 13)
            x = x xor (x ushr 7)
            x = x xor (x shl 17)
            acc += x
        }
        return acc
    }

    /**
     * The fastest of [runs] timed runs of [workload], in ms. The minimum on purpose: a run interrupted
     * by other work (the startup is busy) only ever looks SLOWER than the device really is, and the
     * first run is often interpreted before the JIT warms up.
     */
    fun measureMs(runs: Int = RUNS, iterations: Int = ITERATIONS, nanoTime: () -> Long = System::nanoTime): Float {
        var best = Long.MAX_VALUE
        repeat(runs) {
            val start = nanoTime()
            lastChecksum += workload(iterations)
            best = minOf(best, nanoTime() - start)
        }
        return best / 1_000_000f
    }
}
