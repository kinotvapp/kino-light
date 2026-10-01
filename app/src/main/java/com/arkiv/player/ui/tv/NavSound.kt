package com.arkiv.player.ui.tv

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.R
import com.arkiv.player.ui.rememberGraph
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun rememberNavSound(): () -> Unit {
    val context = LocalContext.current.applicationContext
    val enabled by rememberGraph().settings.uiSoundsEnabled.collectAsState()
    val player = remember(enabled) {
        if (enabled) NavSoundPlayer(NavSoundPlayer.sharedExecutor) { SoundPoolClick(context) } else null
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    return remember(player) {
        { player?.play() }
    }
}

/** The click itself: what [NavSoundPlayer] drives, kept behind an interface so it can be tested. */
internal interface ClickSound {
    fun play()
    fun release()
}

/**
 * Plays the TV's navigation tick without ever touching the UI thread.
 *
 * `SoundPool.play` can park inside the audio HAL on cheap TV boxes, and on the main thread that was
 * an ANR (ERRORES-19: onn and Fire TV boxes). Building the pool, loading the sample, playing and
 * releasing all run on one background thread, in order. Taps are coalesced: while one play is
 * still waiting its turn, further ones are dropped, so a stuck HAL cannot pile up work.
 */
internal class NavSoundPlayer(
    private val executor: Executor,
    create: () -> ClickSound,
) {
    /** Only read or written on [executor]'s thread. */
    private var sound: ClickSound? = null
    private val playQueued = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    init {
        executor.execute { if (!released.get()) sound = runCatching(create).getOrNull() }
    }

    fun play() {
        if (released.get() || !playQueued.compareAndSet(false, true)) return
        executor.execute {
            playQueued.set(false)
            runCatching { sound?.play() }
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        executor.execute {
            runCatching { sound?.release() }
            sound = null
        }
    }

    companion object {
        /** One daemon thread for every screen's tick: they are short and must stay in order. */
        val sharedExecutor: Executor by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "kino-nav-sound").apply { isDaemon = true } }
        }
    }
}

/** A [SoundPool] holding the one click sample. Built (and loaded) on [NavSoundPlayer]'s thread. */
private class SoundPoolClick(context: Context) : ClickSound {
    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val soundId = pool.load(context, R.raw.nav_click, 1)

    override fun play() {
        pool.play(soundId, 0.5f, 0.5f, 1, 0, 1f)
    }

    override fun release() {
        pool.release()
    }
}
