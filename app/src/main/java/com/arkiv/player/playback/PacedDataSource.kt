package com.arkiv.player.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.InterruptedIOException

/**
 * A [DataSource] that holds its reads while [shouldWait] says so: how the cast's remux stops
 * pulling the title from the CDN once it is far enough ahead of the TV (see [RemuxPacing]).
 *
 * The wait is in [read], on the loader's own thread, so nothing upstream changes: the connection
 * simply stops being drained (TCP pushes back to the proxy and from there to the CDN). If the CDN
 * drops it meanwhile, the next read fails and ExoPlayer's loader reopens from where it was, as it
 * does for any network error. Cancelling the export interrupts the loader thread, which ends the
 * wait at once.
 *
 * [shouldWait] is asked at most every [checkEveryMs] while reads flow (it costs an index refresh),
 * and every [sleepMs] while waiting.
 */
@UnstableApi
class PacedDataSource(
    private val upstream: DataSource,
    private val shouldWait: () -> Boolean,
    private val sleepMs: Long = 250L,
    private val checkEveryMs: Long = 500L,
    private val clock: () -> Long = System::currentTimeMillis,
) : DataSource {

    private var lastCheckAt = Long.MIN_VALUE / 2

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (clock() - lastCheckAt >= checkEveryMs) {
            while (shouldWait()) {
                try {
                    Thread.sleep(sleepMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("paced read cancelled")
                }
            }
            lastCheckAt = clock()
        }
        return upstream.read(buffer, offset, length)
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long = upstream.open(dataSpec)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(
        private val upstream: DataSource.Factory,
        private val shouldWait: () -> Boolean,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = PacedDataSource(upstream.createDataSource(), shouldWait)
    }
}
