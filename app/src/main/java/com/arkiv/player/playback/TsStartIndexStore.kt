package com.arkiv.player.playback

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Each title's [TsStartIndex], by its origin key (the CDN object, the same across re-resolved
 * proxy URLs): in memory, and on disk in [folder] -- the remux cache's, one `<hash>.tsidx` of a few
 * KB per title -- so a re-cast, another audio or a seek back skips the ranged reads even after the
 * app restarted. The name is a hash: the key's query (auth) never reaches the filesystem. Cleared
 * with the remux cache ([clear]); an index older than [MAX_AGE_MS] is not trusted (the object behind
 * a key is not expected to change, but a month is long enough not to bet on it).
 */
class TsStartIndexStore(private val folder: File, private val now: () -> Long = System::currentTimeMillis) {

    private val memory = ConcurrentHashMap<String, TsStartIndex>()

    /** The index of [originKey], or null when none was kept (or it is stale or unreadable). */
    fun get(originKey: String): TsStartIndex? {
        memory[originKey]?.let { return it }
        val file = fileFor(originKey)
        if (!file.isFile || now() - file.lastModified() > MAX_AGE_MS) return null
        val index = runCatching { DataInputStream(file.inputStream().buffered()).use(TsStartIndex::read) }.getOrNull()
            ?: return null
        return memory.putIfAbsent(originKey, index) ?: index
    }

    /** Keeps [index] for [originKey]: in memory at once, on disk through a rename so a reader never sees half. */
    fun put(originKey: String, index: TsStartIndex) {
        memory[originKey] = index
        runCatching {
            if (!folder.exists()) folder.mkdirs()
            val file = fileFor(originKey)
            val part = File(folder, "${file.name}.${Thread.currentThread().id}.part")
            DataOutputStream(part.outputStream().buffered()).use(index::write)
            if (!part.renameTo(file)) part.delete()
        }
    }

    /** Forgets what is in memory (the files go with the folder's own clearing). */
    fun clear() = memory.clear()

    fun fileFor(originKey: String): File = File(folder, nameFor(originKey))

    companion object {
        const val SUFFIX = ".tsidx"
        const val MAX_AGE_MS = 30L * 24 * 3600 * 1000

        fun nameFor(originKey: String): String =
            MessageDigest.getInstance("SHA-256").digest(originKey.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) } + SUFFIX
    }
}
