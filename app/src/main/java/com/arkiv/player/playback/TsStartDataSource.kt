package com.arkiv.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * The MPEG-TS a mid-file remux reads ([TsStart]): the stream's PAT + PMT packets, then the file
 * from the keyframe's byte on. To the extractor it is one ordinary stream that happens to begin
 * with a keyframe; positions past the tables map to `byteOffset + (position - tables)` upstream,
 * so its duration read at the end and any reopen after a network error land where they should.
 */
@UnstableApi
class TsStartDataSource(private val upstream: DataSource, private val start: TsStart) : DataSource {

    /** Next byte of [TsStart.tables] to serve, or -1 once reading upstream. */
    private var tablesAt = -1
    /** Where serving [TsStart.tables] stops: their end, or sooner for a read that ends inside them. */
    private var tablesEnd = 0
    private var upstreamOpen = false
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val tables = start.tables.size.toLong()
        val position = dataSpec.position
        if (position >= tables) {
            tablesAt = -1
            val opened = upstream.open(dataSpec.buildUpon().setPosition(start.byteOffset + position - tables).build())
            upstreamOpen = true
            return opened
        }
        tablesAt = position.toInt()
        tablesEnd = start.tables.size
        val fromTables = tables - position
        val wanted = dataSpec.length
        if (wanted != C.LENGTH_UNSET.toLong() && wanted <= fromTables) {
            tablesEnd = (position + wanted).toInt()
            return wanted
        }
        val rest = if (wanted == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else wanted - fromTables
        val opened = upstream.open(dataSpec.buildUpon().setPosition(start.byteOffset).setLength(rest).build())
        upstreamOpen = true
        return if (opened == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else fromTables + opened
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val tables = start.tables
        if (tablesAt in 0 until tablesEnd) {
            val n = minOf(length, tablesEnd - tablesAt)
            System.arraycopy(tables, tablesAt, buffer, offset, n)
            tablesAt += n
            return n
        }
        if (!upstreamOpen) return C.RESULT_END_OF_INPUT
        return upstream.read(buffer, offset, length)
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun getUri(): Uri? = if (upstreamOpen) upstream.uri ?: uri else uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (upstreamOpen) upstream.responseHeaders else emptyMap()

    override fun close() {
        tablesAt = -1
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    class Factory(private val upstream: DataSource.Factory, private val start: TsStart) : DataSource.Factory {
        override fun createDataSource(): DataSource = TsStartDataSource(upstream.createDataSource(), start)
    }
}
